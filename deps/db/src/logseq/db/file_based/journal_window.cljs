(ns logseq.db.file-based.journal-window
  "Index-walk fast path for the journals default queries of file graphs: the
   built-in \"NOW\" and \"NEXT\" (frontend.state/file-default-config) and the
   legacy 0.10.x config.edn template of the same shape.

   The DataScript fork runs :where clauses in order and uses a bound variable
   as a lookup key only when its relation holds exactly one tuple. These
   queries therefore read every :block/marker, :block/page and
   :block/journal-day datom of the graph, and on a lazily restored db restore
   all of their leaves from SQLite (ADR-003, bench/now-query-design.json).

   `plan` recognizes the shape structurally: clause order does not matter, as
   long as d/q itself would accept the order. `run` answers a plan with index
   walks whose cost follows the blocks on the journal pages inside the window,
   not the size of the graph:
   AVET :block/journal-day between the bounds, then each page's blocks via
   AVET :block/page (or :block/refs), then an EAVT :block/marker check per
   block, then a pull of every match with the query's own selector.

   Anything that does not match exactly is ::no-match; run it with d/q."
  (:require [datascript.core :as d]
            [logseq.db.file-based.rules :as file-rules]
            [logseq.db.file-based.schema :as file-schema]
            [logseq.db.frontend.rules :as rules]))

(def task-rules
  "The % input the frontend passes with a query that calls `task`
   (frontend.db.query-custom/add-rules-to-query)"
  (rules/extract-rules file-rules/query-dsl-rules [:task]))

