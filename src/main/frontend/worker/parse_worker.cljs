(ns frontend.worker.parse-worker
  "Web Worker for parallel file parsing.
   Receives files via Transit, parses them, returns results.

   CODEX: No DOM/global state access allowed in this namespace.
   This file runs in a Web Worker context (self, not window)."
  (:require ["comlink" :as Comlink]
            [logseq.db :as ldb]
            [logseq.graph-parser :as graph-parser]))

;; =============================================================================
;; Error Handling
;; =============================================================================

(defn- setup-error-handlers!
  "CODEX: Global error handler to catch unhandled errors and keep worker alive."
  []
  (set! js/self.onerror
        (fn [msg url line col error]
          (js/console.error "Parse worker error:" msg error)
          false))  ; Don't prevent default handling
  (set! js/self.onunhandledrejection
        (fn [event]
          (js/console.error "Parse worker unhandled rejection:" (.-reason event)))))

;; =============================================================================
;; Parsing Functions
;; =============================================================================

(defn parse-files-batch
  "Parse multiple files in this worker.
   Input: Transit-serialized files and options
   Output: Transit-serialized parse results

   CODEX: Returns plain data, errors isolated per-file.
   Strips AST from results to reduce serialization overhead.
   Outer try/catch handles transit decode errors to prevent worker crash.

   Files should include :stat {:ctime ... :mtime ...} for timestamp parity
   with main-thread parsing path."
  [files-transit opts-transit]
  ;; CODEX: Outer try/catch to handle transit decode errors
  (try
    (let [files (ldb/read-transit-str files-transit)
          opts (ldb/read-transit-str opts-transit)]
      (->> files
           (mapv (fn [file]
                   (try
                     ;; CODEX: Use some? instead of truthiness to preserve 0 timestamps
                     (let [stat (:stat file)
                           parse-opts (cond-> opts
                                        (some? (:ctime stat)) (assoc :ctime (:ctime stat))
                                        (some? (:mtime stat)) (assoc :mtime (:mtime stat)))]
                       {:status :ok
                        :file-path (:file/path file)
                        ;; CODEX: Only serialize essential fields, not full AST
                        :result (-> (graph-parser/parse-file-data
                                     (:file/path file)
                                     (:file/content file)
                                     parse-opts)
                                    ;; Strip AST to reduce serialization overhead
                                    (dissoc :ast))})
                     (catch :default e
                       {:status :error
                        :file-path (:file/path file)
                        :error (str e)
                        :message (ex-message e)
                        :stack (.-stack e)}))))
           ldb/write-transit-str))
    (catch :default e
      ;; Transit decode failed - return error result that can be deserialized
      (ldb/write-transit-str
       [{:status :error
         :file-path "batch-decode"
         :error (str e)
         :message (ex-message e)
         :stack (.-stack e)}]))))

;; =============================================================================
;; Worker Initialization
;; =============================================================================

(defn init
  "Initialize the parse worker and expose functions via Comlink."
  []
  (setup-error-handlers!)
  (js/console.log "Parse worker initialized")
  (Comlink/expose #js {:parseFilesBatch parse-files-batch}))
