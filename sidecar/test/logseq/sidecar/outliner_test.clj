(ns logseq.sidecar.outliner-test
  "Tests for outliner operations.

   These tests verify:
   - save-block updates block content
   - insert-blocks adds new blocks correctly
   - delete-blocks removes blocks and optionally children
   - move-blocks relocates blocks in the tree
   - indent-outdent-blocks changes nesting level
   - apply-ops! handles batched operations

   Run with: cd sidecar && clj -M:test -n logseq.sidecar.outliner-test"
  (:require [clojure.test :refer [deftest testing is use-fixtures]]
            [datascript.core :as d]
            [logseq.sidecar.outliner :as outliner]
            [logseq.sidecar.server :as server]))

;; =============================================================================
;; Test Fixtures
;; =============================================================================

(def ^:dynamic *test-server* nil)
(def ^:dynamic *test-repo* nil)

(def test-schema
  {:block/uuid        {:db/unique :db.unique/identity}
   :block/name        {:db/unique :db.unique/identity}
   :block/parent      {:db/valueType :db.type/ref}
   :block/page        {:db/valueType :db.type/ref}
   :block/refs        {:db/valueType :db.type/ref :db/cardinality :db.cardinality/many}
   :block/content     {}
   :block/title       {}
   :block/type        {}
   :block/format      {}
   :block/order       {}
   :block/created-at  {}
   :block/updated-at  {}})

(defn with-test-server
  "Fixture that creates a fresh server and graph for each test."
  [f]
  (let [server (server/map->SidecarServer
                {:pipe-server nil
                 :ws-server nil
                 :graphs (java.util.concurrent.ConcurrentHashMap.)
                 :running? (atom true)})
        repo "test-outliner-repo"]
    ;; Create graph with test schema
    (server/create-graph server repo {:schema test-schema})
    (try
      (binding [*test-server* server
                *test-repo* repo]
        (f))
      (finally
        (server/remove-graph server repo)
        (reset! (:running? server) false)))))

(use-fixtures :each with-test-server)

;; =============================================================================
;; Helper Functions
;; =============================================================================

(defn get-conn []
  (let [graphs (:graphs *test-server*)]
    (:conn (.get graphs *test-repo*))))

(defn setup-test-page!
  "Create a test page with some blocks for testing."
  []
  (let [conn (get-conn)
        page-uuid (random-uuid)
        block1-uuid (random-uuid)
        block2-uuid (random-uuid)
        block3-uuid (random-uuid)]
    (d/transact! conn
      [;; Page
       {:db/id -1
        :block/uuid page-uuid
        :block/name "test-page"
        :block/title "Test Page"
        :block/type "page"}
       ;; Block 1 (child of page) - order "a" (first)
       {:db/id -2
        :block/uuid block1-uuid
        :block/content "Block 1"
        :block/parent -1
        :block/page -1
        :block/order "a"
        :block/type "block"}
       ;; Block 2 (child of page, after block 1) - order "b" (second)
       {:db/id -3
        :block/uuid block2-uuid
        :block/content "Block 2"
        :block/parent -1
        :block/page -1
        :block/order "b"
        :block/type "block"}
       ;; Block 3 (child of block 1)
       {:db/id -4
        :block/uuid block3-uuid
        :block/content "Block 3 (child of 1)"
        :block/parent -2
        :block/page -1
        :block/order "a"
        :block/type "block"}])
    {:page-uuid page-uuid
     :block1-uuid block1-uuid
     :block2-uuid block2-uuid
     :block3-uuid block3-uuid}))

;; =============================================================================
;; Save Block Tests
;; =============================================================================

