(ns logseq.graph-parser.block-identity
  "For file graphs, keeps block uuids (and so the blocks' entities) across
  re-parses of their file. The parser gives a block the uuid of its id:: /
  custom-id:: property, else a fresh one; without this every re-parse of a file
  replaced all id-less blocks with new entities, so editor state, refs to them
  and search rows pointed at gone blocks after each external edit.

  `reuse-block-uuids` matches the blocks parsed from the new file content
  against the blocks of the file's page in the db, and gives each matched new
  block the uuid of its old block. The key of a block is its :block/title (its
  raw content incl. its own property lines, without children). Rules:

  1. Explicit ids win. New blocks with an id:: / custom-id:: uuid are left
     alone; old blocks that have such a property, or whose uuid one of those new
     blocks claims, take no part in the matching.
  2. Fast path: when the key sequences of the remaining old and new blocks are
     equal, they are matched one to one.
  3. Unique anchors (patience diff): a key that occurs exactly once among the
     old and exactly once among the new blocks matches those two blocks,
     wherever they are, so a moved block keeps its uuid. The longest run of
     anchors whose old positions increase in new order (LIS) cuts both
     sequences into gaps; anchors outside that run are matched too but sit in
     no gap.
  4. Within a gap, equal keys are matched first come, first served in order
     (duplicated content).
  5. Then the gap's remaining old and new blocks are zipped in order, and a
     pair is matched when both are at the same tree level (an edited block); a
     pair at different levels is not matched and the zip goes on. Every other
     new block keeps its fresh uuid (inserted), every other old block is deleted
     by the caller.
  6. No old uuid is given out twice. When the file's page changed its name
     (title::), `page-blocks-for-identity` returns nil and nothing is reused.

  O(n + m) apart from sorting the old tree's children and O(k log k) for the
  LIS over k anchors."
  (:require [clojure.string :as string]
            [datascript.core :as d]
            [logseq.db :as ldb]))

(defn- property-uuid
  "The uuid an id:: / custom-id:: property names, read the way
  gp-block/get-custom-id-or-new-id reads it, else nil"
  [properties]
  (let [v (or (:custom-id properties) (:custom_id properties) (:id properties))]
    (when (string? v)
      (parse-uuid (string/trim v)))))

(defn- explicit-uuid
  "The uuid a parsed block got from its own id property, else nil"
  [block]
  (property-uuid (:block/properties block)))

(defn- macro? [block] (= "macro" (:block/type block)))

