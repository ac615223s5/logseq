(ns logseq.common.uuid
  "uuid generators"
  (:require [datascript.core :as d]))

(defn- gen-journal-page-uuid
  "00000001-2024-0620-0000-000000000000
first 8 chars as type, currently only '00000001' for journal-day-page.
the remaining chars for data of this type"
  [journal-day]
  {:pre [(pos-int? journal-day)
         (> 1 (/ journal-day 100000000))]}
  (let [journal-day-str  (str journal-day)
        part1 (subs journal-day-str 0 4)
        part2 (subs journal-day-str 4 8)]
    (uuid (str "00000001" "-" part1 "-" part2 "-0000-000000000000"))))

(defn- fill-with-0
  [s length]
  (let [s-length (count s)]
    (apply str s (repeat (- length s-length) "0"))))

(defn- gen-block-uuid
  "prefix-<hash-of-db-ident>-<padding-with-0>"
  [k prefix]
  (let [hash-num (str (Math/abs (hash k)))
        part1 (fill-with-0 (subs hash-num 0 4) 4)
        part2 (fill-with-0 (subs hash-num 4 8) 4)
        part3 (fill-with-0 (subs hash-num 8 12) 4)
        part4 (fill-with-0 (subs hash-num 12) 12)]
    (uuid (str prefix "-" part1 "-" part2 "-" part3 "-" part4))))

(defn- gen-db-ident-block-uuid
  "00000002-<hash-of-db-ident>-<padding-with-0>"
  [db-ident]
  {:pre [(keyword? db-ident)]}
  (gen-block-uuid db-ident "00000002"))

(def ^:private uuid-pattern
  #"^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$")

;; Account-less self-hosted sync ids: "sk-" followed by 32 hex chars (the 16
;; bytes of HKDF output derived from the passphrase). See frontend.common.sync-key.
(def ^:private sync-key-user-id-pattern #"^sk-([0-9a-fA-F]{32})$")

(defn- hex32->uuid
  [hex]
  (uuid (str (subs hex 0 8) "-" (subs hex 8 12) "-" (subs hex 12 16) "-"
             (subs hex 16 20) "-" (subs hex 20 32))))

(defn user-id->uuid
  "Stable :block/uuid for a sync user id.

  Cognito subs are already UUIDs and pass through unchanged. Account-less
  self-hosted ids carry exactly 128 bits, so they map straight onto the UUID
  layout; anything else falls back to a hashed '00000007-' uuid.

  This has to return a real UUID: the created-by entity's :block/uuid reaches
  the search index, which rejects ids that aren't uuid-shaped and aborts the
  whole batch."
  [user-id]
  (when (string? user-id)
    (if-let [hex (second (re-matches sync-key-user-id-pattern user-id))]
      (hex32->uuid hex)
      (if (re-matches uuid-pattern user-id)
        (uuid user-id)
        (gen-block-uuid user-id "00000007")))))

(defn gen-uuid
  "supported type:
  - :journal-page-uuid, 00000001
  - :db-ident-block-uuid, 00000002
  - :migrate-new-block-uuid, 00000003
  - :builtin-block-uuid, 00000004
  - :view-block-uuid, 00000006"
  ([] (d/squuid))
  ([type' v]
   (assert (some? v))
   (case type'
     :journal-page-uuid (gen-journal-page-uuid v)
     :db-ident-block-uuid (gen-db-ident-block-uuid v)
     :migrate-new-block-uuid (gen-block-uuid v "00000003")
     :builtin-block-uuid (gen-block-uuid v "00000004")
     :view-block-uuid (gen-block-uuid v "00000006"))))

(defn gen-journal-template-block
  "Persistent uuid for journal template block"
  [journal-uuid template-block-uuid]
  (assert (uuid? journal-uuid) (str journal-uuid))
  (assert (uuid? template-block-uuid) (str template-block-uuid))
  (uuid
   (str "00000005"
        "-"
       ;; journal day
        (subs (str journal-uuid) 9 23)
       ;; template block uuid
        (subs (str template-block-uuid) 23))))
