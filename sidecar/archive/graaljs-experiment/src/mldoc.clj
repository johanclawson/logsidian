(ns logseq.sidecar.mldoc
  "GraalJS mldoc wrapper for parsing markdown/org files in the JVM sidecar.

   Key design decisions:
   1. Pooled Context: Single warm GraalJS context with mldoc preloaded
   2. Host Bindings: Pass content/config as bindings, not escaped strings
   3. JSON→EDN: Convert mldoc JSON output to EDN on JVM side
   4. Thread Safety: Synchronized access to GraalJS context
   5. Polyglot API: Uses modern GraalVM Polyglot API for best performance

   Usage:
   (require '[logseq.sidecar.mldoc :as mldoc])
   (mldoc/parse-json \"# Hello\" :markdown)
   ;; => [[\"Heading\" {...}] {:start_pos 0 :end_pos 7}]"
  (:require [clojure.java.io :as io]
            [clojure.data.json :as json]
            [clojure.string :as string]
            [clojure.tools.logging :as log])
  (:import [org.graalvm.polyglot Context Value Source Engine]
           [org.graalvm.polyglot HostAccess]))

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
(defonce ^:private *context (atom nil))
(defonce ^:private *mldoc-source (atom nil))
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
  "Create a shared GraalVM Engine for better performance across contexts"
  []
  (-> (Engine/newBuilder (into-array String ["js"]))
      (.option "engine.WarnInterpreterOnly" "false")
      (.build)))

(defn- create-context
  "Create a new GraalJS Context with the shared engine"
  [^Engine engine]
  (-> (Context/newBuilder (into-array String ["js"]))
      (.engine engine)
      (.allowHostAccess HostAccess/ALL)
      (.allowExperimentalOptions true)
      (.option "js.ecmascript-version" "2024")
      (.build)))

(defn- load-mldoc!
  "Load mldoc.js into the context. Returns the context."
  [^Context context]
  (let [mldoc-path (find-mldoc-js)]
    (when-not mldoc-path
      (throw (ex-info "Cannot find mldoc.js"
                      {:searched mldoc-search-paths
                       :hint "Run from repository root or set MLDOC_PATH"})))
    (log/info "Loading mldoc.js from:" mldoc-path)
    (let [mldoc-content (slurp mldoc-path)
          ;; Create a Source object for better caching
          source (-> (Source/newBuilder "js" mldoc-content "mldoc.js")
                     (.cached true)
                     (.build))]
      ;; Create globalThis since mldoc (js_of_ocaml) expects it
      (.eval context "js" "var globalThis = this;")
      ;; Load mldoc.js
      (.eval context source)
      ;; Verify Mldoc object exists
      (let [mldoc-type (.eval context "js" "typeof Mldoc")]
        (when-not (= "object" (.asString mldoc-type))
          (throw (ex-info "Mldoc not found after loading"
                          {:type (.asString mldoc-type)}))))
      ;; Store the source for potential reuse
      (reset! *mldoc-source source)
      context)))

(defn- ensure-initialized!
  "Ensure GraalJS context is initialized with mldoc loaded.
   Thread-safe: uses locking to prevent race conditions."
  []
  (when-not @*initialized?
    (locking *lock
      (when-not @*initialized?
        (log/info "Initializing GraalJS mldoc context (Polyglot API)...")
        (let [engine (create-engine)
              context (create-context engine)]
          (load-mldoc! context)
          (reset! *engine engine)
          (reset! *context context)
          (reset! *initialized? true)
          (log/info "GraalJS mldoc context initialized"))))))

(defn shutdown!
  "Shutdown the GraalJS context and release resources.
   Call this when shutting down the sidecar."
  []
  (locking *lock
    (when @*initialized?
      (log/info "Shutting down GraalJS mldoc context")
      (when-let [ctx @*context]
        (.close ctx))
      (when-let [eng @*engine]
        (.close eng))
      (reset! *context nil)
      (reset! *engine nil)
      (reset! *mldoc-source nil)
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
  "Call a mldoc function with content and config using Polyglot bindings.
   Thread-safe: synchronizes on the context."
  [fn-name content config-json]
  (ensure-initialized!)
  (locking *lock
    (let [^Context context @*context
          bindings (.getBindings context "js")]
      ;; Set content and config in the JS scope
      (.putMember bindings "_content" content)
      (.putMember bindings "_config" config-json)
      ;; Call mldoc function
      (try
        (let [^Value result (.eval context "js" (str "Mldoc." fn-name "(_content, _config)"))]
          (when-not (.isNull result)
            (.asString result)))
        (finally
          ;; Clean up temporary bindings
          (.removeMember bindings "_content")
          (.removeMember bindings "_config"))))))

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
     (let [^Context context @*context
           bindings (.getBindings context "js")
           config (make-config format)
           config-json (config->json config)
           refs-json (json/write-str (or references {:embed_blocks [] :embed_pages []}))]
       (.putMember bindings "_ast" (if (string? ast) ast (json/write-str ast)))
       (.putMember bindings "_config" config-json)
       (.putMember bindings "_refs" refs-json)
       (try
         (let [^Value result (.eval context "js" "Mldoc.astExportMarkdown(_ast, _config, _refs)")]
           (when-not (.isNull result)
             (.asString result)))
         (finally
           (.removeMember bindings "_ast")
           (.removeMember bindings "_config")
           (.removeMember bindings "_refs")))))))

;; =============================================================================
;; Diagnostics
;; =============================================================================

(defn status
  "Return status of the mldoc context.
   Useful for health checks and debugging."
  []
  {:initialized? @*initialized?
   :context-class (when @*context (class @*context))
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
