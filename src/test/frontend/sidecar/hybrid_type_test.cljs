(ns frontend.sidecar.hybrid-type-test
  "Unit tests for hybrid backend type handling.

   Tests the logic that determines when sidecar setup should run
   based on the result from start-db-backend!.

   Bug context: browser.cljs was checking (= type :sidecar) but
   start-db-backend! returns {:type :hybrid :sidecar :ipc} when
   both worker and sidecar are running."
  (:require [cljs.test :refer [deftest testing is]]))

;; =============================================================================
;; Test: Sidecar setup should run for hybrid type
;; =============================================================================

(defn should-run-sidecar-setup?
  "Determines if sidecar setup should run based on backend result.

   This is the CORRECT logic that browser.cljs should use.
   Returns true if the result indicates sidecar is actually active.

   Note: :type :hybrid alone is not sufficient - we need to check
   that sidecar actually started (truthy :sidecar key)."
  [{:keys [type sidecar]}]
  (or (= type :sidecar)    ;; Legacy pure-sidecar mode
      sidecar))            ;; Any result with truthy sidecar (includes :hybrid)

(deftest hybrid-type-triggers-sidecar-setup
  (testing "Hybrid backend type should trigger sidecar setup"
    (let [result {:type :hybrid :worker true :sidecar :ipc}]
      (is (should-run-sidecar-setup? result)
          "Hybrid type with sidecar key should trigger setup"))))

(deftest sidecar-type-triggers-setup
  (testing "Pure sidecar type should trigger sidecar setup"
    (let [result {:type :sidecar :port 47632}]
      (is (should-run-sidecar-setup? result)
          "Sidecar type should trigger setup"))))

(deftest worker-type-does-not-trigger-setup
  (testing "Pure worker type should NOT trigger sidecar setup"
    (let [result {:type :worker}]
      (is (not (should-run-sidecar-setup? result))
          "Worker-only type should not trigger sidecar setup"))))

(deftest worker-with-sidecar-requested-does-not-trigger-setup
  (testing "Worker type with failed sidecar request should NOT trigger setup"
    (let [result {:type :worker :sidecar-requested true}]
      (is (not (should-run-sidecar-setup? result))
          "Worker type with sidecar-requested flag (but no actual sidecar) should not trigger setup"))))

(deftest sidecar-key-presence-triggers-setup
  (testing "Any result with truthy sidecar key should trigger setup"
    (is (should-run-sidecar-setup? {:type :hybrid :sidecar :ipc})
        ":ipc sidecar should trigger")
    (is (should-run-sidecar-setup? {:type :hybrid :sidecar :ws})
        ":ws sidecar should trigger")
    (is (not (should-run-sidecar-setup? {:type :hybrid :sidecar nil}))
        "nil sidecar should NOT trigger")
    (is (not (should-run-sidecar-setup? {:type :hybrid :sidecar false}))
        "false sidecar should NOT trigger")))

;; =============================================================================
;; Bug reproduction: Old browser.cljs logic
;; =============================================================================

(deftest bug-old-logic-fails-for-hybrid
  (testing "Old browser.cljs logic incorrectly fails for hybrid type"
    (let [result {:type :hybrid :worker true :sidecar :ipc}
          old-logic (fn [{:keys [type]}] (= type :sidecar))]
      (is (not (old-logic result))
          "Old logic (= type :sidecar) returns false for :hybrid type - THIS IS THE BUG"))))

(deftest bug-new-logic-works-for-hybrid
  (testing "New logic correctly handles hybrid type"
    (let [result {:type :hybrid :worker true :sidecar :ipc}]
      (is (should-run-sidecar-setup? result)
          "New logic correctly triggers setup for hybrid type"))))
