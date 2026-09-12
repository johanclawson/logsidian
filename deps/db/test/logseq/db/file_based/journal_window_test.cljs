(ns logseq.db.file-based.journal-window-test
  "Journal window fast path against d/q on in-memory file-schema dbs: the
   built-in NOW and NEXT, the legacy 0.10.x template, clause orders, and
   shapes that must stay on d/q"
  (:require [cljs.test :refer [deftest is testing]]
            [clojure.walk :as walk]
            [datascript.core :as d]
            [logseq.db.file-based.journal-window :as jw]
            [logseq.db.file-based.rules :as file-rules]
            [logseq.db.file-based.schema :as file-schema]
            [logseq.db.frontend.rules :as rules]))

;; Queries
;; =======

(def ^:private task-rules
  "% as frontend.db.query-custom/add-rules-to-query builds it"
  (rules/extract-rules file-rules/query-dsl-rules [:task]))

(def ^:private now-query
  "Built-in NOW (frontend.state/file-default-config), after add-rules-to-query"
  '[:find (pull ?h [*])
    :in $ ?start ?today %
    :where
    (task ?h #{"NOW" "DOING"})
    [?h :block/page ?p]
    [?p :block/journal-day ?d]
    [(>= ?d ?start)]
    [(<= ?d ?today)]])

(def ^:private next-query
  "Built-in NEXT, after add-rules-to-query"
  '[:find (pull ?h [*])
    :in $ ?start ?next %
    :where
    (task ?h #{"NOW" "LATER" "TODO"})
    [?h :block/page ?p]
    [?p :block/journal-day ?d]
    [(> ?d ?start)]
    [(< ?d ?next)]])

