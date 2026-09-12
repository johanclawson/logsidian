(ns frontend.extensions.calc-grammar
  "Compile-time helpers for frontend.extensions.calc"
  (:require [instaparse.core]
            [shadow.resource :as rc]))

(defmacro defparser-from-resource
  "Like instaparse.core/defparser, but takes a classpath resource path.

  The resource is read at macro time and handed to defparser as a string
  literal. defparser only precompiles string literals: given any other form
  (e.g. (rc/inline ...)) it expands to a runtime (instaparse.core/parser ...)
  call, which re-parses the grammar with instaparse's GLL parser on every app
  start. The resource is read via shadow.resource so shadow-cljs recompiles the
  caller when it changes."
  [name resource-path & opts]
  (let [grammar (rc/slurp-resource &env resource-path)]
    `(instaparse.core/defparser ~name ~grammar :no-slurp true ~@opts)))
