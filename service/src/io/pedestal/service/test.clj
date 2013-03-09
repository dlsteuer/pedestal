(ns ^{:doc "Pedestal testing utilities to simplify working with pedestal apps."}
  io.pedestal.service.test
  (:require [io.pedestal.service.http.servlet :as servlets]
            [clojure.string :as str]
            clojure.java.io)
  (:import (javax.servlet.http HttpServlet HttpServletRequest HttpServletResponse)
           (javax.servlet Servlet ServletOutputStream ServletInputStream)
           (java.io ByteArrayInputStream ByteArrayOutputStream)
           (clojure.lang IMeta)
           (java.util Enumeration NoSuchElementException)))

(defn- ^Servlet test-servlet
  [interceptor-service-fn]
  (servlets/servlet :service interceptor-service-fn))

(defn parse-url
  [url]
  (->> url
      (re-matches #"(?:([^:]+)://)?([^/]+)?(?:/([^\?]+)(?:\?(.*))?)?")
      (drop 1)
      ((fn [[scheme host path query-string]]
         {:scheme scheme
          :host host
          :path path
          :query-string query-string}))))

(def empty-enumeration
  (reify Enumeration
    (hasMoreElements [this] false)
    (nextElement [this] (throw (NoSuchElementException. "Attempted to fetch element from io.pedestal.service.test/empty-enumeration.")))))

(defn- test-servlet-input-stream
  ([]
     (proxy [ServletInputStream]
         []
       (readLine [bytes off len] -1)))
  ([^String string]
     (let [wrapped-stream (ByteArrayInputStream. (.getBytes string))]
       (proxy [ServletInputStream]
           []
         (read ([] (.read wrapped-stream))
           ([^bytes b] (.read wrapped-stream b))
           ([^bytes b ^Integer off ^Integer len] (.read wrapped-stream b off len)))))))

(defn- test-servlet-request
  [verb url & args]
  (let [{:keys [scheme host path query-string]} (parse-url url)
        options (apply array-map args)]
    (reify HttpServletRequest
     (getMethod [this] (-> verb
                           name
                           str/upper-case))
     (getRequestURL [this] url)
     (getLocalPort [this] -1)
     (getLocalName [this] host)
     (getServerPort [this] -1)
     (getServerName [this] host)
     (getRemoteAddr [this] "127.0.0.1")
     (getRequestURI [this] (str "/" path))
     (getQueryString [this] query-string)
     (getScheme [this] scheme)
     (getInputStream [this] (apply test-servlet-input-stream (when-let [body (:body options)] [body])))
     (getProtocol [this] "HTTP/1.1")
     (isAsyncSupported [this] false)
     (getHeaderNames [this] empty-enumeration)
     (getContentLength [this] (int 0))
     (getContentType [this] "")
     (getCharacterEncoding [this] "UTF-8")
     (getAttribute [this attribute] nil))))

(defn- test-servlet-output-stream
  []
  (let [output-stream (ByteArrayOutputStream.)]
    (proxy [ServletOutputStream IMeta]
        []
      (write
        ([arg] (if (= java.lang.Integer (type arg))
                 (.write output-stream (int arg))
                 (.write output-stream (bytes arg))))
        ([contents off len] (.write output-stream (bytes contents) (int off) (int len))))
      (meta [] {:output-stream output-stream}))))

(defn ^HttpServletResponse test-servlet-response
  "Returns a mock servlet response with a ServletOutputStream over a
  ByteArrayOutputStream. Captures the ByteArrayOutputStream in
  metadata. All headers set will swap a headers map held in an atom,
  also held in metadata."
  []
  (let [output-stream (test-servlet-output-stream)
        headers-map (atom {})
        status-val (atom nil)
        meta-data {:output-stream (:output-stream (meta output-stream))
                   :status status-val
                   :headers-map headers-map}]
    (with-meta (reify HttpServletResponse
                 (getOutputStream [this] output-stream)
                 (setStatus [this status] (reset! status-val status))
                 (getStatus [this] @status-val)
                 (setHeader [this header value] (swap! headers-map update-in [:set-header] assoc header value))
                 (addHeader [this header value] (swap! headers-map update-in [:added-headers header] conj value))
                 (setContentType [this content-type] (swap! headers-map assoc :content-type content-type))
                 (setContentLength [this content-length] (swap! headers-map assoc :content-length content-length))
                 (flushBuffer [this])
                 (isCommitted [this] false))
      meta-data)))

(defn test-servlet-response-status
  [test-servlet-response]
  (-> test-servlet-response
      meta
      :status
      deref))

(defn test-servlet-response-body
  [test-servlet-response]
  (let [^ByteArrayOutputStream baos (-> test-servlet-response
                                        meta
                                        :output-stream)]
    (.flush baos)
    (.close baos)
    (.toString baos "UTF-8")))

(defn test-servlet-response-headers
  [test-servlet-response]
  (-> test-servlet-response
      meta
      :headers-map
      deref))

(defn servlet-response-for
  "Return a ring response map for an HTTP request of type `verb`
  against url `url`, when applied to interceptor-service-fn. Useful
  for integration testing pedestal applications and getting all
  relevant middlewares invoked, including ones which integrate with
  the servlet infrastructure."
  [interceptor-service-fn verb url & args]
  (let [servlet (test-servlet interceptor-service-fn)
        servlet-request (apply test-servlet-request verb url args)
        servlet-response (test-servlet-response)]
    (.service servlet servlet-request servlet-response)
    {:status (test-servlet-response-status servlet-response)
     :body (test-servlet-response-body servlet-response)
     :headers (test-servlet-response-headers servlet-response)}))

(defn response-for
  "Return a ring response map for an HTTP request of type `verb`
  against url `url`, when applied to interceptor-service-fn. Useful
  for integration testing pedestal applications and getting all
  relevant middlewares invoked, including ones which integrate with
  the servlet infrastructure.
  Options:

  :body : An optional string that is the request body."
  [interceptor-service-fn verb url & options]
  (let [servlet-resp (apply servlet-response-for interceptor-service-fn verb url options)]
    (update-in servlet-resp [:headers] #(merge (:set-headers %)
                                               (:add-headers %)
                                               (when-let [content-type (:content-type %)]
                                                 {"Content-Type" content-type})
                                               (when-let [content-length (:content-length %)]
                                                 {"Content-Length" content-length})))))