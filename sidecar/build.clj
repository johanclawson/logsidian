(ns build
  "Build script for Logsidian sidecar uberjar.

   Usage:
     clojure -T:build uberjar
     clojure -T:build clean"
  (:require [clojure.tools.build.api :as b]))

(def lib 'com.logsidian/sidecar)
(def version "0.1.0-SNAPSHOT")
(def class-dir "target/classes")
(def uber-file "target/logsidian-sidecar.jar")

;; Basis from deps.edn
(def basis (b/create-basis {:project "deps.edn"}))

(defn clean
  "Clean build artifacts."
  [_]
  (b/delete {:path "target"})
  (println "Cleaned target/"))

(defn uberjar
  "Build an uberjar with AOT compilation.

   The jar includes all dependencies and can be run with:
     java -jar target/logsidian-sidecar.jar [port]"
  [_]
  (clean nil)
  (println "Compiling Clojure sources...")
  (b/compile-clj {:basis basis
                  :src-dirs ["src"]
                  :class-dir class-dir})
  (println "Building uberjar...")
  (b/uber {:class-dir class-dir
           :uber-file uber-file
           :basis basis
           :main 'logseq.sidecar.server})
  (println "Built:" uber-file))
