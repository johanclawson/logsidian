(ns frontend.sidecar.parity-test
  "Parity tests to verify sidecar produces identical results to DataScript.

   These tests ensure that queries and transactions through the sidecar
   produce the same results as they would through the web worker's DataScript.

   Test categories:
   - ^:parity - All parity tests (requires running sidecar)"
  (:require [clojure.test :refer [is use-fixtures]]
            [frontend.sidecar.client :as client]
            [frontend.test.helper :as test-helper :include-macros true :refer [deftest-async]]
            [promesa.core :as p]))

;; =============================================================================
;; Configuration
;; =============================================================================

(def ^:const TEST_PORT 47632)

;; =============================================================================
;; Fixtures
;; =============================================================================

(defn- cleanup-before []
  (client/disconnect!))

(defn- cleanup-after []
  (client/disconnect!))

(use-fixtures :each {:before cleanup-before
                     :after cleanup-after})

;; =============================================================================
;; Schema Parity Tests
;; =============================================================================

(deftest-async ^:parity schema-uuid-unique-test
  "Test that :block/uuid is unique as per schema."
  (p/do!
    (p/let [_ (client/connect! {:port TEST_PORT})
            graph-id (str "schema-uuid-" (random-uuid))
            test-uuid (random-uuid)
            _ (client/send-request :create-graph {:graph-id graph-id})
            ;; Transact first block
            _ (client/send-request :thread-api/transact
                {:graph-id graph-id
                 :tx-data [{:block/uuid test-uuid
                            :block/name "first-block"}]})
            ;; Transact second block with same UUID - should upsert
            _ (client/send-request :thread-api/transact
                {:graph-id graph-id
                 :tx-data [{:block/uuid test-uuid
                            :block/name "updated-block"}]})
            ;; Query should return only one entity
            result (client/send-request :thread-api/q
                     {:graph-id graph-id
                      :query '[:find (count ?e) :where [?e :block/uuid]]})]
      (is (= [[1]] result) "UUID should be unique - upsert not duplicate"))))

(deftest-async ^:parity schema-name-unique-test
  "Test that :block/name is unique as per schema."
  (p/do!
    (p/let [_ (client/connect! {:port TEST_PORT})
            graph-id (str "schema-name-" (random-uuid))
            _ (client/send-request :create-graph {:graph-id graph-id})
            ;; Transact first page
            _ (client/send-request :thread-api/transact
                {:graph-id graph-id
                 :tx-data [{:block/uuid (random-uuid)
                            :block/name "test-page"}]})
            ;; Transact second page with same name - should upsert
            _ (client/send-request :thread-api/transact
                {:graph-id graph-id
                 :tx-data [{:block/uuid (random-uuid)
                            :block/name "test-page"
                            :block/title "Updated Page"}]})
            ;; Query should return only one page with that name
            result (client/send-request :thread-api/q
                     {:graph-id graph-id
                      :query '[:find (count ?e) :where [?e :block/name "test-page"]]})]
      (is (= [[1]] result) "Name should be unique - upsert not duplicate"))))

;; =============================================================================
;; Reference Cardinality Tests
;; =============================================================================

(deftest-async ^:parity refs-cardinality-many-test
  "Test that :block/refs has cardinality many."
  (p/do!
    (p/let [_ (client/connect! {:port TEST_PORT})
            graph-id (str "refs-card-" (random-uuid))
            page1-uuid (random-uuid)
            page2-uuid (random-uuid)
            page3-uuid (random-uuid)
            block-uuid (random-uuid)
            _ (client/send-request :create-graph {:graph-id graph-id})
            ;; Create pages
            _ (client/send-request :thread-api/transact
                {:graph-id graph-id
                 :tx-data [{:block/uuid page1-uuid :block/name "page1"}
                           {:block/uuid page2-uuid :block/name "page2"}
                           {:block/uuid page3-uuid :block/name "page3"}]})
            ;; Query page entity IDs
            page-ids (client/send-request :thread-api/q
                       {:graph-id graph-id
                        :query '[:find ?e ?name
                                 :where [?e :block/name ?name]]})
            ;; Create block with multiple refs
            _ (client/send-request :thread-api/transact
                {:graph-id graph-id
                 :tx-data [{:block/uuid block-uuid
                            :block/name "linking-block"
                            :block/refs [[:block/uuid page1-uuid]
                                         [:block/uuid page2-uuid]
                                         [:block/uuid page3-uuid]]}]})
            ;; Query refs count
            result (client/send-request :thread-api/q
                     {:graph-id graph-id
                      :query '[:find (count ?ref)
                               :where
                               [?b :block/name "linking-block"]
                               [?b :block/refs ?ref]]})]
      (is (= [[3]] result) "Block should have 3 refs (cardinality many)"))))

(deftest-async ^:parity tags-cardinality-many-test
  "Test that :block/tags has cardinality many."
  (p/do!
    (p/let [_ (client/connect! {:port TEST_PORT})
            graph-id (str "tags-card-" (random-uuid))
            tag1-uuid (random-uuid)
            tag2-uuid (random-uuid)
            page-uuid (random-uuid)
            _ (client/send-request :create-graph {:graph-id graph-id})
            ;; Create tags
            _ (client/send-request :thread-api/transact
                {:graph-id graph-id
                 :tx-data [{:block/uuid tag1-uuid :block/name "tag1"}
                           {:block/uuid tag2-uuid :block/name "tag2"}]})
            ;; Create page with multiple tags
            _ (client/send-request :thread-api/transact
                {:graph-id graph-id
                 :tx-data [{:block/uuid page-uuid
                            :block/name "tagged-page"
                            :block/tags [[:block/uuid tag1-uuid]
                                         [:block/uuid tag2-uuid]]}]})
            ;; Query tags count
            result (client/send-request :thread-api/q
                     {:graph-id graph-id
                      :query '[:find (count ?tag)
                               :where
                               [?p :block/name "tagged-page"]
                               [?p :block/tags ?tag]]})]
      (is (= [[2]] result) "Page should have 2 tags (cardinality many)"))))

;; =============================================================================
;; Query Result Parity Tests
;; =============================================================================

(deftest-async ^:parity query-find-patterns-test
  "Test various :find patterns return expected shapes."
  (p/do!
    (p/let [_ (client/connect! {:port TEST_PORT})
            graph-id (str "find-patterns-" (random-uuid))
            _ (client/send-request :create-graph {:graph-id graph-id})
            _ (client/send-request :thread-api/transact
                {:graph-id graph-id
                 :tx-data [{:block/uuid (random-uuid) :block/name "page1" :block/title "Page One"}
                           {:block/uuid (random-uuid) :block/name "page2" :block/title "Page Two"}
                           {:block/uuid (random-uuid) :block/name "page3" :block/title "Page Three"}]})
            ;; Test :find ?e (returns tuples)
            tuple-result (client/send-request :thread-api/q
                           {:graph-id graph-id
                            :query '[:find ?name ?title
                                     :where [?e :block/name ?name]
                                            [?e :block/title ?title]]})
            ;; Test :find (count ?e)
            count-result (client/send-request :thread-api/q
                           {:graph-id graph-id
                            :query '[:find (count ?e)
                                     :where [?e :block/name]]})]
      ;; Tuple results should be vectors of vectors
      (is (vector? tuple-result))
      (is (every? vector? tuple-result))
      (is (= 3 (count tuple-result)))
      ;; Count result should be [[3]]
      (is (= [[3]] count-result)))))

(deftest-async ^:parity query-with-inputs-test
  "Test queries with :in inputs."
  (p/do!
    (p/let [_ (client/connect! {:port TEST_PORT})
            graph-id (str "query-inputs-" (random-uuid))
            _ (client/send-request :create-graph {:graph-id graph-id})
            _ (client/send-request :thread-api/transact
                {:graph-id graph-id
                 :tx-data [{:block/uuid (random-uuid) :block/name "target-page"}
                           {:block/uuid (random-uuid) :block/name "other-page"}]})
            ;; Query with input
            result (client/send-request :thread-api/q
                     {:graph-id graph-id
                      :query '[:find ?e
                               :in $ ?name
                               :where [?e :block/name ?name]]
                      :inputs ["target-page"]})]
      (is (= 1 (count result)) "Should find exactly one page matching input"))))

