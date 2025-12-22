(ns logseq.graph-parser.parse-file-data-test
  "Tests for parse-file-data - the deterministic parsing function.
   These tests verify that parse-file-data is pure and produces identical
   output given the same inputs."
  (:require [cljs.test :refer [deftest testing is]]
            [clojure.string :as string]
            [datascript.core :as d]
            [logseq.graph-parser :as graph-parser]
            [logseq.graph-parser.db :as gp-db]))

;; Test fixtures - deterministic UUID and timestamp generators
(defn- pad-left
  "Pad string s to length n with char c"
  [s n c]
  (let [s (str s)]
    (if (>= (count s) n)
      s
      (str (apply str (repeat (- n (count s)) c)) s))))

(defn make-uuid-generator
  "Creates a deterministic UUID generator that returns sequential UUIDs.
   Returns a function that produces a new UUID each time it's called."
  []
  (let [counter (atom 0)]
    (fn []
      (let [n (swap! counter inc)]
        ;; Create a deterministic UUID based on counter
        (uuid (str "00000000-0000-0000-0000-" (pad-left n 12 "0")))))))

(defn make-now-generator
  "Creates a deterministic timestamp generator.
   Returns a function that produces timestamps starting from a fixed date."
  []
  (let [base-time (js/Date. "2024-01-01T00:00:00Z")
        counter (atom 0)]
    (fn []
      (let [n (swap! counter inc)]
        (js/Date. (+ (.getTime base-time) (* n 1000)))))))

;; =============================================================================
;; Step 1: Deterministic Parse Contract Tests
;; =============================================================================

(deftest ^:parallel parse-file-data-returns-data-structure
  (testing "parse-file-data returns expected keys"
    (let [db @(gp-db/start-conn)
          result (graph-parser/parse-file-data
                  "test.md"
                  "- Hello World"
                  {:db db
                   :uuid-fn (make-uuid-generator)
                   :now-fn (make-now-generator)
                   :extract-options {:verbose false}})]
      (is (map? result) "Result should be a map")
      (is (contains? result :pages) "Result should contain :pages")
      (is (contains? result :blocks) "Result should contain :blocks")
      (is (contains? result :file-entity) "Result should contain :file-entity")
      (is (contains? result :primary-page) "Result should contain :primary-page")
      (is (contains? result :ast) "Result should contain :ast"))))

(deftest ^:parallel parse-file-data-is-deterministic
  (testing "parsing same input twice with same uuid-fn yields identical structure"
    (let [db @(gp-db/start-conn)
          content "- Block 1\n  - Nested block\n- Block 2"
          ;; Parse twice with fresh generators (same sequence)
          result1 (graph-parser/parse-file-data
                   "test.md"
                   content
                   {:db db
                    :uuid-fn (make-uuid-generator)
                    :now-fn (make-now-generator)
                    :extract-options {:verbose false}})
          result2 (graph-parser/parse-file-data
                   "test.md"
                   content
                   {:db db
                    :uuid-fn (make-uuid-generator)
                    :now-fn (make-now-generator)
                    :extract-options {:verbose false}})]
      ;; Compare pages
      (is (= (count (:pages result1)) (count (:pages result2)))
          "Same number of pages")
      (is (= (map :block/name (:pages result1))
             (map :block/name (:pages result2)))
          "Same page names")
      ;; Note: Page UUIDs use common-uuid/gen-uuid which is time-based
      ;; Full page UUID determinism requires more refactoring (tracked as TODO)
      ;; Compare blocks - block UUIDs should use our uuid-fn
      (is (= (count (:blocks result1)) (count (:blocks result2)))
          "Same number of blocks")
      (is (= (map :block/uuid (:blocks result1))
             (map :block/uuid (:blocks result2)))
          "Same block UUIDs (deterministic)")
      (is (= (map :block/title (:blocks result1))
             (map :block/title (:blocks result2)))
          "Same block titles"))))

(deftest ^:parallel parse-file-data-uses-provided-uuid-fn
  (testing "parse-file-data uses the provided uuid-fn for generating UUIDs"
    (let [db @(gp-db/start-conn)
          ;; Use a generator that produces known UUIDs
          uuid-gen (make-uuid-generator)
          result (graph-parser/parse-file-data
                  "test.md"
                  "- Block with no id property"
                  {:db db
                   :uuid-fn uuid-gen
                   :now-fn (make-now-generator)
                   :extract-options {:verbose false}})
          block-uuids (map :block/uuid (:blocks result))]
      ;; UUIDs should be from our generator (start with zeros)
      (is (every? #(clojure.string/starts-with? (str %) "00000000-")
                  block-uuids)
          "Block UUIDs should come from provided uuid-fn"))))

(deftest ^:parallel parse-file-data-uses-provided-now-fn
  (testing "parse-file-data uses the provided now-fn for timestamps"
    (let [db @(gp-db/start-conn)
          fixed-time (js/Date. "2024-06-15T12:00:00Z")
          result (graph-parser/parse-file-data
                  "test.md"
                  "- Test block"
                  {:db db
                   :uuid-fn (make-uuid-generator)
                   :now-fn (constantly fixed-time)
                   :extract-options {:verbose false}})
          file-entity (:file-entity result)]
      (is (= fixed-time (:file/created-at file-entity))
          "File created-at should use provided now-fn"))))

