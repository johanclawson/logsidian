(ns frontend.context.i18n
  "This ns is a system component that handles translation for the entire
  application. The ns dependencies for this ns must be small since it is used
  throughout the application."
  (:require [clojure.string :as string]
            [frontend.dicts :as dicts]
            [medley.core :as medley]
            [tongue.core :as tongue]
            [frontend.state :as state]
            [lambdaisland.glogi :as log]))

(def dicts (merge dicts/dicts {:tongue/fallback :en}))

(defn- locale-tags
  "The dict keys tongue looks `locale` up under, most specific first:
  :zh-Hant-TW => (:zh-Hant-TW :zh-Hant :zh). Mirrors tongue.core/tags."
  [locale]
  (when locale
    (let [subtags (string/split (name locale) #"-")]
      (map #(keyword (string/join "-" (take % subtags)))
           (range (count subtags) 0 -1)))))

;; tongue/build-translate compiles (flattens and alias-resolves) every locale's
;; dictionary up front; doing that for all ~23 locales at namespace load cost
;; ~0.4 s of the boot long task. A tongue lookup for `locale` only reads the
;; dicts under the locale's own tags and under the :tongue/fallback locale, so
;; compile exactly those, on first use, and keep one translate fn per locale.
(def ^:private *locale->translate (atom {}))

(defn- locale-translate
  [locale]
  (or (get @*locale->translate locale)
      (let [f (tongue/build-translate
               (select-keys dicts (concat (locale-tags locale)
                                          (locale-tags (:tongue/fallback dicts))
                                          [:tongue/fallback])))]
        (swap! *locale->translate assoc locale f)
        f)))

(defn translate
  "Same contract as the fn returned by tongue/build-translate for `dicts`"
  ([locale key] ((locale-translate locale) locale key))
  ([locale key x] ((locale-translate locale) locale key x))
  ([locale key x & args] (apply (locale-translate locale) locale key x args)))

(defn t
  [& args]
  (let [preferred-language (keyword (state/sub :preferred-language))]
    (try
      (apply translate preferred-language args)
      (catch :default e
        (log/error :failed-translation {:arguments args
                                        :lang preferred-language})
        (state/pub-event! [:capture-error {:error e
                                           :payload {:type :failed-translation
                                                     :arguments args
                                                     :lang preferred-language}}])
        (apply translate :en args)))))

(defn tt
  [& keys]
  (some->
   (medley/find-first
    #(not (string/starts-with? (t %) "{Missing key"))
    keys)
   t))

(defn- fetch-local-language []
  (.. js/window -navigator -language))

;; TODO: Fetch preferred language from backend if user is logged in
(defn start []
  (let [preferred-language (state/sub :preferred-language)]
    (when (nil? preferred-language)
      (state/set-preferred-language! (fetch-local-language)))))
