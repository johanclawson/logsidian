(ns electron.write-guard-test
  (:require ["fs" :as fs]
            ["os" :as os]
            ["path" :as node-path]
            [cljs.test :refer [deftest is testing]]
            [electron.write-guard :as wg]))

(defn- buf [s] (.from js/Buffer s "utf8"))

(def ^:private bom-crlf
  "\"\\uFEFF- a\\r\\n- b\\r\\n\" as raw bytes, as a Windows editor saves it"
  (.from js/Buffer #js [0xEF 0xBB 0xBF 0x2D 0x20 0x61 0x0D 0x0A 0x2D 0x20 0x62 0x0D 0x0A]))

(deftest guarded-write-decision-test
  (testing "nil expected is a legacy write, whatever is on disk"
    (is (= :legacy (wg/guarded-write-decision (buf "x") nil)))
    (is (= :legacy (wg/guarded-write-decision :missing nil))))
  (testing "exactly equal bytes write"
    (is (= :write (wg/guarded-write-decision (buf "- a\n- b\n") "- a\n- b\n")))
    (is (= :write (wg/guarded-write-decision (buf "") ""))
        "an existing empty file matches \"\""))
  (testing "formatting-only differences refuse"
    (is (= :mismatch (wg/guarded-write-decision (buf "\uFEFF- a\n") "- a\n")) "BOM on disk only")
    (is (= :mismatch (wg/guarded-write-decision (buf "- a\n") "\uFEFF- a\n")) "BOM expected only")
    (is (= :mismatch (wg/guarded-write-decision (buf "- a\r\n- b\r\n") "- a\n- b\n")) "CRLF on disk")
    (is (= :mismatch (wg/guarded-write-decision (buf "- a\n") "- a")) "trailing newline on disk")
    (is (= :mismatch (wg/guarded-write-decision (buf "- a") "- a\n")) "trailing newline expected")
    (is (= :mismatch (wg/guarded-write-decision (buf "- a  \n") "- a\n")) "trailing spaces")
    (is (= :mismatch (wg/guarded-write-decision (buf "  - a\n") "- a\n")) "leading spaces"))
  (testing "a BOM and CRLF file read by the app (Buffer.toString) and unchanged since writes"
    (is (= :write (wg/guarded-write-decision bom-crlf (.toString bom-crlf)))))
  (testing ":absent"
    (is (= :create (wg/guarded-write-decision :missing :absent)))
    (is (= :exists (wg/guarded-write-decision (buf "x") :absent)))
    (is (= :exists (wg/guarded-write-decision (buf "") :absent))
        "an empty file is not an absent one"))
  (testing "a missing file with expected content refuses: it was deleted or moved on disk"
    (is (= :mismatch (wg/guarded-write-decision :missing "- a\n")))
    (is (= :mismatch (wg/guarded-write-decision :missing ""))
        "\"\" means an existing empty file, not a missing one")))

(deftest buffer-round-trip-test
  (testing "Buffer.toString keeps the BOM and CRLF, and Buffer.from(s, \"utf8\") restores the bytes"
    (let [s (.toString bom-crlf)]
      (is (= 0xFEFF (.charCodeAt s 0)))
      (is (= "- a\r\n- b\r\n" (subs s 1)))
      (is (.equals bom-crlf (.from js/Buffer s "utf8"))))))

(deftest normalize-expected-test
  (is (nil? (wg/normalize-expected nil)))
  (is (= "" (wg/normalize-expected "")))
  (is (= "absent" (wg/normalize-expected "absent"))
      "the string is content: a file may contain exactly \"absent\"")
  (is (= :absent (wg/normalize-expected :absent)))
  (is (= :absent (wg/normalize-expected {:absent true})))
  (is (thrown? js/Error (wg/normalize-expected 42)))
  (is (thrown? js/Error (wg/normalize-expected {:absent false}))))

(deftest guarded-write!-test
  (let [dir (fs/mkdtempSync (node-path/join (os/tmpdir) "write-guard-"))
        in-dir #(node-path/join dir %)
        text #(.toString (fs/readFileSync %))]
    (try
      (testing "exact match replaces the file"
        (let [p (in-dir "match.md")]
          (fs/writeFileSync p "- a\n")
          (is (= "written" (:result (wg/guarded-write! p "- a\n- b\n" "- a\n"))))
          (is (= "- a\n- b\n" (text p)))))

      (testing "each formatting-only difference refuses and leaves the disk bytes"
        (doseq [[label disk expected] [["BOM" (buf "\uFEFF- a\n") "- a\n"]
                                       ["CRLF" (buf "- a\r\n") "- a\n"]
                                       ["trailing newline" (buf "- a\n") "- a"]
                                       ["trailing spaces" (buf "- a \n") "- a\n"]
                                       ["other text" (buf "- external\n") "- a\n"]]]
          (let [p (in-dir "differs.md")]
            (fs/writeFileSync p disk)
            (is (= {:result "mismatch" :disk "present"}
                   (wg/guarded-write! p "- proposed\n" expected))
                label)
            (is (.equals disk (fs/readFileSync p)) label))))

      (testing "a BOM and CRLF file the app read unchanged is written"
        (let [p (in-dir "windows.md")]
          (fs/writeFileSync p bom-crlf)
          (is (= "written" (:result (wg/guarded-write! p "- a\n" (.toString (fs/readFileSync p))))))
          (is (= "- a\n" (text p)))))

      (testing "a missing file with expected content refuses and creates nothing"
        (let [p (in-dir "gone.md")]
          (is (= {:result "mismatch" :disk "missing"} (wg/guarded-write! p "- x\n" "- a\n")))
          (is (not (fs/existsSync p)))))

      (testing ":absent creates a new file"
        (let [p (in-dir "new.md")]
          (is (= "written" (:result (wg/guarded-write! p "- new\n" :absent))))
          (is (= "- new\n" (text p)))))

      (testing ":absent refuses an existing file, an empty one too, and leaves it"
        (let [p (in-dir "exists.md")]
          (fs/writeFileSync p "")
          (is (= {:result "exists"} (wg/guarded-write! p "- new\n" :absent)))
          (is (= "" (text p)))))

      (testing "a file created between the read and the create (wx collision) is left untouched"
        (let [p (in-dir "raced.md")]
          (fs/writeFileSync p "- external\n")
          (with-redefs [wg/read-snapshot (constantly :missing)]
            (is (= {:result "exists"} (wg/guarded-write! p "- new\n" :absent))))
          (is (= "- external\n" (text p)))))

      (testing "before-replace runs only right before a replacement"
        (let [p (in-dir "hook.md")
              *calls (atom [])
              hook #(swap! *calls conj %)]
          (fs/writeFileSync p "- a\n")
          (wg/guarded-write! p "- b\n" "- other\n" :before-replace hook)
          (wg/guarded-write! (in-dir "hook-new.md") "- b\n" :absent :before-replace hook)
          (is (= [] @*calls) "not on a refusal, not on a create")
          (wg/guarded-write! p "- b\n" "- a\n" :before-replace hook)
          (is (= [p] @*calls))))

      (testing "an io error is returned, not thrown, and writes nothing"
        (let [p (in-dir "a-directory")]
          (fs/mkdirSync p)
          (let [result (wg/guarded-write! p "- x\n" "- a\n")]
            (is (= "io-error" (:result result)))
            (is (string? (:error result))))
          (is (.isDirectory (fs/statSync p)))))
      (finally
        (fs/rmSync dir #js {:recursive true :force true})))))