(deftest save-block-test
  (testing "save-block updates block content"
    (let [{:keys [block1-uuid]} (setup-test-page!)
          conn (get-conn)]
      ;; Update block content
      (outliner/save-block! conn
                            {:block/uuid block1-uuid
                             :block/content "Updated Block 1"}
                            {})
      ;; Verify update
      (let [block (d/entity @conn [:block/uuid block1-uuid])]
        (is (= "Updated Block 1" (:block/content block)))
        (is (some? (:block/updated-at block))))))

  (testing "save-block preserves other attributes"
    (let [{:keys [block1-uuid]} (setup-test-page!)
          conn (get-conn)
          original (d/entity @conn [:block/uuid block1-uuid])
          original-parent (:db/id (:block/parent original))]
      ;; Update content only
      (outliner/save-block! conn
                            {:block/uuid block1-uuid
                             :block/content "New content"}
                            {})
      ;; Verify parent unchanged
      (let [updated (d/entity @conn [:block/uuid block1-uuid])]
        (is (= original-parent (:db/id (:block/parent updated))))))))

;; =============================================================================
;; Insert Blocks Tests
;; =============================================================================

(deftest insert-blocks-test
  (testing "insert-blocks adds new block as sibling"
    (let [{:keys [block1-uuid]} (setup-test-page!)
          conn (get-conn)
          block1 (d/entity @conn [:block/uuid block1-uuid])
          new-uuid (random-uuid)]
      ;; Insert new block after block1
      (let [result (outliner/insert-blocks! conn
                                            [{:block/uuid new-uuid
                                              :block/content "New Block"}]
                                            (:db/id block1)
                                            {:sibling? true})]
        (is (some? result))
        (is (= 1 (count (:inserted-blocks result)))))
      ;; Verify new block exists with correct parent
      (let [new-block (d/entity @conn [:block/uuid new-uuid])
            parent-id (:db/id (:block/parent block1))]
        (is (some? new-block))
        (is (= "New Block" (:block/content new-block)))
        (is (= parent-id (:db/id (:block/parent new-block)))))))

  (testing "insert-blocks adds new block as child"
    (let [{:keys [block1-uuid]} (setup-test-page!)
          conn (get-conn)
          block1 (d/entity @conn [:block/uuid block1-uuid])
          new-uuid (random-uuid)]
      ;; Insert new block as child of block1
      (outliner/insert-blocks! conn
                               [{:block/uuid new-uuid
                                 :block/content "Child Block"}]
                               (:db/id block1)
                               {:sibling? false})
      ;; Verify new block is child of block1
      (let [new-block (d/entity @conn [:block/uuid new-uuid])]
        (is (some? new-block))
        (is (= (:db/id block1) (:db/id (:block/parent new-block))))))))

;; =============================================================================
;; Delete Blocks Tests
;; =============================================================================

(deftest delete-blocks-test
  (testing "delete-blocks removes block"
    (let [{:keys [block2-uuid]} (setup-test-page!)
          conn (get-conn)
          block2 (d/entity @conn [:block/uuid block2-uuid])
          block2-id (:db/id block2)]
      ;; Delete block2
      (outliner/delete-blocks! conn [block2-id] {})
      ;; Verify block is gone
      (is (nil? (d/entity @conn [:block/uuid block2-uuid])))))

  (testing "delete-blocks removes children by default"
    (let [{:keys [block1-uuid block3-uuid]} (setup-test-page!)
          conn (get-conn)
          block1 (d/entity @conn [:block/uuid block1-uuid])
          block1-id (:db/id block1)]
      ;; Delete block1 (which has block3 as child)
      (outliner/delete-blocks! conn [block1-id] {:children? true})
      ;; Verify both are gone
      (is (nil? (d/entity @conn [:block/uuid block1-uuid])))
      (is (nil? (d/entity @conn [:block/uuid block3-uuid]))))))

;; =============================================================================
;; Move Blocks Tests
;; =============================================================================

