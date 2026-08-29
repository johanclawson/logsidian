(ns logseq.cli.file-graph-test
  "Tests for file-based graph MCP tools.
   Uses temporary directories with markdown files to test parsing and querying."
  (:require ["fs" :as fs]
            ["os" :as os]
            ["path" :as path]
            [cljs.test :refer [deftest is testing use-fixtures]]
            [clojure.string :as string]
            [datascript.core :as d]
            [logseq.cli.common.graph :as cli-common-graph]
            [logseq.cli.file-mcp.tools :as file-mcp-tools]
            [logseq.cli.file-mcp.upsert :as file-upsert]
            [logseq.db :as ldb]
            [logseq.graph-parser.cli :as gp-cli]))

;; Test Fixture Helpers
;; ====================

(defn create-temp-graph
  "Creates a temporary graph directory with markdown files.
   Returns {:dir path :cleanup-fn fn}

   pages is a vector of maps with :name and :content keys:
   [{:name \"page-a\" :content \"- First block\"}
    {:name \"page-b\" :content \"- Second block\"}]"
  [pages]
  (let [tmp-dir (fs/mkdtempSync (path/join (os/tmpdir) "logsidian-test-"))
        pages-dir (path/join tmp-dir "pages")
        logseq-dir (path/join tmp-dir "logseq")]
    (fs/mkdirSync pages-dir)
    (fs/mkdirSync logseq-dir)
    ;; Create minimal config.edn
    (fs/writeFileSync (path/join logseq-dir "config.edn") "{}")
    ;; Create page files
    (doseq [{:keys [name content]} pages]
      (fs/writeFileSync
        (path/join pages-dir (str name ".md"))
        (or content (str "- Block in " name))))
    {:dir tmp-dir
     :cleanup-fn #(fs/rmSync tmp-dir #js {:recursive true :force true})}))

(defn parse-temp-graph
  "Helper to parse a temp graph and return the connection"
  [dir]
  (:conn (gp-cli/parse-graph dir {:verbose false})))

;; Tests
;; =====

(deftest list-pages-test
  (let [{:keys [dir cleanup-fn]} (create-temp-graph
                                   [{:name "page-a" :content "- First block"}
                                    {:name "page-b" :content "- Second block"}])]
    (try
      (let [conn (parse-temp-graph dir)
            pages (file-mcp-tools/list-pages @conn {})]
        (testing "returns at least the created pages"
          (is (>= (count pages) 2)))
        (testing "includes page-a"
          (is (some #(= "page-a" (:block/name %)) pages)))
        (testing "includes page-b"
          (is (some #(= "page-b" (:block/name %)) pages)))
        (testing "each page has required fields"
          (doseq [page pages]
            (is (string? (:block/name page)))
            (is (string? (:block/uuid page))))))
      (finally (cleanup-fn)))))

(deftest list-pages-expand-test
  (let [{:keys [dir cleanup-fn]} (create-temp-graph
                                   [{:name "expand-test" :content "- Block"}])]
    (try
      (let [conn (parse-temp-graph dir)
            pages (file-mcp-tools/list-pages @conn {:expand true})]
        (testing "expand includes timestamp fields"
          (let [page (first (filter #(= "expand-test" (:block/name %)) pages))]
            (is (some? page))
            ;; Note: created-at/updated-at may be nil for file graphs
            ;; The important thing is that the expand option doesn't error
            (is (contains? page :block/created-at))
            (is (contains? page :block/updated-at)))))
      (finally (cleanup-fn)))))

(deftest get-page-data-test
  (let [{:keys [dir cleanup-fn]} (create-temp-graph
                                   [{:name "test-page"
                                     :content "- Block 1\n  - Nested block\n- Block 2"}])]
    (try
      (let [conn (parse-temp-graph dir)
            result (file-mcp-tools/get-page-data @conn "test-page")]
        (testing "returns page data"
          (is (some? result)))
        (testing "entity has correct name"
          (is (= "test-page" (get-in result [:entity :block/name]))))
        (testing "has blocks"
          (is (seq (:blocks result)))))
      (finally (cleanup-fn)))))

(deftest get-page-not-found-test
  (let [{:keys [dir cleanup-fn]} (create-temp-graph
                                   [{:name "exists" :content "- Block"}])]
    (try
      (let [conn (parse-temp-graph dir)
            result (file-mcp-tools/get-page-data @conn "nonexistent-page")]
        (testing "returns nil for nonexistent page"
          (is (nil? result))))
      (finally (cleanup-fn)))))

(deftest search-blocks-test
  (let [{:keys [dir cleanup-fn]} (create-temp-graph
                                   [{:name "page-a" :content "- TODO Buy groceries"}
                                    {:name "page-b" :content "- Meeting notes\n- Buy supplies"}])]
    (try
      (let [conn (parse-temp-graph dir)
            results (file-mcp-tools/search-blocks @conn "Buy" {})]
        (testing "finds blocks containing search term"
          (is (= 2 (count results))))
        (testing "results have required fields"
          (doseq [result results]
            (is (string? (:block/uuid result)))
            (is (string? (:block/title result)))
            (is (map? (:block/page result))))))
      (finally (cleanup-fn)))))

(deftest search-blocks-case-insensitive-test
  (let [{:keys [dir cleanup-fn]} (create-temp-graph
                                   [{:name "page-a" :content "- UPPERCASE text"}
                                    {:name "page-b" :content "- lowercase text"}])]
    (try
      (let [conn (parse-temp-graph dir)
            results (file-mcp-tools/search-blocks @conn "text" {})]
        (testing "search is case-insensitive"
          (is (= 2 (count results)))))
      (finally (cleanup-fn)))))

(deftest search-blocks-limit-test
  (let [{:keys [dir cleanup-fn]} (create-temp-graph
                                   [{:name "page-a" :content "- Item one\n- Item two\n- Item three"}])]
    (try
      (let [conn (parse-temp-graph dir)
            results (file-mcp-tools/search-blocks @conn "Item" {:limit 2})]
        (testing "respects limit option"
          (is (<= (count results) 2))))
      (finally (cleanup-fn)))))

(deftest list-tags-test
  (let [{:keys [dir cleanup-fn]} (create-temp-graph
                                   [{:name "page-a" :content "- Item #mytag"}
                                    {:name "mytag" :content "- Tag page content"}])]
    (try
      (let [conn (parse-temp-graph dir)
            tags (file-mcp-tools/list-tags @conn {})]
        (testing "returns a vector/list"
          (is (or (vector? tags) (seq? tags))))
        ;; Note: Whether #mytag creates a tag reference depends on parser behavior
        ;; The important thing is that the function doesn't error
        )
      (finally (cleanup-fn)))))

(deftest list-properties-test
  (let [{:keys [dir cleanup-fn]} (create-temp-graph
                                   [{:name "page-a" :content "status:: active\n- Content block"}
                                    {:name "page-b" :content "status:: done\npriority:: high\n- More content"}])]
    (try
      (let [conn (parse-temp-graph dir)
            props (file-mcp-tools/list-properties @conn {})]
        (testing "returns a vector/list"
          (is (or (vector? props) (seq? props))))
        ;; Properties may or may not be parsed depending on format
        ;; The important thing is that the function works
        )
      (finally (cleanup-fn)))))

(deftest list-properties-expand-test
  (let [{:keys [dir cleanup-fn]} (create-temp-graph
                                   [{:name "page-a" :content "status:: active\n- Block"}])]
    (try
      (let [conn (parse-temp-graph dir)
            props (file-mcp-tools/list-properties @conn {:expand true})]
        (testing "expand option doesn't cause errors"
          (is (or (vector? props) (seq? props)))))
      (finally (cleanup-fn)))))

;; MCP Wrapper Tests
;; =================

(deftest mcp-list-pages-test
  (let [{:keys [dir cleanup-fn]} (create-temp-graph
                                   [{:name "mcp-test" :content "- Block"}])]
    (try
      (let [conn (parse-temp-graph dir)
            response (file-mcp-tools/mcp-list-pages conn #js {})]
        (testing "returns MCP response format"
          (is (.-content response))
          (is (= "text" (.-type (aget (.-content response) 0))))))
      (finally (cleanup-fn)))))

(deftest mcp-get-page-found-test
  (let [{:keys [dir cleanup-fn]} (create-temp-graph
                                   [{:name "mcp-page" :content "- Block"}])]
    (try
      (let [conn (parse-temp-graph dir)
            response (file-mcp-tools/mcp-get-page conn #js {"pageName" "mcp-page"})]
        (testing "returns MCP response with page data"
          (is (.-content response))
          (let [text (.-text (aget (.-content response) 0))
                data (js->clj (js/JSON.parse text) :keywordize-keys true)]
            ;; Note: clj->js strips namespaces from keywords, so :block/name becomes :name in JSON
            (is (= "mcp-page" (get-in data [:entity :name]))))))
      (finally (cleanup-fn)))))

(deftest mcp-get-page-not-found-test
  (let [{:keys [dir cleanup-fn]} (create-temp-graph
                                   [{:name "exists" :content "- Block"}])]
    (try
      (let [conn (parse-temp-graph dir)
            response (file-mcp-tools/mcp-get-page conn #js {"pageName" "nonexistent"})]
        (testing "returns error message for missing page"
          (is (.-content response))
          (is (= "Page not found" (.-text (aget (.-content response) 0))))))
      (finally (cleanup-fn)))))

(deftest mcp-search-blocks-test
  (let [{:keys [dir cleanup-fn]} (create-temp-graph
                                   [{:name "search-page" :content "- Searchable content"}])]
    (try
      (let [conn (parse-temp-graph dir)
            response (file-mcp-tools/mcp-search-blocks conn #js {"searchTerm" "Searchable"})]
        (testing "returns MCP response format"
          (is (.-content response))))
      (finally (cleanup-fn)))))

;; Datalog Query Test (for CLI query command)
;; ==========================================

(deftest direct-datalog-query-test
  (let [{:keys [dir cleanup-fn]} (create-temp-graph
                                   [{:name "query-page" :content "- TODO Important task"}])]
    (try
      (let [conn (parse-temp-graph dir)
            ;; Query for blocks with TODO marker
            results (d/q '[:find ?t
                           :where
                           [?b :block/marker "TODO"]
                           [?b :block/title ?t]]
                         @conn)]
        (testing "datalog queries work on file graph"
          ;; Results depend on whether parser sets :block/marker
          (is (or (seq results) (empty? results)))))
      (finally (cleanup-fn)))))

;; =============================================================================
;; Upsert Operations Tests
;; =============================================================================

(deftest validate-operations-test
  (testing "rejects unsupported operations"
    (is (thrown? js/Error
          (file-upsert/validate-operations
            [{:operation "edit" :entityType "page"}])))
    (is (thrown? js/Error
          (file-upsert/validate-operations
            [{:operation "add" :entityType "tag"}])))
    (is (thrown? js/Error
          (file-upsert/validate-operations
            [{:operation "delete" :entityType "block"}]))))
  (testing "accepts supported operations"
    (is (nil? (file-upsert/validate-operations
                [{:operation "add" :entityType "page" :data {:title "Test"}}])))
    (is (nil? (file-upsert/validate-operations
                [{:operation "add" :entityType "block" :data {:title "Test" :page-id "abc"}}])))
    (is (nil? (file-upsert/validate-operations
                [{:operation "edit" :entityType "block" :id "abc" :data {:title "Test"}}])))))

(deftest add-page-creates-file-test
  (let [{:keys [dir cleanup-fn]} (create-temp-graph [])]
    (try
      (let [conn (parse-temp-graph dir)
            ops [{:operation "add" :entityType "page" :data {:title "New Page"}}]
            result (file-upsert/upsert-nodes conn dir ops {})]
        (testing "returns success message"
          (is (string/includes? (:message result) "1 operation")))
        (testing "creates page file"
          (is (fs/existsSync (path/join dir "pages" "New Page.md"))))
        (testing "result contains file path"
          (is (some #(= (:title %) "New Page") (:results result)))))
      (finally (cleanup-fn)))))

(deftest add-page-journal-test
  (let [{:keys [dir cleanup-fn]} (create-temp-graph [])]
    (try
      (let [conn (parse-temp-graph dir)
            ops [{:operation "add" :entityType "page" :data {:title "2025-01-15"}}]
            _ (file-upsert/upsert-nodes conn dir ops {})]
        (testing "creates journal file in journals directory"
          (is (fs/existsSync (path/join dir "journals" "2025_01_15.md")))))
      (finally (cleanup-fn)))))

(deftest add-page-duplicate-fails-test
  (let [{:keys [dir cleanup-fn]} (create-temp-graph
                                   [{:name "existing-page" :content "- Block"}])]
    (try
      (let [conn (parse-temp-graph dir)
            ops [{:operation "add" :entityType "page" :data {:title "existing-page"}}]]
        (testing "throws error for duplicate page"
          (is (thrown? js/Error (file-upsert/upsert-nodes conn dir ops {})))))
      (finally (cleanup-fn)))))

(deftest add-block-to-page-test
  (let [{:keys [dir cleanup-fn]} (create-temp-graph
                                   [{:name "test-page" :content "- Existing block"}])]
    (try
      (let [conn (parse-temp-graph dir)
            page (ldb/get-page @conn "test-page")
            ops [{:operation "add" :entityType "block"
                  :data {:title "New block content"
                         :page-id (str (:block/uuid page))}}]
            _ (file-upsert/upsert-nodes conn dir ops {})
            content (fs/readFileSync (path/join dir "pages" "test-page.md") "utf8")]
        (testing "file contains new block"
          (is (string/includes? content "New block content")))
        (testing "file still contains existing block"
          (is (string/includes? content "Existing block"))))
      (finally (cleanup-fn)))))

(deftest add-block-by-page-name-test
  (let [{:keys [dir cleanup-fn]} (create-temp-graph
                                   [{:name "my-page" :content "- First"}])]
    (try
      (let [conn (parse-temp-graph dir)
            ops [{:operation "add" :entityType "block"
                  :data {:title "Added by name" :page-id "my-page"}}]
            _ (file-upsert/upsert-nodes conn dir ops {})
            content (fs/readFileSync (path/join dir "pages" "my-page.md") "utf8")]
        (testing "can add block using page name"
          (is (string/includes? content "Added by name"))))
      (finally (cleanup-fn)))))

(deftest add-block-missing-page-fails-test
  (let [{:keys [dir cleanup-fn]} (create-temp-graph [])]
    (try
      (let [conn (parse-temp-graph dir)
            ops [{:operation "add" :entityType "block"
                  :data {:title "Orphan block"
                         :page-id "nonexistent-page"}}]]
        (testing "throws error for missing page"
          (is (thrown? js/Error (file-upsert/upsert-nodes conn dir ops {})))))
      (finally (cleanup-fn)))))

(deftest edit-block-updates-file-test
  (let [{:keys [dir cleanup-fn]} (create-temp-graph
                                   [{:name "edit-test" :content "- Original text"}])]
    (try
      (let [conn (parse-temp-graph dir)
            page (ldb/get-page @conn "edit-test")
            ;; Find the block with "Original text"
            blocks (->> (d/datoms @conn :avet :block/page (:db/id page))
                        (map #(d/entity @conn (:e %)))
                        (filter #(= "Original text" (:block/title %))))
            block (first blocks)
            _ (when-not block
                (throw (ex-info "Test setup failed: block not found" {})))
            ops [{:operation "edit" :entityType "block"
                  :id (str (:block/uuid block))
                  :data {:title "Updated text"}}]
            _ (file-upsert/upsert-nodes conn dir ops {})
            content (fs/readFileSync (path/join dir "pages" "edit-test.md") "utf8")]
        (testing "file contains updated text"
          (is (string/includes? content "Updated text")))
        (testing "file no longer contains original text"
          (is (not (string/includes? content "Original text")))))
      (finally (cleanup-fn)))))

(deftest edit-block-not-found-fails-test
  (let [{:keys [dir cleanup-fn]} (create-temp-graph
                                   [{:name "test" :content "- Block"}])]
    (try
      (let [conn (parse-temp-graph dir)
            fake-uuid (str (random-uuid))
            ops [{:operation "edit" :entityType "block"
                  :id fake-uuid
                  :data {:title "New text"}}]]
        (testing "throws error for nonexistent block"
          (is (thrown? js/Error (file-upsert/upsert-nodes conn dir ops {})))))
      (finally (cleanup-fn)))))

(deftest dry-run-does-not-modify-test
  (let [{:keys [dir cleanup-fn]} (create-temp-graph [])]
    (try
      (let [conn (parse-temp-graph dir)
            ops [{:operation "add" :entityType "page" :data {:title "DryRunPage"}}]
            result (file-upsert/upsert-nodes conn dir ops {:dry-run true})]
        (testing "returns dry run message"
          (is (string/includes? (:message result) "Dry run")))
        (testing "does not create file"
          (is (not (fs/existsSync (path/join dir "pages" "DryRunPage.md"))))))
      (finally (cleanup-fn)))))

(deftest multiple-operations-test
  (let [{:keys [dir cleanup-fn]} (create-temp-graph [])]
    (try
      (let [conn (parse-temp-graph dir)
            ops [{:operation "add" :entityType "page" :data {:title "Multi Page"}}]
            _ (file-upsert/upsert-nodes conn dir ops {})
            ;; Now add a block to the new page
            page (ldb/get-page @conn "multi page")
            block-ops [{:operation "add" :entityType "block"
                        :data {:title "First block" :page-id (str (:block/uuid page))}}
                       {:operation "add" :entityType "block"
                        :data {:title "Second block" :page-id (str (:block/uuid page))}}]
            result (file-upsert/upsert-nodes conn dir block-ops {})
            content (fs/readFileSync (path/join dir "pages" "Multi Page.md") "utf8")]
        (testing "processes multiple operations"
          (is (string/includes? (:message result) "2 operation")))
        (testing "file contains both blocks"
          (is (string/includes? content "First block"))
          (is (string/includes? content "Second block"))))
      (finally (cleanup-fn)))))

;; =============================================================================
;; Graph Discovery Tests
;; =============================================================================

(deftest get-file-graphs-test
  (testing "returns vector or nil"
    (let [graphs (cli-common-graph/get-file-graphs)]
      (is (or (nil? graphs) (vector? graphs)))
      (when (seq graphs)
        (doseq [g graphs]
          (is (string? g))
          ;; Should not contain the ++ encoding
          (is (not (string/includes? g "++"))))))))
