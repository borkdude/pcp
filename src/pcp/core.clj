(ns pcp.core
  (:require
    [sci.core :as sci :refer [copy-var]] 
    [sci.addons :as addons]
    [pcp.resp :as resp]
    [clojure.java.io :as io]
    [clojure.edn :as edn]
    [clojure.string :as str]
    [pcp.scgi :as scgi]
    [pcp.includes :refer [includes]]
    [hiccup.compiler :as compiler]
    [hiccup.util :as util]       
    [selmer.parser :as parser]
    [cheshire.core :as json]
    [clj-uuid :as uuid]
    [clojure.walk :as walk]
    [ring.util.codec :as codec]
    [taoensso.nippy :as nippy]
    [ring.middleware.params :refer [params-request]]
    [environ.core :refer [env]])
  (:import [java.net URLDecoder]
           [java.io File FileOutputStream ByteArrayOutputStream ByteArrayInputStream]
           [org.apache.commons.io IOUtils]
           [com.google.common.primitives Bytes]
           [org.apache.commons.codec.digest DigestUtils]) 
  (:gen-class))

(set! *warn-on-reflection* 1)

(defn keydb []
  (or (env :pcp-keydb) "/usr/local/etc/pcp-db"))

(def uns (sci/create-ns 'hiccup.util nil))
(def html-mode (copy-var util/*html-mode* uns))
(def escape-strings? (copy-var util/*escape-strings?* uns))

(defn render-html [& contents]
  (binding [util/*html-mode* @html-mode
            util/*escape-strings?* @escape-strings?]
    (apply compiler/render-html contents)))

(defn format-response [status body mime-type]
  (-> (resp/response body)    
      (resp/status status)
      (resp/content-type mime-type))) 

(defn file-response [path ^File file]
  (let [code (if (.exists file) 200 404)
        mime (resp/get-mime-type (re-find #"\.[0-9A-Za-z]{1,7}$" path))]
    (-> (resp/response file)    
        (resp/status code)
        (resp/content-type mime))))

(defn read-source [path]
  (try
    (str (slurp path))
    (catch java.io.FileNotFoundException _ nil)))

(defn build-path [path root]
  (str root "/" path))

(defn include [parent {:keys [namespace]}]
  (try
    {:file namespace
    :source (slurp (str parent "/" (-> namespace (str/replace "." "/") (str/replace "-" "_")) ".clj"))}
    (catch java.io.FileNotFoundException _ nil)))
     
(defn process-script [full-source opts]
  (sci/eval-string full-source opts)) ;sci/eval-string* pass context

(defn longer [str1 str2]
  (if (> (count str1) (count str2)) str1 str2))

(defn get-secret [root env-var]
  (try
    (let [project (-> (str root "/../pcp.edn") slurp edn/read-string :project)
          keypath (str (keydb) "/" project ".db")
          secret (nippy/thaw-from-file 
                  (str root "/../.pcp/"  
                    (-> ^String env-var str/trim ^"[B" DigestUtils/sha512Hex) ".npy") 
                    {:password [:cached (-> keypath slurp)]})]
      (if (= env-var (:name secret)) 
        (:value secret)
        nil))
    (catch java.io.FileNotFoundException _  (println "No passphrase has been set for this project") nil)
    (catch Exception e (.printStackTrace e) nil)))

(defn run-script [url-path &{:keys [root params]}]
  (let [path (URLDecoder/decode url-path "UTF-8")
        source (read-source path)
        file (io/file path)
        parent (longer root (-> ^File file (.getParentFile) str))
        response (atom nil)]
    (if (string? source)
      (let [opts  (-> { :load-fn #(include parent %)
                        :namespaces (merge includes {'pcp { 'body (:body params)
                                                            'request params
                                                            'response (fn [status body mime-type] (reset! response (format-response status body mime-type)))
                                                            'html render-html
                                                            'render-html render-html
                                                            'secret #(get-secret root %)
                                                            'echo pr-str
                                                            'now #(quot (System/currentTimeMillis) 1000)}})
                        :bindings {'slurp #(slurp (str parent "/" %))}
                        :classes {'org.postgresql.jdbc.PgConnection org.postgresql.jdbc.PgConnection}}
                        (addons/future))
            _ (parser/set-resource-path! root)                        
            result (process-script source opts)
            _ (selmer.parser/set-resource-path! nil)]
        (if (nil? @response) result @response))
      nil)))

(defn extract-multipart [req]
  (let [bytes (IOUtils/toByteArray ^ByteArrayInputStream  (:body req))
        len (count bytes)
        body (IOUtils/toString ^"[B" bytes "UTF-8")
        content-type-string (:content-type req)
        boundary (str "--" (second (re-find #"boundary=(.*)$" content-type-string)))
        boundary-byte (IOUtils/toByteArray ^String boundary)
        real-body (str/replace body (re-pattern (str boundary "--.*")) "")
        parts (filter #(seq %) (str/split real-body (re-pattern boundary)))
        form  (apply merge
                (for [part parts]
                  (if (str/includes? part "filename=\"")
                    {(keyword (second (re-find #"form-data\u003B name=\"(.*?)\"" part)))
                      (let [filename (second (re-find #"form-data\u003B.*filename=\"(.*?)\"\r\n" part))
                            filetype-result (re-find #"Content-Type: (.*)\r\n\r\n" part)
                            mark (first (re-find #"(?sm)(.*?)\r\n\r\n" part))
                            realmark (IOUtils/toByteArray ^String mark)
                            start (+ (Bytes/indexOf ^"[B" bytes ^"[B" realmark) (count realmark))
                            end (let [baos (ByteArrayOutputStream.)]
                                  (.write baos bytes start (- len start))
                                  (+ start (Bytes/indexOf ^"[B" (.toByteArray baos) ^"[B" boundary-byte)))
                            tempfilename (str "/tmp/pcp-temp/" (uuid/v1) "-" filename)
                            _ (let [_ (io/make-parents tempfilename)
                                    f (FileOutputStream. ^String tempfilename)]
                                (.write f bytes start (- end start))
                                (.close f))
                            file (io/file tempfilename)]
                      { :filename filename
                        :type (second filetype-result)
                        :tempfile file
                        :size (.length ^File file)})}
                    {(keyword (second (re-find #"form-data\u003B name=\"(.*?)\"\r\n" part)))
                      (second (re-find #"\r\n\r\n(.*)$" part))})))]       
      (assoc req :body form)))

(defn make-map [thing]
  (if (map? thing) thing {}))

(defn body-handler [req]
  (let [type (:content-type req)]
    (if (nil? type)
      req
      (cond 
        (str/includes? type "application/x-www-form-urlencoded")
          (update req :body #(-> % slurp codec/form-decode make-map walk/keywordize-keys))
        (str/includes? type "application/json")
          (update req :body #(-> % slurp (json/decode true)))
        (str/includes? type "multipart/form-data")
            (extract-multipart req)
        :else req))))

(defn scgi-handler [req]
  (let [request (-> req body-handler params-request walk/keywordize-keys)
        root (:document-root request)
        doc (:document-uri request)
        path (str root doc)
        r (try (run-script path :root root :params request) (catch Exception e  (.printStackTrace e) (format-response 500 (.getMessage e) nil)))]
    r))

(defn -main 
  ([]
    (-main ""))
  ([path]       
    (let [scgi-port (Integer/parseInt (or (System/getenv "SCGI_PORT") "9000"))]
      (case path
        "" (scgi/serve scgi-handler scgi-port)
        (run-script path)))))

      