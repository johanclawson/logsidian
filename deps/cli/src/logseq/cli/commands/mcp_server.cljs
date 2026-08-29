(ns logseq.cli.commands.mcp-server
  "Command to run a MCP server"
  (:require ["@modelcontextprotocol/sdk/server/stdio.js" :refer [StdioServerTransport]]
            ["fastify$default" :as Fastify]
            ["fs" :as fs]
            [logseq.cli.common.mcp.server :as cli-common-mcp-server]
            [logseq.cli.common.mcp.tools :as cli-common-mcp-tools]
            [logseq.cli.file-mcp.tools :as file-mcp-tools]
            [logseq.cli.util :as cli-util]
            [logseq.db.common.sqlite-cli :as sqlite-cli]
            [logseq.graph-parser.cli :as gp-cli]
            [nbb.core :as nbb]
            [promesa.core :as p]))

(defn- local-get-page [conn args]
  (if-let [resp (cli-common-mcp-tools/get-page-data @conn (aget args "pageName"))]
    (cli-common-mcp-server/mcp-success-response resp)
    (cli-common-mcp-server/mcp-error-response (str "Error: Page " (pr-str (aget args "pageName")) " not found"))))

(defn- local-list-pages [conn args]
  (cli-common-mcp-server/mcp-success-response
   (cli-common-mcp-tools/list-pages @conn {:expand (aget args "expand")})))

(defn- local-list-properties [conn args]
  (cli-common-mcp-server/mcp-success-response
   (cli-common-mcp-tools/list-properties @conn {:expand (aget args "expand")})))

(defn- local-list-tags [conn args]
  (cli-common-mcp-server/mcp-success-response
   (cli-common-mcp-tools/list-tags @conn {:expand (aget args "expand")})))

(defn- local-upsert-nodes [conn args]
  (cli-common-mcp-server/mcp-success-response
   (cli-common-mcp-tools/upsert-nodes
    conn
    ;; string is used by a -t invocation
    (-> (if (string? (.-operations args)) (js/JSON.parse (.-operations args)) (.-operations args))
        (js->clj :keywordize-keys true))
    {:dry-run (.-dry-run args)})))

(def ^:private local-tools
  "MCP Tools when running with a local DB graph"
  (let [tools {:getPage {:fn local-get-page}
               :listPages {:fn local-list-pages}
               :listProperties {:fn local-list-properties}
               :listTags {:fn local-list-tags}
               :upsertNodes {:fn local-upsert-nodes}}]
    (merge-with
     merge
     (select-keys cli-common-mcp-server/api-tools (keys tools))
     tools)))

;; File graph tool wrappers
;; ========================

(defn- file-get-page [conn args]
  (if-let [resp (file-mcp-tools/get-page-data @conn (aget args "pageName"))]
    (cli-common-mcp-server/mcp-success-response resp)
    (cli-common-mcp-server/mcp-error-response (str "Error: Page " (pr-str (aget args "pageName")) " not found"))))

(defn- file-list-pages [conn args]
  (cli-common-mcp-server/mcp-success-response
   (file-mcp-tools/list-pages @conn {:expand (aget args "expand")})))

(defn- file-list-properties [conn args]
  (cli-common-mcp-server/mcp-success-response
   (file-mcp-tools/list-properties @conn {:expand (aget args "expand")})))

(defn- file-list-tags [conn args]
  (cli-common-mcp-server/mcp-success-response
   (file-mcp-tools/list-tags @conn {:expand (aget args "expand")})))

(defn- file-search-blocks [conn args]
  (cli-common-mcp-server/mcp-success-response
   (file-mcp-tools/search-blocks @conn (aget args "searchTerm")
                                 {:limit (or (aget args "limit") 100)})))

(defn- make-file-upsert-nodes
  "Creates an upsertNodes handler that captures the graph-dir"
  [graph-dir]
  (fn [conn args]
    (cli-common-mcp-server/mcp-success-response
      (let [operations (-> (if (string? (.-operations args))
                             (js/JSON.parse (.-operations args))
                             (.-operations args))
                           (js->clj :keywordize-keys true))
            result (file-mcp-tools/mcp-upsert-nodes conn graph-dir
                                                     #js {"operations" (clj->js operations)
                                                          "dry-run" (.-dry-run args)})]
        ;; mcp-upsert-nodes already wraps in mcp format, extract the data
        (-> (.-content result)
            (aget 0)
            (.-text)
            js/JSON.parse)))))

