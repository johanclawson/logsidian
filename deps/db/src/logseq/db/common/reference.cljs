(ns logseq.db.common.reference
  "References"
  (:require [cljs.reader :as reader]
            [clojure.set :as set]
            [clojure.string :as string]
            [datascript.core :as d]
            [logseq.common.log :as log]
            [logseq.db :as ldb]
            [logseq.db.common.entity-plus :as entity-plus]
            [logseq.db.common.initial-data :as common-initial-data]
            [logseq.db.frontend.class :as db-class]))

(defn get-filters
  [db page]
  (let [db-based? (entity-plus/db-based-graph? db)]
    (if db-based?
      (let [included-pages (:logseq.property.linked-references/includes page)
            excluded-pages (:logseq.property.linked-references/excludes page)]
        (when (or (seq included-pages) (seq excluded-pages))
          {:included included-pages
           :excluded excluded-pages}))
      (let [k :filters
            properties (:block/properties page)
            properties-str (or (get properties k) "{}")]
        (try (let [result (reader/read-string properties-str)]
               (when (seq result)
                 (let [excluded-pages (->> (filter #(false? (second %)) result)
                                           (keep first)
                                           (keep #(ldb/get-page db %)))
                       included-pages (->> (filter #(true? (second %)) result)
                                           (keep first)
                                           (keep #(ldb/get-page db %)))]
                   {:included included-pages
                    :excluded excluded-pages})))
             (catch :default e
               (log/error :syntax/filters e)))))))

(defn- ancestor-ref-ids
  "Ids in `interesting` referenced by the strict ancestors of `e`: every node
   reached through one or more :block/parent steps, page included, as the
   `parent` rule derives them. The visited set ends the walk on a parent cycle,
   where the rule also counts a node of the cycle as its own ancestor."
  [e interesting]
  (loop [p (:block/parent e)
         visited #{}
         result #{}]
    (if (and p (not (contains? visited (:db/id p))))
      (recur (:block/parent p)
             (conj visited (:db/id p))
             (into result (keep #(interesting (:db/id %))) (:block/refs p)))
      result)))

(defn- get-matched-ref-block-ids
  "Same set as the former Datalog query
   `[:find [?b ...] :in $ % [?id ...] :where (has-ref ?b ?id) <filters>]`:
   blocks that reference one of the ids (`full-ref-block-ids`) or have an
   ancestor that does, whose own refs plus their ancestors' refs contain every
   include and no exclude, and that have no tag in `class-ids`.
   Index walk instead of the has-ref/parent rules, which scanned every
   :block/parent datom of the graph: start from the direct refs (:block/_refs)
   and walk their :block/_parent subtrees, carrying the include/exclude refs
   found on the path down. Ancestors of the direct refs are read only when there
   are filters. Cost follows the refs, their subtrees and their ancestor chains,
   not the graph size. The seen set also ends the walk on parent cycles.
   Everything is realized in this call."
  [db full-ref-block-ids includes excludes class-ids]
  (let [includes (set includes)
        excludes (set excludes)
        class-ids (set class-ids)
        interesting (set/union includes excludes)
        filters? (seq interesting)
        own-ref-ids (fn [e] (into #{} (keep #(interesting (:db/id %))) (:block/refs e)))
        matched? (fn [e ref-ids]
                   (and (every? ref-ids includes)
                        (not-any? ref-ids excludes)
                        (or (empty? class-ids)
                            (not-any? #(contains? class-ids (:db/id %)) (:block/tags e)))))]
    ;; stack holds [entity ref-ids], ref-ids = interesting ids referenced by the
    ;; entity or one of its ancestors
    (loop [stack ()
           roots (seq full-ref-block-ids)
           seen #{}
           result #{}]
      (if-let [[e ref-ids] (first stack)]
        (let [eid (:db/id e)]
          (if (contains? seen eid)
            (recur (rest stack) roots seen result)
            (recur (into (rest stack)
                         (map (fn [child]
                                [child (if filters? (into ref-ids (own-ref-ids child)) ref-ids)]))
                         (:block/_parent e))
                   roots
                   (conj seen eid)
                   (if (matched? e ref-ids) (conj result eid) result))))
        (if roots
          (let [root (d/entity db (first roots))]
            (recur (if (and root (not (contains? seen (:db/id root))))
                     (list [root (if filters?
                                   (into (ancestor-ref-ids root interesting) (own-ref-ids root))
                                   #{})])
                     ())
                   (next roots)
                   seen
                   result))
          result)))))

(defn- get-path-refs
  [db entity]
  (let [refs (mapcat :block/refs (ldb/get-block-parents db (:block/uuid entity)))
        block-page (:block/page entity)]
    (->> (cond->> refs (some? block-page) (cons block-page))
         distinct)))

(defn- get-ref-pages-count
  [db id ref-blocks children-ids]
  (when (seq ref-blocks)
    (let [children (->> children-ids
                        (map (fn [id] (d/entity db id))))]
      (->> (concat (mapcat #(get-path-refs db %) ref-blocks)
                   (mapcat :block/refs (concat ref-blocks children)))
           frequencies
           (keep (fn [[ref size]]
                   (when (and (ldb/page? ref)
                              (not= (:db/id ref) id)
                              (not= :block/tags (:db/ident ref))
                              (not (common-initial-data/hidden-ref? db ref id)))
                     [(:block/title ref) size])))
           (sort-by second #(> %1 %2))))))

(defn- get-block-parents-until-top-ref
  "Climbs from ref-id to its nearest ref block. The visited set ends the climb
   on a parent cycle without a ref block: nothing is added, as when the chain
   ends without one."
  [db id ref-id ref-block-ids *result]
  (loop [eid ref-id
         parents' []
         visited #{}]
    (when (and eid (not (contains? visited eid)))
      (cond
        (contains? @*result eid)
        (swap! *result into parents')

        (contains? ref-block-ids eid)
        (when-not (common-initial-data/hidden-ref? db (d/entity db eid) id)
          (swap! *result into (conj parents' eid)))
        :else
        (let [e (d/entity db eid)]
          (recur (:db/id (:block/parent e)) (conj parents' eid) (conj visited eid)))))))

(defn get-linked-references
  [db id]
  (let [entity (d/entity db id)
        ids (set (cons id (ldb/get-block-alias db id)))
        page-filters (get-filters db entity)
        excludes (map :db/id (:excluded page-filters))
        includes (map :db/id (:included page-filters))
        class-ids (when (ldb/class? entity)
                    (let [class-children (db-class/get-structured-children db id)]
                      (set (conj class-children id))))
        full-ref-block-ids (->> (mapcat (fn [id] (map :db/id (:block/_refs (d/entity db id)))) ids)
                                set)
        matched-ref-block-ids (get-matched-ref-block-ids db full-ref-block-ids includes excludes class-ids)
        matched-refs-with-children-ids (let [*result (atom #{})]
                                         (doseq [ref-id matched-ref-block-ids]
                                           (get-block-parents-until-top-ref db id ref-id full-ref-block-ids *result))
                                         @*result)
        ref-blocks (->> (set/intersection full-ref-block-ids matched-refs-with-children-ids)
                        (map (fn [id] (d/entity db id))))
        filter-exists? (or (seq excludes) (seq includes))
        children-ids (set (remove full-ref-block-ids matched-refs-with-children-ids))]
    {:ref-blocks ref-blocks
     :ref-pages-count (get-ref-pages-count db id ref-blocks children-ids)
     :ref-matched-children-ids (when filter-exists? children-ids)}))

(defn get-unlinked-references
  [db id]
  (let [entity (d/entity db id)
        title (string/lower-case (:block/title entity))]
    (when-not (string/blank? title)
      (let [ids (->> (d/datoms db :avet :block/title)
                     (keep (fn [d]
                             (when (and (not= id (:e d)) (string/includes? (string/lower-case (:v d)) title))
                               (:e d)))))]
        (keep
         (fn [eid]
           (let [e (d/entity db eid)]
             (when-not (or (some #(= id %) (map :db/id (:block/refs e)))
                           (:block/link e)
                           (ldb/built-in? e))
               e)))
         ids)))))