(def ^:private link-attrs
  "Block -> page attributes a window query may join on. :block/refs is what
   frontend.db.query-react/resolve-query makes of the legacy :block/ref-pages."
  #{:block/page :block/refs})

(def ^:private compare-fns
  "The bound predicates. d/q's built-ins compare with value-compare, which
   agrees with these on numbers; run falls back for any other value."
  {'>= >= '> > '<= <= '< <})

;; Matching
;; ========

(defn- logic-var?
  [x]
  (and (symbol? x)
       (nil? (namespace x))
       (let [n (name x)]
         (and (< 1 (count n)) (= "?" (subs n 0 1))))))

(defn- form-shape
  "x with every vector and seq tagged, so strict= tells [a b] from (a b)"
  [x]
  (cond
    (vector? x) (into [::vec] (map form-shape) x)
    (seq? x) (into [::seq] (map form-shape) x)
    :else x))

(defn- strict=
  [a b]
  (= (form-shape a) (form-shape b)))

(defn- query-sections
  "{:find [..] :in [..] :where [..]} of a vector query; nil when it does not
   start with :find or repeats a section"
  [query]
  (when (and (vector? query) (= :find (first query)))
    (some-> (reduce (fn [[m k] x]
                      (if (keyword? x)
                        (if (contains? m x) (reduced nil) [(assoc m x []) x])
                        [(update m k conj x) k]))
                    [{} nil]
                    query)
            first)))

(defn- scalar-const?
  [x]
  (or (string? x)
      (boolean? x)
      (keyword? x)
      (and (number? x) (js/isFinite x))))

(defn- plain-attr?
  "An attribute whose values d/q and d/datoms both compare as they are: no
   ref (d/q resolves idents and lookup refs there), no tuple, no :db/ or
   reverse attribute"
  [schema attr]
  (and (keyword? attr)
       (not= "db" (namespace attr))
       (not= "_" (subs (name attr) 0 1))
       (let [s (get schema attr)]
         (and (nil? (:db/valueType s))
              (nil? (:db/tupleAttrs s))
              (nil? (:db/tupleType s))
              (nil? (:db/tupleTypes s))))))

(defn- marker-set?
  [x]
  (and (set? x) (every? string? x)))

(defn- classify
  "One :where clause -> [kind data], or nil for anything the fast path does
   not know. Data carries the vars; plan checks how they connect."
  [clause]
  (cond
    ;; (task ?h #{"NOW" "DOING"})
    (and (seq? clause)
         (= 3 (count clause))
         (= 'task (first clause))
         (logic-var? (second clause))
         (marker-set? (nth clause 2)))
    [:task {:e (second clause) :markers (nth clause 2)}]

    ;; [(pred x y)]
    (and (vector? clause)
         (= 1 (count clause))
         (seq? (first clause))
         (= 3 (count (first clause))))
    (let [[op x y] (first clause)]
      (cond
        (and (= 'contains? op) (marker-set? x) (logic-var? y))
        [:contains {:markers x :v y}]

        (and (contains? '#{>= >} op) (logic-var? x) (logic-var? y))
        [:lower {:op op :d x :in y}]

        (and (contains? '#{<= <} op) (logic-var? x) (logic-var? y))
        [:upper {:op op :d x :in y}]))

    ;; [e a v]
    (and (vector? clause)
         (= 3 (count clause))
         (logic-var? (first clause))
         (keyword? (second clause)))
    (let [[e a v] clause]
      (cond
        (and (= :block/marker a) (logic-var? v)) [:marker {:e e :v v}]
        (and (contains? link-attrs a) (logic-var? v)) [:link {:e e :a a :v v}]
        (and (= :block/journal-day a) (logic-var? v)) [:day {:e e :v v}]
        ;; [?p :block/journal? true]: checked per page, never dropped
        (and (scalar-const? v) (plain-attr? file-schema/schema a)) [:const {:e e :a a :v v}]))))

(defn- one
  "The only element of coll, or nil"
  [coll]
  (when (= 1 (count coll)) (first coll)))

(defn- plan*
  [query inputs]
  (let [{find' :find in :in where :where :as sections} (query-sections query)
        inputs (vec inputs)
        pull-form (one find')]
    (when (and (= #{:find :in :where} (set (keys sections)))
               ;; :find (pull ?h SELECTOR)
               (seq? pull-form)
               (= 3 (count pull-form))
               (= 'pull (first pull-form))
               (logic-var? (second pull-form))
               (vector? (nth pull-form 2))
               ;; :in $ ?lo ?hi, or :in $ ?lo ?hi %
               (contains? #{3 4} (count in))
               (= '$ (first in))
               (logic-var? (nth in 1))
               (logic-var? (nth in 2))
               (or (= 3 (count in)) (= '% (nth in 3)))
               (= (count inputs) (dec (count in)))
               (integer? (nth inputs 0))
               (integer? (nth inputs 1)))
      (let [h (second pull-form)
            in-vals {(nth in 1) (nth inputs 0)
                     (nth in 2) (nth inputs 1)}
            rules? (= 4 (count in))
            classified (map-indexed (fn [i c] (when-let [[kind m] (classify c)]
                                                [kind (assoc m :i i)]))
                                    where)
            by-kind (group-by first classified)
            of-kind (fn [kind] (map second (get by-kind kind)))
            link (one (of-kind :link))
            p (:v link)
            day (one (of-kind :day))
            d (:v day)
            lower (one (of-kind :lower))
            upper (one (of-kind :upper))
            consts (of-kind :const)
            ;; the marker constraint: the file `task` rule, or its body inline
            task (one (of-kind :task))
            marker (one (of-kind :marker))
            contains (one (of-kind :contains))
            [markers marker-vars]
            (cond
              (and rules? task (empty? (of-kind :marker)) (empty? (of-kind :contains))
                   (= h (:e task))
                   (strict= task-rules (nth inputs 2)))
              [(:markers task) []]

              (and (not rules?) marker contains (empty? (of-kind :task))
                   (= h (:e marker))
                   (= (:v marker) (:v contains))
                   ;; d/q raises "Insufficient bindings" for a predicate
                   ;; before the clause that binds its var
                   (< (:i marker) (:i contains)))
              [(:markers contains) [(:v marker)]])
            vars (concat [h p d] marker-vars (keys in-vals))]
        (when (and markers
                   (not-any? nil? classified)
                   link day lower upper
                   (= h (:e link))
                   (= p (:e day))
                   (apply distinct? vars)
                   (= d (:d lower) (:d upper))
                   (contains? in-vals (:in lower))
                   (contains? in-vals (:in upper))
                   (not= (:in lower) (:in upper))
                   (< (:i day) (:i lower))
                   (< (:i day) (:i upper))
                   (every? #(= p (:e %)) consts))
          {:query query
           :inputs inputs
           :selector (nth pull-form 2)
           :link-attr (:a link)
           :lower-op (:op lower)
           :lo (in-vals (:in lower))
           :upper-op (:op upper)
           :hi (in-vals (:in upper))
           :markers markers
           :page-consts (mapv (juxt :a :v) consts)})))))

(defn plan
  "Matches `query` (with `inputs`, everything d/q gets after the db) against
   the journal window shape:

     :find (pull ?h SELECTOR)
     :in $ ?lo ?hi [%]
     :where, in any order d/q accepts:
       (task ?h #{\"NOW\" ..}) with the file task rule as %, or
         [?h :block/marker ?m] [(contains? #{\"NOW\" ..} ?m)]
       [?h :block/page ?p] or [?h :block/refs ?p]
       [?p :block/journal-day ?d]
       [(>= ?d ?lo)] or [(> ?d ?lo)], and [(<= ?d ?hi)] or [(< ?d ?hi)]
       any number of [?p ATTR SCALAR] on non-ref attributes

   with integer ?lo and ?hi. Returns a plan for `run`, else ::no-match."
  [query inputs]
  (or (plan* query inputs) ::no-match))

;; Walking
;; =======

(defn- indexed?
  "Mirrors datascript.db/attr->properties: which attributes have AVET"
  [schema attr]
  (let [s (get schema attr)]
    (or (true? (:db/index s))
        (= :db.type/ref (:db/valueType s))
        (some? (:db/unique s))
        (some? (:db/tupleAttrs s)))))

(defn- walkable?
  "Whether db's schema lets the index walks answer the plan like d/q"
  [db {:keys [link-attr page-consts]}]
  (let [schema (:schema db)]
    (and (indexed? schema :block/journal-day)
         (not= :db.type/ref (get-in schema [:block/journal-day :db/valueType]))
         (indexed? schema link-attr)
         (= :db.type/ref (get-in schema [link-attr :db/valueType]))
         (every? (fn [[a _]] (plain-attr? schema a)) page-consts))))

(defn run-stats
  "Answers a plan with index walks and realizes everything, pulls included,
   before it returns. Returns {:result :pages :blocks :hits}, where :result
   has the shape d/q gives for :find (pull ?h SELECTOR), a list of [pulled-map]
   rows (transit writes it with the same list tag as d/q's lazy seq). Returns
   ::no-match when db cannot be walked for the plan; run it with d/q then."
  [db {:keys [selector link-attr lower-op lo upper-op hi markers page-consts] :as plan}]
  (if-not (walkable? db plan)
    ::no-match
    (let [lower? (compare-fns lower-op)
          upper? (compare-fns upper-op)
          ;; lo > hi selects nothing in d/q either
          days (if (<= lo hi)
                 (vec (d/index-range db :block/journal-day lo hi))
                 [])]
      ;; The AVET order and d/q's predicates both use value-compare, so no
      ;; value outside the slice can pass. Inside it, only numbers are
      ;; compared here.
      (if-not (every? #(number? (:v %)) days)
        ::no-match
        (let [pages (into []
                          (comp (filter #(and (lower? (:v %) lo) (upper? (:v %) hi)))
                                (map :e)
                                (distinct)
                                (filter (fn [p]
                                          (every? (fn [[a v]] (seq (d/datoms db :eavt p a v)))
                                                  page-consts))))
                          days)
              blocks (into []
                           (comp (mapcat #(d/datoms db :avet link-attr %))
                                 (map :e)
                                 (distinct))
                           pages)
              hits (filterv (fn [e]
                              (some #(contains? markers (:v %))
                                    (d/datoms db :eavt e :block/marker)))
                            blocks)
              ;; d/q parses the selector only when there are rows
              rows (if (seq hits)
                     (mapv vector (d/pull-many db selector hits))
                     [])]
          {:result (apply list rows)
           :pages (count pages)
           :blocks (count blocks)
           :hits (count hits)})))))

(defn run
  "The result of (apply d/q (:query plan) db (:inputs plan)) for a plan from
   `plan`, fully realized: by index walks, or by that d/q call when db
   cannot be walked"
  [db plan]
  (let [r (run-stats db plan)]
    (if (= ::no-match r)
      (apply d/q (:query plan) db (:inputs plan))
      (:result r))))