(deftest move-blocks-test
  (testing "move-blocks relocates block as sibling"
    (let [{:keys [block1-uuid block2-uuid block3-uuid]} (setup-test-page!)
          conn (get-conn)
          block2 (d/entity @conn [:block/uuid block2-uuid])
          block3 (d/entity @conn [:block/uuid block3-uuid])]
      ;; Move block3 to be sibling of block2
      (outliner/move-blocks! conn
                             [(:db/id block3)]
                             (:db/id block2)
                             {:sibling? true})
      ;; Verify block3's parent changed
      (let [moved-block3 (d/entity @conn [:block/uuid block3-uuid])
            block2-parent (:db/id (:block/parent block2))]
        (is (= block2-parent (:db/id (:block/parent moved-block3)))))))

  (testing "move-blocks relocates block as child"
    (let [{:keys [block2-uuid block3-uuid]} (setup-test-page!)
          conn (get-conn)
          block2 (d/entity @conn [:block/uuid block2-uuid])
          block3 (d/entity @conn [:block/uuid block3-uuid])]
      ;; Move block3 to be child of block2
      (outliner/move-blocks! conn
                             [(:db/id block3)]
                             (:db/id block2)
                             {:sibling? false})
      ;; Verify block3's parent is now block2
      (let [moved-block3 (d/entity @conn [:block/uuid block3-uuid])]
        (is (= (:db/id block2) (:db/id (:block/parent moved-block3))))))))

;; =============================================================================
;; Indent/Outdent Tests
;; =============================================================================

(deftest indent-outdent-test
  (testing "indent makes block child of previous sibling"
    (let [{:keys [block1-uuid block2-uuid]} (setup-test-page!)
          conn (get-conn)
          block1 (d/entity @conn [:block/uuid block1-uuid])
          block2 (d/entity @conn [:block/uuid block2-uuid])]
      ;; Indent block2 (should become child of block1)
      (outliner/indent-outdent-blocks! conn
                                       [(:db/id block2)]
                                       true  ;; indent
                                       {})
      ;; Verify block2's parent is now block1
      (let [indented (d/entity @conn [:block/uuid block2-uuid])]
        (is (= (:db/id block1) (:db/id (:block/parent indented)))))))

  (testing "outdent makes block sibling of parent"
    (let [{:keys [block1-uuid block3-uuid]} (setup-test-page!)
          conn (get-conn)
          block1 (d/entity @conn [:block/uuid block1-uuid])
          block3 (d/entity @conn [:block/uuid block3-uuid])
          ;; block3 is child of block1, block1's parent is page
          grandparent-id (:db/id (:block/parent block1))]
      ;; Outdent block3 (should become sibling of block1)
      (outliner/indent-outdent-blocks! conn
                                       [(:db/id block3)]
                                       false  ;; outdent
                                       {})
      ;; Verify block3's parent is now the page (block1's parent)
      (let [outdented (d/entity @conn [:block/uuid block3-uuid])]
        (is (= grandparent-id (:db/id (:block/parent outdented))))))))

;; =============================================================================
;; Apply Ops Tests (Batched Operations)
;; =============================================================================

(deftest apply-ops-test
  (testing "apply-ops handles multiple operations"
    (let [{:keys [block1-uuid block2-uuid]} (setup-test-page!)
          conn (get-conn)
          block1 (d/entity @conn [:block/uuid block1-uuid])
          block2 (d/entity @conn [:block/uuid block2-uuid])]
      ;; Batch: update block1, delete block2
      (outliner/apply-ops! conn
                           [[:save-block [{:block/uuid block1-uuid
                                           :block/content "Batch updated"} {}]]
                            [:delete-blocks [[(:db/id block2)] {:children? false}]]]
                           {})
      ;; Verify both operations applied
      (let [updated-block1 (d/entity @conn [:block/uuid block1-uuid])]
        (is (= "Batch updated" (:block/content updated-block1))))
      (is (nil? (d/entity @conn [:block/uuid block2-uuid])))))

  (testing "apply-ops handles :transact operation"
    (let [conn (get-conn)
          new-uuid (random-uuid)]
      ;; Raw transact through apply-ops
      (outliner/apply-ops! conn
                           [[:transact [[{:block/uuid new-uuid
                                          :block/name "raw-page"
                                          :block/content "Created via transact"}]
                                        nil]]]
                           {})
      ;; Verify created
      (let [page (d/entity @conn [:block/uuid new-uuid])]
        (is (some? page))
        (is (= "raw-page" (:block/name page)))))))

