(ns lupapalvelu.mongo
  (:refer-clojure :exclude [count])
  (:use monger.operators
        clojure.tools.logging)
  (:require [sade.env :as env]
            [monger.core :as m]
            [monger.collection :as mc]
            [monger.db :as db]
            [monger.gridfs :as gfs]
            [monger.command :refer [server-status]]
            [sade.status :refer [defstatus]])
  (:import [javax.net.ssl SSLSocketFactory]
           [org.bson.types ObjectId]
           [com.mongodb WriteConcern MongoClientOptions MongoClientOptions$Builder]
           [com.mongodb.gridfs GridFS GridFSInputFile]))

;;
;; Utils
;;

(defn with-_id [m]
  (if-let [id (:id m)]
    (-> m
      (assoc :_id id)
      (dissoc :id))
    m))

(defn with-id [m]
  (if-let [id (:_id m)]
    (-> m
      (assoc :id id)
      (dissoc :_id))
    m))

(defn create-id []
  (str (ObjectId.)))

;;
;; Database Api
;;

(defn update
  "Updates data into collection by query. Always returns nil."
  [collection query data & opts]
  (apply mc/update collection query data opts)
  nil)

(defn update-by-id
  "Updates data into collection by id (which is mapped to _id). Always returns nil."
  [collection id data]
  (mc/update-by-id collection id data)
  nil)

(defn update-by-query
  "Updates data into collection. Returns the number of documents updated"
  [collection query data]
  (.getN (mc/update collection query data)))

(defn insert
  "Inserts data into collection. The 'id' in 'data' (if it exists) is persisted as _id"
  [collection data]
  (mc/insert collection (with-_id data))
  nil)

(defn by-id [collection id]
  (with-id (mc/find-one-as-map collection {:_id id})))

(defn select
  "returns multiple entries by matching the monger query"
  ([collection]
    (select collection {}))
  ([collection query]
    (select collection query {}))
  ([collection query projection]
    (map with-id (mc/find-maps collection query projection))))

(defn select-one
  "returns one entry by matching the monger query"
  ([collection query]
    (with-id (mc/find-one-as-map collection query)))
  ([collection query projection]
    (with-id (mc/find-one-as-map collection query projection))))

(defn update-one-and-return
  "Updates first document in collection matching conditions. Returns updated document or nil."
  [collection conditions document & {:keys [fields sort remove upsert] :or {fields nil sort nil remove false upsert false}}]
  (mc/find-and-modify collection conditions document :return-new true :upsert upsert :remove remove :sort sort :fields fields))

(defn remove-many
  "Returns all documents matching query."
  [collection query] (mc/remove collection query))

(defn set-file-id [^GridFSInputFile input ^String id]
  (.setId input id)
  input)

(defn upload [applicationId file-id filename content-type tempfile timestamp]
  (gfs/store-file
    (gfs/make-input-file tempfile)
    (set-file-id file-id)
    (gfs/filename filename)
    (gfs/content-type content-type)
    (gfs/metadata {:uploaded timestamp, :application applicationId})))

(defn download [file-id]
  (if-let [attachment (gfs/find-one {:_id file-id})]
    {:content (fn [] (.getInputStream attachment))
     :content-type (.getContentType attachment)
     :content-length (.getLength attachment)
     :file-name (.getFilename attachment)
     :application (.getString (.getMetaData attachment) "application")}))

(defn delete-file [file-id]
  (info "removing file" file-id)
  (gfs/remove {:_id file-id}))

(defn count
  "returns count of objects in collection"
  ([collection]
    (mc/count collection))
  ([collection query]
    (mc/count collection query)))

(defn get-next-sequence-value [sequence-name]
  (:count (update-one-and-return :sequences {:_id (name sequence-name)} {$inc {:count 1}} :upsert true)))

;;
;; Bootstrappin'
;;

