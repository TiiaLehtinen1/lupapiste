(ns lupapalvelu.singlepage
  (:use [clojure.tools.logging]
        [clj-time.coerce :only [from-long to-date]]
        [lupapalvelu.components.ui-components :only [ui-components]])
  (:require [clojure.java.io :as io]
            [net.cgrand.enlive-html :as enlive]
            [sade.env :as env]
            [lupapalvelu.components.core :as c])
  (:import [java.io ByteArrayOutputStream ByteArrayInputStream]
           [java.util.zip GZIPOutputStream]
           [org.apache.commons.io IOUtils]
           [com.googlecode.htmlcompressor.compressor HtmlCompressor]
           [com.yahoo.platform.yui.compressor JavaScriptCompressor CssCompressor]
           [org.mozilla.javascript ErrorReporter EvaluatorException]))

(def utf8 (java.nio.charset.Charset/forName "UTF-8"))

(defn write-header [kind out n]
  (when (env/dev-mode?)
    (.write out (format "\n\n/*\n * %s\n */\n" n)))
  (when (= kind :js)
    (.write out "\n;\n\n"))
  out)

(def error-reporter
  (reify ErrorReporter
    (^void warning [this ^String message, ^String sourceName,
                    ^int line, ^String lineSource, ^int lineOffset]
      (warn message))
    (^void error [this ^String message, ^String sourceName,
                    ^int line, ^String lineSource, ^int lineOffset]
      (error message))
    (^EvaluatorException runtimeError [this ^String message, ^String sourceName,
                    ^int line, ^String lineSource, ^int lineOffset]
      (error message) (EvaluatorException. message))))

(defn- minified [kind ^java.io.Reader in ^java.io.Writer out]
  (cond
    (env/dev-mode?) (IOUtils/copy in out)
    (= kind :js) (let [c (JavaScriptCompressor. in error-reporter)]
                   ; no linebreaks, obfuscate locals, no verbose,
                   (.compress c out -1 true false
                     ; preserve semicolons, disable optimizations
                     true true))
    (= kind :css) (let [c (CssCompressor. in)]
                    ; no linebreaks
                    (.compress c out -1))))

(defn- fn-name [f]
  (-> f str (.replace \$ \/) (.split "@") first))

(defn compose-resource [kind component]
  (let [stream (ByteArrayOutputStream.)]
    (with-open [out (io/writer stream)]
      (doseq [src (c/get-resources ui-components kind component)]
        (if (fn? src)
          (.write (write-header kind out (str "fn: " (fn-name src))) (src))
          (with-open [in (-> src c/path io/resource io/input-stream io/reader)]
            (if (.contains src ".min.")
              (IOUtils/copy in (write-header kind out src))
              (minified kind in (write-header kind out src)))))))
    (.toByteArray stream)))

(defn parse-html-resource [c resource]
  (let [h (enlive/html-resource resource)]
    (assoc c
      :header (concat (:header c) (enlive/select h [:header]))
      :nav    (concat (:nav c)    (enlive/select h [:nav]))
      :footer (concat (:footer c) (enlive/select h [:footer]))
      :page   (concat (:page  c)  (enlive/select h [:section.page])))))

(defn- resource-url [component kind]
  (str (kind (:cdn env/config)) (name component) "." (name kind) "?b=" (:build-number env/buildinfo)))

(def ^:private buildinfo-summary
  (format "%s-%s %3$tF %3$tT [%4$s]"
          env/target-env
          (:build-number env/buildinfo)
          (to-date (from-long (:time env/buildinfo)))
          (name env/mode)))

(defn inject-content [t {:keys [header nav page footer]} component]
  (enlive/emit* (-> t
                  (enlive/transform [:body] (fn [e] (assoc-in e [:attrs :class] (name component))))
                  (enlive/transform [:header] (constantly (first header)))
                  (enlive/transform [:nav] (constantly (first nav)))
                  (enlive/transform [:section] (enlive/content page))
                  (enlive/transform [:footer] (constantly (first footer)))
                  (enlive/transform [:script] (fn [e] (if (= (-> e :attrs :src) "inject") (assoc-in e [:attrs :src] (resource-url component :js)) e)))
                  (enlive/transform [:link] (fn [e] (if (= (-> e :attrs :href) "inject") (assoc-in e [:attrs :href] (resource-url component :css)) e)))
                  (enlive/transform [:#buildinfo] (enlive/content buildinfo-summary)))))

(defn compose-html [component]
  (let [out (ByteArrayOutputStream.)]
    (doseq [element (inject-content
                      (enlive/html-resource (c/path "template.html"))
                      (reduce parse-html-resource {} (map (partial str (c/path)) (c/get-resources ui-components :html component)))
                      component)]
      (.write out (.getBytes element utf8)))
    (let [c (doto (HtmlCompressor.)
              (.setRemoveComments true)            ; if false keeps HTML comments (default is true)
              (.setRemoveIntertagSpaces true)      ; removes iter-tag whitespace characters
              (.setRemoveScriptAttributes true)    ; remove optional attributes from script tags
              (.setRemoveStyleAttributes true)     ; remove optional attributes from style tags
              (.setRemoveLinkAttributes true)      ; remove optional attributes from link tags
              (.setRemoveFormAttributes true)      ; remove optional attributes from form tags
              (.setSimpleBooleanAttributes true)   ; remove values from boolean tag attributes
              (.setRemoveJavaScriptProtocol true)  ; remove "javascript:" from inline event handlers
              (.setRemoveHttpProtocol true)        ; replace "http://" with "//" inside tag attributes
              (.setRemoveHttpsProtocol true)       ; replace "https://" with "//" inside tag attributes
              (.setRemoveSurroundingSpaces HtmlCompressor/BLOCK_TAGS_MAX)  ; remove spaces around provided tags
              (.setPreservePatterns [(re-pattern "<!--\\s*/?ko.*-->")]) ; preserve KnockoutJS comments
          )
          html (.toString out (.name utf8))
          compressed-html (.compress c html)
          saved (- (.length html) (.length compressed-html))]
      (debugf "Original HTML: %d - Compressed HTML: %d - Saving %d chars = %s%%"
              (.length html) (.length compressed-html) saved (/ (int (/ (* 10000 saved) (.length html))) 100.0))
      (.getBytes compressed-html utf8))))

(defn compose [kind component]
  (tracef "Compose %s%s" component kind)
  (if (= :html kind)
    (compose-html component)
    (compose-resource kind component)))
