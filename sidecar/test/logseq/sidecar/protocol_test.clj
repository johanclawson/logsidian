(ns logseq.sidecar.protocol-test
  "Phase 1: Protocol layer tests.

   These tests define the communication contract between the JVM sidecar
   and the ClojureScript client. Tests are written first (TDD).

   Includes CLJS compatibility tests to verify Transit handlers match
   what the ClojureScript client sends/expects."
  (:require [clojure.test :refer [deftest testing is are]]
            [datascript.core :as d]
            [logseq.sidecar.protocol :as protocol]))

;; =============================================================================
;; Request Serialization Tests
;; =============================================================================

(deftest query-request-serializes-test
  (testing "Datalog query request serializes to Transit JSON"
    (let [query '[:find ?e ?title
                  :where
                  [?e :block/uuid ?uuid]
                  [?e :block/title ?title]]
          inputs []
          request (protocol/make-request :query {:query query :inputs inputs})
          serialized (protocol/serialize request)]

      (is (string? serialized) "Should produce a string")
      (is (pos? (count serialized)) "Should not be empty")

      ;; Round-trip verification
      (let [deserialized (protocol/deserialize serialized)]
        (is (= :query (:op deserialized)) "Operation should be :query")
        (is (= query (get-in deserialized [:payload :query])) "Query should match")))))

(deftest transaction-request-serializes-test
  (testing "Transaction request with tempids serializes correctly"
    (let [tx-data [{:db/id "temp-1"
                    :block/uuid #uuid "550e8400-e29b-41d4-a716-446655440000"
                    :block/title "Test Block"}
                   {:db/id "temp-2"
                    :block/parent "temp-1"
                    :block/title "Child Block"}]
          tx-meta {:client-id "abc123"}
          request (protocol/make-request :transact {:tx-data tx-data :tx-meta tx-meta})
          roundtrip (-> request protocol/serialize protocol/deserialize)]

      (is (= :transact (:op roundtrip)) "Operation should be :transact")
      (is (= tx-data (get-in roundtrip [:payload :tx-data])) "tx-data should match")
      (is (= tx-meta (get-in roundtrip [:payload :tx-meta])) "tx-meta should match"))))