(defn- make-file-graph-tools
  "Creates MCP Tools for a file-based graph with upsertNodes support"
  [graph-dir]
  (let [tools {:getPage {:fn file-get-page}
               :listPages {:fn file-list-pages}
               :listProperties {:fn file-list-properties}
               :listTags {:fn file-list-tags}
               :searchBlocks {:fn file-search-blocks}
               :upsertNodes {:fn (make-file-upsert-nodes graph-dir)}}]
    (merge-with
     merge
     (select-keys cli-common-mcp-server/api-tools (keys tools))
     tools)))

(defn- create-http-server
  [mcp-server opts]
  (let [app (Fastify. #js {:requestTimeout (* 1000 30)})]
    (.post app "/mcp" #(cli-common-mcp-server/handle-post-request mcp-server opts %1 %2))
    (.get app "/mcp" cli-common-mcp-server/handle-get-request)
    (.delete app "/mcp" cli-common-mcp-server/handle-delete-request)
    app))

(defn- start-http-server [mcp-server {:keys [port host] :as opts}]
  (let [app (create-http-server mcp-server opts)]
    (.listen app (clj->js (select-keys opts [:port :host]))
             (fn [error]
               (if error
                 (do (js/console.error "Failed to start server:" error)
                     (js/process.exit 1))
                 (js/console.log
                  (str "MCP Streamable HTTP Server started on " host ":" port)))))))

(defn- call-api
  "Calls API from CLI for use w/ cli-common-mcp-server/api-tool"
  [api-server-token api-method method-args]
  (p/let [resp (cli-util/api-fetch api-server-token api-method method-args)]
    (if (= 200 (.-status resp))
      (.json resp)
      (p/let [body (.text resp)]
        #js {:error (str "Server status " (.-status resp)
                         "\nAPI Response: " (pr-str body))}))))

(defn- open-graph
  "Opens a graph and returns [conn is-file-graph? graph-path]"
  [graph]
  (let [path (cli-util/get-graph-path graph)]
    (if (cli-util/file-graph? path)
      ;; File graph - parse markdown files into DataScript
      [(:conn (gp-cli/parse-graph path {:verbose false})) true path]
      ;; DB graph - open SQLite database
      [(apply sqlite-cli/open-db! (cli-util/->open-db-args graph)) false path])))

(defn- create-mcp-server [{{:keys [api-server-token] :as opts} :opts} graph]
  (if (cli-util/api-command? opts)
    ;; Make an initial /api call to ensure the API server is on
    (-> (p/let [_resp (call-api api-server-token "logseq.app.search" ["foo"])]
          (cli-common-mcp-server/create-mcp-api-server (partial call-api api-server-token)))
        (p/catch cli-util/command-catch-handler))
    (let [mcp-server (cli-common-mcp-server/create-mcp-server)
          [conn is-file-graph? graph-path] (open-graph graph)
          tools (if is-file-graph?
                  (make-file-graph-tools graph-path)
                  local-tools)]
      (doseq [[k v] tools]
        (.registerTool mcp-server (name k) (:config v) (partial (:fn v) conn)))
      mcp-server)))

(defn start [{{:keys [debug-tool graph stdio api-server-token] :as opts} :opts :as m}]
  (when (and graph (not (fs/existsSync (cli-util/get-graph-path graph))))
    (cli-util/error "Graph" (pr-str graph) "does not exist"))
  (if debug-tool
    (if graph
      (let [[conn is-file-graph? graph-path] (open-graph graph)
            tools (if is-file-graph?
                    (make-file-graph-tools graph-path)
                    local-tools)]
        (if-let [tool-m (get tools debug-tool)]
          (p/let [resp ((:fn tool-m) conn (clj->js (dissoc opts :debug-tool)))]
            (js/console.log (clj->js resp)))
          (cli-util/error "Tool" (pr-str debug-tool) "not found. Available tools:"
                          (pr-str (keys tools)))))
      (if-let [tool-m (get cli-common-mcp-server/api-tools debug-tool)]
        (p/let [resp (cli-common-mcp-server/call-api-tool (:fn tool-m)
                                                          (partial call-api api-server-token)
                                                          (clj->js (dissoc opts :debug-tool)))]
          (js/console.log resp))
        (cli-util/error "Tool" (pr-str debug-tool) "not found")))
    (p/let [mcp-server (create-mcp-server m graph)]
      (if stdio
        (nbb/await (.connect mcp-server (StdioServerTransport.)))
        (start-http-server mcp-server (select-keys opts [:port :host]))))))