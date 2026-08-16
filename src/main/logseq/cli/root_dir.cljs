(ns logseq.cli.root-dir
  "Root-dir validation and path derivation for the CLI and db-worker-node."
  (:require ["fs" :as fs]
            ["os" :as os]
            ["path" :as node-path]
            [cljs.reader :as reader]
            [logseq.common.graph :as common-graph]))

(defn default-root-dir
  []
  (node-path/join (.homedir os) "logseq"))

(defn normalize-root-dir
  [path]
  (node-path/resolve (common-graph/expand-home (or path (default-root-dir)))))

(defn graphs-dir
  [root-dir]
  (node-path/join (normalize-root-dir root-dir) "graphs"))

(defn cli-config-path
  "The CLI looks for its config at `<default-root-dir>/cli.edn` before it knows
  about any override, so the pointer always lives under the default root."
  []
  (node-path/join (default-root-dir) "cli.edn"))

(defn with-root-dir
  "Merges `root-dir` into an existing cli.edn map. A nil root-dir drops the key
  so the CLI falls back to its own default."
  [config root-dir]
  (let [config (or config {})]
    (if root-dir
      (assoc config :root-dir root-dir)
      (dissoc config :root-dir))))

(defn write-cli-root-dir!
  "Points the `logseq` CLI at `root-dir` by writing `<config-path>`. Merges into
  any existing config and returns nil without writing when the file exists but
  isn't a readable EDN map, so a hand-edited config is never clobbered."
  [config-path root-dir]
  (let [existing (when (fs/existsSync config-path)
                   (let [body (.toString (fs/readFileSync config-path))]
                     (if (seq (.trim body)) (reader/read-string body) {})))]
    (when (or (nil? existing) (map? existing))
      (let [config (with-root-dir existing root-dir)]
        (if (seq config)
          (do (fs/mkdirSync (node-path/dirname config-path) #js {:recursive true})
              (fs/writeFileSync config-path (pr-str config)))
          (when (fs/existsSync config-path)
            (fs/unlinkSync config-path)))
        config))))

(defn ensure-root-dir!
  [path]
  (let [path (normalize-root-dir path)]
    (try
      (when-not (fs/existsSync path)
        (fs/mkdirSync path #js {:recursive true}))
      (let [stat (fs/statSync path)]
        (when-not (.isDirectory stat)
          (throw (ex-info (str "root-dir is not a directory: " path)
                          {:code :root-dir-permission
                           :path path
                           :cause "ENOTDIR"}))))
      (let [constants (.-constants fs)
            mode (bit-or (.-R_OK constants) (.-W_OK constants))]
        (fs/accessSync path mode))
      path
      (catch :default e
        (if (= :root-dir-permission (:code (ex-data e)))
          (throw e)
          (throw (ex-info (str "root-dir is not readable/writable: " path)
                          {:code :root-dir-permission
                           :path path
                           :cause (.-code e)})))))))
