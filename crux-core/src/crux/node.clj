(ns crux.node
  (:require [clojure.java.io :as io]
            [clojure.spec.alpha :as s]
            [clojure.tools.logging :as log]
            [com.stuartsierra.dependency :as dep]
            [crux.api :as api]
            [crux.backup :as backup]
            [crux.codec :as c]
            [crux.config :as cc]
            [crux.db :as db]
            [crux.index :as idx]
            [crux.io :as cio]
            [crux.kv :as kv]
            crux.object-store
            [crux.query :as q]
            [crux.status :as status]
            [crux.tx :as tx]
            [crux.bus :as bus])
  (:import [crux.api ICruxAPI ICruxAsyncIngestAPI NodeOutOfSyncException]
           java.io.Closeable
           java.util.Date
           [java.util.concurrent Executors]
           java.util.concurrent.locks.StampedLock))

(s/check-asserts (if-let [check-asserts (System/getProperty "clojure.spec.compile-asserts")]
                   (Boolean/parseBoolean check-asserts)
                   true))

(defrecord CruxVersion [version revision]
  status/Status
  (status-map [this]
    {:crux.version/version version
     :crux.version/revision revision}))

(def crux-version
  (memoize
   (fn []
     (when-let [pom-file (io/resource "META-INF/maven/juxt/crux-core/pom.properties")]
       (with-open [in (io/reader pom-file)]
         (let [{:strs [version
                       revision]} (cio/load-properties in)]
           (->CruxVersion version revision)))))))

(defn- ensure-node-open [{:keys [closed?]}]
  (when @closed?
    (throw (IllegalStateException. "Crux node is closed"))))