;; =============================================================================
;; Move Blocks Up/Down Tests
;; =============================================================================

(deftest move-blocks-up-test
  (testing "move-blocks-up swaps with previous sibling"
    (let [{:keys [block1-uuid block2-uuid]} (setup-test-page!)
          conn (get-conn)
          block2 (d/entity @conn [:block/uuid block2-uuid])
          block1 (d/entity @conn [:block/uuid block1-uuid])
          _original-block1-order (:block/order block1)
          _original-block2-order (:block/order block2)]
      ;; Block2 is after block1, move block2 up
      (outliner/move-blocks-up-down! conn [(:db/id block2)] true)
      ;; Verify block2 is now before block1 (by comparing orders)
      (let [updated-block1 (d/entity @conn [:block/uuid block1-uuid])
            updated-block2 (d/entity @conn [:block/uuid block2-uuid])]
        (is (neg? (compare (:block/order updated-block2) (:block/order updated-block1)))
            "Block2 should now have a lower order than Block1")))))

(deftest move-blocks-down-test
  (testing "move-blocks-down swaps with next sibling"
    (let [{:keys [block1-uuid block2-uuid]} (setup-test-page!)
          conn (get-conn)
          ;; Get block1's db/id before moving
          block1-id (:db/id (d/entity @conn [:block/uuid block1-uuid]))]
      ;; Block1 is before block2 (order "a" vs "b"), move block1 down
      (outliner/move-blocks-up-down! conn [block1-id] false)
      ;; Re-query to get fresh values
      (let [db @conn
            block1-order (:block/order (d/pull db [:block/order] [:block/uuid block1-uuid]))
            block2-order (:block/order (d/pull db [:block/order] [:block/uuid block2-uuid]))]
        (is (pos? (compare block1-order block2-order))
            (str "Block1 order (" block1-order ") should be > Block2 order (" block2-order ")"))))))

;; =============================================================================
;; Page Operations Tests
;; =============================================================================

(deftest create-page-test
  (testing "create-page creates a new page"
    (let [conn (get-conn)
          page (outliner/create-page! conn "My New Page" {})]
      (is (some? page))
      (is (= "my new page" (:block/name page)))
      (is (= "My New Page" (:block/title page)))
      (is (= "page" (:block/type page)))
      (is (some? (:block/uuid page)))
      (is (some? (:block/created-at page)))))

  (testing "create-page with custom UUID"
    (let [conn (get-conn)
          custom-uuid #uuid "99999999-9999-9999-9999-999999999999"
          page (outliner/create-page! conn "Custom UUID Page" {:uuid custom-uuid})]
      (is (= custom-uuid (:block/uuid page))))))

(deftest rename-page-test
  (testing "rename-page updates page title and name"
    (let [conn (get-conn)
          page (outliner/create-page! conn "Original Title" {})
          page-uuid (:block/uuid page)]
      ;; Rename the page
      (outliner/rename-page! conn page-uuid "New Title")
      ;; Verify rename
      (let [renamed (d/entity @conn [:block/uuid page-uuid])]
        (is (= "New Title" (:block/title renamed)))
        (is (= "new title" (:block/name renamed)))))))

(deftest delete-page-test
  (testing "delete-page removes page and its blocks"
    (let [{:keys [page-uuid block1-uuid block2-uuid]} (setup-test-page!)
          conn (get-conn)]
      ;; Verify page and blocks exist
      (is (some? (d/entity @conn [:block/uuid page-uuid])))
      (is (some? (d/entity @conn [:block/uuid block1-uuid])))
      ;; Delete the page
      (outliner/delete-page! conn page-uuid)
      ;; Verify page and all blocks are gone
      (is (nil? (d/entity @conn [:block/uuid page-uuid])))
      (is (nil? (d/entity @conn [:block/uuid block1-uuid])))
      (is (nil? (d/entity @conn [:block/uuid block2-uuid]))))))

