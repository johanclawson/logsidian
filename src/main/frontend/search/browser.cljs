(ns frontend.search.browser
  "Browser implementation of search protocol"
  (:require [frontend.search.protocol :as protocol]
            [frontend.state :as state]
            [promesa.core :as p]))

(defrecord Browser [repo]
  protocol/Engine
  (query [_this q option]
    (state/<invoke-db-worker :thread-api/search-blocks (state/get-current-repo) q option))
  (rebuild-pages-indice! [_this]
    ;; The db worker builds the Fuse page index itself, in slices, when a
    ;; search first needs it (frontend.worker.search-indexer/ensure-fuse!).
    (p/resolved nil))
  (rebuild-blocks-indice! [this]
    (protocol/rebuild-blocks-indice! this {:force? true}))
  (rebuild-blocks-indice! [_this opts]
    ;; The db worker walks the graph and writes blocks_fts itself, in slices
    ;; bounded by a time budget (frontend.worker.search-indexer): no block rows
    ;; cross to the UI thread. {:force? true} truncates and walks, and resolves
    ;; when the walk ends: {:state "complete" ...}, {:cancelled true} or
    ;; {:error msg}. {:force? false} resumes a pending walk and resolves with
    ;; the index status at once.
    (state/<invoke-db-worker :thread-api/search-rebuild-blocks-index (state/get-current-repo) opts))
  (transact-blocks! [_this {:keys [blocks-to-remove-set
                                   blocks-to-add]}]
    (let [repo (state/get-current-repo)]
      (p/let [_ (when (seq blocks-to-remove-set)
                  (state/<invoke-db-worker :thread-api/search-delete-blocks repo blocks-to-remove-set))]
        (when (seq blocks-to-add)
          (state/<invoke-db-worker :thread-api/search-upsert-blocks repo blocks-to-add)))))
  (truncate-blocks! [_this]
    (state/<invoke-db-worker :thread-api/search-truncate-tables (state/get-current-repo)))
  (remove-db! [_this]
    ;; Already removed in OPFS
    (p/resolved nil)))