(def ^:private legacy-now-query
  "The 0.10.x config.edn template: no rule, and [?p :block/journal? true],
   which nothing writes any more"
  '[:find (pull ?h [*])
    :in $ ?start ?today
    :where
    [?h :block/marker ?marker]
    [(contains? #{"NOW" "DOING"} ?marker)]
    [?h :block/page ?p]
    [?p :block/journal? true]
    [?p :block/journal-day ?d]
    [(>= ?d ?start)]
    [(<= ?d ?today)]])

(def ^:private legacy-next-query
  "The template's NEXT after resolve-query turned :block/ref-pages into
   :block/refs"
  '[:find (pull ?h [*])
    :in $ ?start ?next
    :where
    [?h :block/marker ?marker]
    [(contains? #{"NOW" "LATER" "TODO"} ?marker)]
    [?h :block/refs ?p]
    [?p :block/journal? true]
    [?p :block/journal-day ?d]
    [(> ?d ?start)]
    [(< ?d ?next)]])

(defn- async-form
  "The query as the worker receives it: frontend.db.async.util adds :db/id to
   the pull"
  [query]
  (walk/postwalk (fn [f]
                   (if (and (seq? f) (= 'pull (first f)))
                     (list 'pull (second f) (conj (nth f 2) :db/id))
                     f))
                 query))

(defn- with-where
  "query with its :where clauses replaced"
  [query clauses]
  (let [head (vec (take-while #(not= :where %) query))]
    (into (conj head :where) clauses)))

(defn- where-of
  [query]
  (vec (rest (drop-while #(not= :where %) query))))

(defn- permutations
  [xs]
  (if (<= (count xs) 1)
    [(vec xs)]
    (for [i (range (count xs))
          :let [x (nth xs i)
                others (into (subvec xs 0 i) (subvec xs (inc i)))]
          p (permutations others)]
      (into [x] p))))

(defn- rotations
  [xs]
  (for [i (range (count xs))]
    (into (subvec xs i) (subvec xs 0 i))))

;; Fixture
;; =======

(def ^:private markers [nil "NOW" "DOING" "LATER" "TODO" "DONE"])

(def ^:private pages
  "Journal pages across a year boundary, one of them without :block/type
   \"journal\", one without blocks, and two non-journal pages"
  [{:id "j1220" :day 20251220}
   {:id "j1225" :day 20251225}
   {:id "j1226" :day 20251226}
   {:id "j1228" :day 20251228 :type "page"}
   {:id "j1231" :day 20251231}
   {:id "j0101" :day 20260101}
   {:id "j0102" :day 20260102 :no-blocks? true}
   {:id "j0105" :day 20260105}
   {:id "j0108" :day 20260108}
   {:id "j0109" :day 20260109}
   {:id "j0120" :day 20260120}
   {:id "project"}
   {:id "notes"}])

(def ^:private refs
  "block -> journal pages it references"
  {"project-b4" ["j0105"]
   "project-b2" ["j1231" "j0101"]
   "j1220-b3" ["j0101" "j0108"]
   "j0101-b1" ["j0101" "j1226"]
   "j0105-b5" ["j0105"]
   "notes-b1" ["j0109"]
   "j1231-b1-c" ["j0105"]})

(defn- page-tx
  [{:keys [id day type]}]
  (cond-> {:db/id id
           :block/uuid (random-uuid)
           :block/name id
           :block/title id
           :block/type (or type (if day "journal" "page"))}
    day (assoc :block/journal-day day)))

(defn- block-tx
  [id page parent order marker]
  (cond-> {:db/id id
           :block/uuid (random-uuid)
           :block/page page
           :block/parent parent
           :block/order order
           :block/title (str (or marker "note") " " id)}
    marker (assoc :block/marker marker)
    (= "NOW" marker) (assoc :block/priority "A")
    (contains? refs id) (assoc :block/refs (refs id))))

(defn- blocks-tx
  "One top-level block per marker on every page, and on two pages a nested
   chain under the NOW block"
  [{:keys [id no-blocks?]}]
  (when-not no-blocks?
    (concat
     (for [[i marker] (map-indexed vector markers)]
       (block-tx (str id "-b" i) id id (str "a" i) marker))
     (when (contains? #{"j1231" "project"} id)
       [(block-tx (str id "-b1-c") id (str id "-b1") "b0" "TODO")
        (block-tx (str id "-b1-c-c") id (str id "-b1-c") "c0" "DOING")
        (block-tx (str id "-b1-c-c-c") id (str id "-b1-c-c") "d0" nil)]))))

(defn- build-db
  "One transaction, so the string tempids in :block/page, :block/parent and
   :block/refs resolve to the pages and blocks"
  ([] (build-db file-schema/schema))
  ([schema]
   (d/db-with (d/empty-db schema)
              (concat (map page-tx pages) (mapcat blocks-tx pages)))))

(defn- page-eid
  [db id]
  (:db/id (d/entity db [:block/name id])))

(def ^:private windows
  "[lo hi]: across the year boundary with both ends on journal pages, inside
   a month, one day, reversed, before any journal, around everything"
  [[20251225 20260108]
   [20251226 20251231]
   [20251231 20260101]
   [20260108 20260108]
   [20260109 20251225]
   [20240101 20240131]
   [20251201 20260131]])

;; Helpers
;; =======

(defn- fast-path
  "run-stats of the query, or ::jw/no-match"
  [db query inputs]
  (let [plan (jw/plan query inputs)]
    (if (= ::jw/no-match plan)
      plan
      (jw/run-stats db plan))))

(defn- check-same
  "Asserts that the fast path answers query and returns exactly what d/q
   returns; returns the d/q result"
  [db query inputs]
  (let [expected (apply d/q query db inputs)
        stats (fast-path db query inputs)
        actual (jw/run db (jw/plan query inputs))]
    (is (map? stats) "the fast path answers the query")
    (is (= (set expected) (set (:result stats))))
    (is (= (count expected) (count (:result stats))) "no duplicate rows")
    (is (= (set expected) (set actual)))
    (is (seq? actual) "a list, which transit writes with d/q's list tag")
    (is (every? vector? actual) "[pulled-map] rows")
    (is (= (count actual) (:hits stats)))
    expected))

(defn- block-ids
  [result]
  (set (map (comp :block/title first) result)))

;; Tests
;; =====

(deftest built-in-now-and-next-match-d-q
  (let [db (build-db)]
    (doseq [[lo hi] windows
            query [now-query next-query (async-form now-query) (async-form next-query)]]
      (testing (str (first (where-of query)) " " lo ".." hi " " (second query))
        (check-same db query [lo hi task-rules])))
    (testing "The fixture gives non-empty results, so the comparison means something"
      (is (= #{"NOW j1225-b1" "DOING j1225-b2"
               "NOW j1226-b1" "DOING j1226-b2"
               "NOW j1228-b1" "DOING j1228-b2"
               "NOW j1231-b1" "DOING j1231-b2" "DOING j1231-b1-c-c"
               "NOW j0101-b1" "DOING j0101-b2"
               "NOW j0105-b1" "DOING j0105-b2"
               "NOW j0108-b1" "DOING j0108-b2"}
             (block-ids (check-same db now-query [20251225 20260108 task-rules]))))
      (is (= #{"NOW j1231-b1" "LATER j1231-b3" "TODO j1231-b4" "TODO j1231-b1-c"
               "NOW j0101-b1" "LATER j0101-b3" "TODO j0101-b4"}
             (block-ids (check-same db next-query [20251228 20260102 task-rules])))
          "> and < exclude the boundary days"))
    (testing "The async form pulls :db/id too"
      (is (every? (comp :db/id first)
                  (check-same db (async-form now-query) [20251225 20260108 task-rules]))))))

(deftest walk-is-bounded-by-the-window
  (let [db (build-db)
        {:keys [pages blocks hits]} (fast-path db now-query [20251225 20251226 task-rules])]
    (is (= 2 pages))
    (is (= 12 blocks) "only the blocks on the two pages in the window")
    (is (= 4 hits)))
  (testing "A large graph outside the window adds no work"
    (let [n 1500
          big (d/db-with (build-db)
                         (for [i (range n)
                               :let [page (str "old" i)]
                               tx [{:db/id page :block/uuid (random-uuid) :block/name page
                                    :block/title page :block/type "journal"
                                    :block/journal-day (+ 20000101 i)}
                                   {:block/uuid (random-uuid) :block/page page :block/parent page
                                    :block/order "a0" :block/title (str "NOW " page) :block/marker "NOW"}
                                   {:block/uuid (random-uuid) :block/page page :block/parent page
                                    :block/order "a1" :block/title (str "TODO " page) :block/marker "TODO"}]]
                           tx))
          {:keys [pages blocks hits]} (fast-path big now-query [20251225 20251226 task-rules])]
      (is (= [2 12 4] [pages blocks hits]))
      (check-same big now-query [20251225 20251226 task-rules])
      (is (= (* 2 n) (count (check-same big next-query [20000100 (+ 20000101 n) task-rules])))
          "the old pages in their own window (> and < are strict)"))))

(deftest legacy-template-matches-d-q
  (let [db (build-db)]
    (testing "Nothing writes :block/journal?, so the template returns nothing, now as before"
      (doseq [[lo hi] windows
              query [legacy-now-query legacy-next-query
                     (async-form legacy-now-query) (async-form legacy-next-query)]]
        (is (= [] (vec (check-same db query [lo hi]))) (str query " " lo ".." hi))))
    (testing "The page constant is checked per page, not dropped"
      (let [db' (d/db-with db [{:db/id (page-eid db "j1231") :block/journal? true}
                               {:db/id (page-eid db "j0101") :block/journal? true}
                               {:db/id (page-eid db "j0105") :block/journal? false}])]
        (is (= #{"NOW j1231-b1" "DOING j1231-b2" "DOING j1231-b1-c-c"
                 "NOW j0101-b1" "DOING j0101-b2"}
               (block-ids (check-same db' legacy-now-query [20251225 20260108]))))
        (is (= #{"TODO project-b4" "TODO j1231-b1-c"}
               ;; only j0105 has journal? false; its DONE referrer is not a NEXT task
               (block-ids (check-same db' (with-where legacy-next-query
                                            (assoc (where-of legacy-next-query) 3 '[?p :block/journal? false]))
                                      [20260101 20260109]))))
        (is (= #{"NOW j0101-b1" "LATER j1220-b3"}
               ;; j1231 is outside (20251231, 20260109): > is strict; project-b2 is DOING
               (block-ids (check-same db' legacy-next-query [20251231 20260109]))))))
    (testing "Other scalar page constants, e.g. a :block/type the compat design would write"
      (doseq [[lo hi] windows
              constant ['[?p :block/type "journal"] '[?p :block/type "page"]
                        '[?p :block/name "j1231"] '[?p :block/journal-day 20260105]]]
        (check-same db (with-where legacy-now-query
                         (assoc (where-of legacy-now-query) 3 constant))
                    [lo hi])
        (check-same db (with-where now-query (conj (where-of now-query) constant))
                    [lo hi task-rules]))
      (is (not (contains? (block-ids (check-same db (with-where now-query (conj (where-of now-query)
                                                                                  '[?p :block/type "journal"]))
                                                 [20251225 20260108 task-rules]))
                          "NOW j1228-b1"))
          "j1228 has a journal day but :block/type \"page\""))))

(deftest refs-join-matches-d-q
  (let [db (build-db)
        refs-next (with-where next-query (assoc (where-of next-query) 1 '[?h :block/refs ?p]))]
    (doseq [[lo hi] windows]
      (check-same db refs-next [lo hi task-rules]))
    (is (= #{"TODO project-b4" "TODO j1231-b1-c" "LATER j1220-b3"}
           (block-ids (check-same db refs-next [20260101 20260109 task-rules]))))))

(defn- well-ordered?
  "Whether d/q accepts this order: every predicate after the clause that
   binds its var"
  [clauses]
  (let [index-of (fn [pred] (first (keep-indexed #(when (pred %2) %1) clauses)))
        day (index-of #(and (vector? %) (= :block/journal-day (second %))))
        marker (index-of #(and (vector? %) (= :block/marker (second %))))
        bounds (keep-indexed #(when (and (vector? %2) (seq? (first %2))
                                         (contains? '#{>= > <= <} (ffirst %2)))
                                %1)
                             clauses)
        contains (index-of #(and (vector? %) (seq? (first %)) (= 'contains? (ffirst %))))]
    (and (every? #(< day %) bounds)
         (or (nil? contains) (< marker contains)))))

(deftest clause-order-does-not-matter
  (let [db (build-db)]
    (doseq [[query inputs] [[now-query [20251225 20260108 task-rules]]
                            [next-query [20251228 20260105 task-rules]]]
            clauses (permutations (where-of query))
            :let [query' (with-where query clauses)]]
      (if (well-ordered? clauses)
        (check-same db query' inputs)
        (do
          (is (thrown-with-msg? js/Error #"Insufficient bindings" (apply d/q query' db inputs)))
          (is (= ::jw/no-match (jw/plan query' inputs)) "d/q keeps raising the same error"))))
    (let [db' (d/db-with db [{:db/id (page-eid db "j1231") :block/journal? true}
                             {:db/id (page-eid db "j0105") :block/journal? true}])
          legacy-where (where-of legacy-now-query)]
      (doseq [graph [db db']
              clauses (concat (rotations legacy-where) (rotations (vec (reverse legacy-where))))
              :let [query' (with-where legacy-now-query clauses)]]
        (if (well-ordered? clauses)
          (check-same graph query' [20251225 20260108])
          (is (= ::jw/no-match (jw/plan query' [20251225 20260108]))))))))

(deftest unwalkable-db-falls-back-to-d-q
  (let [schema (update file-schema/schema :block/journal-day dissoc :db/index)
        db (build-db schema)
        plan (jw/plan now-query [20251225 20260108 task-rules])]
    (is (= ::jw/no-match (jw/run-stats db plan)))
    (is (= (set (d/q now-query db 20251225 20260108 task-rules))
           (set (jw/run db plan))))
    (is (seq (jw/run db plan)))))

(deftest near-misses-stay-on-d-q
  (let [in3 [20251225 20260108 task-rules]
        in2 [20251225 20260108]
        now-where (where-of now-query)
        legacy-where (where-of legacy-now-query)
        cases
        {"extra clause on the block"
         [(with-where now-query (conj now-where '[?h :block/priority "A"])) in3]
         "extra join"
         [(with-where now-query (conj now-where '[?h :block/refs ?r])) in3]
         "a user rule named task"
         [now-query [20251225 20260108 '[[(task ?b ?markers) [?b :block/marker ?marker]]]]]
         "the task rule plus another rule"
         [now-query [20251225 20260108 (into task-rules
                                             (rules/extract-rules file-rules/query-dsl-rules [:priority]))]]
         "the task rule with a vector head"
         [now-query [20251225 20260108 (walk/postwalk #(if (seq? %) (vec %) %) task-rules)]]
         "string input"
         [now-query ["20251225" 20260108 task-rules]]
         "fractional input"
         [now-query [20251225.5 20260108 task-rules]]
         "nil input"
         [now-query [nil 20260108 task-rules]]
         "rules missing from the inputs"
         [now-query in2]
         "an extra input"
         [now-query [20251225 20260108 task-rules 1]]
         "task rule without % in :in"
         [(into '[:find (pull ?h [*]) :in $ ?start ?today :where] now-where) in2]
         "inline marker form with %"
         [(into '[:find (pull ?h [*]) :in $ ?start ?today % :where] legacy-where) in3]
         "find without pull"
         [(into '[:find ?h :in $ ?start ?today % :where] now-where) in3]
         "find with a second element"
         [(into '[:find (pull ?h [*]) ?p :in $ ?start ?today % :where] now-where) in3]
         "collection find"
         [(into '[:find [(pull ?h [*]) ...] :in $ ?start ?today % :where] now-where) in3]
         "pull of the page"
         [(into '[:find (pull ?p [*]) :in $ ?start ?today % :where] now-where) in3]
         "pull with a source"
         [(into '[:find (pull $ ?h [*]) :in $ ?start ?today % :where] now-where) in3]
         "a :with section"
         [(into '[:find (pull ?h [*]) :with ?p :in $ ?start ?today % :where] now-where) in3]
         "map query"
         [{:find '[(pull ?h [*])] :in '[$ ?start ?today %] :where now-where} in3]
         "no :in"
         [(into '[:find (pull ?h [*]) :where] now-where) []]
         "missing upper bound"
         [(with-where now-query (pop now-where)) in3]
         "two lower bounds"
         [(with-where now-query (conj (pop now-where) '[(>= ?d ?today)])) in3]
         "both bounds on one input"
         [(with-where now-query (conj (pop now-where) '[(<= ?d ?start)])) in3]
         "a constant bound"
         [(with-where now-query (conj (pop now-where) '[(<= ?d 20260108)])) in3]
         "reversed bound arguments"
         [(with-where now-query (conj (pop now-where) '[(>= ?today ?d)])) in3]
         "a duplicated bound"
         [(with-where now-query (conj now-where '[(<= ?d ?today)])) in3]
         "journal day bound to an input var"
         [(with-where now-query (assoc now-where 2 '[?p :block/journal-day ?start])) in3]
         "page constant on a ref attribute"
         [(with-where now-query (conj now-where '[?p :block/namespace 1])) in3]
         "page constant on a ref attribute with a string"
         [(with-where now-query (conj now-where '[?p :block/alias "x"])) in3]
         "page constant nil"
         [(with-where now-query (conj now-where '[?p :block/journal? nil])) in3]
         "page constant on :db/ident"
         [(with-where now-query (conj now-where '[?p :db/ident :foo])) in3]
         "page attribute bound to a var"
         [(with-where now-query (conj now-where '[?p :block/type ?t])) in3]
         "constant on the block instead of the page"
         [(with-where now-query (conj now-where '[?h :block/type "journal"])) in3]
         "source-prefixed pattern"
         [(with-where now-query (assoc now-where 1 '[$ ?h :block/page ?p])) in3]
         "join through :block/parent"
         [(with-where now-query (assoc now-where 1 '[?h :block/parent ?p])) in3]
         "markers from an input"
         [(into '[:find (pull ?h [*]) :in $ ?start ?today ?m % :where]
                (assoc now-where 0 '(task ?h ?m)))
          [20251225 20260108 #{"NOW"} task-rules]]
         "keyword markers"
         [(with-where now-query (assoc now-where 0 '(task ?h #{:NOW}))) in3]
         "task rule on the page"
         [(with-where now-query (assoc now-where 0 '(task ?p #{"NOW"}))) in3]
         "contains? on another var"
         [(with-where legacy-now-query (assoc legacy-where 1 '[(contains? #{"NOW"} ?x)])) in2]
         "an or clause"
         [(with-where now-query (conj now-where '(or [?h :block/priority "A"] [?h :block/priority "B"]))) in3]
         "a not clause"
         [(with-where now-query (conj now-where '(not [?h :block/priority "A"]))) in3]
         "a rule call with a vector head"
         [(with-where now-query (assoc now-where 0 '[task ?h #{"NOW"}])) in3]
         "pattern written as a list"
         [(with-where now-query (assoc now-where 1 '(?h :block/page ?p))) in3]}]
    (doseq [[label [query inputs]] cases]
      (is (= ::jw/no-match (jw/plan query inputs)) label))
    (testing "Garbage in"
      (is (= ::jw/no-match (jw/plan nil nil)))
      (is (= ::jw/no-match (jw/plan "[:find ?h]" [])))
      (is (= ::jw/no-match (jw/plan [:find] nil))))))