;; =============================================================================
;; Pull Pattern Parity Tests
;; =============================================================================

(deftest-async ^:parity pull-wildcard-test
  "Test pull with wildcard pattern."
  (p/do!
    (p/let [_ (client/connect! {:port TEST_PORT})
            graph-id (str "pull-wildcard-" (random-uuid))
            test-uuid (random-uuid)
            _ (client/send-request :create-graph {:graph-id graph-id})
            _ (client/send-request :thread-api/transact
                {:graph-id graph-id
                 :tx-data [{:block/uuid test-uuid
                            :block/name "test-page"
                            :block/title "Test Page"
                            :block/marker "TODO"}]})
            result (client/send-request :thread-api/pull
                     {:graph-id graph-id
                      :selector '[*]
                      :eid [:block/uuid test-uuid]})]
      (is (map? result))
      (is (= test-uuid (:block/uuid result)))
      (is (= "test-page" (:block/name result)))
      (is (= "Test Page" (:block/title result)))
      (is (= "TODO" (:block/marker result))))))

(deftest-async ^:parity pull-nested-refs-test
  "Test pull with nested ref pattern."
  (p/do!
    (p/let [_ (client/connect! {:port TEST_PORT})
            graph-id (str "pull-nested-" (random-uuid))
            parent-uuid (random-uuid)
            child-uuid (random-uuid)
            _ (client/send-request :create-graph {:graph-id graph-id})
            ;; Create parent page first
            tx1-result (client/send-request :thread-api/transact
                         {:graph-id graph-id
                          :tx-data [{:block/uuid parent-uuid
                                     :block/name "parent-page"}]})
            ;; Create child block with parent ref
            _ (client/send-request :thread-api/transact
                {:graph-id graph-id
                 :tx-data [{:block/uuid child-uuid
                            :block/name "child-block"
                            :block/parent [:block/uuid parent-uuid]}]})
            result (client/send-request :thread-api/pull
                     {:graph-id graph-id
                      :selector '[:block/name {:block/parent [:block/name]}]
                      :eid [:block/uuid child-uuid]})]
      (is (= "child-block" (:block/name result)))
      (is (= "parent-page" (get-in result [:block/parent :block/name]))))))