;; =============================================================================
;; Batch Import Tests
;; =============================================================================

(deftest batch-import-edn-test
  (testing "batch-import-edn imports pages with blocks"
    (let [conn (get-conn)
          page1-uuid (random-uuid)
          block1-uuid (random-uuid)
          block2-uuid (random-uuid)
          block3-uuid (random-uuid)
          import-data {:blocks [{:uuid page1-uuid
                                 :title "Imported Page"
                                 :type "page"
                                 :format :markdown
                                 :children [{:uuid block1-uuid
                                             :content "First block"
                                             :children [{:uuid block3-uuid
                                                         :content "Nested block"}]}
                                            {:uuid block2-uuid
                                             :content "Second block"}]}]}
          result (outliner/batch-import-edn! conn import-data {})]
      ;; Check result statistics
      (is (= 1 (:page-count result)))
      (is (pos? (:block-count result)))
      ;; Verify page was created
      (let [page (d/entity @conn [:block/uuid page1-uuid])]
        (is (some? page))
        (is (= "Imported Page" (:block/title page)))
        (is (= "imported page" (:block/name page)))
        (is (= "page" (:block/type page))))
      ;; Verify blocks were created
      (let [block1 (d/entity @conn [:block/uuid block1-uuid])
            block2 (d/entity @conn [:block/uuid block2-uuid])
            block3 (d/entity @conn [:block/uuid block3-uuid])]
        (is (some? block1))
        (is (= "First block" (:block/content block1)))
        (is (some? block2))
        (is (= "Second block" (:block/content block2)))
        (is (some? block3))
        (is (= "Nested block" (:block/content block3)))
        ;; Verify parent relationships
        (is (= (:db/id (d/entity @conn [:block/uuid page1-uuid]))
               (:db/id (:block/parent block1))))
        (is (= (:db/id block1)
               (:db/id (:block/parent block3)))))))

  (testing "batch-import-edn imports multiple pages"
    (let [conn (get-conn)
          page1-uuid (random-uuid)
          page2-uuid (random-uuid)
          import-data {:blocks [{:uuid page1-uuid
                                 :title "Page One"
                                 :children [{:content "Block in page 1"}]}
                                {:uuid page2-uuid
                                 :title "Page Two"
                                 :children [{:content "Block in page 2"}]}]}
          result (outliner/batch-import-edn! conn import-data {})]
      (is (= 2 (:page-count result)))
      ;; Verify both pages exist
      (is (some? (d/entity @conn [:block/uuid page1-uuid])))
      (is (some? (d/entity @conn [:block/uuid page2-uuid])))))

  (testing "batch-import-edn generates UUIDs when not provided"
    (let [conn (get-conn)
          import-data {:blocks [{:title "Auto UUID Page"
                                 :children [{:content "Block without UUID"}]}]}
          result (outliner/batch-import-edn! conn import-data {})]
      (is (= 1 (:page-count result)))
      ;; Verify page was created (find by name)
      (let [page (d/entity @conn [:block/name "auto uuid page"])]
        (is (some? page))
        (is (uuid? (:block/uuid page)))))))

(deftest batch-import-edn-via-apply-ops-test
  (testing "batch-import-edn works through apply-ops"
    (let [conn (get-conn)
          page-uuid (random-uuid)
          import-data {:blocks [{:uuid page-uuid
                                 :title "Apply Ops Import"
                                 :children [{:content "Child block"}]}]}
          {:keys [result affected-pages]} (outliner/apply-ops! conn
                                                               [[:batch-import-edn [import-data {}]]]
                                                               {})]
      (is (= 1 (:page-count result)))
      (is (some? (d/entity @conn [:block/uuid page-uuid])))
      ;; Verify affected pages tracked
      (is (seq affected-pages)))))

;; =============================================================================
;; Page Tree Export Tests
;; =============================================================================

