(ns ^:no-doc frontend.search.protocol)

(defprotocol Engine
  (query [this q option])
  ;; opts: {:force? true} rebuilds (the 1-arity default), {:force? false} only
  ;; ensures the index is complete or being built
  (rebuild-blocks-indice! [this] [this opts]) ;; TODO: rename to rebuild-indice!
  (rebuild-pages-indice! [this]) ;; TODO: rename to rebuild-indice!
  (transact-blocks! [this data])
  (truncate-blocks! [this]) ;; TODO: rename to truncate-indice!
  (remove-db! [this]))