(defrecord CruxNode [kv-store tx-log remote-document-store indexer object-store bus
                     options close-fn status-fn closed? ^StampedLock lock]
  ICruxAPI
  (db [this]
    (.db this nil nil))

  (db [this valid-time]
    (.db this valid-time nil))

  (db [this valid-time tx-time]
    (cio/with-read-lock lock
      (ensure-node-open this)
      (let [latest-tx-time (:crux.tx/tx-time (db/read-index-meta indexer :crux.tx/latest-completed-tx))
            _ (when (and tx-time (or (nil? latest-tx-time) (pos? (compare tx-time latest-tx-time))))
                (throw (NodeOutOfSyncException. (format "node hasn't indexed the requested transaction: requested: %s, available: %s"
                                                        tx-time latest-tx-time)
                                                tx-time latest-tx-time)))
            tx-time (or tx-time latest-tx-time)
            valid-time (or valid-time (Date.))]

        (q/db kv-store object-store valid-time tx-time))))

  (document [this content-hash]
    (cio/with-read-lock lock
      (ensure-node-open this)
      (with-open [snapshot (kv/new-snapshot kv-store)]
        (db/get-single-object object-store snapshot (c/new-id content-hash)))))

  (documents [this content-hash-set]
    (cio/with-read-lock lock
      (ensure-node-open this)
      (with-open [snapshot (kv/new-snapshot kv-store)]
        (db/get-objects object-store snapshot (map c/new-id content-hash-set)))))

  (history [this eid]
    (cio/with-read-lock lock
      (ensure-node-open this)
      (with-open [snapshot (kv/new-snapshot kv-store)]
        (mapv c/entity-tx->edn (idx/entity-history snapshot eid)))))

  (historyRange [this eid valid-time-start transaction-time-start valid-time-end transaction-time-end]
    (cio/with-read-lock lock
      (ensure-node-open this)
      (with-open [snapshot (kv/new-snapshot kv-store)]
        (->> (idx/entity-history-range snapshot eid valid-time-start transaction-time-start valid-time-end transaction-time-end)
             (mapv c/entity-tx->edn)
             (sort-by (juxt :crux.db/valid-time :crux.tx/tx-time))))))

  (status [this]
    (cio/with-read-lock lock
      (ensure-node-open this)
      (status-fn)))

  (attributeStats [this]
    (cio/with-read-lock lock
      (ensure-node-open this)
      (idx/read-meta kv-store :crux.kv/stats)))

  (submitTx [this tx-ops]
    (cio/with-read-lock lock
      (ensure-node-open this)
      (db/submit-docs remote-document-store (tx/tx-ops->id-and-docs tx-ops))
      @(db/submit-tx tx-log tx-ops)))

  (hasTxCommitted [this {:keys [crux.tx/tx-id
                                crux.tx/tx-time] :as submitted-tx}]
    (cio/with-read-lock lock
      (ensure-node-open this)
      (let [{latest-tx-id :crux.tx/tx-id
             latest-tx-time :crux.tx/tx-time} (db/read-index-meta indexer :crux.tx/latest-completed-tx)]
        (if (and tx-id (or (nil? latest-tx-id) (pos? (compare tx-id latest-tx-id))))
          (throw
           (NodeOutOfSyncException.
            (format "Node hasn't indexed the transaction: requested: %s, available: %s" tx-time latest-tx-time)
            tx-time latest-tx-time))
          (nil?
           (kv/get-value (kv/new-snapshot kv-store)
                         (c/encode-failed-tx-id-key-to nil tx-id)))))))

  (newTxLogContext [this]
    (cio/with-read-lock lock
      (ensure-node-open this)
      (db/new-tx-log-context tx-log)))

  (txLog [this tx-log-context from-tx-id with-ops?]
    (cio/with-read-lock lock
      (ensure-node-open this)
      (for [{:keys [crux.tx/tx-id
                    crux.tx.event/tx-events] :as tx-log-entry} (db/tx-log tx-log tx-log-context from-tx-id)
            :when (with-open [snapshot (kv/new-snapshot kv-store)]
                    (nil? (kv/get-value snapshot (c/encode-failed-tx-id-key-to nil tx-id))))]
        (if with-ops?
          (-> tx-log-entry
              (dissoc :crux.tx.event/tx-events)
              (assoc :crux.api/tx-ops
                     (with-open [snapshot (kv/new-snapshot kv-store)]
                       (->> tx-events
                            (mapv #(tx/tx-event->tx-op % snapshot object-store))))))
          tx-log-entry))))

  (sync [this timeout]
    (when-let [tx (db/latest-submitted-tx (:tx-log this))]
      (-> (api/await-tx this tx nil)
          :crux.tx/tx-time)))

  (awaitTxTime [this tx-time timeout]
    (cio/with-read-lock lock
      (ensure-node-open this)
      (-> (tx/await-tx-time indexer tx-time (or (and timeout (.toMillis timeout))
                                                (:crux.tx-log/await-tx-timeout options)))
          :crux.tx/tx-time)))

  (awaitTx [this submitted-tx timeout]
    (cio/with-read-lock lock
      (ensure-node-open this)
      (tx/await-tx indexer submitted-tx (or (and timeout (.toMillis timeout))
                                            (:crux.tx-log/await-tx-timeout options)))))

  ICruxAsyncIngestAPI
  (submitTxAsync [this tx-ops]
    (cio/with-read-lock lock
      (ensure-node-open this)
      (db/submit-docs remote-document-store (tx/tx-ops->id-and-docs tx-ops))
      (db/submit-tx tx-log tx-ops)))

  backup/INodeBackup
  (write-checkpoint [this {:keys [crux.backup/checkpoint-directory] :as opts}]
    (cio/with-read-lock lock
      (ensure-node-open this)
      (kv/backup kv-store (io/file checkpoint-directory "kv-store"))

      (when (satisfies? tx-log backup/INodeBackup)
        (backup/write-checkpoint tx-log opts))))

  Closeable
  (close [_]
    (cio/with-write-lock lock
      (when (and (not @closed?) close-fn) (close-fn))
      (reset! closed? true))))

(s/def ::topology-id
  (fn [id]
    (and (or (string? id) (keyword? id) (symbol? id))
         (namespace (symbol id)))))

(s/def ::start-fn ifn?)
(s/def ::deps (s/coll-of keyword?))
(s/def ::args (s/map-of keyword?
                        (s/keys :req [:crux.config/type]
                                :req-un [:crux.config/doc]
                                :opt-un [:crux.config/default
                                         :crux.config/required?])))

(defn- resolve-topology-id [id]
  (s/assert ::topology-id id)
  (-> id symbol requiring-resolve var-get))

(s/def ::module (s/and (s/and (s/or :module-id ::topology-id :module map?)
                              (s/conformer
                               (fn [[m-or-id s]]
                                 (if (= :module-id m-or-id)
                                   (resolve-topology-id s) s))))
                       (s/keys :req-un [::start-fn]
                               :opt-un [::deps ::args])))

(defn- start-order [system]
  (let [g (reduce-kv (fn [g k m]
                       (let [m (s/conform ::module m)]
                         (reduce (fn [g d] (dep/depend g k d)) g (:deps m))))
                     (dep/graph)
                     system)
        dep-order (dep/topo-sort g)
        dep-order (->> (keys system)
                       (remove #(contains? (set dep-order) %))
                       (into dep-order))]
    dep-order))

(defn- parse-opts [args options]
  (into {}
        (for [[k {:keys [crux.config/type default required?]}] args
              :let [[validate-fn parse-fn] (s/conform :crux.config/type type)
                    v (some-> (get options k) parse-fn)
                    v (if (nil? v) default v)]]
          (do
            (when (and required? (not v))
              (throw (IllegalArgumentException. (format "Arg %s required" k))))
            (when (and v (not (validate-fn v)))
              (throw (IllegalArgumentException. (format "Arg %s invalid" k))))
            [k v]))))

(defn start-module [m started options]
  (s/assert ::module m)
  (let [{:keys [start-fn deps spec args]} (s/conform ::module m)
        deps (select-keys started deps)
        options (merge options (parse-opts args options))]
    (start-fn deps options)))

(s/def ::topology-map (s/map-of keyword? ::module))

(defn start-modules [topology options]
  (s/assert ::topology-map topology)
  (let [started-order (atom [])
        started (atom {})
        started-modules (try
                          (into {}
                                (for [k (start-order topology)]
                                  (let [m (topology k)
                                        _ (assert m (str "Could not find module " k))
                                        m (start-module m @started options)]
                                    (swap! started-order conj m)
                                    (swap! started assoc k m)
                                    [k m])))
                          (catch Throwable t
                            (doseq [c (reverse @started-order)]
                              (when (instance? Closeable c)
                                (cio/try-close c)))
                            (throw t)))]
    [started-modules (fn []
                       (doseq [m (reverse @started-order)
                               :when (instance? Closeable m)]
                         (cio/try-close m)))]))

(def base-topology
  {::kv-store 'crux.kv.rocksdb/kv
   ::object-store 'crux.object-store/kv-object-store
   ::indexer 'crux.tx/kv-indexer
   ::bus 'crux.bus/bus})

(defn options->topology [{:keys [crux.node/topology] :as options}]
  (when-not topology
    (throw (IllegalArgumentException. "Please specify :crux.node/topology")))
  (let [topology (if (map? topology) topology (resolve-topology-id topology))
        topology-overrides (select-keys options (keys topology))
        topology (merge topology (zipmap (keys topology-overrides)
                                         (map resolve-topology-id (vals topology-overrides))))]
    (s/assert ::topology-map topology)
    topology))

(def node-args
  {:crux.tx-log/await-tx-timeout
   {:doc "Default timeout in milliseconds for waiting."
    :default 10000
    :crux.config/type :crux.config/nat-int}})

(defn start ^crux.api.ICruxAPI [options]
  (let [options (into {} options)
        topology (options->topology options)
        [modules close-fn] (start-modules topology options)
        {::keys [kv-store tx-log remote-document-store bus indexer object-store]} modules
        status-fn (fn [] (apply merge (map status/status-map (cons (crux-version) (vals modules)))))
        node-opts (parse-opts node-args options)]
    (map->CruxNode {:close-fn close-fn
                    :status-fn status-fn
                    :options node-opts
                    :kv-store kv-store
                    :tx-log tx-log
                    :remote-document-store remote-document-store
                    :indexer indexer
                    :object-store object-store
                    :bus bus
                    :closed? (atom false)
                    :lock (StampedLock.)})))
