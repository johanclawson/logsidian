(ns frontend.handler.command-palette
  "System-component-like ns for command palette's functionality"
  (:require [cljs.spec.alpha :as s]
            [frontend.handler.plugin :as plugin-handler]
            [frontend.modules.shortcut.data-helper :as shortcut-helper]
            [frontend.spec :as spec]
            [frontend.state :as state]
            [frontend.storage :as storage]
            [lambdaisland.glogi :as log]))

(s/def :command/id keyword?)
(s/def :command/desc string?)
(s/def :command/action fn?)
(s/def :command/shortcut (s/or :nil nil? :keybinding string?))
(s/def :command/tag vector?)

(s/def :command/command
  (s/keys :req-un [:command/id :command/action]
          ;; :command/desc is optional for internal commands since view
          ;; checks translation ns first
          :opt-un [:command/desc :command/shortcut :command/tag :command/handler-id]))

(defn global-shortcut-commands []
  ;; Build the full shortcut map and read the (user-overridden) bindings once
  ;; for all handlers instead of once per shortcut id.
  (let [full-map (shortcut-helper/shortcuts-map-full)
        bindings (shortcut-helper/get-bindings)]
    (->> [:shortcut.handler/editor-global
          :shortcut.handler/global-prevent-default
          :shortcut.handler/global-non-editing-only]
         (mapcat #(shortcut-helper/shortcuts->commands % full-map bindings)))))

(defn get-commands []
  (->> @(get @state/state :command-palette/commands)
       (sort-by :id)))

(defn get-commands-unique []
  (reduce #(assoc %1 (:id %2) %2) {}
          @(get @state/state :command-palette/commands)))

(defn history
  ([] (or (try (storage/get "commands-history")
               (catch js/Error e
                 (log/error :commands-history e)))
          []))
  ([vals] (storage/set "commands-history" vals)))

(defn- assoc-invokes [cmds]
  (let [invokes (->> (history)
                     (map :id)
                     (frequencies))]
    (mapv (fn [{:keys [id] :as cmd}]
            (if (contains? invokes id)
              (assoc cmd :invokes-count (get invokes id))
              cmd))
          cmds)))

(defn add-history [{:keys [id]}]
  (storage/set "commands-history" (conj (history) {:id id :timestamp (.getTime (js/Date.))})))

(defn invoke-command [{:keys [id action] :as cmd}]
  (add-history cmd)
  (plugin-handler/hook-lifecycle-fn! id action))

(defn top-commands [limit]
  (->> (get-commands)
       (assoc-invokes)
       (sort-by :invokes-count)
       (reverse)
       (take limit)))

(defn register-commands!
  "Registers `commands`, in order, with a single state update.

  Same result as calling `register` on each command in turn: a command with
  :command/shortcut, or whose id is already registered (before or earlier in
  `commands`), is logged and skipped. Registering one command used to store
  `(conj (get-commands) command)`, i.e. the command consed onto the registry
  sorted by :id, so after a run the registry is the last accepted command
  consed onto all the other commands sorted by :id. That is what's stored here,
  without re-sorting the registry for every command."
  [commands]
  (let [existing @(get @state/state :command-palette/commands)
        accepted (first
                  (reduce
                   (fn [[accepted ids :as acc] {:keys [id] :as command}]
                     (if (:command/shortcut command)
                       (do (log/error :shortcut/missing (str "Shortcut is missing for " id))
                           acc)
                       (try
                         (spec/validate :command/command command)
                         (if (contains? ids id)
                           (do (log/error :command/register {:msg "Failed to register command. Command with same id already exist"
                                                             :id  id})
                               acc)
                           [(conj accepted command) (conj ids id)])
                         ;; Catch unexpected errors so that subsequent commands still register
                         (catch :default e
                           (log/error :command/register {:msg "Unexpectedly failed to register command"
                                                         :id id
                                                         :error (str e)})
                           acc))))
                   [[] (set (map :id existing))]
                   commands))]
    (when (seq accepted)
      (try
        (state/set-state! :command-palette/commands
                          (conj (sort-by :id (concat existing (pop accepted)))
                                (peek accepted)))
        (catch :default e
          (doseq [{:keys [id]} accepted]
            (log/error :command/register {:msg "Unexpectedly failed to register command"
                                          :id id
                                          :error (str e)})))))))

(defn register
  "Register a global command searchable by command palette.
  `id` is defined as a global unique namespaced key :scope/command-name
  `action` must be a zero arity function

  Example:
  ```clojure
  (register
   {:id :document/open-logseq-doc
    :desc \"Document: open Logseq documents\"
    :action (fn [] (js/window.open \"https://docs.logseq.com/\"))})
  ```

  To add i18n support, prefix `id` with command and put that item in dict.
  Example: {:zh-CN {:command.document/open-logseq-doc \"打开文档\"}}"
  [command]
  (register-commands! [command]))

(defn unregister
  [id]
  (let [id (keyword id)
        cmds (get-commands-unique)]
    (when (contains? cmds id)
      (state/set-state! :command-palette/commands (vals (dissoc cmds id)))
      ;; clear history
      (history (filter #(not= (:id %) id) (history))))))

(defn register-global-shortcut-commands []
  (register-commands! (global-shortcut-commands)))

(comment
  ;; register custom command example
  (register
   {:id :document/open-logseq-doc
    :desc "Document: open Logseq documents"
    :action (fn [] (js/window.open "https://docs.logseq.com/"))}))
