(ns logseq.db.common.reference-test
  "Linked references on file-schema dbs: the index walk in
   get-linked-references against the former has-ref/parent Datalog query"
  (:require [cljs.test :refer [deftest is testing]]
            [clojure.set :as set]
            [clojure.string :as string]
            [clojure.walk :as walk]
            [datascript.core :as d]
            [logseq.db :as ldb]
            [logseq.db.common.initial-data :as common-initial-data]
            [logseq.db.common.reference :as reference]
            [logseq.db.common.view :as view]
            [logseq.db.file-based.schema :as file-schema]
            [logseq.db.frontend.class :as db-class]
            [logseq.db.frontend.rules :as rules]))

;; Former implementation, kept as the reference (oracle)
;; =====================================================

(defn- old-build-include-exclude-query
  [includes excludes]
  (concat
   (for [include includes]
     (list 'has-ref '?b include))
   (for [exclude excludes]
     (list 'not (list 'has-ref '?b exclude)))))

(defn- old-filter-refs-query
  [includes excludes class-ids]
  (let [clauses (concat
                 (old-build-include-exclude-query includes excludes)
                 (for [class-id class-ids]
                   (list 'not ['?b :block/tags class-id])))]
    (into [:find '[?b ...]
           :in '$ '% '[?id ...]
           :where
           (list 'has-ref '?b '?id)]
          clauses)))

(defn- old-get-block-parents-until-top-ref
  [db id ref-id ref-block-ids *result]
  (loop [eid ref-id
         parents' []]
    (when eid
      (cond
        (contains? @*result eid)
        (swap! *result into parents')

        (contains? ref-block-ids eid)
        (when-not (common-initial-data/hidden-ref? db (d/entity db eid) id)
          (swap! *result into (conj parents' eid)))
        :else
        (let [e (d/entity db eid)]
          (recur (:db/id (:block/parent e)) (conj parents' eid)))))))

(defn- old-get-linked-references
  [db id]
  (let [entity (d/entity db id)
        ids (set (cons id (ldb/get-block-alias db id)))
        page-filters (reference/get-filters db entity)
        excludes (map :db/id (:excluded page-filters))
        includes (map :db/id (:included page-filters))
        class-ids (when (ldb/class? entity)
                    (let [class-children (db-class/get-structured-children db id)]
                      (set (conj class-children id))))
        full-ref-block-ids (->> (mapcat (fn [id] (map :db/id (:block/_refs (d/entity db id)))) ids)
                                set)
        matched-ref-block-ids (set (d/q (old-filter-refs-query includes excludes class-ids)
                                        db
                                        (rules/extract-rules rules/db-query-dsl-rules
                                                             [:has-ref]
                                                             {:deps rules/rules-dependencies})
                                        ids))
        matched-refs-with-children-ids (let [*result (atom #{})]
                                         (doseq [ref-id matched-ref-block-ids]
                                           (old-get-block-parents-until-top-ref db id ref-id full-ref-block-ids *result))
                                         @*result)
        ref-blocks (->> (set/intersection full-ref-block-ids matched-refs-with-children-ids)
                        (map (fn [id] (d/entity db id))))
        filter-exists? (or (seq excludes) (seq includes))
        children-ids (set (remove full-ref-block-ids matched-refs-with-children-ids))]
    {:ref-blocks ref-blocks
     ;; unchanged helper
     :ref-pages-count (#'reference/get-ref-pages-count db id ref-blocks children-ids)
     :ref-matched-children-ids (when filter-exists? children-ids)}))

;; Fixtures
;; ========

(def ^:private graph
  "Pages first, then blocks. :page, :parent, :refs, :alias and :tags name other
   entries by :key. Every block has its own :block/order, so the view's parent
   groups never tie."
  [{:key :foo :title "foo" :alias [:fa1]}
   ;; aliases of foo through all four alias rule branches:
   ;; foo -> fa1, fa2 -> foo, foo -> fa1 -> fa3, fa4 -> fa2 -> foo
   {:key :fa1 :title "fa1" :alias [:fa3]}
   {:key :fa2 :title "fa2" :alias [:foo]}
   {:key :fa3 :title "fa3"}
   {:key :fa4 :title "fa4" :alias [:fa2]}
   {:key :bar :title "bar"}
   {:key :baz :title "baz"}
   {:key :other :title "other"}
   {:key :tag1 :title "tag1"}
   {:key :j1 :title "Jan 1st, 2024" :journal-day 20240101}
   {:key :j2 :title "Jan 2nd, 2024" :journal-day 20240102}
   {:key :p1 :title "p1"}
   {:key :p2 :title "p2"}
   ;; a page entity that refs foo: its whole page is the subtree
   {:key :p3 :title "p3" :refs [:foo]}

   {:key :b1 :page :j1 :parent :j1 :order "a01" :refs [:foo :other]}
   {:key :b1-1 :page :j1 :parent :b1 :order "a02" :refs [:bar]}
   {:key :b1-1-1 :page :j1 :parent :b1-1 :order "a03" :refs [:baz]}
   {:key :b1-2 :page :j1 :parent :b1 :order "a04"}
   {:key :b2 :page :j1 :parent :j1 :order "a05" :refs [:bar]}
   {:key :b2-1 :page :j1 :parent :b2 :order "a06" :refs [:fa1]}
   ;; a direct ref nested under another ref
   {:key :b2-1-1 :page :j1 :parent :b2-1 :order "a07" :refs [:foo :other]}
   {:key :b2-1-2 :page :j1 :parent :b2-1 :order "a08" :refs [:baz]}
   {:key :b3 :page :j1 :parent :j1 :order "a09" :refs [:bar :baz]}
   {:key :b3-1 :page :j1 :parent :b3 :order "a10" :refs [:fa2]}
   {:key :c1 :page :j2 :parent :j2 :order "a11" :refs [:fa3] :tags [:tag1]}
   {:key :c1-1 :page :j2 :parent :c1 :order "a12" :refs [:bar]}
   {:key :c2 :page :j2 :parent :j2 :order "a13" :refs [:foo :bar]}
   {:key :d1 :page :p1 :parent :p1 :order "a14" :refs [:fa4 :bar]}
   {:key :u1 :page :p1 :parent :p1 :order "a15" :refs [:other]}
   ;; refs from foo's own page are hidden
   {:key :f1 :page :foo :parent :foo :order "a16" :refs [:foo]}
   {:key :f1-1 :page :foo :parent :f1 :order "a17" :refs [:bar]}
   ;; cy1 and cy2 are each other's parent
   {:key :cy1 :page :p2 :parent :cy2 :order "a18" :refs [:foo]}
   {:key :cy2 :page :p2 :parent :cy1 :order "a19" :refs [:bar]}
   {:key :cy3 :page :p2 :parent :cy1 :order "a20" :refs [:other]}
   ;; rc refs foo, its parent chain ends in the cycle cz1 <-> cz2
   {:key :rc :page :p2 :parent :cz1 :order "a21" :refs [:foo]}
   {:key :cz1 :page :p2 :parent :cz2 :order "a22" :refs [:bar]}
   {:key :cz2 :page :p2 :parent :cz1 :order "a23"}
   ;; sl is its own parent
   {:key :sl :page :p2 :parent :sl :order "a24" :refs [:foo :baz]}
   {:key :e1 :page :p3 :parent :p3 :order "a25" :refs [:bar]}])

(def ^:private cycle-graph
  [{:key :foo :title "foo"}
   {:key :bar :title "bar"}
   {:key :p :title "p"}
   {:key :cy1 :page :p :parent :cy2 :order "a0" :refs [:foo]}
   {:key :cy2 :page :p :parent :cy1 :order "a1" :refs [:bar]}
   {:key :cy3 :page :p :parent :cy1 :order "a2"}
   {:key :rc :page :p :parent :cz1 :order "a3" :refs [:foo]}
   {:key :cz1 :page :p :parent :cz2 :order "a4" :refs [:bar]}
   {:key :cz2 :page :p :parent :cz1 :order "a5"}
   {:key :sl :page :p :parent :sl :order "a6" :refs [:foo]}])

(defn- build-db
  "In-memory file-schema db. Explicit ids, so parent cycles can be transacted."
  [entries]
  (let [key->id (zipmap (map :key entries) (range 1 (inc (count entries))))
        conn (d/create-conn file-schema/schema)]
    (d/transact! conn
                 (for [{k :key :keys [title journal-day page parent order refs alias tags]} entries]
                   (cond-> {:db/id (key->id k)
                            :block/uuid (random-uuid)}
                     title (assoc :block/title title
                                  :block/name (string/lower-case title)
                                  :block/type (if journal-day "journal" "page"))
                     journal-day (assoc :block/journal-day journal-day)
                     page (assoc :block/title (name k)
                                 :block/page (key->id page)
                                 :block/parent (key->id parent)
                                 :block/order order)
                     (seq refs) (assoc :block/refs (mapv key->id refs))
                     (seq alias) (assoc :block/alias (mapv key->id alias))
                     (seq tags) (assoc :block/tags (mapv key->id tags)))))
    {:db @conn :key->id key->id}))

(defn- with-filters
  "File graphs keep linked-reference filters in the page's :filters property"
  [db id filters]
  (cond-> db
    filters
    (d/db-with [{:db/id id :block/properties {:filters filters}}])))

(def ^:private filter-variants
  [nil
   "{\"bar\" true}"
   "{\"baz\" false}"
   "{\"bar\" true, \"baz\" false}"
   "{\"other\" true, \"bar\" false}"
   "{\"foo\" true}"])

(defn- summarize
  "Comparable form: ref blocks as a set, ref-pages-count as a multiset (entries
   with equal counts have no defined order)"
  [{:keys [ref-blocks ref-pages-count ref-matched-children-ids]}]
  {:ref-block-ids (set (map :db/id ref-blocks))
   :ref-block-count (count ref-blocks)
   :ref-pages-count (sort ref-pages-count)
   :ref-matched-children-ids ref-matched-children-ids})

(defn- linked-references-view
  "get-view-data as the Linked References section calls it, fully realized"
  [db id]
  (-> (view/get-view-data db nil {:view-for-id id
                                  :view-feature-type :linked-references
                                  :group-by-property-ident :block/page
                                  :input ""
                                  :filters {}
                                  :sorting [{:id :block/updated-at :asc? false}]})
      (update :ref-pages-count sort)
      (->> (walk/postwalk identity))))

;; Tests
;; =====

(deftest linked-references-match-datalog
  (let [{:keys [db key->id]} (build-db graph)
        ids #(set (map key->id %))]
    (testing "The fixture exercises direct, alias, nested, page and cyclic refs, and hides own-page refs"
      (is (= (ids [:b1 :b2-1 :b2-1-1 :b3-1 :c1 :c2 :d1 :cy1 :rc :sl :p3])
             (set (map :db/id (:ref-blocks (reference/get-linked-references db (key->id :foo))))))))
    (testing "Include and exclude filters reach through ancestors"
      (let [foo (key->id :foo)
            result (reference/get-linked-references
                    (with-filters db foo "{\"bar\" true, \"baz\" false}") foo)]
        (is (= (ids [:b1 :b2-1 :b2-1-1 :c1 :c2 :d1 :cy1 :rc :p3])
               (set (map :db/id (:ref-blocks result)))))
        (is (= (ids [:b1-1 :c1-1 :cy2 :cy3 :e1])
               (:ref-matched-children-ids result)))))
    (doseq [target [:foo :fa1 :fa2 :fa4 :bar :baz :other :tag1 :p2]
            filters filter-variants
            :let [id (key->id target)
                  db' (with-filters db id filters)]]
      (testing (str target " with filters " (pr-str filters))
        (is (= (summarize (old-get-linked-references db' id))
               (summarize (reference/get-linked-references db' id)))
            "get-linked-references")
        (is (= (with-redefs [reference/get-linked-references old-get-linked-references]
                 (linked-references-view db' id))
               (linked-references-view db' id))
            "get-view-data :linked-references")))))

(deftest parent-cycles-return
  (let [{:keys [db key->id]} (build-db cycle-graph)
        foo (key->id :foo)
        ids #(set (map key->id %))]
    (testing "No filters: the subtree walk stops on cycles"
      (let [result (reference/get-linked-references db foo)]
        (is (= (ids [:cy1 :rc :sl]) (set (map :db/id (:ref-blocks result)))))
        (is (= (summarize (old-get-linked-references db foo)) (summarize result)))))
    (testing "Include filter: the ancestor walk stops on cycles, a cycle node is its own ancestor"
      (let [db' (with-filters db foo "{\"bar\" true}")
            result (reference/get-linked-references db' foo)]
        (is (= (ids [:cy1 :rc]) (set (map :db/id (:ref-blocks result)))))
        (is (= (ids [:cy2 :cy3]) (:ref-matched-children-ids result)))
        (is (= (summarize (old-get-linked-references db' foo)) (summarize result)))))
    (testing "Exclude filter"
      (let [db' (with-filters db foo "{\"bar\" false}")
            result (reference/get-linked-references db' foo)]
        (is (= (ids [:sl]) (set (map :db/id (:ref-blocks result)))))
        (is (= #{} (:ref-matched-children-ids result)))
        (is (= (summarize (old-get-linked-references db' foo)) (summarize result)))))
    (testing "The climb to the nearest ref block stops on a cycle without one"
      (let [*result (atom #{})]
        (is (nil? (#'reference/get-block-parents-until-top-ref db foo (key->id :cz1) #{} *result)))
        (is (= #{} @*result))))))
