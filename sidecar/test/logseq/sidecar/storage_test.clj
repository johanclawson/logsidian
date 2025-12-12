(ns logseq.sidecar.storage-test
  "Tests for DataScript IStorage implementation with SQLite-JDBC backing.

   These tests verify:
   - Basic store/restore operations via IStorage protocol
   - Soft references release under memory pressure
   - Data persistence across restarts
   - Integration with DataScript connections

   Run with: cd sidecar && clj -M:test -n logseq.sidecar.storage-test"
  (:require [clojure.test :refer [deftest testing is use-fixtures]]
            [datascript.core :as d]
            [next.jdbc :as jdbc])
  (:import [java.io File]))

;; =============================================================================
;; Test Fixtures
;; =============================================================================

(def ^:dynamic *test-db-path* nil)

(defn with-temp-db
  "Fixture that creates a temporary database file for each test."
  [f]
  (let [temp-file (File/createTempFile "logsidian-test-" ".db")
        path (.getAbsolutePath temp-file)]
    (try
      (binding [*test-db-path* path]
        (f))
      (finally
        (.delete temp-file)))))

(use-fixtures :each with-temp-db)

;; =============================================================================
;; Placeholder: Storage Functions to Implement
;; =============================================================================

;; These functions will be implemented in logseq.sidecar.storage
;; For now, require them conditionally to make tests fail with clear messages

