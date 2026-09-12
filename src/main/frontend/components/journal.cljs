(ns frontend.components.journal
  (:require [frontend.components.page :as page]
            [frontend.components.views :as views]
            [frontend.db-mixins :as db-mixins]
            [frontend.db.react :as react]
            [frontend.state :as state]
            [frontend.ui :as ui]
            [frontend.util :as util]
            [promesa.core :as p]
            [rum.core :as rum]))

(def ^:private journals-page-size
  "Journals added per endReached step."
  10)

(rum/defc journal-cp < rum/static
  [id last?]
  [:div.journal-item.content
   (when last?
     {:class "journal-last-item"})
   (page/page-cp {:db/id id
                  :journals? true})])

(defn- <load-journals
  "Newest `limit` journal ids plus :more?. The worker caps each request, so keep
  requesting from the returned cursor until `limit` ids are loaded."
  [limit]
  (p/loop [ids []
           after nil]
    (p/let [{:keys [data more? cursor]} (views/<load-view-data nil (cond-> {:journals? true
                                                                          :limit (- limit (count ids))}
                                                                   after
                                                                   (assoc :after after)))
            ids' (into ids (remove nil?) data)]
      (if (and more? cursor (< (count ids') limit))
        (p/recur ids' cursor)
        {:ids (vec (distinct ids'))
         :more? (boolean more?)}))))

(defn- sub-journals
  [*limit *loading?]
  (when-let [repo (state/get-current-repo)]
    (some-> (react/q repo
                     [:frontend.worker.react/journals]
                     {:query-fn (fn [_]
                                  ;; Read the limit when the query runs: refresh-affected-queries!
                                  ;; re-runs this cached fn after end-reached grows it.
                                  (-> (<load-journals @*limit)
                                      (p/finally (fn [] (reset! *loading? false)))))}
                     nil)
            util/react)))

(rum/defcs all-journals < rum/reactive db-mixins/query
  (rum/local journals-page-size ::limit)
  (rum/local false ::loading?)
  [state]
  (let [*limit (::limit state)
        *loading? (::loading? state)
        {:keys [ids more?]} (sub-journals *limit *loading?)]
    (when (seq ids)
      [:div#journals
       (ui/virtualized-list
        {:custom-scroll-parent (util/app-scroll-container-node)
         :increase-viewport-by {:top 100 :bottom 100}
         :compute-item-key (fn [idx]
                             (let [id (util/nth-safe ids idx)]
                               (str "journal-" id)))
         :total-count (count ids)
         :end-reached (fn [_idx]
                        ;; Grow while older journals exist and no fetch is in flight;
                        ;; the query-fn clears *loading? when its response lands.
                        (when (and more? (not @*loading?))
                          (reset! *loading? true)
                          (swap! *limit + journals-page-size)
                          (react/refresh-affected-queries! (state/get-current-repo)
                                                           [[:frontend.worker.react/journals]]
                                                           :skip-kv-custom-keys? true)))
         :item-content (fn [idx]
                         (let [id (util/nth-safe ids idx)
                               last? (and (not more?) (= (inc idx) (count ids)))]
                           (journal-cp id last?)))})])))
