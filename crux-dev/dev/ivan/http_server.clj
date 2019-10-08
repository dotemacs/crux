(ns ivan.http-server
  "Barbarous bootstrap for http server"
  (:require [crux.api :as api]
            [crux.http-server :as http-server]))


(def opts
  {:crux.bootstrap/node-topology :crux.standalone/topology
   :kv-backend    "crux.kv.rocksdb.RocksKv"
   :event-log-dir "data/eventlog-1"
   :db-dir        "data/db-dir-1"})

(def simple-node
  (api/start-node opts))

(def srv
  (http-server/start-http-server simple-node))

(api/submit-tx
  simple-node
  [[:crux.tx/put
    {:crux.db/id :ids/raptor}]
   [:crux.tx/put
    {:crux.db/id :ids/owl}]])

(api/history-range
  simple-node
  :ids/owl
  nil nil nil nil)

(api/document simple-node "686c3e1f00fb8ccabd43e93f5cd2da546d50d80d")

(api/q (api/db simple-node)
       '{:find [e]
         :where
         [[e :crux.db/id _]]})

(api/documents
  simple-node
  #{"686c3e1f00fb8ccabd43e93f5cd2da546d50d80d"
    "773d1c878c512d5d50bb1e74e46d4e5e315046de"})