(deftest ^:parallel parse-file-data-does-not-require-conn
  (testing "parse-file-data takes db value, not conn (no mutation)"
    (let [conn (gp-db/start-conn)
          db-before @conn
          _ (graph-parser/parse-file-data
             "test.md"
             "- Test block"
             {:db @conn
              :uuid-fn (make-uuid-generator)
              :now-fn (make-now-generator)
              :extract-options {:verbose false}})
          db-after @conn]
      (is (= db-before db-after)
          "Database should not be modified by parse-file-data"))))

(deftest ^:parallel parse-file-data-respects-existing-block-ids
  (testing "blocks with id property use that ID, not uuid-fn"
    (let [db @(gp-db/start-conn)
          existing-uuid #uuid "12345678-1234-1234-1234-123456789abc"
          result (graph-parser/parse-file-data
                  "test.md"
                  (str "- Block with custom id\n  id:: " existing-uuid)
                  {:db db
                   :uuid-fn (make-uuid-generator)
                   :now-fn (make-now-generator)
                   :extract-options {:verbose false}})
          block (first (:blocks result))]
      (is (= existing-uuid (:block/uuid block))
          "Block should use id from property, not uuid-fn"))))

(deftest ^:parallel parse-file-data-handles-empty-content
  (testing "empty content returns empty result"
    (let [db @(gp-db/start-conn)
          result (graph-parser/parse-file-data
                  "empty.md"
                  ""
                  {:db db
                   :uuid-fn (make-uuid-generator)
                   :now-fn (make-now-generator)
                   :extract-options {:verbose false}})]
      (is (empty? (:pages result)) "No pages for empty content")
      (is (empty? (:blocks result)) "No blocks for empty content"))))

(deftest ^:parallel parse-file-data-empty-content-preserves-mtime
  (testing "empty content preserves mtime when provided"
    (let [db @(gp-db/start-conn)
          fixed-mtime (js/Date. "2024-12-01T10:30:00Z")
          result (graph-parser/parse-file-data
                  "empty.md"
                  ""
                  {:db db
                   :uuid-fn (make-uuid-generator)
                   :now-fn (make-now-generator)
                   :mtime fixed-mtime
                   :extract-options {:verbose false}})]
      (is (= fixed-mtime (:file/last-modified-at (:file-entity result)))
          "Empty file should preserve mtime"))))

(deftest ^:parallel parse-file-data-returns-primary-page
  (testing "primary-page contains the main file page, not ref pages"
    (let [db @(gp-db/start-conn)
          ;; Content with a link to another page - will create ref page
          content "- Block with [[Another Page]] reference"
          result (graph-parser/parse-file-data
                  "test.md"
                  content
                  {:db db
                   :uuid-fn (make-uuid-generator)
                   :now-fn (make-now-generator)
                   :extract-options {:verbose false}})]
      ;; Primary page should be the file's page (test), not the ref page (another page)
      (is (some? (:primary-page result)) "primary-page should be present")
      (is (= "test" (:block/name (:primary-page result)))
          "primary-page should be the file's page")
      ;; Pages should include both the main page and ref pages
      (is (>= (count (:pages result)) 1)
          "Should have at least one page"))))

(deftest ^:parallel parse-file-data-handles-whiteboard-edn
  (testing "whiteboard edn files are parsed correctly"
    (let [db @(gp-db/start-conn)
          whiteboard-content (pr-str
                              '{:blocks ({:block/title "shape content"
                                          :block/format :markdown})
                                :pages ({:block/format :markdown
                                         :block/name "test-whiteboard"
                                         :block/title "Test Whiteboard"
                                         :block/uuid #uuid "aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee"})})
          result (graph-parser/parse-file-data
                  "/whiteboards/test.edn"
                  whiteboard-content
                  {:db db
                   :uuid-fn (make-uuid-generator)
                   :now-fn (make-now-generator)
                   :extract-options {:verbose false}})]
      (is (= 1 (count (:pages result))) "Should have one page")
      (is (= "test-whiteboard" (:block/name (first (:pages result))))
          "Page name should match"))))

;; =============================================================================
;; Step 2: Behavior Parity Tests (parse-file uses parse-file-data internally)
;; =============================================================================

(deftest ^:parallel parse-file-data-produces-valid-tx-data
  (testing "parsed data can be transacted to produce valid DB state"
    (let [conn (gp-db/start-conn)
          db @conn
          result (graph-parser/parse-file-data
                  "test.md"
                  "title:: My Test Page\n- First block\n- Second block"
                  {:db db
                   :uuid-fn (make-uuid-generator)
                   :now-fn (make-now-generator)
                   :extract-options {:verbose false}})
          ;; Build transaction data manually (this will be build-file-tx later)
          tx-data (concat
                   [(:file-entity result)]
                   (:refs result)
                   (map #(select-keys % [:block/name]) (:pages result))
                   (:pages result)
                   (map (fn [b] {:block/uuid (:block/uuid b)}) (:blocks result))
                   (:blocks result))]
      ;; Should be able to transact without error
      (is (d/transact! conn tx-data))
      ;; Verify data is in DB
      (is (= "my test page" (:block/name (d/entity @conn [:block/name "my test page"])))
          "Page should exist in DB after transact"))))
