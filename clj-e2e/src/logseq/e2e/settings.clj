(ns logseq.e2e.settings
  (:require [logseq.e2e.assert :as assert]
            [logseq.e2e.keyboard :as k]
            [wally.main :as w]))

(defn developer-mode
  []
  ;; Click the "More" button in the header
  (w/click "button[title='More']")
  ;; Click Settings menu item
  (w/click "[role='menuitem']:has-text('Settings')")
  ;; Click Advanced tab in settings sidebar
  (w/click "[role='listitem']:has-text('Advanced')")
  ;; Enable developer mode if not already enabled
  (let [dev-mode-checkbox (w/-query "[role='checkbox']:near(:text('Developer mode'))")]
    (when-not (.isChecked dev-mode-checkbox)
      (w/click dev-mode-checkbox)))
  (k/esc)
  (assert/assert-in-normal-mode?))
