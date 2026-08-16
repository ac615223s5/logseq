(ns electron.configs
  (:require ["electron" :refer [^js app] :as electron]
            ["fs-extra" :as ^js fs]
            ["path" :as ^js node-path]
            [cljs.reader :as reader]
            [clojure.string :as string]
            [electron.logger :as logger]
            [logseq.cli.root-dir :as cli-root-dir]
            [logseq.common.graph-registry :as graph-registry]))

;; FIXME: move configs.edn to where it should be
(defonce dot-root (.join node-path (.getPath app "home") ".logseq"))
(defonce cfg-root (.getPath app "userData"))
(defonce cfg-path (.join node-path cfg-root "configs.edn"))

(defn graph-registry-path
  []
  (.join node-path dot-root "graphs.edn"))

(defn- ensure-cfg
  []
  (try
    (.ensureFileSync fs cfg-path)
    (let [body (.toString (.readFileSync fs cfg-path))]
      (if (seq body) (reader/read-string body) {}))
    (catch :default e
      (logger/error :cfg-error e))))

(defn- write-cfg!
  [cfg]
  (try
    (.writeFileSync fs cfg-path (pr-str cfg)) cfg
    (catch :default e
      (logger/error :cfg-error e))))

(defn set-item!
  [k v]
  (when-let [cfg (ensure-cfg)]
    (some->> (assoc cfg k v)
             (write-cfg!))))

(defn get-item
  [k]
  (when-let [cfg (and k (ensure-cfg))]
    (get cfg k)))

(defn get-config
  []
  (ensure-cfg))

(defn semantic-search-enabled?
  []
  (true? (get-item :feature/enable-semantic-search?)))

;; Graph storage location
;;
;; Graphs live in `<root-dir>/graphs/<encoded-graph-dir>`. The root also holds
;; `kv-store.json`, `auth.json` and `server-list`, so the whole data directory
;; moves as a unit.

(def graphs-root-dir-key
  "configs.edn key holding the directory that contains the `graphs` folder."
  :settings/graphs-root-dir)

(defn default-graphs-root-dir
  "Shared with the CLI and `logseq.cli.server`, so all three agree on the default."
  []
  (cli-root-dir/default-root-dir))

(defn get-graphs-root-dir
  "The user-configured root dir, or nil when the default location is in use."
  []
  (let [v (get-item graphs-root-dir-key)]
    (when (string? v)
      (let [v (string/trim v)]
        (when (seq v) v)))))

(defn resolved-graphs-root-dir
  []
  (or (get-graphs-root-dir) (default-graphs-root-dir)))

(defn graphs-dir
  []
  (.join node-path (resolved-graphs-root-dir) "graphs"))

(defn- sync-cli-root-dir!
  "Keep the `logseq` CLI pointed at the same graphs as the desktop app, so both
  operate on one set of graphs after the location changes."
  [root-dir]
  (try
    (cli-root-dir/write-cli-root-dir! (cli-root-dir/cli-config-path) root-dir)
    (catch :default e
      (logger/error :cli-root-dir-sync-error e))))

(defonce ^:private *active-graphs-root-dir (atom nil))

(defn active-graphs-root-dir
  "The root dir this session actually started with. Changing the setting only
  takes effect on relaunch, so daemons started and stopped within a session must
  agree on this rather than on whatever is currently persisted."
  []
  (or @*active-graphs-root-dir (resolved-graphs-root-dir)))

(defn apply-graphs-root-dir!
  "`logseq.common.graph` resolves the graphs dir from LOGSEQ_GRAPHS_DIR and child
  processes inherit the environment, so exporting the configured location keeps
  the main process, the renderer and every spawned db-worker in agreement. An
  externally supplied LOGSEQ_GRAPHS_DIR is left alone when nothing is configured."
  []
  (let [root (resolved-graphs-root-dir)]
    (when (get-graphs-root-dir)
      (aset js/process.env "LOGSEQ_GRAPHS_DIR" (.join node-path root "graphs")))
    (reset! *active-graphs-root-dir root)))

(defn set-graphs-root-dir!
  "Persists the root dir. A nil/blank path restores the default location.
  Takes effect on the next launch; callers are expected to prompt for a relaunch."
  [path]
  (let [path (when (string? path)
               (let [path (string/trim path)]
                 (when (seq path) (.resolve node-path path))))
        path (when (and path (not= path (default-graphs-root-dir))) path)]
    (set-item! graphs-root-dir-key path)
    (sync-cli-root-dir! path)
    (resolved-graphs-root-dir)))

(defn- read-edn-file
  [path]
  (try
    (.ensureFileSync fs path)
    (let [body (.toString (.readFileSync fs path))]
      (if (seq body) (reader/read-string body) []))
    (catch :default e
      (logger/error :graph-registry-read-error e)
      [])))

(defn read-graph-registry
  []
  (read-edn-file (graph-registry-path)))

(defn write-graph-registry!
  [registry]
  (try
    (.ensureDirSync fs dot-root)
    (.writeFileSync fs (graph-registry-path) (pr-str (vec registry)))
    (vec registry)
    (catch :default e
      (logger/error :graph-registry-write-error e)
      nil)))

(defn upsert-graph-registry-entry!
  [entry]
  (let [registry (read-graph-registry)
        registry' (graph-registry/upsert-entry registry entry)]
    (write-graph-registry! registry')))