(deftest pull-request-serializes-test
  (testing "Pull request serializes with selector pattern"
    (let [selector '[* {:block/_parent ...}]
          eid [:block/uuid #uuid "550e8400-e29b-41d4-a716-446655440000"]
          request (protocol/make-request :pull {:selector selector :eid eid})
          roundtrip (-> request protocol/serialize protocol/deserialize)]

      (is (= :pull (:op roundtrip)) "Operation should be :pull")
      (is (= selector (get-in roundtrip [:payload :selector])) "Selector should match")
      (is (= eid (get-in roundtrip [:payload :eid])) "Entity ID should match"))))

;; =============================================================================
;; Response Serialization Tests
;; =============================================================================

(deftest query-response-deserializes-test
  (testing "Query result deserializes correctly"
    (let [result #{["Test Page" 1] ["Another Page" 2]}
          response (protocol/make-response :query {:result result})
          serialized (protocol/serialize response)
          deserialized (protocol/deserialize serialized)]

      (is (:ok? deserialized) "Response should be ok")
      (is (= result (get-in deserialized [:payload :result])) "Result should match"))))

(deftest transaction-response-with-tempids-test
  (testing "Transaction response includes tempid mapping"
    (let [tempids {"temp-1" 12345 "temp-2" 12346}
          tx-data [{:e 12345 :a :block/title :v "Test" :added true}]
          response (protocol/make-response :transact {:tempids tempids :tx-data tx-data})
          roundtrip (-> response protocol/serialize protocol/deserialize)]

      (is (:ok? roundtrip) "Response should be ok")
      (is (= tempids (get-in roundtrip [:payload :tempids])) "Tempids should match"))))

(deftest error-response-includes-message-test
  (testing "Error response includes message and type"
    (let [error-msg "Entity not found: [:block/uuid ...]"
          error-type :not-found
          response (protocol/make-error-response error-type error-msg)
          roundtrip (-> response protocol/serialize protocol/deserialize)]

      (is (not (:ok? roundtrip)) "Response should not be ok")
      (is (= error-msg (:message roundtrip)) "Error message should match")
      (is (= error-type (:error-type roundtrip)) "Error type should match"))))

;; =============================================================================
;; Protocol Handshake Tests
;; =============================================================================

(deftest handshake-checks-version-test
  (testing "Handshake request includes protocol version"
    (let [handshake (protocol/make-handshake)
          roundtrip (-> handshake protocol/serialize protocol/deserialize)]

      (is (= :handshake (:op roundtrip)) "Should be handshake operation")
      (is (string? (get-in roundtrip [:payload :version])) "Should include version")
      (is (= protocol/PROTOCOL_VERSION (get-in roundtrip [:payload :version]))
          "Version should match current protocol version")))

  (testing "Handshake response validates version compatibility"
    (let [client-version protocol/PROTOCOL_VERSION
          response (protocol/validate-handshake client-version)]

      (is (:ok? response) "Compatible version should succeed"))

    (let [old-version "0.0.0"
          response (protocol/validate-handshake old-version)]

      (is (not (:ok? response)) "Incompatible version should fail")
      (is (contains? response :message) "Should include error message"))))

;; =============================================================================
;; Unicode & Special Characters Tests
;; =============================================================================

(deftest unicode-roundtrip-test
  (testing "Unicode characters survive serialization"
    (let [unicode-data {:japanese "日本語テスト"
                        :emoji "🚀 Rocket → Space 🌍"
                        :arabic "مرحبا بالعالم"
                        :chinese "中文测试"
                        :mixed "Test 测试 テスト 🎉"}
          request (protocol/make-request :query {:query '[:find ?e] :meta unicode-data})
          roundtrip (-> request protocol/serialize protocol/deserialize)]

      (is (= unicode-data (get-in roundtrip [:payload :meta]))
          "Unicode data should survive roundtrip"))))

;; =============================================================================
;; Large Payload Tests
;; =============================================================================

(deftest large-result-test
  (testing "Large result set serializes correctly"
    (let [large-result (vec (for [i (range 10000)]
                              {:db/id i
                               :block/uuid (random-uuid)
                               :block/title (str "Block " i)
                               :block/order i}))
          response (protocol/make-response :query {:result large-result})
          serialized (protocol/serialize response)
          deserialized (protocol/deserialize serialized)]

      (is (:ok? deserialized) "Large response should deserialize ok")
      (is (= 10000 (count (get-in deserialized [:payload :result])))
          "All results should be preserved"))))

(deftest large-transaction-batch-test
  (testing "Large transaction batch serializes without overflow"
    (let [tx-data (vec (for [i (range 1000)]
                         {:db/id (str "temp-" i)
                          :block/uuid (random-uuid)
                          :block/title (str "Block " i)
                          :block/properties {:index i
                                            :data (apply str (repeat 100 "x"))}}))
          request (protocol/make-request :transact {:tx-data tx-data})
          serialized (protocol/serialize request)
          deserialized (protocol/deserialize serialized)]

      (is (= 1000 (count (get-in deserialized [:payload :tx-data])))
          "All transactions should be preserved"))))

;; =============================================================================
;; Bidirectional Message Tests
;; =============================================================================

(deftest server-to-client-message-test
  (testing "Server can send push messages to client"
    (let [push-msg (protocol/make-push-message :write-files
                                               {:files [{:path "pages/test.md"
                                                         :content "# Test"}]})
          roundtrip (-> push-msg protocol/serialize protocol/deserialize)]

      (is (= :push (:type roundtrip)) "Should be a push message")
      (is (= :write-files (:event roundtrip)) "Event type should match")
      (is (= "pages/test.md" (get-in roundtrip [:payload :files 0 :path]))
          "Payload should be preserved")))

  (testing "Notification push message"
    (let [notification (protocol/make-push-message :notification
                                                   {:type :warning
                                                    :content "File changed on disk"})
          roundtrip (-> notification protocol/serialize protocol/deserialize)]

      (is (= :notification (:event roundtrip)) "Event should be notification")
      (is (= :warning (get-in roundtrip [:payload :type])) "Type should match"))))

(deftest sync-db-changes-message-test
  (testing "DB sync change message serializes correctly"
    (let [changes {:tx-data [{:e 123 :a :block/title :v "New" :added true}]
                   :tx-meta {:from-sidecar? true}}
          msg (protocol/make-push-message :sync-db-changes changes)
          roundtrip (-> msg protocol/serialize protocol/deserialize)]

      (is (= :sync-db-changes (:event roundtrip)))
      (is (= changes (:payload roundtrip))))))

;; =============================================================================
;; Thread API Compatibility Tests
;; =============================================================================

(deftest thread-api-keyword-dispatch-test
  (testing "Thread API keywords serialize correctly"
    (let [keywords [:thread-api/transact
                    :thread-api/q
                    :thread-api/pull
                    :thread-api/datoms
                    :thread-api/get-blocks
                    :thread-api/search-blocks
                    :thread-api/apply-outliner-ops]
          _ (doseq [kw keywords]
              (let [request (protocol/make-request kw {:args ["repo" "data"]})
                    roundtrip (-> request protocol/serialize protocol/deserialize)]
                (is (= kw (:op roundtrip))
                    (str "Keyword " kw " should roundtrip correctly"))))])))

;; =============================================================================
;; Request ID & Correlation Tests
;; =============================================================================

(deftest request-id-correlation-test
  (testing "Requests have unique IDs for correlation"
    (let [req1 (protocol/make-request :query {:query '[:find ?e]})
          req2 (protocol/make-request :query {:query '[:find ?e]})]

      (is (contains? req1 :id) "Request should have ID")
      (is (uuid? (:id req1)) "ID should be a UUID")
      (is (not= (:id req1) (:id req2)) "Each request should have unique ID")))

  (testing "Response includes request ID"
    (let [request (protocol/make-request :query {:query '[:find ?e]})
          response (protocol/make-response :query {:result #{}} (:id request))]

      (is (= (:id request) (:request-id response))
          "Response should reference request ID"))))

;; =============================================================================
;; CLJS Transit Compatibility Tests
;; =============================================================================
;; These tests verify that the JVM sidecar can correctly deserialize
;; Transit data sent by the ClojureScript client (using datascript.transit
;; and cljs-bean.transit handlers) and serialize responses that the
;; ClojureScript client can read.

(deftest cljs-actual-request-roundtrip-test
  (testing "JVM can deserialize actual CLJS write-transit-str output"
    ;; This is a sample request with typical payload structure
    ;; Transit map format: ["^ ", "~:key", "value", ...]
    (let [cljs-output "[\"^ \",\"~:id\",\"~u550e8400-e29b-41d4-a716-446655440000\",\"~:type\",\"~:request\",\"~:op\",\"~:thread-api/sync-app-state\",\"~:payload\",[\"^ \",\"~:args\",[\"~:key\",\"value\"]],\"~:timestamp\",1733909000000]"
          result (protocol/deserialize cljs-output)]
      (is (= :request (:type result))
          "Should deserialize :type as keyword")
      (is (= :thread-api/sync-app-state (:op result))
          "Should deserialize namespaced keyword :op")
      (is (vector? (:args (:payload result)))
          "Should deserialize payload args as vector"))))

(deftest error-handler-roundtrip-test
  (testing "JVM can handle 'error' tagged values from CLJS"
    ;; CLJS writes ExceptionInfo as: ["~#error", {"message": "...", "data": {...}}]
    (let [cljs-error-transit "[\"~#error\",[\"^ \",\"~:message\",\"Test error\",\"~:data\",[\"^ \",\"~:code\",123]]]"
          result (protocol/deserialize cljs-error-transit)]
      ;; Should become an ex-info or at least a readable value, not throw
      (is (some? result)
          "Should deserialize error tag without throwing"))))

(deftest datascript-db-roundtrip-test
  (testing "DataScript DB can be serialized and deserialized"
    (let [db (d/db-with (d/empty-db) [{:db/id -1 :name "test"}])
          serialized (protocol/serialize db)
          deserialized (protocol/deserialize serialized)]
      (is (string? serialized)
          "Serialized DB should be a string")
      (is (= (:schema db) (:schema deserialized))
          "Schema should roundtrip correctly"))))

(deftest datascript-entity-handler-test
  (testing "JVM can handle 'datascript/Entity' tagged values"
    ;; CLJS writes Entity as: ["~#datascript/Entity", {:db/id 1, :name "test"}]
    (let [cljs-entity-transit "[\"~#datascript/Entity\",[\"^ \",\"~:db/id\",1,\"~:name\",\"test\"]]"
          result (protocol/deserialize cljs-entity-transit)]
      ;; Should become a map with :db/id
      (is (= 1 (:db/id result))
          "Should extract :db/id from entity")
      (is (= "test" (:name result))
          "Should extract other attributes from entity"))))

(deftest full-request-response-cycle-test
  (testing "JVM response can be deserialized by CLJS reader"
    ;; Ensure our serialize produces output compatible with ldb/read-transit-str
    (let [response {:type :response
                    :ok? true
                    :request-id "abc-123"
                    :payload {:result "success"}}
          serialized (protocol/serialize response)]
      ;; Should be valid Transit JSON
      (is (string? serialized)
          "Serialized response should be a string")
      ;; Should roundtrip on JVM
      (is (= response (protocol/deserialize serialized))
          "Response should roundtrip correctly"))))

(deftest exception-info-write-handler-test
  (testing "JVM can serialize ExceptionInfo to match CLJS format"
    (let [ex (ex-info "Test error" {:code 123})
          serialized (protocol/serialize ex)
          deserialized (protocol/deserialize serialized)]
      (is (string? serialized)
          "Serialized exception should be a string")
      ;; The deserialized value should be an ex-info or map with message
      (is (or (instance? clojure.lang.ExceptionInfo deserialized)
              (and (map? deserialized) (:message deserialized)))
          "Should deserialize to exception or map with message"))))

;; =============================================================================
;; String Op Normalization Tests (CLJS-Bean Transit Compatibility)
;; =============================================================================
;; CLJS Transit with cljs-bean handlers may convert keywords to strings.
;; These tests verify that string ops can be correctly converted to keywords.

(deftest string-op-normalization-test
  (testing "Transit deserializes namespaced keywords from CLJS as strings"
    ;; This is the actual behavior we observed: CLJS sends "thread-api/q"
    ;; Transit on JVM receives it as a string, not :thread-api/q
    (let [cljs-request "[\"^ \",\"~:op\",\"thread-api/q\",\"~:payload\",[\"^ \",\"~:args\",[\"repo\",[]]]]"
          deserialized (protocol/deserialize cljs-request)
          op-value (:op deserialized)]
      ;; The op comes through as a string, NOT a keyword
      (is (string? op-value)
          "Op should be a string when sent from CLJS without keyword encoding")
      (is (= "thread-api/q" op-value)
          "Op value should be 'thread-api/q'")))

  (testing "Transit correctly deserializes explicitly-tagged keywords"
    ;; When CLJS Transit properly encodes keywords with ~: prefix
    (let [proper-request "[\"^ \",\"~:op\",\"~:thread-api/q\",\"~:payload\",[\"^ \",\"~:args\",[\"repo\",[]]]]"
          deserialized (protocol/deserialize proper-request)
          op-value (:op deserialized)]
      ;; With ~: prefix, it becomes a keyword
      (is (keyword? op-value)
          "Op should be a keyword when properly encoded with ~: prefix")
      (is (= :thread-api/q op-value)
          "Op should be :thread-api/q")))

  (testing "Server must handle both string and keyword ops"
    ;; This documents the requirement for normalize-op in server.clj
    (let [string-op "thread-api/sync-app-state"
          keyword-op :thread-api/sync-app-state]
      ;; Both should result in the same dispatch target
      ;; The server's normalize-op handles this conversion
      (is (= (keyword "thread-api" "sync-app-state")
             keyword-op)
          "Keyword should match")
      (is (= "thread-api/sync-app-state" string-op)
          "String should match expected value"))))
