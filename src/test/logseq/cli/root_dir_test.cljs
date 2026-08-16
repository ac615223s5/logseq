(ns logseq.cli.root-dir-test
  (:require ["fs" :as fs]
            ["os" :as os]
            ["path" :as node-path]
            [cljs.reader :as reader]
            [cljs.test :refer [deftest is testing]]
            [frontend.test.node-helper :as node-helper]
            [logseq.cli.root-dir :as root-dir]))

(deftest ensure-root-dir-creates-missing-dir
  (testing "creates missing directories and returns normalized path"
    (let [base (node-helper/create-tmp-dir "root-dir")
          target (node-path/join base "nested" "dir")]
      (is (not (fs/existsSync target)))
      (let [resolved (root-dir/ensure-root-dir! target)]
        (is (fs/existsSync target))
        (is (.isDirectory (fs/statSync target)))
        (is (= (node-path/resolve target) resolved))))))

(deftest ensure-root-dir-rejects-file-path
  (testing "rejects paths that are files"
    (let [base (node-helper/create-tmp-dir "root-dir-file")
          target (node-path/join base "file.txt")]
      (fs/writeFileSync target "x")
      (try
        (root-dir/ensure-root-dir! target)
        (is false "expected root-dir permission error")
        (catch :default e
          (let [data (ex-data e)]
            (is (= :root-dir-permission (:code data)))
            (is (= (node-path/resolve target) (:path data)))))))))

(deftest ensure-root-dir-rejects-read-only-dir
  (testing "rejects directories without write permission"
    (when-not (= "win32" (.-platform js/process))
      (let [target (node-helper/create-tmp-dir "root-dir-readonly")]
        (fs/chmodSync target 365)
        (try
          (root-dir/ensure-root-dir! target)
          (is false "expected root-dir permission error")
          (catch :default e
            (let [data (ex-data e)]
              (is (= :root-dir-permission (:code data)))
              (is (= (node-path/resolve target) (:path data))))))))))

(deftest normalize-root-dir-default
  (testing "defaults to ~/logseq"
    (let [expected (node-path/resolve (node-path/join (.homedir os) "logseq"))
          resolved (root-dir/normalize-root-dir nil)]
      (is (= expected resolved)))))

(deftest graphs-dir-derived-from-root-dir
  (testing "graphs dir is derived as <root-dir>/graphs"
    (let [root-dir-path (node-path/join (.homedir os) "custom-logseq")]
      (is (= (node-path/resolve root-dir-path "graphs")
             (root-dir/graphs-dir root-dir-path))))))

(deftest cli-config-path-lives-under-default-root-dir
  (testing "the CLI pointer stays under the default root, not the configured one"
    (is (= (node-path/join (root-dir/default-root-dir) "cli.edn")
           (root-dir/cli-config-path)))))

(deftest with-root-dir-merges-and-clears
  (testing "sets root-dir while preserving unrelated keys"
    (is (= {:graph "demo" :root-dir "/tmp/notes"}
           (root-dir/with-root-dir {:graph "demo"} "/tmp/notes"))))
  (testing "a nil root-dir drops the key instead of writing nil"
    (is (= {:graph "demo"}
           (root-dir/with-root-dir {:graph "demo" :root-dir "/tmp/notes"} nil))))
  (testing "tolerates a missing config"
    (is (= {:root-dir "/tmp/notes"} (root-dir/with-root-dir nil "/tmp/notes")))
    (is (= {} (root-dir/with-root-dir nil nil)))))

(deftest write-cli-root-dir-creates-merges-and-removes
  (let [base (node-helper/create-tmp-dir "cli-root-dir")
        config-path (node-path/join base "nested" "cli.edn")
        read-config #(reader/read-string (.toString (fs/readFileSync config-path)))]
    (testing "creates the config, parent directory included"
      (root-dir/write-cli-root-dir! config-path "/tmp/notes")
      (is (= {:root-dir "/tmp/notes"} (read-config))))
    (testing "merges into an existing config"
      (fs/writeFileSync config-path (pr-str {:graph "demo" :root-dir "/tmp/old"}))
      (root-dir/write-cli-root-dir! config-path "/tmp/notes")
      (is (= {:graph "demo" :root-dir "/tmp/notes"} (read-config))))
    (testing "clearing keeps the rest of the config"
      (root-dir/write-cli-root-dir! config-path nil)
      (is (= {:graph "demo"} (read-config))))
    (testing "removes a config that would be left empty"
      (fs/writeFileSync config-path (pr-str {:root-dir "/tmp/notes"}))
      (root-dir/write-cli-root-dir! config-path nil)
      (is (not (fs/existsSync config-path))))))

(deftest write-cli-root-dir-leaves-unreadable-config-alone
  (testing "a config that isn't an EDN map is never clobbered"
    (let [base (node-helper/create-tmp-dir "cli-root-dir-invalid")
          config-path (node-path/join base "cli.edn")]
      (fs/writeFileSync config-path "[:not :a :map]")
      (is (nil? (root-dir/write-cli-root-dir! config-path "/tmp/notes")))
      (is (= "[:not :a :map]" (.toString (fs/readFileSync config-path)))))))
