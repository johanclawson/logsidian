(ns logseq.sidecar.mldoc
  "GraalJS mldoc wrapper for parsing markdown/org files in the JVM sidecar.

   Key design decisions:
   1. Pooled Context: Single warm GraalJS context with mldoc preloaded
   2. Host Bindings: Pass content/config as bindings, not escaped strings
   3. JSON→EDN: Convert mldoc JSON output to EDN on JVM side
   4. Thread Safety: Synchronized access to GraalJS context

   Usage:
   (require '[logseq.sidecar.mldoc :as mldoc])
   (mldoc/parse-json \"# Hello\" :markdown)
   ;; => [[\"Heading\" {...}] {:start_pos 0 :end_pos 7}]"
  (:require [clojure.java.io :as io]
            [clojure.data.json :as json]
            [clojure.string :as string]
            [clojure.tools.logging :as log])
  (:import [javax.script ScriptEngineManager ScriptEngine Bindings ScriptContext]))

;; =============================================================================
;; Configuration
;; =============================================================================

(def ^:private mldoc-search-paths
  "Paths to search for mldoc.js (relative to sidecar directory)"
  ["../../node_modules/mldoc/index.js"
   "../node_modules/mldoc/index.js"
   "node_modules/mldoc/index.js"])

;; =============================================================================
;; State Management (Pooled Context)
;; =============================================================================

(defonce ^:private *engine (atom nil))
(defonce ^:private *initialized? (atom false))
(defonce ^:private *lock (Object.))

(defn- find-mldoc-js
  "Find mldoc.js file from configured search paths"
  []
  (some (fn [path]
          (let [f (io/file path)]
            (when (.exists f)
              (.getAbsolutePath f))))
        mldoc-search-paths))

(defn- create-engine
  "Create a new GraalJS ScriptEngine"
  []
  (let [manager (ScriptEngineManager.)
        engine (.getEngineByName manager "graal.js")]
    (when-not engine
      (let [available (mapv #(.getEngineName %) (.getEngineFactories manager))]
        (throw (ex-info "GraalJS engine not found"
                        {:available-engines available
                         :hint "Ensure org.graalvm.js/js and js-scriptengine are on classpath"}))))
    engine))

(defn- load-mldoc!
  "Load mldoc.js into the engine. Returns the engine."
  [^ScriptEngine engine]
  (let [mldoc-path (find-mldoc-js)]
    (when-not mldoc-path
      (throw (ex-info "Cannot find mldoc.js"
                      {:searched mldoc-search-paths
                       :hint "Run from repository root or set MLDOC_PATH"})))
    (log/info "Loading mldoc.js from:" mldoc-path)
    (let [mldoc-content (slurp mldoc-path)]
      ;; Create globalThis since mldoc (js_of_ocaml) expects it
      (.eval engine "var globalThis = this;")
      ;; Load mldoc.js
      (.eval engine mldoc-content)
      ;; Verify Mldoc object exists
      (let [mldoc-type (.eval engine "typeof Mldoc")]
        (when-not (= "object" (str mldoc-type))
          (throw (ex-info "Mldoc not found after loading"
                          {:type (str mldoc-type)}))))
      engine)))

(defn- ensure-initialized!
  "Ensure GraalJS context is initialized with mldoc loaded.
   Thread-safe: uses locking to prevent race conditions."
  []
  (when-not @*initialized?
    (locking *lock
      (when-not @*initialized?
        (log/info "Initializing GraalJS mldoc context...")
        (let [engine (create-engine)]
          (load-mldoc! engine)
          (reset! *engine engine)
          (reset! *initialized? true)
          (log/info "GraalJS mldoc context initialized"))))))

(defn shutdown!
  "Shutdown the GraalJS context and release resources.
   Call this when shutting down the sidecar."
  []
  (locking *lock
    (when @*initialized?
      (log/info "Shutting down GraalJS mldoc context")
      (reset! *engine nil)
      (reset! *initialized? false))))

(defn reinitialize!
  "Force reinitialize the context. Useful if context gets corrupted
   or to release memory periodically."
  []
  (shutdown!)
  (ensure-initialized!))

;; =============================================================================
;; Configuration Helpers
;; =============================================================================

(defn make-config
  "Create mldoc config map for the given format.

   Options:
   - :format - :markdown or :org (default :markdown)
   - :parse_outline_only - boolean (default false)
   - :toc - generate table of contents (default false)
   - :heading_number - add heading numbers (default false)
   - :keep_line_break - preserve line breaks (default true)"
  ([format]
   (make-config format {}))
  ([format opts]
   (merge
    {:toc false
     :parse_outline_only false
     :heading_number false
     :keep_line_break true
     :format (case format
               :markdown "Markdown"
               :org "Org"
               (if (string? format)
                 (string/capitalize format)
                 "Markdown"))}
    opts)))

(defn config->json
  "Convert config map to JSON string for mldoc"
  [config]
  (json/write-str config))

;; =============================================================================
;; Parsing Functions
;; =============================================================================

(defn- call-mldoc
  "Call a mldoc function with content and config using host bindings.
   Thread-safe: synchronizes on the engine."
  [fn-name content config-json]
  (ensure-initialized!)
  (locking *lock
    (let [^ScriptEngine engine @*engine
          ;; Get the engine's bindings (ENGINE_SCOPE) which has Mldoc loaded
          bindings (.getBindings engine ScriptContext/ENGINE_SCOPE)]
      ;; Set content and config in the engine's scope
      (.put bindings "_content" content)
      (.put bindings "_config" config-json)
      ;; Call mldoc function
      (try
        (let [result (.eval engine (str "Mldoc." fn-name "(_content, _config)"))]
          (when result
            (str result)))
        (finally
          ;; Clean up temporary bindings
          (.remove bindings "_content")
          (.remove bindings "_config"))))))

(defn parse-json
  "Parse content with mldoc, return EDN AST.

   Arguments:
   - content: String content to parse (markdown or org)
   - format: :markdown or :org
   - opts: Optional config overrides

   Returns: Vector of [block position-meta] tuples
   Example: [[\"Heading\" {:level 1 :title [...]}] {:start_pos 0 :end_pos 15}]"
  ([content format]
   (parse-json content format {}))
  ([content format opts]
   (let [config (make-config format opts)
         config-json (config->json config)
         result-json (call-mldoc "parseJson" content config-json)]
     (when result-json
       (json/read-str result-json :key-fn keyword)))))

(defn parse-inline-json
  "Parse inline content (single line) with mldoc.

   Arguments:
   - text: String text to parse
   - format: :markdown or :org
   - opts: Optional config overrides

   Returns: Parsed inline AST"
  ([text format]
   (parse-inline-json text format {}))
  ([text format opts]
   (let [config (make-config format opts)
         config-json (config->json config)
         result-json (call-mldoc "parseInlineJson" text config-json)]
     (when result-json
       (json/read-str result-json :key-fn keyword)))))

(defn get-references
  "Extract page and block references from text.

   Arguments:
   - text: String text to scan for references
   - format: :markdown or :org

   Returns: Map with :embed_blocks and :embed_pages"
  ([text format]
   (get-references text format {}))
  ([text format opts]
   (when-not (string/blank? text)
     (let [config (make-config format opts)
           config-json (config->json config)
           result-json (call-mldoc "getReferences" text config-json)]
       (when result-json
         (json/read-str result-json :key-fn keyword))))))

(defn ast-export-markdown
  "Export AST back to markdown format.

   Arguments:
   - ast: Mldoc AST (as JSON string)
   - format: :markdown or :org
   - references: Map of embedded references

   Returns: Markdown string"
  ([ast format]
   (ast-export-markdown ast format nil))
  ([ast format references]
   (ensure-initialized!)
   (locking *lock
     (let [^ScriptEngine engine @*engine
           bindings (.getBindings engine ScriptContext/ENGINE_SCOPE)
           config (make-config format)
           config-json (config->json config)
           refs-json (json/write-str (or references {:embed_blocks [] :embed_pages []}))]
       (.put bindings "_ast" (if (string? ast) ast (json/write-str ast)))
       (.put bindings "_config" config-json)
       (.put bindings "_refs" refs-json)
       (try
         (let [result (.eval engine "Mldoc.astExportMarkdown(_ast, _config, _refs)")]
           (when result
             (str result)))
         (finally
           (.remove bindings "_ast")
           (.remove bindings "_config")
           (.remove bindings "_refs")))))))

;; =============================================================================
;; Diagnostics
;; =============================================================================

(defn status
  "Return status of the mldoc context.
   Useful for health checks and debugging."
  []
  {:initialized? @*initialized?
   :engine-class (when @*engine (class @*engine))
   :mldoc-path (find-mldoc-js)})

(defn test-parse
  "Quick test to verify mldoc is working.
   Returns parsed AST for a simple heading."
  []
  (parse-json "# Test Heading" :markdown))

(comment
  ;; REPL usage examples:

  ;; Initialize (happens automatically on first use)
  (ensure-initialized!)

  ;; Check status
  (status)

  ;; Parse markdown
  (parse-json "# Hello World" :markdown)

  ;; Parse org-mode
  (parse-json "* Hello World" :org)

  ;; Parse complex content
  (parse-json "title:: My Page\ntags:: tag1, tag2\n\n# Content\n\nSome text with [[Page Ref]]" :markdown)

  ;; Get references
  (get-references "Link to [[Another Page]] and ((block-uuid))" :markdown)

  ;; Shutdown when done
  (shutdown!))
