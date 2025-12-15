(ns logseq.sidecar.mldoc-graaljs-test
  "Test mldoc.js parsing in GraalJS to verify we can move file parsing to sidecar."
  (:require [clojure.test :refer [deftest testing is]]
            [clojure.java.io :as io]
            [clojure.data.json :as json])
  (:import [javax.script ScriptEngineManager]))

(defn find-mldoc-js
  "Find mldoc.js file from sidecar directory"
  []
  (let [paths ["../../node_modules/mldoc/index.js"
               "../node_modules/mldoc/index.js"
               "node_modules/mldoc/index.js"]]
    (some (fn [p]
            (let [f (io/file p)]
              (when (.exists f)
                (.getAbsolutePath f))))
          paths)))

(defn create-js-engine
  "Create a GraalJS ScriptEngine"
  []
  (let [manager (ScriptEngineManager.)
        engine (.getEngineByName manager "graal.js")]
    (when-not engine
      (throw (ex-info "GraalJS engine not found. Available engines: "
                      {:engines (mapv #(.getEngineName %) (.getEngineFactories manager))})))
    engine))

(defn load-mldoc!
  "Load mldoc.js into the engine and return the engine"
  [engine]
  (let [mldoc-path (find-mldoc-js)]
    (when-not mldoc-path
      (throw (ex-info "Cannot find mldoc.js" {})))
    (let [mldoc-content (slurp mldoc-path)]
      ;; Create globalThis since mldoc expects it
      (.eval engine "var globalThis = this;")
      ;; Load mldoc.js
      (.eval engine mldoc-content)
      engine)))

(defn make-config
  "Create mldoc config JSON string for the given format"
  [format]
  (json/write-str
   {:toc false
    :parse_outline_only false
    :heading_number false
    :keep_line_break true
    :format (case format
              :markdown "Markdown"
              :org "Org"
              "Markdown")}))

(defn parse-content
  "Parse content using mldoc in GraalJS"
  [engine content format]
  (let [config (make-config format)
        ;; Escape the content and config for JavaScript
        escaped-content (-> content
                            (clojure.string/replace "\\" "\\\\")
                            (clojure.string/replace "\"" "\\\"")
                            (clojure.string/replace "\n" "\\n")
                            (clojure.string/replace "\r" "\\r"))
        escaped-config (-> config
                           (clojure.string/replace "\\" "\\\\")
                           (clojure.string/replace "\"" "\\\""))
        js-code (str "Mldoc.parseJson(\"" escaped-content "\", \"" escaped-config "\")")
        result (.eval engine js-code)]
    (when result
      (json/read-str (str result) :key-fn keyword))))

;; =============================================================================
;; Tests
;; =============================================================================

(deftest ^:graaljs test-graaljs-engine-creation
  (testing "Can create GraalJS ScriptEngine"
    (let [engine (create-js-engine)]
      (is (some? engine)))))

(deftest ^:graaljs test-basic-js-execution
  (testing "Can execute basic JavaScript"
    (let [engine (create-js-engine)
          result (.eval engine "1 + 2")]
      (is (= 3 (int result))))))

(deftest ^:graaljs test-find-mldoc
  (testing "Can find mldoc.js file"
    (let [path (find-mldoc-js)]
      (is (some? path))
      (is (.exists (io/file path))))))

(deftest ^:graaljs test-mldoc-load
  (testing "Can load mldoc.js"
    (let [engine (create-js-engine)]
      (load-mldoc! engine)
      ;; Check Mldoc object exists
      (let [mldoc (.eval engine "typeof Mldoc")]
        (is (= "object" (str mldoc))))
      ;; Check parseJson function exists
      (let [parse-json (.eval engine "typeof Mldoc.parseJson")]
        (is (= "function" (str parse-json)))))))

(deftest ^:graaljs test-parse-markdown-heading
  (testing "Can parse markdown heading"
    (let [engine (create-js-engine)
          _ (load-mldoc! engine)
          result (parse-content engine "# Hello World" :markdown)]
      (is (some? result))
      (is (vector? result))
      (is (pos? (count result)))
      ;; First element should be a heading
      (let [[block _pos] (first result)]
        (is (= "Heading" (first block)))))))

(deftest ^:graaljs test-parse-markdown-paragraph
  (testing "Can parse markdown paragraph"
    (let [engine (create-js-engine)
          _ (load-mldoc! engine)
          result (parse-content engine "This is a paragraph." :markdown)]
      (is (some? result))
      (is (vector? result))
      (let [[block _pos] (first result)]
        (is (= "Paragraph" (first block)))))))

(deftest ^:graaljs test-parse-markdown-list
  (testing "Can parse markdown list (Logseq style - parsed as headings)"
    (let [engine (create-js-engine)
          _ (load-mldoc! engine)
          ;; In Logseq markdown, "- item" is parsed as a heading, not a list
          result (parse-content engine "- Item 1\n- Item 2\n- Item 3" :markdown)]
      (is (some? result))
      (is (vector? result))
      ;; Logseq parses "- " as headings for outline structure
      (let [[block _pos] (first result)]
        (is (= "Heading" (first block)))))))

(deftest ^:graaljs test-parse-org-heading
  (testing "Can parse org-mode heading"
    (let [engine (create-js-engine)
          _ (load-mldoc! engine)
          result (parse-content engine "* Hello World" :org)]
      (is (some? result))
      (is (vector? result))
      (is (pos? (count result)))
      (let [[block _pos] (first result)]
        (is (= "Heading" (first block)))))))

(deftest ^:graaljs test-parse-complex-markdown
  (testing "Can parse complex markdown with multiple blocks"
    (let [engine (create-js-engine)
          _ (load-mldoc! engine)
          content "# Title\n\nParagraph text.\n\n- Item 1\n- Item 2\n\n```clojure\n(+ 1 2)\n```"
          result (parse-content engine content :markdown)]
      (is (some? result))
      (is (vector? result))
      ;; Should have multiple blocks
      (is (>= (count result) 3)))))

(deftest ^:graaljs test-parse-page-ref
  (testing "Can parse page reference"
    (let [engine (create-js-engine)
          _ (load-mldoc! engine)
          result (parse-content engine "Link to [[Another Page]]" :markdown)]
      (is (some? result))
      (is (vector? result)))))

(deftest ^:graaljs test-parse-properties
  (testing "Can parse properties block"
    (let [engine (create-js-engine)
          _ (load-mldoc! engine)
          result (parse-content engine "title:: My Page\ntags:: tag1, tag2\n\nContent" :markdown)]
      (is (some? result))
      (is (vector? result)))))

(comment
  ;; Run tests manually:
  ;; cd sidecar && clj -M:test -i graaljs

  ;; Or run specific test:
  (test-graaljs-engine-creation)
  (test-mldoc-load)
  (test-parse-markdown-heading))
