(ns components.queue.rabbit
  (:require [cheshire.core :as json]
            [cheshire.generate :as generators]
            [clojure.core :as clj]
            [components.io :as io]
            [langohr.basic :as basic]
            [langohr.channel :as channel]
            [langohr.consumers :as consumers]
            [langohr.core :as core]
            [langohr.exchange :as exchange]
            [langohr.queue :as queue]
            [environ.core :refer [env]])
  (:import [com.rabbitmq.client LongString]
           [com.fasterxml.jackson.core JsonParseException]))

(generators/add-encoder LongString generators/encode-str)

(defn- parse-meta [meta]
  (let [normalize-kv (fn [[k v]] [(keyword k) (if (instance? LongString v)
                                                (str v)
                                                v)])
        headers (->> meta
                     :headers
                     (map normalize-kv)
                     (into {}))]
    (-> headers (merge meta) (dissoc :headers))))

(def rabbit-default-meta [:cluster-id :app-id :message-id :expiration :type :user-id
                          :delivery-tag :delivery-mode :priority :redelivery?
                          :routing-key :content-type :persistent? :reply-to
                          :content-encoding :correlation-id :exchange :timestamp])

(defn- normalize-headers [meta]
  (let [headers (->> meta
                     (map (fn [[k v]] [(clj/name k) v]))
                     (into {}))]
    (apply dissoc headers (map name rabbit-default-meta))))

(defn- parse-payload [payload]
  (-> payload (String. "UTF-8") (json/decode true)))

(defn- retries-so-far [meta]
  (get-in meta [:headers "retries"] 0))

(defn ack-msg [queue meta]
  (basic/ack (:channel queue) (:delivery-tag meta)))

(defn requeue-msg [queue payload meta]
  (basic/publish (:channel queue)
                 ""
                 (:name queue)
                 payload
                 (update meta :headers (fn [hash-map]
                                         (let [map (into {} hash-map)]
                                           (assoc map "retries"
                                                  (-> meta retries-so-far inc)))))))

(defn reject-or-requeue [queue meta payload]
  (let [retries (retries-so-far meta)
        send-to-deadletter #(basic/reject (:channel queue) (:delivery-tag meta) false)]
    (cond
      (>= retries (:max-retries queue)) (send-to-deadletter)
      :requeue-message (do
                         (requeue-msg queue payload meta)
                         (ack-msg queue meta)))))

(defn- callback-payload [callback max-retries self _ meta payload]
  (let [retries (retries-so-far meta)
        reject-msg #(basic/reject (:channel self) (:delivery-tag meta) false)]
    (if (:redelivery? meta)
      (reject-or-requeue self meta payload)
      (let [payload (try (parse-payload payload) (catch JsonParseException _ :INVALID))]
        (if (= payload :INVALID)
          (reject-msg)
          (callback {:payload payload :meta (parse-meta meta)}))))))

(defn- raise-error []
  (throw (IllegalArgumentException.
          (str "Can't publish to queue without CID. Maybe you tried to send a message "
               "using `queue` from components' namespace. Prefer to use the "
               "components' attribute to create one."))))

(defrecord Queue [channel delayed name max-retries cid]
  io/IO
  (listen [self function]
          (let [callback (partial callback-payload function max-retries self)]
            (consumers/subscribe channel name callback)))

  (send! [_ {:keys [payload meta] :or {meta {}}}]
         (when-not cid (raise-error))
         (let [payload (json/encode payload)
               meta (assoc meta :headers (normalize-headers (assoc meta :cid cid)))]
           (if delayed
             (basic/publish channel name "" payload meta)
             (basic/publish channel "" name payload meta))))

  (ack! [_ {:keys [meta]}]
        (basic/ack channel (:delivery-tag meta)))

  (reject! [self msg _]
           (let [meta (:meta msg)
                 meta (assoc meta :headers (normalize-headers meta))
                 payload (-> msg :payload json/encode)]
             (reject-or-requeue self meta payload))))

(def connections (atom {}))

(def ^:private rabbit-config {:queues (-> env :rabbit-config json/parse-string)
                              :hosts (-> env :rabbit-queues json/parse-string)})

(defn- connection-to-host [host]
  (let [connect! #(let [connection (core/connect (get-in rabbit-config [:hosts host] {}))
                        channel (channel/open connection)]
                    [connection channel])]
    (if-let [conn (get @connections host)]
      conn
      (get (swap! connections assoc host (connect!)) host))))

(defn- connection-to-queue [queue-name]
  (let [queue-host (get-in rabbit-config [:queues queue-name])]
    (if queue-host
      (connection-to-host queue-host)
      (connection-to-host "localhost"))))

(defn disconnect! []
  (doseq [[_ [connection channel]] @connections]
    (core/close channel)
    (core/close connection))
  (reset! connections {}))

(def default-queue-params {:exclusive false
                           :auto-ack false
                           :auto-delete false
                           :max-retries 5
                           :durable true
                           :ttl (* 24 60 60 1000)})

(defn- real-rabbit-queue [name opts cid]
  (let [[connection channel] (connection-to-queue name)
        opts (merge default-queue-params opts)
        dead-letter-name (str name "-dlx")
        dead-letter-q-name (str name "-deadletter")]

    (queue/declare channel name (-> opts
                                    (dissoc :max-retries :ttl)
                                    (assoc :arguments {"x-dead-letter-exchange" dead-letter-name
                                                       "x-message-ttl" (:ttl opts)})))
    (queue/declare channel dead-letter-q-name
                   {:durable true :auto-delete false :exclusive false})

    (when (:delayed opts)
      (exchange/declare channel name "x-delayed-message"
                        {:arguments {"x-delayed-type" "direct"}})
      (queue/bind channel name name))

    (exchange/fanout channel dead-letter-name {:durable true})
    (queue/bind channel dead-letter-q-name dead-letter-name)
    (->Queue channel (:delayed opts) name (:max-retries opts) cid)))

(def queues (atom {}))

(defrecord FakeQueue [messages cid delayed]
  io/IO

  (listen [self function]
    (add-watch messages :watch (fn [_ _ _ actual]
                                 (let [msg (peek actual)]
                                   (when (and (not= msg :ACK)
                                              (not= msg :REJECT)
                                              (not (and delayed
                                                        (some-> meta :x-delay (> 0)))))
                                     (function msg))))))

  (send! [_ {:keys [payload meta] :or {meta {}}}]
    (when-not (and delayed (some-> meta :x-delay (> 0)))
      (swap! messages conj {:payload payload :meta (assoc meta :cid cid)})))

  (ack! [_ {:keys [meta]}]
    (swap! messages conj :ACK))

  (reject! [self msg ex]
    (swap! messages conj :REJECT)))

(defn- mocked-rabbit-queue [name cid delayed]
  (let [name-k (keyword name)
        mock-queue (get @queues name-k (->FakeQueue (atom []) cid delayed))]
    (swap! queues assoc name-k mock-queue)
    mock-queue))

(defn clear-mocked-env! []
  (doseq [[_ queue] @queues]
    (remove-watch (:messages queue) :watch))
  (reset! queues {}))

(defn queue [name & {:as opts}]
  (fn [{:keys [cid mocked]}]
    (if mocked
      (mocked-rabbit-queue name cid (:delayed opts))
      (real-rabbit-queue name opts cid))))
