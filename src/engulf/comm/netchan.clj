(ns engulf.comm.netchan
  (:require
   [clojure.tools.logging :as log]
   [cheshire.core :as chesh])
  (:use
   aleph.tcp
   lamina.core
   gloss.core
   gloss.io
   [clojure.walk :only [keywordize-keys]])
  (:import
   java.util.zip.GZIPOutputStream
   java.util.zip.GZIPInputStream
   java.io.ByteArrayOutputStream
   java.io.ByteArrayInputStream
   java.nio.ByteBuffer
   java.util.Arrays))

(defn compress-byte-array
  [^bytes ba]
  (let [baos (ByteArrayOutputStream.)
        gzos (GZIPOutputStream. baos)]
    (doto gzos (.write ba) (.close))
    (.toByteArray baos)))

(defn decompress-byte-array
  [^bytes ba]
  (let [bais (ByteArrayInputStream. ba)
        gzis (GZIPInputStream. bais)
        buf-size 4096
        baos (ByteArrayOutputStream.)]
    (loop []
      (let [buf (byte-array buf-size)
            read-len (.read gzis buf 0 buf-size)]
        (when (> read-len 0)
          (.write baos buf 0 read-len)
          (recur))))
    (.close gzis)    
    (.toByteArray baos)))

(defn encode-msg
    "Encodes a message using SMILE and GZIP"
    [type body]
    (compress-byte-array (chesh/encode-smile {:type type :body body})))

(defn decode-msg
  "Parses a GZIPed SIMLE msg, ensures it's properly formatted as well"
  [msg]
  {:post [(not= nil (first %))]}
  (let [{:strs [type body]} (chesh/parse-smile (decompress-byte-array msg))]
    [type body]))

;; Simple int32 prefixed frames
(def wire-protocol
  (finite-block (prefix :int32 inc dec)))

(defn decode-frame
  "Returns only the decoded frame payload, stripping off its length prefix"
  [frame]
  (let [frame-arr (.array (contiguous frame))]
    (decode-msg (Arrays/copyOfRange frame-arr 4 (alength frame-arr)))))

(defn encode-frame
  "Encodes a msg into a buffer-seq suitable for gloss framing"
  [[type body]]
  (to-buf-seq (encode-msg type body)))

(defn formatted-channel
  "Takes a channel from a tcp server or client, and returns a new channel that automatically
   decodes and encodes values"
  [conn]
  (let [tx (channel)]
    (siphon (map* encode-frame tx) conn)
    (splice
     (map* decode-frame conn)
     tx)))

(defn start-server
  "Starts a TCP server. Returns an aleph server"
  [port handler]
  (start-tcp-server
   (fn srv-handler [conn client-info]
     (handler (formatted-channel conn) client-info))
   {:port port :frame wire-protocol}))

(defn client-connect
  "Connect to a server running on a given host/port."
  [host port]
  (let [raw-conn (tcp-client {:host host :port port :frame wire-protocol})
        fmtd-conn (result-channel)]
    (on-realized
     raw-conn
     (fn client-conn-succ [ch] (enqueue fmtd-conn (formatted-channel ch)))
     (fn client-conn-err [t] (error fmtd-conn t)))
    fmtd-conn))