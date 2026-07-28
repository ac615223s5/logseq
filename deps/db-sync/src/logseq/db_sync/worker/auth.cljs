(ns logseq.db-sync.worker.auth
  (:require [clojure.string :as string]
            [logseq.common.authorization :as authorization]
            [promesa.core :as p]))

(defn- bearer-token [auth-header]
  (when (and (string? auth-header) (string/starts-with? auth-header "Bearer "))
    (subs auth-header 7)))

(defn token-from-request [request]
  (or (bearer-token (.get (.-headers request) "authorization"))
      (let [url (js/URL. (.-url request))]
        (.get (.-searchParams url) "token"))))

(defn- decode-jwt-part [part]
  (let [pad (if (pos? (mod (count part) 4))
              (apply str (repeat (- 4 (mod (count part) 4)) "="))
              "")
        base64 (-> (str part pad)
                   (string/replace "-" "+")
                   (string/replace "_" "/"))
        raw (js/atob base64)]
    (js/JSON.parse raw)))

(defn unsafe-jwt-claims [token]
  (try
    (when (string? token)
      (let [parts (string/split token #"\.")]
        (when (= 3 (count parts))
          (decode-jwt-part (nth parts 1)))))
    (catch :default _
      nil)))

(def ^:private recoverable-auth-errors
  #{"invalid" "iss not found" "aud not found" "exp" "kid"})

(def ^:private truthy-env-values
  #{"1" "true" "yes" "on"})

(defn- recoverable-auth-error?
  [error]
  (when error
    (let [message (or (ex-message error) (some-> error .-message))]
      (contains? recoverable-auth-errors message))))

(defn- env-flag-enabled?
  [env k]
  (let [v (some-> env (aget k))]
    (cond
      (true? v) true
      (false? v) false
      (string? v) (contains? truthy-env-values (string/lower-case v))
      :else false)))

(defn- allow-unverified-jwt-claims?
  [env]
  (env-flag-enabled? env "DB_SYNC_ALLOW_UNVERIFIED_JWT_CLAIMS"))

(defn- expired-token?
  [token]
  (when-let [claims (unsafe-jwt-claims token)]
    (let [exp (aget claims "exp")
          now-s (js/Math.floor (/ (.now js/Date) 1000))]
      (and (number? exp)
           (<= exp now-s)))))

(defn- shared-key [env]
  (let [k (some-> env (aget "DB_SYNC_SHARED_KEY"))]
    (when (and (string? k) (not (string/blank? k))) k)))

(defn- jwt-alg [token]
  (try
    (let [parts (string/split token #"\.")]
      (when (= 3 (count parts))
        (aget (decode-jwt-part (first parts)) "alg")))
    (catch :default _ nil)))

(defn- self-host-claims
  "Decoded claims iff `token` is one of our account-less self-hosted tokens
   (HS256, iss=logseq-selfhost). Signature is NOT checked here."
  [token]
  (let [claims (unsafe-jwt-claims token)]
    (when (and (= "HS256" (jwt-alg token))
               (= "logseq-selfhost" (some-> claims (aget "iss"))))
      claims)))

(defn- sub-allowed?
  "Optional allowlist. When DB_SYNC_ALLOWED_SUBS is set (comma-separated), only
   those subs are accepted; otherwise every sub is allowed (encryption-only)."
  [env sub]
  (let [allow (some-> env (aget "DB_SYNC_ALLOWED_SUBS"))]
    (if (and (string? allow) (not (string/blank? allow)))
      (contains? (set (map string/trim (string/split allow #","))) sub)
      true)))

(defn auth-claims [request env]
  (let [token (token-from-request request)]
    (if (string? token)
      (if (expired-token? token)
        (p/resolved nil)
        (let [sk (shared-key env)
              sh-claims (self-host-claims token)]
          (cond
            ;; Account-less self-hosted token with an HMAC shared key configured:
            ;; enforce the signature (possession of the key authorizes).
            (and sh-claims sk)
            (-> (authorization/verify-hs256 token sk)
                (p/catch (fn [_] nil)))

            ;; Encryption-only (dumb server): no shared key -> trust the claims and
            ;; namespace by `sub`. Auth is implicit: notes are E2E-encrypted and the
            ;; sub is an unguessable passphrase hash. Optional DB_SYNC_ALLOWED_SUBS.
            sh-claims
            (p/resolved (when (sub-allowed? env (aget sh-claims "sub")) sh-claims))

            ;; Anything else (e.g. a real Cognito token) -> verify normally.
            :else
            (-> (authorization/verify-jwt token env)
                (p/catch (fn [error]
                           (cond
                             (recoverable-auth-error? error)
                             nil

                             (allow-unverified-jwt-claims? env)
                             (unsafe-jwt-claims token)

                             :else
                             (p/rejected error))))))))
      (p/resolved nil))))