;; =============================================================================
;; Transaction Result Parity Tests
;; =============================================================================

(deftest-async ^:parity transact-returns-tempids-test
  "Test transaction returns tempids mapping."
  (p/do!
    (p/let [_ (client/connect! {:port TEST_PORT})
            graph-id (str "tempids-" (random-uuid))
            _ (client/send-request :create-graph {:graph-id graph-id})
            result (client/send-request :thread-api/transact
                     {:graph-id graph-id
                      :tx-data [{:block/uuid (random-uuid)
                                 :block/name "new-page"}]})]
      (is (map? result))
      (is (contains? result :tempids))
      (is (map? (:tempids result))))))

(deftest-async ^:parity transact-retract-test
  "Test transaction with retract."
  (p/do!
    (p/let [_ (client/connect! {:port TEST_PORT})
            graph-id (str "retract-" (random-uuid))
            test-uuid (random-uuid)
            _ (client/send-request :create-graph {:graph-id graph-id})
            ;; Add entity
            _ (client/send-request :thread-api/transact
                {:graph-id graph-id
                 :tx-data [{:block/uuid test-uuid
                            :block/name "to-delete"
                            :block/marker "TODO"}]})
            ;; Retract attribute
            _ (client/send-request :thread-api/transact
                {:graph-id graph-id
                 :tx-data [[:db/retract [:block/uuid test-uuid] :block/marker]]})
            ;; Pull should not have marker
            result (client/send-request :thread-api/pull
                     {:graph-id graph-id
                      :selector '[:block/name :block/marker]
                      :eid [:block/uuid test-uuid]})]
      (is (= "to-delete" (:block/name result)))
      (is (nil? (:block/marker result)) "Marker should be retracted"))))

;; =============================================================================
;; Index-based Query Parity Tests
;; =============================================================================

(deftest-async ^:parity datoms-eavt-test
  "Test datoms EAVT index access."
  (p/do!
    (p/let [_ (client/connect! {:port TEST_PORT})
            graph-id (str "datoms-eavt-" (random-uuid))
            test-uuid (random-uuid)
            _ (client/send-request :create-graph {:graph-id graph-id})
            _ (client/send-request :thread-api/transact
                {:graph-id graph-id
                 :tx-data [{:block/uuid test-uuid
                            :block/name "datom-page"
                            :block/title "Datom Test"}]})
            result (client/send-request :thread-api/datoms
                     {:graph-id graph-id
                      :index :eavt
                      :components [[:block/uuid test-uuid]]})]
      (is (vector? result) "Datoms should return a vector")
      (is (pos? (count result)) "Should have datoms for the entity"))))

