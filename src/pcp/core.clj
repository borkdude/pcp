(ns pcp.core
  (:require
    [sci.core :as sci]
    [sci.addons :as addons]
    [pcp.resp :as resp]
    [clojure.java.io :as io]
    [clojure.string :as str]
    [pcp.scgi :as scgi]
    [pcp.includes :refer [includes html]]
    [selmer.parser :as parser]
    [cheshire.core :as json]
    [clj-uuid :as uuid]
    [clojure.walk :as walk]
    [ring.util.codec :as codec])
  (:import [java.net URLDecoder]
           [java.io File FileOutputStream ByteArrayOutputStream ByteArrayInputStream]
           [org.apache.commons.io IOUtils]
           [com.google.common.primitives Bytes]) 
  (:gen-class))

(set! *warn-on-reflection* 1)

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
    (catch java.io.FileNotFoundException fnfe nil)))

(defn build-path [path root]
  (str root "/" path))

(defn clean-source [source]
  (str/replace source #"\u003B.*\n" ""))

(defn process-includes [raw-source parent]
  (let [source (clean-source raw-source)
        includes-used (re-seq #"\(use\s*?\"(.*?)\"\s*?\)" source)]
    (loop [code source externals includes-used]
      (if (empty? externals)
        code
        (let [included (-> externals first second (build-path parent) read-source)]
          (if (nil? included)
            (throw 
              (ex-info (str "Included file '" (-> externals first second (build-path parent)) "' was not found.")
                        {:cause   (str (-> externals first first))}))
            (recur 
              (str/replace code (-> externals first first) included) 
              (rest externals))))))))

(defn process-script [full-source opts]
  (sci/eval-string full-source opts))

(defn longer [str1 str2]
  (if (> (count str1) (count str2)) str1 str2))

(defn run-script [url-path &{:keys [root params]}]
  (let [path (URLDecoder/decode url-path "UTF-8")
        source (read-source path)
        file (io/file path)
        parent (longer root (-> ^File file (.getParentFile) str))]
    (if (string? source)
      (let [opts  (-> { :namespaces (merge includes {'pcp { 'params (:body params)
                                                            'request params
                                                            'response format-response
                                                            'html html}})
                        :bindings {'println println 'use identity  'include identity 'slurp #(slurp (str parent "/" %))}
                        :classes {'org.postgresql.jdbc.PgConnection org.postgresql.jdbc.PgConnection}}
                        (addons/future))
            _ (parser/set-resource-path! root)                        
            full-source (process-includes source parent)
            result (process-script full-source opts)
            _ (selmer.parser/set-resource-path! nil)]
        result)
      nil)))

(defn extract-multipart [req]
  (let [bytes (IOUtils/toByteArray ^ByteArrayInputStream  (:body req))
        len (count bytes)
        body (IOUtils/toString ^"[B" bytes "UTF-8")
        content-type-string (:content-type req)
        boundary (str "--" (second (re-find #"boundary=(.*)$" content-type-string)))
        boundary-byte (IOUtils/toByteArray boundary)
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
                                  (+ start (Bytes/indexOf (.toByteArray baos) boundary-byte)))
                            tempfilename (str "./tmp/pcp/" (uuid/v1) "-" filename)
                            _ (let [_ (io/make-parents tempfilename)
                                    f (FileOutputStream. tempfilename)]
                                (.write f bytes start (- end start))
                                (.close f))
                            file (io/file tempfilename)]
                      { :filename filename
                        :type (second filetype-result)
                        :tempfile file
                        :size (.length file)})}
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
  (let [request (body-handler req)
        root (:document-root request)
        doc (:document-uri request)
        path (str root doc)
        r (try (run-script path :root root :params request) (catch Exception e  (format-response 500 (.getMessage e) nil)))]
    r))

(defn -main 
  ([]
    (-main ""))
  ([path]       
    (let [scgi-port (Integer/parseInt (or (System/getenv "SCGI_PORT") "9000"))]
      (case path
        ""  (scgi/serve scgi-handler scgi-port)
        (run-script path)))))

      