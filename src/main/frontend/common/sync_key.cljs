(ns frontend.common.sync-key
  "Account-less sync auth: derive a stable identity + keys from a single
   passphrase and mint a self-signed HS256 JWT for the self-hosted sync server.

   One passphrase, via HKDF-SHA256 (RFC 5869) with distinct `info` labels,
   yields three non-overlapping sub-keys:
   - :auth-key       32-byte HMAC key that signs the HS256 JWT (shared with the
                     server as base64url via DB_SYNC_SHARED_KEY).
   - :sub            stable user id placed in the token (same passphrase => same
                     identity on every device).
   - :e2ee-password  password fed into the existing E2EE flow so notes stay
                     end-to-end encrypted.

   The raw passphrase is never used directly as a key and never leaves the
   client; the server only ever holds the derived :auth-key."
  (:require [clojure.string :as string]
            [goog.crypt :as crypt]
            [goog.crypt.Hmac]
            [goog.crypt.Sha256]
            [goog.crypt.base64 :as base64]))

(def ^:private hkdf-salt (vec (crypt/stringToUtf8ByteArray "logseq-selfhost-sync-v1")))

;; Long-lived tokens keep the sync socket stable; the client re-mints well
;; before expiry (see handler.user / worker.sync.auth), so this is only an
;; outer bound on a leaked token's validity.
(def token-ttl-seconds (* 30 24 60 60))
(def issuer "logseq-selfhost")
(def audience "logseq-selfhost")

(defn- ->bytes [s]
  (vec (crypt/stringToUtf8ByteArray s)))

(defn- hmac-sha256
  "HMAC-SHA256(key, data) -> vector of bytes. Both args are byte seqs."
  [key-bytes data-bytes]
  (let [h (crypt/Hmac. (crypt/Sha256.) (clj->js (vec key-bytes)))]
    (vec (.getHmac h (clj->js (vec data-bytes))))))

(defn- hkdf
  "HKDF-SHA256 (RFC 5869). Only supports length <= 32 (one expand block),
   which is all we need. Matches Node's crypto.hkdfSync('sha256', ...)."
  [ikm-bytes info length]
  (let [prk (hmac-sha256 hkdf-salt ikm-bytes)
        t1 (hmac-sha256 prk (conj (->bytes info) 1))]
    (vec (take length t1))))

(defn- b64url
  "base64url without padding, from a byte seq."
  [bytes]
  (-> (base64/encodeByteArray (clj->js (vec bytes)))
      (string/replace "+" "-")
      (string/replace "/" "_")
      (string/replace "=" "")))

(defn- b64url-str [s]
  (b64url (->bytes s)))

(defn derive
  "Derive {:auth-key :sub :e2ee-password} from a passphrase.
   :auth-key is a byte vector; :sub and :e2ee-password are strings."
  [passphrase]
  (let [ikm (->bytes passphrase)]
    {:auth-key (hkdf ikm "auth" 32)
     :sub (str "sk-" (crypt/byteArrayToHex (clj->js (hkdf ikm "sub" 16))))
     :e2ee-password (b64url (hkdf ikm "e2ee" 32))}))

(defn server-shared-key
  "The value to configure as DB_SYNC_SHARED_KEY on the server (base64url of the
   derived auth-key). The server decodes it and verifies the JWT's HMAC with it."
  [passphrase]
  (b64url (:auth-key (derive passphrase))))

(defn mint-token
  "Mint a self-signed HS256 JWT for `passphrase`. `now-ms` is the current epoch
   millis (pass js/Date.now from callers)."
  [passphrase now-ms]
  (let [{:keys [auth-key sub]} (derive passphrase)
        now-s (quot now-ms 1000)
        header (b64url-str (js/JSON.stringify #js {:alg "HS256" :typ "JWT"}))
        payload (b64url-str
                 (js/JSON.stringify
                  ;; NOTE: frontend.flows/current-login-user-schema requires
                  ;; :email, :sub and :cognito:username to all be strings; omit
                  ;; any and the app-state validator throws at login.
                  (js-obj "sub" sub
                          "cognito:username" "self"
                          "email" (str sub "@localhost")
                          "iss" issuer
                          "aud" audience
                          "iat" now-s
                          "exp" (+ now-s token-ttl-seconds))))
        signing-input (str header "." payload)
        signature (b64url (hmac-sha256 auth-key (->bytes signing-input)))]
    (str signing-input "." signature)))