(deftest-async ^:parity datoms-avet-test
  "Test datoms AVET index access for indexed attributes."
  (p/do!
    (p/let [_ (client/connect! {:port TEST_PORT})
            graph-id (str "datoms-avet-" (random-uuid))
            _ (client/send-request :create-graph {:graph-id graph-id})
            _ (client/send-request :thread-api/transact
                {:graph-id graph-id
                 :tx-data [{:block/uuid (random-uuid)
                            :block/name "indexed-page"
                            :block/created-at 1234567890}]})
            ;; :block/created-at is indexed (db/index true)
            result (client/send-request :thread-api/datoms
                     {:graph-id graph-id
                      :index :avet
                      :components [:block/created-at]})]
      (is (vector? result) "Datoms should return a vector")
      (is (some #(= 1234567890 (nth % 2)) result)
          "Should find the created-at datom via AVET index"))))

;; =============================================================================
;; Complex Query Edge Cases
;; =============================================================================

(deftest-async ^:parity query-or-clause-test
  "Test queries with or clauses."
  (p/do!
    (p/let [_ (client/connect! {:port TEST_PORT})
            graph-id (str "query-or-" (random-uuid))
            _ (client/send-request :create-graph {:graph-id graph-id})
            _ (client/send-request :thread-api/transact
                {:graph-id graph-id
                 :tx-data [{:block/uuid (random-uuid) :block/name "page-a" :block/marker "TODO"}
                           {:block/uuid (random-uuid) :block/name "page-b" :block/marker "DONE"}
                           {:block/uuid (random-uuid) :block/name "page-c" :block/marker "WAITING"}]})
            ;; Query with or clause
            result (client/send-request :thread-api/q
                     {:graph-id graph-id
                      :query '[:find ?name
                               :where
                               [?e :block/name ?name]
                               (or [?e :block/marker "TODO"]
                                   [?e :block/marker "DONE"])]})]
      (is (= 2 (count result)) "Should find 2 pages with TODO or DONE marker")
      (is (= #{["page-a"] ["page-b"]} (set result))))))

(deftest-async ^:parity query-not-clause-test
  "Test queries with not clauses."
  (p/do!
    (p/let [_ (client/connect! {:port TEST_PORT})
            graph-id (str "query-not-" (random-uuid))
            _ (client/send-request :create-graph {:graph-id graph-id})
            _ (client/send-request :thread-api/transact
                {:graph-id graph-id
                 :tx-data [{:block/uuid (random-uuid) :block/name "page-with-marker" :block/marker "TODO"}
                           {:block/uuid (random-uuid) :block/name "page-without-marker"}]})
            ;; Query with not clause - find pages without marker
            result (client/send-request :thread-api/q
                     {:graph-id graph-id
                      :query '[:find ?name
                               :where
                               [?e :block/name ?name]
                               (not [?e :block/marker])]})]
      (is (= 1 (count result)) "Should find 1 page without marker")
      (is (= [["page-without-marker"]] result)))))

(deftest-async ^:parity query-not-join-test
  "Test queries with not-join clauses."
  (p/do!
    (p/let [_ (client/connect! {:port TEST_PORT})
            graph-id (str "query-not-join-" (random-uuid))
            parent1-uuid (random-uuid)
            parent2-uuid (random-uuid)
            child-uuid (random-uuid)
            _ (client/send-request :create-graph {:graph-id graph-id})
            _ (client/send-request :thread-api/transact
                {:graph-id graph-id
                 :tx-data [{:block/uuid parent1-uuid :block/name "parent-with-child"}
                           {:block/uuid parent2-uuid :block/name "parent-without-child"}
                           {:block/uuid child-uuid :block/name "child" :block/parent [:block/uuid parent1-uuid]}]})
            ;; Find pages that have no children
            result (client/send-request :thread-api/q
                     {:graph-id graph-id
                      :query '[:find ?name
                               :where
                               [?e :block/name ?name]
                               (not-join [?e]
                                 [?child :block/parent ?e])]})]
      ;; parent-without-child and child itself have no children
      (is (= 2 (count result)) "Should find 2 entities without children"))))

(deftest-async ^:parity query-rules-test
  "Test queries with rules."
  (p/do!
    (p/let [_ (client/connect! {:port TEST_PORT})
            graph-id (str "query-rules-" (random-uuid))
            grandparent-uuid (random-uuid)
            parent-uuid (random-uuid)
            child-uuid (random-uuid)
            _ (client/send-request :create-graph {:graph-id graph-id})
            _ (client/send-request :thread-api/transact
                {:graph-id graph-id
                 :tx-data [{:block/uuid grandparent-uuid :block/name "grandparent"}
                           {:block/uuid parent-uuid :block/name "parent" :block/parent [:block/uuid grandparent-uuid]}
                           {:block/uuid child-uuid :block/name "child" :block/parent [:block/uuid parent-uuid]}]})
            ;; Query with recursive ancestor rule
            result (client/send-request :thread-api/q
                     {:graph-id graph-id
                      :query '[:find ?ancestor-name
                               :in $ % ?child-name
                               :where
                               [?c :block/name ?child-name]
                               (ancestor ?c ?a)
                               [?a :block/name ?ancestor-name]]
                      :inputs [;; Rules for ancestor relationship
                               '[[(ancestor ?c ?a)
                                  [?c :block/parent ?a]]
                                 [(ancestor ?c ?a)
                                  [?c :block/parent ?p]
                                  (ancestor ?p ?a)]]
                               "child"]})]
      (is (= 2 (count result)) "Child should have 2 ancestors")
      (is (= #{["parent"] ["grandparent"]} (set result))))))

(deftest-async ^:parity query-aggregates-test
  "Test queries with various aggregates."
  (p/do!
    (p/let [_ (client/connect! {:port TEST_PORT})
            graph-id (str "query-agg-" (random-uuid))
            _ (client/send-request :create-graph {:graph-id graph-id})
            _ (client/send-request :thread-api/transact
                {:graph-id graph-id
                 :tx-data [{:block/uuid (random-uuid) :block/name "page1" :block/journal-day 20240101}
                           {:block/uuid (random-uuid) :block/name "page2" :block/journal-day 20240102}
                           {:block/uuid (random-uuid) :block/name "page3" :block/journal-day 20240103}
                           {:block/uuid (random-uuid) :block/name "page4" :block/journal-day 20240101}]})
            ;; Test count
            count-result (client/send-request :thread-api/q
                           {:graph-id graph-id
                            :query '[:find (count ?e) :where [?e :block/journal-day]]})
            ;; Test min
            min-result (client/send-request :thread-api/q
                         {:graph-id graph-id
                          :query '[:find (min ?day) :where [?e :block/journal-day ?day]]})
            ;; Test max
            max-result (client/send-request :thread-api/q
                         {:graph-id graph-id
                          :query '[:find (max ?day) :where [?e :block/journal-day ?day]]})
            ;; Test count-distinct
            distinct-result (client/send-request :thread-api/q
                              {:graph-id graph-id
                               :query '[:find (count-distinct ?day) :where [?e :block/journal-day ?day]]})]
      (is (= [[4]] count-result) "Should count 4 entities")
      (is (= [[20240101]] min-result) "Min should be 20240101")
      (is (= [[20240103]] max-result) "Max should be 20240103")
      (is (= [[3]] distinct-result) "Should have 3 distinct days"))))

(deftest-async ^:parity query-collection-input-test
  "Test queries with collection inputs."
  (p/do!
    (p/let [_ (client/connect! {:port TEST_PORT})
            graph-id (str "query-coll-" (random-uuid))
            _ (client/send-request :create-graph {:graph-id graph-id})
            _ (client/send-request :thread-api/transact
                {:graph-id graph-id
                 :tx-data [{:block/uuid (random-uuid) :block/name "page1"}
                           {:block/uuid (random-uuid) :block/name "page2"}
                           {:block/uuid (random-uuid) :block/name "page3"}
                           {:block/uuid (random-uuid) :block/name "page4"}]})
            ;; Query with collection input (find pages in list)
            result (client/send-request :thread-api/q
                     {:graph-id graph-id
                      :query '[:find ?name
                               :in $ [?name ...]
                               :where [?e :block/name ?name]]
                      :inputs [["page1" "page3"]]})]
      (is (= 2 (count result)) "Should find 2 pages from collection")
      (is (= #{["page1"] ["page3"]} (set result))))))

(deftest-async ^:parity query-tuple-input-test
  "Test queries with tuple inputs."
  (p/do!
    (p/let [_ (client/connect! {:port TEST_PORT})
            graph-id (str "query-tuple-" (random-uuid))
            _ (client/send-request :create-graph {:graph-id graph-id})
            _ (client/send-request :thread-api/transact
                {:graph-id graph-id
                 :tx-data [{:block/uuid (random-uuid) :block/name "page1" :block/marker "TODO"}
                           {:block/uuid (random-uuid) :block/name "page2" :block/marker "DONE"}
                           {:block/uuid (random-uuid) :block/name "page3" :block/marker "TODO"}]})
            ;; Query with tuple input
            result (client/send-request :thread-api/q
                     {:graph-id graph-id
                      :query '[:find ?e
                               :in $ [?name ?marker]
                               :where
                               [?e :block/name ?name]
                               [?e :block/marker ?marker]]
                      :inputs [["page1" "TODO"]]})]
      (is (= 1 (count result)) "Should find exactly one matching entity"))))

;; =============================================================================
;; Nested Transaction Edge Cases
;; =============================================================================

(deftest-async ^:parity transact-nested-refs-test
  "Test transaction with deeply nested references."
  (p/do!
    (p/let [_ (client/connect! {:port TEST_PORT})
            graph-id (str "nested-refs-" (random-uuid))
            root-uuid (random-uuid)
            level1-uuid (random-uuid)
            level2-uuid (random-uuid)
            level3-uuid (random-uuid)
            _ (client/send-request :create-graph {:graph-id graph-id})
            ;; Create deeply nested structure in single transaction
            _ (client/send-request :thread-api/transact
                {:graph-id graph-id
                 :tx-data [{:block/uuid root-uuid :block/name "root"}
                           {:block/uuid level1-uuid :block/name "level1" :block/parent [:block/uuid root-uuid]}
                           {:block/uuid level2-uuid :block/name "level2" :block/parent [:block/uuid level1-uuid]}
                           {:block/uuid level3-uuid :block/name "level3" :block/parent [:block/uuid level2-uuid]}]})
            ;; Pull with nested pattern to verify structure
            result (client/send-request :thread-api/pull
                     {:graph-id graph-id
                      :selector '[:block/name {:block/parent [:block/name {:block/parent [:block/name {:block/parent [:block/name]}]}]}]
                      :eid [:block/uuid level3-uuid]})]
      (is (= "level3" (:block/name result)))
      (is (= "level2" (get-in result [:block/parent :block/name])))
      (is (= "level1" (get-in result [:block/parent :block/parent :block/name])))
      (is (= "root" (get-in result [:block/parent :block/parent :block/parent :block/name]))))))

(deftest-async ^:parity transact-tempid-refs-test
  "Test transaction with tempid references within same transaction."
  (p/do!
    (p/let [_ (client/connect! {:port TEST_PORT})
            graph-id (str "tempid-refs-" (random-uuid))
            _ (client/send-request :create-graph {:graph-id graph-id})
            ;; Use negative tempids to reference entities within same tx
            tx-result (client/send-request :thread-api/transact
                        {:graph-id graph-id
                         :tx-data [{:db/id -1
                                    :block/uuid (random-uuid)
                                    :block/name "parent-via-tempid"}
                                   {:db/id -2
                                    :block/uuid (random-uuid)
                                    :block/name "child-via-tempid"
                                    :block/parent -1}]})
            ;; Query to verify the relationship was established
            result (client/send-request :thread-api/q
                     {:graph-id graph-id
                      :query '[:find ?child-name ?parent-name
                               :where
                               [?c :block/name ?child-name]
                               [?c :block/parent ?p]
                               [?p :block/name ?parent-name]]})]
      (is (map? (:tempids tx-result)) "Should return tempids")
      (is (= 1 (count result)) "Should have one parent-child relationship")
      (is (= [["child-via-tempid" "parent-via-tempid"]] result)))))

(deftest-async ^:parity transact-db-add-test
  "Test transaction with explicit :db/add operations."
  (p/do!
    (p/let [_ (client/connect! {:port TEST_PORT})
            graph-id (str "db-add-" (random-uuid))
            test-uuid (random-uuid)
            _ (client/send-request :create-graph {:graph-id graph-id})
            ;; First create entity with map syntax
            _ (client/send-request :thread-api/transact
                {:graph-id graph-id
                 :tx-data [{:block/uuid test-uuid :block/name "test-page"}]})
            ;; Then add attributes with :db/add syntax
            _ (client/send-request :thread-api/transact
                {:graph-id graph-id
                 :tx-data [[:db/add [:block/uuid test-uuid] :block/marker "TODO"]
                           [:db/add [:block/uuid test-uuid] :block/priority "A"]]})
            ;; Pull to verify
            result (client/send-request :thread-api/pull
                     {:graph-id graph-id
                      :selector '[:block/name :block/marker :block/priority]
                      :eid [:block/uuid test-uuid]})]
      (is (= "test-page" (:block/name result)))
      (is (= "TODO" (:block/marker result)))
      (is (= "A" (:block/priority result))))))

(deftest-async ^:parity transact-retract-entity-test
  "Test transaction with :db/retractEntity."
  (p/do!
    (p/let [_ (client/connect! {:port TEST_PORT})
            graph-id (str "retract-entity-" (random-uuid))
            test-uuid (random-uuid)
            _ (client/send-request :create-graph {:graph-id graph-id})
            ;; Create entity
            _ (client/send-request :thread-api/transact
                {:graph-id graph-id
                 :tx-data [{:block/uuid test-uuid
                            :block/name "to-be-retracted"
                            :block/marker "TODO"
                            :block/content "Some content"}]})
            ;; Verify it exists
            before-count (client/send-request :thread-api/q
                           {:graph-id graph-id
                            :query '[:find (count ?e) :where [?e :block/name "to-be-retracted"]]})
            ;; Retract entire entity
            _ (client/send-request :thread-api/transact
                {:graph-id graph-id
                 :tx-data [[:db/retractEntity [:block/uuid test-uuid]]]})
            ;; Verify it's gone - use direct find, not count (count of 0 returns empty result)
            after-result (client/send-request :thread-api/q
                           {:graph-id graph-id
                            :query '[:find ?e :where [?e :block/name "to-be-retracted"]]})]
      (is (= [[1]] before-count) "Entity should exist before retract")
      (is (= [] after-result) "Entity should be gone after retract"))))

(deftest-async ^:parity transact-cardinality-many-ops-test
  "Test add/retract operations on cardinality-many attributes."
  (p/do!
    (p/let [_ (client/connect! {:port TEST_PORT})
            graph-id (str "card-many-ops-" (random-uuid))
            page-uuid (random-uuid)
            tag1-uuid (random-uuid)
            tag2-uuid (random-uuid)
            tag3-uuid (random-uuid)
            _ (client/send-request :create-graph {:graph-id graph-id})
            ;; Create tags and page
            _ (client/send-request :thread-api/transact
                {:graph-id graph-id
                 :tx-data [{:block/uuid tag1-uuid :block/name "tag1"}
                           {:block/uuid tag2-uuid :block/name "tag2"}
                           {:block/uuid tag3-uuid :block/name "tag3"}
                           {:block/uuid page-uuid
                            :block/name "tagged-page"
                            :block/tags [[:block/uuid tag1-uuid]
                                         [:block/uuid tag2-uuid]]}]})
            ;; Count initial tags
            initial-count (client/send-request :thread-api/q
                            {:graph-id graph-id
                             :query '[:find (count ?t)
                                      :where
                                      [?p :block/name "tagged-page"]
                                      [?p :block/tags ?t]]})
            ;; Add another tag
            _ (client/send-request :thread-api/transact
                {:graph-id graph-id
                 :tx-data [[:db/add [:block/uuid page-uuid] :block/tags [:block/uuid tag3-uuid]]]})
            after-add-count (client/send-request :thread-api/q
                              {:graph-id graph-id
                               :query '[:find (count ?t)
                                        :where
                                        [?p :block/name "tagged-page"]
                                        [?p :block/tags ?t]]})
            ;; Retract one tag
            _ (client/send-request :thread-api/transact
                {:graph-id graph-id
                 :tx-data [[:db/retract [:block/uuid page-uuid] :block/tags [:block/uuid tag1-uuid]]]})
            after-retract-count (client/send-request :thread-api/q
                                  {:graph-id graph-id
                                   :query '[:find (count ?t)
                                            :where
                                            [?p :block/name "tagged-page"]
                                            [?p :block/tags ?t]]})]
      (is (= [[2]] initial-count) "Should start with 2 tags")
      (is (= [[3]] after-add-count) "Should have 3 tags after add")
      (is (= [[2]] after-retract-count) "Should have 2 tags after retract"))))

;; =============================================================================
;; Pull-Many Edge Cases
;; =============================================================================

(deftest-async ^:parity pull-many-mixed-results-test
  "Test pull-many with mix of existing and non-existing entities."
  (p/do!
    (p/let [_ (client/connect! {:port TEST_PORT})
            graph-id (str "pull-many-mixed-" (random-uuid))
            uuid1 (random-uuid)
            uuid2 (random-uuid)
            non-existent-uuid (random-uuid)
            _ (client/send-request :create-graph {:graph-id graph-id})
            _ (client/send-request :thread-api/transact
                {:graph-id graph-id
                 :tx-data [{:block/uuid uuid1 :block/name "page1"}
                           {:block/uuid uuid2 :block/name "page2"}]})
            ;; Pull multiple entities including one that doesn't exist
            result (client/send-request :thread-api/pull-many
                     {:graph-id graph-id
                      :selector '[:block/name]
                      :eids [[:block/uuid uuid1]
                             [:block/uuid non-existent-uuid]
                             [:block/uuid uuid2]]})]
      (is (vector? result))
      (is (= 3 (count result)) "Should return 3 results")
      ;; Non-existent entity returns empty map or nil
      (is (= "page1" (:block/name (first result))))
      (is (= "page2" (:block/name (nth result 2)))))))

(deftest-async ^:parity pull-reverse-ref-test
  "Test pull with reverse reference pattern."
  (p/do!
    (p/let [_ (client/connect! {:port TEST_PORT})
            graph-id (str "pull-reverse-" (random-uuid))
            parent-uuid (random-uuid)
            child1-uuid (random-uuid)
            child2-uuid (random-uuid)
            _ (client/send-request :create-graph {:graph-id graph-id})
            _ (client/send-request :thread-api/transact
                {:graph-id graph-id
                 :tx-data [{:block/uuid parent-uuid :block/name "parent"}
                           {:block/uuid child1-uuid :block/name "child1" :block/parent [:block/uuid parent-uuid]}
                           {:block/uuid child2-uuid :block/name "child2" :block/parent [:block/uuid parent-uuid]}]})
            ;; Pull parent with reverse refs to get children
            result (client/send-request :thread-api/pull
                     {:graph-id graph-id
                      :selector '[:block/name {:block/_parent [:block/name]}]
                      :eid [:block/uuid parent-uuid]})]
      (is (= "parent" (:block/name result)))
      (is (= 2 (count (:block/_parent result))) "Should have 2 children via reverse ref")
      (is (= #{"child1" "child2"} (set (map :block/name (:block/_parent result))))))))

;; =============================================================================
;; Edge Cases for Data Types
;; =============================================================================

(deftest-async ^:parity data-types-test
  "Test various data types are preserved through round-trip."
  (p/do!
    (p/let [_ (client/connect! {:port TEST_PORT})
            graph-id (str "data-types-" (random-uuid))
            test-uuid (random-uuid)
            _ (client/send-request :create-graph {:graph-id graph-id})
            _ (client/send-request :thread-api/transact
                {:graph-id graph-id
                 :tx-data [{:block/uuid test-uuid
                            :block/name "data-test"
                            :block/collapsed? true           ;; boolean
                            :block/journal-day 20240115      ;; integer
                            :block/content "String content"  ;; string
                            :block/properties {:key1 "val1" :key2 42}}]})  ;; map
            result (client/send-request :thread-api/pull
                     {:graph-id graph-id
                      :selector '[*]
                      :eid [:block/uuid test-uuid]})]
      (is (= true (:block/collapsed? result)) "Boolean should be preserved")
      (is (= 20240115 (:block/journal-day result)) "Integer should be preserved")
      (is (= "String content" (:block/content result)) "String should be preserved")
      (is (= test-uuid (:block/uuid result)) "UUID should be preserved")
      (is (map? (:block/properties result)) "Map should be preserved"))))

(deftest-async ^:parity empty-results-test
  "Test handling of empty results."
  (p/do!
    (p/let [_ (client/connect! {:port TEST_PORT})
            graph-id (str "empty-results-" (random-uuid))
            _ (client/send-request :create-graph {:graph-id graph-id})
            ;; Query empty graph
            query-result (client/send-request :thread-api/q
                           {:graph-id graph-id
                            :query '[:find ?e :where [?e :block/name]]})
            ;; Pull non-existent entity
            pull-result (client/send-request :thread-api/pull
                          {:graph-id graph-id
                           :selector '[:block/name]
                           :eid [:block/uuid (random-uuid)]})
            ;; Datoms on empty graph
            datoms-result (client/send-request :thread-api/datoms
                            {:graph-id graph-id
                             :index :avet
                             :components [:block/name]})]
      (is (= [] query-result) "Empty query should return empty vector")
      (is (or (nil? pull-result) (= {} pull-result)) "Pull non-existent should return nil or empty map")
      (is (= [] datoms-result) "Empty datoms should return empty vector"))))