(deftest get-page-tree-test
  (testing "get-page-tree returns page with blocks as tree"
    (let [{:keys [page-uuid block1-uuid block2-uuid block3-uuid]} (setup-test-page!)
          conn (get-conn)
          page-id (:db/id (d/entity @conn [:block/uuid page-uuid]))
          result (outliner/get-page-tree @conn page-id)]
      ;; Check page attributes
      (is (map? result))
      (is (= page-uuid (:block/uuid (:page result))))
      (is (= "test-page" (:block/name (:page result))))
      (is (= "Test Page" (:block/title (:page result))))
      ;; Check blocks
      (is (vector? (:blocks result)))
      (is (= 2 (count (:blocks result))) "Should have 2 top-level blocks")
      ;; Find block1 and verify its structure
      (let [block1-tree (first (filter #(= block1-uuid (:block/uuid %)) (:blocks result)))]
        (is (some? block1-tree))
        (is (= "Block 1" (:block/content block1-tree)))
        ;; block3 should be a child of block1
        (is (= 1 (count (:children block1-tree))))
        (is (= block3-uuid (:block/uuid (first (:children block1-tree))))))))

  (testing "get-page-tree returns nil for non-existent page"
    (let [conn (get-conn)
          result (outliner/get-page-tree @conn 999999)]
      (is (nil? result)))))

(deftest get-pages-for-file-sync-test
  (testing "get-pages-for-file-sync returns multiple page trees"
    (let [conn (get-conn)
          ;; Create two pages with blocks
          page1 (outliner/create-page! conn "Page One" {})
          page2 (outliner/create-page! conn "Page Two" {})
          _ (outliner/insert-blocks! conn
                                     [{:block/content "Block in page 1"}]
                                     (:db/id page1)
                                     {:sibling? false})
          _ (outliner/insert-blocks! conn
                                     [{:block/content "Block in page 2"}]
                                     (:db/id page2)
                                     {:sibling? false})
          result (outliner/get-pages-for-file-sync @conn [(:db/id page1) (:db/id page2)])]
      (is (= 2 (count result)))
      (is (some #(= "page one" (:block/name (:page %))) result))
      (is (some #(= "page two" (:block/name (:page %))) result))))

  (testing "get-pages-for-file-sync skips non-existent pages"
    (let [conn (get-conn)
          page (outliner/create-page! conn "Real Page" {})
          result (outliner/get-pages-for-file-sync @conn [(:db/id page) 999999])]
      (is (= 1 (count result)))
      (is (= "real page" (:block/name (:page (first result))))))))

;; =============================================================================
;; Affected Pages Tracking Tests
;; =============================================================================

(deftest affected-pages-tracking-test
  (testing "apply-ops tracks affected pages for save-block"
    (let [{:keys [block1-uuid page-uuid]} (setup-test-page!)
          conn (get-conn)
          page-id (:db/id (d/entity @conn [:block/uuid page-uuid]))
          {:keys [affected-pages]} (outliner/apply-ops! conn
                                                        [[:save-block [{:block/uuid block1-uuid
                                                                        :block/content "Updated"} {}]]]
                                                        {})]
      (is (seq affected-pages))
      (is (some #(= page-id %) affected-pages))))

  (testing "apply-ops tracks affected pages for insert-blocks"
    (let [{:keys [block1-uuid page-uuid]} (setup-test-page!)
          conn (get-conn)
          block1 (d/entity @conn [:block/uuid block1-uuid])
          page-id (:db/id (d/entity @conn [:block/uuid page-uuid]))
          {:keys [affected-pages]} (outliner/apply-ops! conn
                                                        [[:insert-blocks [[{:block/content "New"}]
                                                                          (:db/id block1)
                                                                          {:sibling? true}]]]
                                                        {})]
      (is (seq affected-pages))
      (is (some #(= page-id %) affected-pages))))

  (testing "apply-ops tracks affected pages for delete-blocks"
    (let [{:keys [block1-uuid page-uuid]} (setup-test-page!)
          conn (get-conn)
          block1 (d/entity @conn [:block/uuid block1-uuid])
          page-id (:db/id (d/entity @conn [:block/uuid page-uuid]))
          {:keys [affected-pages]} (outliner/apply-ops! conn
                                                        [[:delete-blocks [[(:db/id block1)] {}]]]
                                                        {})]
      (is (seq affected-pages))
      (is (some #(= page-id %) affected-pages))))

  (testing "apply-ops tracks affected pages for create-page"
    (let [conn (get-conn)
          {:keys [result affected-pages]} (outliner/apply-ops! conn
                                                               [[:create-page ["New Page" {}]]]
                                                               {})]
      (is (some? result))
      (is (seq affected-pages))
      (is (some #(= (:db/id result) %) affected-pages))))

  (testing "apply-ops tracks affected pages for rename-page"
    (let [conn (get-conn)
          page (outliner/create-page! conn "Original" {})
          page-id (:db/id page)
          page-uuid (:block/uuid page)
          {:keys [affected-pages]} (outliner/apply-ops! conn
                                                        [[:rename-page [page-uuid "Renamed"]]]
                                                        {})]
      (is (seq affected-pages))
      (is (some #(= page-id %) affected-pages))))

  (testing "apply-ops tracks affected pages for delete-page"
    (let [conn (get-conn)
          page (outliner/create-page! conn "ToDelete" {})
          page-id (:db/id page)
          page-uuid (:block/uuid page)
          {:keys [affected-pages]} (outliner/apply-ops! conn
                                                        [[:delete-page [page-uuid]]]
                                                        {})]
      (is (seq affected-pages))
      (is (some #(= page-id %) affected-pages))))

  (testing "apply-ops tracks multiple affected pages for move-blocks across pages"
    (let [conn (get-conn)
          ;; Create two pages
          page1 (outliner/create-page! conn "Page One" {})
          page2 (outliner/create-page! conn "Page Two" {})
          ;; Add a block to page1
          page1-id (:db/id page1)
          page2-id (:db/id page2)
          _ (d/transact! conn [{:block/uuid (random-uuid)
                                :block/content "Block to move"
                                :block/parent page1-id
                                :block/page page1-id
                                :block/type "block"}])
          block (first (d/q '[:find [?b ...]
                              :in $ ?page
                              :where [?b :block/page ?page]
                                     [?b :block/content _]]
                            @conn page1-id))
          ;; Move block to page2
          {:keys [affected-pages]} (outliner/apply-ops! conn
                                                        [[:move-blocks [[block] page2-id {:sibling? false}]]]
                                                        {})]
      ;; Both pages should be affected
      (is (>= (count affected-pages) 2))
      (is (some #(= page1-id %) affected-pages) "Source page should be affected")
      (is (some #(= page2-id %) affected-pages) "Target page should be affected"))))

;; =============================================================================
;; Server Integration Test
;; =============================================================================

(deftest server-apply-outliner-ops-test
  (testing "thread-api/apply-outliner-ops works via server"
    (let [{:keys [block1-uuid]} (setup-test-page!)
          request {:op :thread-api/apply-outliner-ops
                   :payload {:args [*test-repo*
                                    [[:save-block [{:block/uuid block1-uuid
                                                    :block/content "Via server"} {}]]]
                                    {}]}
                   :id "test-123"}
          response (#'server/handle-request *test-server* request)]
      ;; Verify response is successful
      (is (:ok? response))
      ;; Verify block updated
      (let [conn (get-conn)
            block (d/entity @conn [:block/uuid block1-uuid])]
        (is (= "Via server" (:block/content block))))))

  (testing "page operations via server"
    (let [request {:op :thread-api/apply-outliner-ops
                   :payload {:args [*test-repo*
                                    [[:create-page ["Server Created Page" {}]]]
                                    {}]}
                   :id "test-456"}
          response (#'server/handle-request *test-server* request)]
      (is (:ok? response))
      ;; Verify page was created
      (let [conn (get-conn)
            page (d/entity @conn [:block/name "server created page"])]
        (is (some? page))
        (is (= "Server Created Page" (:block/title page)))))))
