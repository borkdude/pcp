(ns pcp.scgi
  (:require [clojure.string :as str]
            [clojure.core.async :as async])
  (:import [java.nio.channels ServerSocketChannel SocketChannel Selector SelectionKey]
           [java.nio ByteBuffer]
           [java.net InetSocketAddress InetAddress]
           [java.io ByteArrayInputStream ByteArrayOutputStream])
  (:gen-class))

(set! *warn-on-reflection* 1)

(defn to-byte-array [^String text]
  (-> text (.getBytes "UTF-8") ByteBuffer/wrap))

(defn extract-headers [req]
  (let [data (str/split (:header req) #"\u0000")
        keys (map #(-> % (str/replace "_" "-") str/lower-case keyword) (take-nth 2 data))
        values (take-nth 2 (rest data))
        h (transient (zipmap keys values))]
    ;make the ring linter happy.
    (-> h
      (assoc! :server-port (Integer/parseInt (if (str/blank? (:server-port h)) "0" (:server-port h))))
      (assoc! :content-length (Integer/parseInt (if (str/blank? (:content-length h)) "0" (:content-length h))))
      (assoc! :request-method (-> (:request-method h) str str/lower-case keyword))
      (assoc! :headers {  "sec-fetch-site" (-> h :http-sec-fetch-site)   
                          "host" (-> h :http-host)   
                          "user-agent" (-> h :http-user-agent)     
                          "cookie" (-> h :http-cookie)   
                          "sec-fetch-user" (-> h :http-sec-fetch-user)   
                          "connection" (-> h :hhttp-connection)   
                          "upgrade-insecure-requests" (-> h :http-sec-fetch-site)   
                          "accept"  (-> h :http-accept)   
                          "accept-language"   (-> h :http-accept-language)   
                          "sec-fetch-dest" (-> h :http-sec-fetch-dest)   
                          "accept-encoding" (-> h :http-accept-encoding)   
                          "sec-fetch-mode" (-> h :http-sec-fetch-mode)    
                          "cache-control" (-> h :http-cache-control)})
      (assoc! :uri (:request-uri h))
      (assoc! :scheme (-> h :request-scheme keyword))
      (assoc! :body (:body req))
      (persistent!))))

(defn on-accept [selector ^SelectionKey key]
  (let [^ServerSocketChannel channel     (.channel key)
        ^SocketChannel socketChannel   (.accept channel)]
    (.configureBlocking socketChannel false)
    (.register socketChannel selector SelectionKey/OP_READ)))

(defn create-scgi-string [resp]
  (let [nl "\r\n"
        response (str (:status resp) nl (apply str (for [[k v] (:headers resp)] (str k ": " v nl))) nl (:body resp))]
    response))

(defn on-read [^SelectionKey key handler]
  (let [^SocketChannel socket-channel (.channel key)]
    (try
      (let [buf (ByteBuffer/allocate 1)
            real-buf (ByteBuffer/allocate 16384)
            len-out (ByteArrayOutputStream.)
            header-out (ByteArrayOutputStream.)
            body-out (ByteArrayOutputStream.)]
        (.clear buf)
        ;Saving reqs
        ; (loop [len (.read socket-channel real-buf)]
        ;     (when (> len 0)
        ;       (.write body-out (.array real-buf) 0 len)
        ;       (.clear real-buf)
        ;       (recur (.read socket-channel real-buf)))) 
        ; (let [f (java.io.FileOutputStream. "test-resources/json.bin")
        ;       byties (.toByteArray body-out)]
        ;   (.write f byties 0 (count byties))
        ;   (.close f))
        (loop [_ (.read socket-channel buf)]
          (when (not= (-> buf .array String.) ":")
            (.write len-out (.array buf) 0 1)
            (.clear buf)
            (recur (.read socket-channel buf))))
        (let [maxi (try (Integer/parseInt (.toString len-out "UTF-8")) (catch Exception _ 0))]         
          (.clear buf)
          (loop [read 0 len (.read socket-channel buf)]
            (when (< read maxi)
              (.write header-out (.array buf) 0 len)
              (.clear buf)
              (recur (+ read len) (.read socket-channel buf)))))              
        (let [header (.toString header-out "UTF-8")]  
          (loop [len (.read socket-channel real-buf)]
            (when (> len 0)
              (.write body-out (.array real-buf) 0 len)
              (.clear real-buf)
              (recur (.read socket-channel real-buf))))   
              (let [^ByteBuffer resp (-> {:header header :body (ByteArrayInputStream. (.toByteArray body-out))} extract-headers handler create-scgi-string to-byte-array)]
                (.write socket-channel resp)
                (.close socket-channel)
                (.cancel key))))
      (catch Exception _ (.close socket-channel) (.cancel key)))))

(defn build-server [port selector]
  (let [^ServerSocketChannel serverChannel (ServerSocketChannel/open)
        portAddr (InetSocketAddress. ^InetAddress (InetAddress/getByName "127.0.0.1") ^Integer port)]
      (.configureBlocking serverChannel false)
      (.bind (.socket serverChannel) portAddr)
      (.register serverChannel selector SelectionKey/OP_ACCEPT)
      serverChannel))

(defn run-selection [active handler ^Selector selector]
  (async/thread
    (while (some? @active)
      (if (not= 0 (.select selector 50))
          (let [keys (.selectedKeys selector)]      
            (doseq [^SelectionKey key keys]
              (let [ops (.readyOps key)]
                (cond
                  (= ops SelectionKey/OP_ACCEPT) (on-accept selector key)
                  (= ops SelectionKey/OP_READ)   (on-read key handler))))
            (.clear keys))
            nil))))

(defn serve [handler port &{:keys [cluster]}]
  (let [active (atom true)
        ^Selector selector  (Selector/open)
        ^Selector selector2 (when cluster (Selector/open))
        ^Selector selector3 (when cluster (Selector/open))
        ^Selector selector4 (when cluster (Selector/open))
        ^ServerSocketChannel server  (build-server port selector)
        ^ServerSocketChannel server2 (when cluster (build-server 9007 selector2))
        ^ServerSocketChannel server3 (when cluster (build-server 9014 selector3))
        ^ServerSocketChannel server4 (when cluster (build-server 9021 selector4))]
    (run-selection active handler selector)        
    (when cluster 
      (run-selection active handler selector2)        
      (run-selection active handler selector3)        
      (run-selection active handler selector4))
    (future (while (some? @active) nil))
    (fn [] 
      (.close server)
      (when cluster (.close server2))
      (when cluster (.close server3))
      (when cluster (.close server4))
      (reset! active false))))

