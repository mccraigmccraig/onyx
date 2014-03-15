(ns onyx.peer.hornetq-util
  (:require [clojure.data.fressian :as fressian])
  (:import [org.hornetq.api.core.client HornetQClient]
           [org.hornetq.api.core TransportConfiguration HornetQQueueExistsException]
           [org.hornetq.core.remoting.impl.netty NettyConnectorFactory]))

(defn create-queue [session queue-name]
  (try
    (.createQueue session queue-name queue-name true)
    (catch Exception e)))

(defn write-and-cap! [queue-name messages]
  (let [tc (TransportConfiguration. (.getName NettyConnectorFactory))
        locator (HornetQClient/createServerLocatorWithoutHA (into-array [tc]))
        session-factory (.createSessionFactory locator)
        session (.createTransactedSession session-factory)]
    
    (create-queue session queue-name)
    
    (let [producer (.createProducer session queue-name)]
      (.start session)
      (doseq [m messages]
        (let [message (.createMessage session true)]
          (.writeBytes (.getBodyBuffer message) (.array (fressian/write m)))
          (.send producer message)))

      (let [sentinel (.createMessage session true)]
        (.writeBytes (.getBodyBuffer sentinel) (.array (fressian/write :done)))
        (.send producer sentinel))

      (.commit session)
      (.close producer)
      (.close session)
      (.close session-factory)
      (.close locator))))

(defn read! [queue-name n]
  (let [tc (TransportConfiguration. (.getName NettyConnectorFactory))
        locator (HornetQClient/createServerLocatorWithoutHA (into-array [tc]))
        _ (.setConsumerWindowSize locator 0)
        session-factory (.createSessionFactory locator)
        session (.createTransactedSession session-factory)]

    (create-queue session queue-name)
    
    (let [consumer (.createConsumer session queue-name)
          results (atom [])]
      (.start session)
      (doseq [k (range n)]
        (let [message (.receive consumer)]
          (.acknowledge message)
          (swap! results conj (fressian/read (.toByteBuffer (.getBodyBuffer message))))))
      (.commit session)
      (.close consumer)
      (.close session)

      @results)))
