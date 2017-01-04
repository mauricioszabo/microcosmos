(ns components.queue.rabbit-test
  (:require [components.core :as components]
            [components.future :as future]
            [components.queue.rabbit :as rabbit]
            [cheshire.core :as json]
            [langohr.core :as core]
            [midje.sweet :refer :all]))

(def all-msgs (atom []))
(def all-processed (atom []))
(def all-deadletters (atom []))
(def last-promise (atom (promise)))

(defn- send-msg [fut-value {:keys [result-q]}]
  (future/map (fn [value]
                (swap! all-msgs conj value)
                (case (:payload value)
                  "error" (throw (Exception. "Some Error"))
                  (components/send! result-q value)))
              fut-value))

(def sub (components/subscribe-with :result-q (rabbit/queue "test-result" :auto-delete true)
                                    :logger (fn [_] (components.logging/->DebugLogger "FOO"))))

(defn in-future [f]
  (fn [future _]
    (future/map f future)))

(defn send-messages [msgs]
  (let [test-queue (rabbit/queue "test" :auto-delete true :max-retries 1)
        result-queue (rabbit/queue "test-result" :auto-delete true)
        channel (:channel (result-queue {}))
        deadletter-queue (fn [_] (rabbit/->Queue channel "test-deadletter" 1000 "FOO"))]
    (sub test-queue send-msg)
    (sub result-queue (in-future #(do
                                    (swap! all-processed conj %)
                                    (when (realized? @last-promise)
                                      (reset! last-promise (promise)))
                                    (deliver @last-promise %))))
    (sub deadletter-queue (in-future #(swap! all-deadletters conj %)))
    (doseq [msg msgs]
      (components/send! (test-queue {:cid "FOO"}) msg))))

(defn send-and-wait [ & msgs]
  (send-messages msgs)
  (deref @last-promise 1000 {:payload :timeout
                             :meta :timeout}))

(defn prepare-tests []
  (reset! last-promise (promise))
  (reset! all-msgs [])
  (reset! all-processed [])
  (reset! all-deadletters []))

(facts "Handling messages on RabbitMQ's queue"
  (fact "handles message if successful"
    (:payload (send-and-wait {:payload {:some "msg"}})) => {:some "msg"})

  (fact "attaches metadata into msg"
    (:meta (send-and-wait {:payload {:some "msg"}, :meta {:a 10}}))
    => (contains {:a 10, :cid "FOO.BAR"}))

  (fact "attaches CID between services"
    (get-in (send-and-wait {:payload "msg"}) [:meta :cid]) => "FOO.BAR")

  (against-background
    (components/generate-cid nil) => "FOO"
    (components/generate-cid "FOO") => "FOO.BAR"
    (components/generate-cid "FOO.BAR") => ..irrelevant..
    (before :facts (prepare-tests))
    (after :facts (rabbit/disconnect!))))

; OH MY GOSH, how difficult is to test asynchronous code!
(fact "when message results in a failure process multiple times (till max-retries)"
  (fact "acks the original msg, and generates other to retry things"
    (:payload (send-and-wait {:payload "error"} {:payload "msg"})) => "msg"
    (reset! last-promise (promise))
    (:payload (send-and-wait {:payload "other-msg"})) => "other-msg"
    (map :payload @all-deadletters) => ["error"]
    (map :payload @all-msgs) => ["error" "msg" "error" "other-msg"])

  (future-fact "don't process anything if old server died (but mark to retry later)")

  (against-background
    (before :facts (prepare-tests))
    (after :facts (rabbit/disconnect!))))

; Mocks
(defn a-function [test-q]
  (let [extract-payload :payload
        upcases #(clojure.string/upper-case %)
        publish #(components/send! %2 {:payload %1})]
    (sub test-q (fn [msg {:keys [result-q]}]
                  (->> msg
                       (future/map extract-payload)
                       (future/map upcases)
                       (future/map #(publish % result-q)))))))

(facts "when mocking RabbitMQ's queue"
  (fact "subscribes correctly to messages"
    (components/mocked
      (a-function (rabbit/queue "test"))
      (components/send! (:test @rabbit/queues) {:payload "message"})
      (-> @rabbit/queues :test-result :messages deref)
      => (just [(contains {:payload "MESSAGE"})])))

  (fact "ignores delayed messages"
    (components/mocked
      (a-function (rabbit/queue "test" :delayed true))
      (components/send! (:test @rabbit/queues) {:payload "msg one"})
      (components/send! (:test @rabbit/queues) {:payload "msg two", :meta {:x-delay 400}})
      (-> @rabbit/queues :test-result :messages deref)
      => (just [(contains {:payload "MSG ONE"})])))

  (background
    (after :facts (rabbit/clear-mocked-env!))))
