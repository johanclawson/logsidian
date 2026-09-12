(ns frontend.handler.command-palette-test
  (:require [cljs.test :refer [deftest is testing]]
            [clojure.set :refer [rename-keys]]
            [frontend.handler.command-palette :as cp]
            [frontend.modules.shortcut.config :as shortcut-config]
            [frontend.modules.shortcut.data-helper :as dh]
            [frontend.state :as state]))

(def ^:private global-handler-ids
  [:shortcut.handler/editor-global
   :shortcut.handler/global-prevent-default
   :shortcut.handler/global-non-editing-only])

(defn- old-shortcuts->commands
  "data-helper/shortcuts->commands before it shared one shortcut map: it
  rebuilt the full shortcut map and re-read the bindings for every id."
  [handler-id]
  (->> (get @shortcut-config/*config handler-id)
       (map (fn [[id _]]
              (-> (assoc (-> (dh/shortcuts-map-full) id)
                         :binding (dh/binding-for-display id (dh/shortcut-binding id)))
                  (assoc :id id :handler-id handler-id)
                  (rename-keys {:binding :shortcut
                                :fn      :action}))))))

(defn- old-register!
  "The state transition of command-palette/register before commands were
  registered in one batch (its logging and dev-only spec validation elided)."
  [command]
  (when-not (:command/shortcut command)
    (let [cmds (cp/get-commands)]
      (when-not (some #(= (:id %) (:id command)) cmds)
        (state/set-state! :command-palette/commands (conj cmds command))))))

(defn- registry-after
  "Sets the command registry to `initial`, calls `f` and returns the registry,
  restoring the registry's previous value."
  [initial f]
  (let [*commands (get @state/state :command-palette/commands)
        saved @*commands]
    (try
      (reset! *commands initial)
      (f)
      @*commands
      (finally
        (reset! *commands saved)))))

(deftest shortcuts->commands-matches-per-id-build
  (testing "each handler's commands are the ones the per-id build produced"
    (doseq [handler-id global-handler-ids]
      (is (seq (dh/shortcuts->commands handler-id)) (str handler-id))
      (is (= (old-shortcuts->commands handler-id)
             (dh/shortcuts->commands handler-id))
          (str handler-id))))
  (testing "global-shortcut-commands shares one build across the three handlers"
    (is (= (mapcat old-shortcuts->commands global-handler-ids)
           (cp/global-shortcut-commands)))))

(deftest register-global-shortcut-commands-matches-per-command-loop
  (let [cmds (cp/global-shortcut-commands)
        old (registry-after [] #(doseq [cmd cmds] (old-register! cmd)))
        new (registry-after [] cp/register-global-shortcut-commands)]
    (is (> (count old) 50))
    (is (= (map :id old) (map :id new)) "same ids in the same order")
    (is (= old new) "same stored commands")
    (is (apply distinct? (map :id new)) "no duplicate ids")))

(deftest register-commands!-matches-register-loop
  (let [action (fn [])
        existing [{:id :zeta/existing :action action}
                  {:id :alpha/existing :action action}]
        check (fn [label initial batch]
                (let [old (registry-after initial #(doseq [cmd batch] (old-register! cmd)))
                      new (registry-after initial #(cp/register-commands! batch))]
                  (is (= (map :id old) (map :id new)) label)
                  (is (= old new) label)
                  new))]
    (testing "dedupe against existing and earlier commands, skip :command/shortcut, keep order"
      (let [new (check "mixed batch"
                       existing
                       [{:id :mid/one :action action}
                        {:id :alpha/existing :action action :desc "dup of existing"}
                        {:id :beta/two :action action}
                        {:id :mid/one :action action :desc "dup within batch"}
                        {:id :gamma/bad :action action :command/shortcut "mod+x"}
                        {:id :aaa/last :action action}])]
        ;; the last accepted command consed onto the rest sorted by :id
        (is (= [:aaa/last :alpha/existing :beta/two :mid/one :zeta/existing]
               (map :id new)))
        (is (every? (comp nil? :desc) new) "the first command with an id wins")))
    (testing "batch ending with a rejected command"
      (check "trailing dup" existing [{:id :beta/two :action action}
                                      {:id :zeta/existing :action action}]))
    (testing "nothing accepted leaves the registry untouched"
      (is (identical? existing
                      (registry-after existing
                                      #(cp/register-commands! [{:id :zeta/existing :action action}])))))
    (testing "register of a single command, empty and non-empty registry"
      (let [cmd {:id :document/quick-tour :action action}]
        (is (= (registry-after [] #(old-register! cmd))
               (registry-after [] #(cp/register cmd))))
        (is (= (registry-after existing #(old-register! cmd))
               (registry-after existing #(cp/register cmd))))))))
