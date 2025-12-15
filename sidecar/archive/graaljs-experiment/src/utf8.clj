(ns logseq.sidecar.utf8
  "UTF-8 utilities for the sidecar, equivalent to graph-parser utf8.cljs.
   Used for byte-accurate position handling in mldoc AST."
  (:import [java.nio.charset StandardCharsets]))

(defn encode
  "Encode a string to UTF-8 bytes.
   Returns a byte array."
  [^String s]
  (when s
    (.getBytes s StandardCharsets/UTF_8)))

(defn decode
  "Decode UTF-8 bytes to a string.
   Accepts byte array."
  [^bytes arr]
  (when arr
    (String. arr StandardCharsets/UTF_8)))

(defn substring
  "Extract substring from UTF-8 encoded content by byte positions.
   This is needed because mldoc returns byte positions, not character positions."
  ([arr start]
   (when arr
     (let [len (alength arr)]
       (when (< start len)
         (decode (java.util.Arrays/copyOfRange arr (int start) len))))))
  ([arr start end]
   (when arr
     (if end
       (let [len (alength arr)
             end' (min end len)]
         (when (< start end')
           (decode (java.util.Arrays/copyOfRange arr (int start) (int end')))))
       (substring arr start)))))

(comment
  ;; Test UTF-8 handling
  (def content "Hello 世界!")
  (def encoded (encode content))
  (count content)      ;; => 9 characters
  (alength encoded)    ;; => 13 bytes (UTF-8)

  ;; Substring by byte position
  (substring encoded 0 5)   ;; => "Hello"
  (substring encoded 6 12)  ;; => "世界"
  (substring encoded 6)     ;; => "世界!"
  )