;; From monger.core, pimped with SSL option
(defn mongo-options
  [& { :keys [connections-per-host threads-allowed-to-block-for-connection-multiplier
              max-wait-time connect-timeout socket-timeout socket-keep-alive auto-connect-retry max-auto-connect-retry-time ssl
              safe w w-timeout fsync j] :or [auto-connect-retry true] }]
  (let [mob (MongoClientOptions$Builder.)]
    (when connections-per-host
      (.connectionsPerHost mob connections-per-host))
    (when threads-allowed-to-block-for-connection-multiplier
      (.threadsAllowedToBlockForConnectionMultiplier mob threads-allowed-to-block-for-connection-multiplier))
    (when max-wait-time
      (.maxWaitTime mob max-wait-time))
    (when connect-timeout
      (.connectTimeout mob connect-timeout))
    (when socket-timeout
      (.socketTimeout mob socket-timeout))
    (when socket-keep-alive
      (.socketKeepAlive mob socket-keep-alive))
    (when auto-connect-retry
      (.autoConnectRetry mob auto-connect-retry))
    (when max-auto-connect-retry-time
      (.maxAutoConnectRetryTime mob max-auto-connect-retry-time))
    (when ssl
      (.socketFactory mob (SSLSocketFactory/getDefault)))
    (when safe
      (.safe mob safe))
    (when w
      (.w mob w))
    (when w-timeout
      (.wtimeout mob w-timeout))
    (when j
      (.j mob j))
    (when fsync
      (.fsync mob fsync))
    (.build mob)))

(def server-list
  (let [servers (vals (get-in env/config [:mongodb :servers]))]
    (map #(apply m/server-address [(:host %) (:port %)]) servers)))

(def connected (atom false))

(defn connect!
  ([]
    (let [conf (:mongodb env/config)
          db   (:dbname conf)
          user (-> conf :credentials :username)
          pw   (-> conf :credentials :password)
          ssl  (:ssl conf)]
      (connect! server-list db user pw ssl)))
  ([servers db username password ssl]
    (if @connected
      (debug "Already connected!")
      (do
        (debug "Connecting to DB:" servers (if ssl "using ssl" "without encryption"))
        (m/connect! servers (mongo-options :ssl ssl))
        (reset! connected true)
        (m/set-default-write-concern! WriteConcern/SAFE)
        (when (and username password)
          (m/authenticate (m/get-db db) username (.toCharArray password))
          (debugf "Authenticated to DB '%s' as '%s'" db username))
        (m/use-db! db)
        (debug "DB is" (.getName (m/get-db)))))))

(defn disconnect! []
  (if @connected
    (do
      (m/disconnect!)
      (reset! connected false))
    (debug "Not connected")))

(defn ensure-indexes []
  (debug "ensure-indexes")
  (mc/ensure-index :users {:username 1} {:unique true})
  (mc/ensure-index :users {:email 1} {:unique true})
  (mc/ensure-index :users {:municipality 1} {:sparse true})
  (mc/ensure-index :users {:private.apikey 1} {:unique true :sparse true})
  (mc/ensure-index "users" {:personId 1} {:unique true :sparse true :dropDups (env/in-dev)})
  (mc/ensure-index :applications {:municipality 1})
  (mc/ensure-index :applications {:auth.id 1})
  (mc/ensure-index :applications {:auth.invite.user.id 1} {:sparse true})
  (mc/ensure-index :activation {:created-at 1} {:expireAfterSeconds (* 60 60 24 7)})
  (mc/ensure-index :activation {:email 1})
  (mc/ensure-index :vetuma {:created-at 1} {:expireAfterSeconds (* 60 30)})
  (mc/ensure-index :municipalities {:municipalityCode 1}))

(defn clear! []
  (warn "Clearing MongoDB")
  (gfs/remove-all)
  ; Collections must be dropped individially, otherwise index cache will be stale
  (doseq [coll (db/get-collection-names)]
    (when-not (.startsWith coll "system") (mc/drop coll)))
  (ensure-indexes))

(defstatus :mongo (server-status))
