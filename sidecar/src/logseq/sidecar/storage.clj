(ns logseq.sidecar.storage
  "SQLite-backed IStorage implementation for DataScript.

   Provides lazy loading with soft references for memory-efficient
   storage of large graphs.

   Usage:
   ```clojure
   (require '[logseq.sidecar.storage :as storage])
   (require '[datascript.core :as d])

   ;; Create storage (file-based or in-memory)
   (def store (storage/create-sqlite-storage \"path/to/db.sqlite\"))
   ;; Or in-memory: (storage/create-sqlite-storage \":memory:\")

   ;; Create connection with storage and soft references
   (def conn (d/create-conn schema {:storage store :ref-type :soft}))

   ;; Use normally
   (d/transact! conn [...])

   ;; Restore from storage
   (def conn2 (d/restore-conn store))
   ```"
  (:require [clojure.edn :as edn]
            [clojure.tools.logging :as log]
            [datascript.storage :as ds-storage]
            [next.jdbc :as jdbc]
            [next.jdbc.result-set :as rs])
  (:import [java.io PushbackReader StringReader]))

;; =============================================================================
;; SQLite Schema
;; =============================================================================

(def ^:private create-table-sql
  "CREATE TABLE IF NOT EXISTS datascript_storage (
     address INTEGER PRIMARY KEY,
     data TEXT NOT NULL
   )")

(def ^:private create-index-sql
  "CREATE INDEX IF NOT EXISTS idx_address ON datascript_storage(address)")

;; =============================================================================
;; Serialization
;; =============================================================================

(defn- freeze
  "Serialize data to EDN string."
  [data]
  (pr-str data))

(defn- thaw
  "Deserialize data from EDN string."
  [^String s]
  (when s
    (with-open [rdr (PushbackReader. (StringReader. s))]
      (edn/read rdr))))

;; =============================================================================
;; SQLite IStorage Implementation
;; =============================================================================

(defn- ensure-table!
  "Create the storage table if it doesn't exist."
  [ds]
  (jdbc/execute! ds [create-table-sql])
  (jdbc/execute! ds [create-index-sql]))

(defn- store-batch!
  "Store a batch of [address, data] pairs."
  [ds addr+data-seq]
  (jdbc/with-transaction [tx ds]
    (doseq [[addr data] addr+data-seq]
      (jdbc/execute! tx
                     ["INSERT OR REPLACE INTO datascript_storage (address, data) VALUES (?, ?)"
                      addr (freeze data)]))))

(defn- restore-one
  "Restore data for a single address."
  [ds addr]
  (let [result (jdbc/execute-one! ds
                                  ["SELECT data FROM datascript_storage WHERE address = ?"
                                   addr]
                                  {:builder-fn rs/as-unqualified-maps})]
    (when result
      (thaw (:data result)))))

(defn- list-all-addresses
  "List all addresses in storage."
  [ds]
  (let [results (jdbc/execute! ds
                               ["SELECT address FROM datascript_storage"]
                               {:builder-fn rs/as-unqualified-maps})]
    (map :address results)))

(defn- delete-addresses!
  "Delete data at specified addresses."
  [ds addrs]
  (when (seq addrs)
    (let [placeholders (clojure.string/join "," (repeat (count addrs) "?"))
          sql (str "DELETE FROM datascript_storage WHERE address IN (" placeholders ")")]
      (jdbc/execute! ds (into [sql] addrs)))))

(defn create-sqlite-storage
  "Create a SQLite-backed IStorage implementation.

   Arguments:
   - db-path: Path to SQLite database file, or \":memory:\" for in-memory

   Returns an IStorage implementation that can be passed to
   d/create-conn or d/restore-conn.

   Example:
   ```clojure
   (def storage (create-sqlite-storage \"graph.db\"))
   (def conn (d/create-conn schema {:storage storage :ref-type :soft}))
   ```"
  [db-path]
  (let [;; For in-memory databases, we need a shared cache to allow
        ;; multiple connections to see the same data
        jdbc-url (if (= db-path ":memory:")
                   "jdbc:sqlite:file::memory:?cache=shared"
                   (str "jdbc:sqlite:" db-path))
        ;; Create datasource - for in-memory, use a connection pool to keep
        ;; the database alive
        ds (jdbc/get-datasource {:jdbcUrl jdbc-url})
        ;; For in-memory databases, keep a connection open to prevent
        ;; the database from being garbage collected
        keep-alive-conn (when (= db-path ":memory:")
                          (jdbc/get-connection ds))]
    ;; Ensure table exists
    (ensure-table! ds)
    (log/info :storage-created {:path db-path})

    ;; Return IStorage implementation
    (reify
      ds-storage/IStorage
      (-store [_ addr+data-seq]
        (store-batch! ds addr+data-seq))

      (-restore [_ addr]
        (restore-one ds addr))

      (-list-addresses [_]
        (list-all-addresses ds))

      (-delete [_ addrs]
        (delete-addresses! ds addrs))

      java.io.Closeable
      (close [_]
        (when keep-alive-conn
          (.close keep-alive-conn))))))

;; =============================================================================
;; Utility Functions
;; =============================================================================

(defn storage-stats
  "Get statistics about storage usage.

   Returns map with:
   - :count - Number of stored addresses
   - :size-bytes - Total size of stored data (approximate)"
  [storage]
  (when (satisfies? ds-storage/IStorage storage)
    (let [addrs (ds-storage/-list-addresses storage)]
      {:count (count addrs)})))

(defn clear-storage!
  "Clear all data from storage. Use with caution!"
  [storage db-path]
  (let [jdbc-url (if (= db-path ":memory:")
                   "jdbc:sqlite::memory:"
                   (str "jdbc:sqlite:" db-path))
        ds (jdbc/get-datasource {:jdbcUrl jdbc-url})]
    (jdbc/execute! ds ["DELETE FROM datascript_storage"])
    (log/info :storage-cleared {:path db-path})))
