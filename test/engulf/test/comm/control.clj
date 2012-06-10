(ns engulf.test.comm.control
  (:require [engulf.comm.control :as ctrl]
            [engulf.comm.message :as cmsg]
            [lamina.core :as lc]
            [cheshire.core :as chesh])
  (:use midje.sweet
        [clojure.walk :only [keywordize-keys]]))

(defmacro with-clean-node-ch
  "Used in testing to reset currently queued messages"
  [& body]
  `(binding [ctrl/node-ch (lc/permanent-channel)]
     ~@body))

(defn clear-nodes
  []
  (dosync (ref-set ctrl/nodes {})))

(facts
 "about registering nodes"
 (with-clean-node-ch
   (clear-nodes)
   (let [ident "a-unique-identifier"
         n  (ctrl/register-node ident {})]
     (fact
      "the node should have the right uuid"
      (:uuid n) => ident)
     (fact
      "the node should have a channel"
      (:channel n) =not=> nil?)
     (fact
      "the node should have enqueued a creation message"
      (lc/receive ctrl/node-ch
                  (fn [msg]
                    msg => {:type "new-node" :body n} )))))
 (facts
   "for nodes that do currently exist"
   (let [ident "some-unique-id"
         existing  (ctrl/register-node ident {})]
     (fact
      "calling create should return nil"
      (ctrl/register-node ident {}) => nil))))

(facts
 "about removing nodes"
 (clear-nodes)
 (facts
  "for nodes that don't yet exist"
  (let [ident "a-unique-identifier"
        n (ctrl/register-node ident {})]
    (fact
     "the node should no longer be present after removal"
     (ctrl/get-node ident) =not=> nil
     (ctrl/deregister-node n)
     (ctrl/get-node ident) => nil))))

(with-clean-node-ch
  (facts
   "about handling messages"
   (let [n (ctrl/node "htest-unique-id" {})
         msg {:type "htest-type" :body "htest-body"}
         res (ctrl/handle-message n (cmsg/parse-msg (cmsg/encode-msg msg)))
         expected-msg (assoc msg :node n)]
     (fact
      "the parsed and tagged message message should be enqueued on node-ch"
      res => expected-msg)
     (fact
      "the parsed and and tagged message should be emitted onto node-ch"
      (lc/receive ctrl/node-ch (fn [m] m => res))))))

(facts
 "about starting and stopping servers"
 (let [port (+ 10000 (int (rand 20000)))
       srv (ctrl/start-server port)]
   (fact "the server should start" srv => truthy)
   (fact "the server should stop" (srv) => truthy)))