(defn page-blocks-for-identity
  "The blocks of the page named `page-name` (the name the new content gives the
  file's page) that is bound to `file-path`, in file (pre-)order, as
  `reuse-block-uuids` takes them: maps of :uuid, :title, :level (1 = top level)
  and :has-id? (the block has an id:: / custom-id:: property). nil when no page
  is bound to the file yet or when none of its pages has that name (the new
  content renamed the page with title::). A rename leaves the old page bound
  to the file as well, hence the lookup by name rather than the first page."
  [db file-path page-name]
  (when-let [page (some->> (d/entity db [:file/path file-path])
                           :block/_file
                           (some #(when (= page-name (:block/name %)) %)))]
    (let [children (fn [e] (some-> (seq (ldb/sort-by-order (:block/_parent e))) vec rseq))
          push (fn [stack e level] (into stack (map (fn [c] [c level])) (children e)))]
      (loop [stack (push [] page 1)
             out (transient [])]
        (if-let [[e level] (peek stack)]
          (recur (push (pop stack) e (inc level))
                 (conj! out {:uuid (:block/uuid e)
                             :title (:block/title e)
                             :level level
                             :has-id? (some? (property-uuid (:block/properties e)))}))
          (persistent! out))))))

(defn- tree-levels
  "The tree level (1 = top level) of each block, in order, derived from the
  blocks' raw :block/level as gp-block/with-parent-and-order builds the tree
  from it; nil for macro blocks, which that fn leaves out of the tree"
  [blocks]
  ;; path: [raw-level level] of the page and the last block's ancestors-or-self
  (loop [bs (seq blocks)
         path [[0 0]]
         out (transient [])]
    (if-let [b (first bs)]
      (if (macro? b)
        (recur (next bs) path (conj! out nil))
        (let [raw (or (:block/level b) 1)
              [top-raw top-level] (peek path)]
          (cond
            (= raw top-raw)                       ; sibling of the last block
            (recur (next bs) (conj (pop path) [raw top-level]) (conj! out top-level))

            (> raw top-raw)                       ; child of the last block
            (let [level (inc top-level)]
              (recur (next bs) (conj path [raw level]) (conj! out level)))

            :else
            (let [kept (into [] (take-while #(<= (first %) raw)) path)]
              (if (= raw (first (peek kept)))
                (recur bs kept out)               ; outdent: a sibling of that ancestor
                ;; no ancestor at this raw level: the block takes the place of
                ;; the first deeper one
                (let [[left-raw left-level] (nth path (count kept))]
                  (recur (next bs) (conj kept [left-raw left-level]) (conj! out left-level))))))))
      (persistent! out))))

(defn- increasing-anchor-run
  "The longest subsequence of `anchors` ([old-pos new-pos] in new-pos order)
  whose old positions increase (patience sorting with back links)"
  [anchors]
  (let [old-pos (fn [i] (first (nth anchors i)))
        [tails prevs]
        (reduce
         (fn [[tails prevs] i]
           (let [x (old-pos i)
                 ;; first run length whose smallest tail is >= x
                 k (loop [lo 0 hi (count tails)]
                     (if (< lo hi)
                       (let [mid (quot (+ lo hi) 2)]
                         (if (< (old-pos (nth tails mid)) x)
                           (recur (inc mid) hi)
                           (recur lo mid)))
                       lo))]
             [(assoc tails k i)
              (conj prevs (if (pos? k) (nth tails (dec k)) -1))]))
         [[] []]
         (range (count anchors)))]
    (loop [i (if (seq tails) (peek tails) -1)
           run ()]
      (if (neg? i)
        (vec run)
        (recur (nth prevs i) (conj run (nth anchors i)))))))

(defn- match-gap
  "Rules 4 and 5 for one gap. `gap-old` and `gap-new` are positions into `olds`
  and `news`, in order. Returns [old-pos new-pos] pairs."
  [olds news gap-old gap-new]
  (let [by-key (group-by #(:title (nth olds %)) gap-old)
        [pairs _taken left-new]
        (reduce (fn [[pairs taken left-new] ni]
                  (let [k (:title (nth news ni))
                        n (get taken k 0)]
                    (if-let [oi (get (get by-key k) n)]
                      [(conj pairs [oi ni]) (assoc taken k (inc n)) left-new]
                      [pairs taken (conj left-new ni)])))
                [[] {} []]
                gap-new)
        paired-old (into #{} (map first) pairs)
        left-old (remove paired-old gap-old)]
    (into pairs
          (keep (fn [[oi ni]]
                  (when (= (:level (nth olds oi)) (:level (nth news ni)))
                    [oi ni])))
          (map vector left-old left-new))))

(defn- match-positions
  "[old-pos new-pos] pairs for pool vectors `olds` and `news` of {:title :level}"
  [olds news]
  (let [old-keys (mapv :title olds)
        new-keys (mapv :title news)]
    (if (= old-keys new-keys)
      (map vector (range (count olds)) (range (count news)))
      (let [old-freq (frequencies old-keys)
            new-freq (frequencies new-keys)
            unique-old-pos (into {}
                                 (keep-indexed (fn [i k]
                                                 (when (and (= 1 (old-freq k)) (= 1 (new-freq k)))
                                                   [k i])))
                                 old-keys)
            anchors (into []
                          (keep-indexed (fn [ni k]
                                          (when-let [oi (get unique-old-pos k)]
                                            [oi ni])))
                          new-keys)
            anchored-old (into #{} (map first) anchors)
            anchored-new (into #{} (map second) anchors)
            bounds (concat [[-1 -1]]
                           (increasing-anchor-run anchors)
                           [[(count olds) (count news)]])]
        (into anchors
              (mapcat (fn [[[oa na] [ob nb]]]
                        (match-gap olds news
                                   (remove anchored-old (range (inc oa) ob))
                                   (remove anchored-new (range (inc na) nb)))))
              (partition 2 1 bounds))))))

(defn reuse-block-uuids
  "Gives blocks parsed from a file's new content the uuids of the blocks they
  continue (see the ns doc for the rules). `old-blocks` are
  `page-blocks-for-identity` maps, `new-blocks` are gp-block/extract-blocks
  output in file order. Returns `new-blocks` as a vector with :block/uuid
  replaced where matched."
  [old-blocks new-blocks]
  (let [new-blocks (vec new-blocks)]
    (if (or (empty? old-blocks) (empty? new-blocks))
      new-blocks
      (let [claimed (into #{} (keep explicit-uuid) new-blocks)
            olds (filterv (fn [{:keys [uuid has-id?]}]
                            (not (or has-id? (contains? claimed uuid))))
                          old-blocks)
            levels (tree-levels new-blocks)
            news (into []
                       (keep-indexed (fn [i b]
                                       (when-not (or (macro? b) (explicit-uuid b))
                                         {:index i
                                          :title (:block/title b)
                                          :level (nth levels i)})))
                       new-blocks)]
        (reduce (fn [blocks [oi ni]]
                  (assoc-in blocks [(:index (nth news ni)) :block/uuid] (:uuid (nth olds oi))))
                new-blocks
                (when (and (seq olds) (seq news))
                  (match-positions olds news)))))))