(defn require-storage
  "Attempt to require storage namespace, return nil if not found."
  []
  (try
    (require '[logseq.sidecar.storage :as storage])
    @(resolve 'logseq.sidecar.storage/create-sqlite-storage)
    (catch Exception _
      nil)))

;; =============================================================================
;; IStorage Basic Operations
;; =============================================================================

(deftest storage-creation-test
  (testing "Create SQLite storage instance"
    (if-let [create-fn (require-storage)]
      (let [storage (create-fn *test-db-path*)]
        (is (some? storage) "Storage should be created")
        ;; Storage should implement IStorage protocol
        (is (satisfies? datascript.storage/IStorage storage)
            "Storage should implement IStorage protocol"))
      (is false "logseq.sidecar.storage/create-sqlite-storage not implemented yet"))))

(deftest storage-basic-operations-test
  (testing "Store and restore datoms via DataScript"
    (if-let [create-fn (require-storage)]
      (let [storage (create-fn *test-db-path*)
            schema {:block/name {:db/unique :db.unique/identity}
                    :block/uuid {:db/unique :db.unique/identity}}
            conn (d/create-conn schema {:storage storage})]
        ;; Transact some data
        (d/transact! conn [{:block/name "test-page"
                            :block/uuid (random-uuid)
                            :block/content "Hello, World!"}])

        ;; Query should work
        (is (some? (d/entity @conn [:block/name "test-page"]))
            "Entity should be queryable after transact")

        ;; Verify content
        (let [entity (d/entity @conn [:block/name "test-page"])]
          (is (= "Hello, World!" (:block/content entity))
              "Content should match")))
      (is false "logseq.sidecar.storage/create-sqlite-storage not implemented yet"))))

(deftest storage-persistence-test
  (testing "Data persists across storage restarts"
    (if-let [create-fn (require-storage)]
      (let [test-uuid #uuid "12345678-1234-1234-1234-123456789abc"]
        ;; Create and populate
        (let [storage (create-fn *test-db-path*)
              schema {:block/uuid {:db/unique :db.unique/identity}}
              conn (d/create-conn schema {:storage storage})]
          (d/transact! conn [{:block/uuid test-uuid
                              :block/content "Persistent block"}]))

        ;; Reopen and verify using d/restore-conn
        (let [storage (create-fn *test-db-path*)
              conn (d/restore-conn storage)]
          (is (some? (d/entity @conn [:block/uuid test-uuid]))
              "Entity should exist after restart")
          (is (= "Persistent block"
                 (:block/content (d/entity @conn [:block/uuid test-uuid])))
              "Content should persist")))
      (is false "logseq.sidecar.storage/create-sqlite-storage not implemented yet"))))

(deftest storage-in-memory-test
  (testing "In-memory storage for testing (no file persistence)"
    (if-let [create-fn (require-storage)]
      (let [storage (create-fn ":memory:")
            schema {:block/name {:db/unique :db.unique/identity}}
            conn (d/create-conn schema {:storage storage})]
        ;; Should work like normal
        (d/transact! conn [{:block/name "temp-page"
                            :block/content "Temporary"}])
        (is (some? (d/entity @conn [:block/name "temp-page"]))
            "In-memory storage should work"))
      (is false "logseq.sidecar.storage/create-sqlite-storage not implemented yet"))))

;; =============================================================================
;; Soft References (Memory Management)
;; =============================================================================

(deftest storage-soft-references-test
  (testing "Storage with soft references (:ref-type :soft)"
    (if-let [create-fn (require-storage)]
      (let [storage (create-fn ":memory:")
            schema {:block/uuid {:db/unique :db.unique/identity}}
            ;; Create conn with :ref-type :soft for automatic memory management
            conn (d/create-conn schema {:storage storage
                                        :ref-type :soft})]
        ;; Transact data
        (d/transact! conn [{:block/uuid (random-uuid)
                            :block/content "Block with soft ref"}])

        ;; Should still be queryable
        (is (pos? (count (d/datoms @conn :eavt)))
            "Datoms should be present"))
      (is false "logseq.sidecar.storage/create-sqlite-storage not implemented yet"))))

(deftest storage-memory-pressure-test
  (testing "Soft references release under memory pressure"
    (if-let [create-fn (require-storage)]
      (let [storage (create-fn ":memory:")
            conn (d/create-conn {} {:storage storage :ref-type :soft})]
        ;; Transact many blocks to create memory pressure
        (doseq [batch (partition-all 100 (range 5000))]
          (d/transact! conn
                       (vec (for [i batch]
                              {:block/uuid (random-uuid)
                               :block/content (str "Block " i " with some content to take up space")}))))

        ;; Force GC to trigger soft reference clearing
        (System/gc)
        (Thread/sleep 100)

        ;; Data should still be accessible (lazy-loaded from SQLite)
        (let [sample-query (d/q '[:find ?e .
                                  :where [?e :block/content _]]
                                @conn)]
          (is (some? sample-query)
              "Data should still be queryable after GC (lazy loaded)")))
      (is false "logseq.sidecar.storage/create-sqlite-storage not implemented yet"))))

;; =============================================================================
;; Transaction Operations
;; =============================================================================

(deftest storage-multiple-transactions-test
  (testing "Multiple transactions persist correctly"
    (if-let [create-fn (require-storage)]
      (let [storage (create-fn *test-db-path*)
            schema {:block/uuid {:db/unique :db.unique/identity}}
            conn (d/create-conn schema {:storage storage})]
        ;; Multiple transactions
        (let [uuid1 (random-uuid)
              uuid2 (random-uuid)
              uuid3 (random-uuid)]
          (d/transact! conn [{:block/uuid uuid1 :block/content "First"}])
          (d/transact! conn [{:block/uuid uuid2 :block/content "Second"}])
          (d/transact! conn [{:block/uuid uuid3 :block/content "Third"}])

          ;; All should be queryable
          (is (= 3 (count (d/q '[:find ?e :where [?e :block/uuid _]] @conn)))
              "All three entities should exist")))
      (is false "logseq.sidecar.storage/create-sqlite-storage not implemented yet"))))

(deftest storage-retraction-test
  (testing "Retractions persist correctly"
    (if-let [create-fn (require-storage)]
      (let [storage (create-fn *test-db-path*)
            schema {:block/uuid {:db/unique :db.unique/identity}}
            conn (d/create-conn schema {:storage storage})
            test-uuid (random-uuid)]
        ;; Add and then retract
        (d/transact! conn [{:block/uuid test-uuid :block/content "To be deleted"}])
        (let [eid (:db/id (d/entity @conn [:block/uuid test-uuid]))]
          (d/transact! conn [[:db/retractEntity eid]]))

        ;; Should be gone
        (is (nil? (d/entity @conn [:block/uuid test-uuid]))
            "Entity should be retracted"))
      (is false "logseq.sidecar.storage/create-sqlite-storage not implemented yet"))))

;; =============================================================================
;; Schema Handling
;; =============================================================================

(deftest storage-schema-persistence-test
  (testing "Schema persists with storage"
    (if-let [create-fn (require-storage)]
      (let [schema {:block/name {:db/unique :db.unique/identity}
                    :block/refs {:db/valueType :db.type/ref
                                 :db/cardinality :db.cardinality/many}
                    :block/parent {:db/valueType :db.type/ref}}]
        ;; Create with schema
        (let [storage (create-fn *test-db-path*)
              conn (d/create-conn schema {:storage storage})]
          (d/transact! conn [{:block/name "page1"}]))

        ;; Restore should have same schema
        (let [storage (create-fn *test-db-path*)
              conn (d/restore-conn storage)]
          (is (= :db.unique/identity
                 (get-in (d/schema @conn) [:block/name :db/unique]))
              "Schema should persist")))
      (is false "logseq.sidecar.storage/create-sqlite-storage not implemented yet"))))

;; =============================================================================
;; Index Operations
;; =============================================================================

(deftest storage-datoms-index-test
  (testing "Datoms indexes work with storage"
    (if-let [create-fn (require-storage)]
      (let [storage (create-fn ":memory:")
            schema {:block/name {:db/unique :db.unique/identity
                                 :db/index true}}
            conn (d/create-conn schema {:storage storage})]
        (d/transact! conn [{:block/name "page-a" :block/content "AAA"}
                           {:block/name "page-b" :block/content "BBB"}
                           {:block/name "page-c" :block/content "CCC"}])

        ;; AVET index lookup
        (let [datoms (d/datoms @conn :avet :block/name)]
          (is (= 3 (count datoms))
              "Should find all names via AVET index"))

        ;; EAVT index
        (let [entity (d/entity @conn [:block/name "page-a"])
              eid (:db/id entity)
              datoms (d/datoms @conn :eavt eid)]
          (is (>= (count datoms) 2)
              "Should find datoms for entity via EAVT")))
      (is false "logseq.sidecar.storage/create-sqlite-storage not implemented yet"))))

;; =============================================================================
;; Query Performance (Sanity Check)
;; =============================================================================

(deftest storage-query-performance-test
  (testing "Queries complete in reasonable time"
    (if-let [create-fn (require-storage)]
      (let [storage (create-fn ":memory:")
            schema {:block/uuid {:db/unique :db.unique/identity}
                    :block/name {:db/unique :db.unique/identity}}
            conn (d/create-conn schema {:storage storage})]
        ;; Insert 1000 blocks
        (d/transact! conn
                     (vec (for [i (range 1000)]
                            {:block/uuid (random-uuid)
                             :block/name (str "page-" i)
                             :block/content (str "Content for page " i)})))

        ;; Simple query should be fast
        (let [start (System/currentTimeMillis)
              result (d/entity @conn [:block/name "page-500"])
              elapsed (- (System/currentTimeMillis) start)]
          (is (some? result) "Query should return result")
          (is (< elapsed 100) (str "Query took " elapsed "ms, should be < 100ms"))))
      (is false "logseq.sidecar.storage/create-sqlite-storage not implemented yet"))))
