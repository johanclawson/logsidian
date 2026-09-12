(ns frontend.common.async-util
  "Some cljs.core.async relate macros and fns, used in worker and frontend
   namespaces. See also: https://gist.github.com/vvvvalvalval/f1250cec76d3719a8343"
  #?(:cljs (:require [cljs.core.async.impl.channels :refer [ManyToManyChannel]]
                     [clojure.core.async :as async]
                     [logseq.common.util :as common-util]
                     [promesa.core :as p])))

#?(:cljs
   (defn throw-err
     [v]
     (if (instance? ExceptionInfo v) (throw v) v)))

(defmacro <?
  [port]
  `(throw-err (cljs.core.async/<! ~port)))

#?(:cljs
   (defn c->p
     "Converts a Core.async channel to a Promise"
     [chan]
     (let [d (p/deferred)]
       (if chan
         (async/go
           (let [result (async/<! chan)]
             (if (instance? ExceptionInfo result)
               (p/reject! d result)
               (p/resolve! d result))))
         (p/resolve! d nil))
       d)))

#?(:cljs
   (defn <map-bounded
     "Like `(p/all (map f xs))`, but with at most `n` calls of `f` in flight: the
      next item starts only when an earlier call has settled. Resolves to a
      vector of the results in the order of `xs`.

      A call that throws or rejects does not stop the others. Its slot holds
      the error, so a caller that must tell failures apart catches inside `f`.

      Why: p/all over a large seq starts every call at once. The reopen
      reconcile did that for every graph file, which queued one worker pull per
      file at once (ADR-003)."
     [n f xs]
     (let [items (vec xs)
           total (count items)
           n (max 1 n)
           *results (volatile! (vec (repeat total nil)))
           *next (volatile! 0)
           *active (volatile! 0)]
       (p/create
        (fn [done _reject]
          (letfn [(start-more! []
                    (while (and (< @*next total) (< @*active n))
                      (let [i @*next]
                        (vswap! *next inc)
                        (vswap! *active inc)
                        ;; A synchronous throw of f becomes a rejection here,
                        ;; explicitly: relying on p/do for that left the throw
                        ;; unhandled (seen with nbb's promesa), the bookkeeping
                        ;; below never ran and the whole call never settled.
                        ;; The error is stored inside the catch handler, which
                        ;; returns nil, for the same reason: an error returned
                        ;; from a handler is coerced back into a rejection.
                        (-> (try (p/promise (f (nth items i)))
                                 (catch :default e (p/rejected e)))
                            (p/then (fn [v] (vswap! *results assoc i v) nil))
                            (p/catch (fn [e] (vswap! *results assoc i e) nil))
                            (p/then (fn [_]
                                      (vswap! *active dec)
                                      (if (and (= @*next total) (zero? @*active))
                                        (done @*results)
                                        (start-more!))))))))]
            (if (zero? total)
              (done [])
              (start-more!))))))))

#?(:cljs
   (defn drain-chan
     "drop all stuffs in CH, and return all of them"
     [ch]
     (->> (repeatedly #(async/poll! ch))
          (take-while identity))))

#?(:cljs
   (defn <ratelimit
     "return a channel CH,
  ratelimit flush items in in-ch every max-duration(ms),
  opts:
  - :filter-fn filter item before putting items into returned CH, (filter-fn item)
               will poll it when its return value is channel,
  - :flush-fn exec flush-fn when time to flush, (flush-fn item-coll)
  - :stop-ch stop go-loop when stop-ch closed
  - :distinct-key-fn distinct coll when put into CH
  - :chan-buffer buffer of return CH, default use (async/chan 1000)
  - :flush-now-ch flush the content in the queue immediately
  - :refresh-timeout-ch refresh (timeout max-duration)"
     [in-ch max-duration & {:keys [filter-fn flush-fn stop-ch distinct-key-fn chan-buffer flush-now-ch refresh-timeout-ch]}]
     (let [ch (if chan-buffer (async/chan chan-buffer) (async/chan 1000))
           stop-ch* (or stop-ch (async/chan))
           flush-now-ch* (or flush-now-ch (async/chan))
           refresh-timeout-ch* (or refresh-timeout-ch (async/chan))]
       (async/go-loop [timeout-ch (async/timeout max-duration) coll []]
         (let [{:keys [refresh-timeout timeout e stop flush-now]}
               (async/alt! refresh-timeout-ch* {:refresh-timeout true}
                           timeout-ch {:timeout true}
                           in-ch ([e] {:e e})
                           stop-ch* {:stop true}
                           flush-now-ch* {:flush-now true})]
           (cond
             refresh-timeout
             (recur (async/timeout max-duration) coll)

             (or flush-now timeout)
             (do (async/onto-chan! ch coll false)
                 (flush-fn coll)
                 (drain-chan flush-now-ch*)
                 (recur (async/timeout max-duration) []))

             (some? e)
             (let [filter-v (filter-fn e)
                   filter-v* (if (instance? ManyToManyChannel filter-v)
                               (async/<! filter-v)
                               filter-v)]
               (if filter-v*
                 (recur timeout-ch (cond->> (conj coll e)
                                     distinct-key-fn (common-util/distinct-by distinct-key-fn)
                                     true vec))
                 (recur timeout-ch coll)))

             (or stop
                 ;; got nil from in-ch, means in-ch is closed
                 ;; so we stop the whole go-loop
                 (nil? e))
             (async/close! ch))))
       ch)))
