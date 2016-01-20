(ns lupapalvelu.tiedonohjaus
  (:require [sade.http :as http]
            [sade.env :as env]
            [clojure.core.memoize :as memo]
            [lupapalvelu.organization :as o]
            [lupapalvelu.action :as action]
            [monger.operators :refer :all]))

(defn- build-url [& path-parts]
  (apply str (env/value :toj :host) path-parts))

(defn- get-tos-functions-from-toj [organization-id]
  (if (:permanent-archive-enabled (o/get-organization organization-id))
    (try
      (let [url (build-url "/tiedonohjaus/api/org/" organization-id "/asiat")
            response (http/get url {:as :json
                                    :throw-exceptions false})]
        (if (= 200 (:status response))
          (:body response)
          []))
      (catch Exception _
        []))
    []))

(def available-tos-functions
  (memo/ttl get-tos-functions-from-toj
            :ttl/threshold 10000))

(defn- get-metadata-for-document-from-toj [organization tos-function document-type]
  (if (and organization tos-function document-type)
    (try
      (let [doc-id (if (map? document-type) (str (name (:type-group document-type)) "." (name (:type-id document-type))) document-type)
            url (build-url "/tiedonohjaus/api/org/" organization "/asiat/" tos-function "/document/" doc-id)
            response (http/get url {:as :json
                                    :throw-exceptions false})]
        (if (= 200 (:status response))
          (:body response)
          {}))
      (catch Exception _
        {}))
    {}))

(def metadata-for-document
  (memo/ttl get-metadata-for-document-from-toj
            :ttl/threshold 10000))

(defn- get-metadata-for-process-from-toj [organization tos-function]
  (if (and organization tos-function)
    (try
      (let [url (build-url "/tiedonohjaus/api/org/" organization "/asiat/" tos-function)
            response (http/get url {:as :json
                                    :throw-exceptions false})]
        (if (= 200 (:status response))
          (:body response)
          {}))
      (catch Exception _
        {}))
    {}))

(def metadata-for-process
  (memo/ttl get-metadata-for-process-from-toj
            :ttl/threshold 10000))

(defn document-with-updated-metadata [{:keys [metadata] :as document} organization tos-function & [type]]
  (let [document-type (or type (:type document))
        existing-tila (:tila metadata)
        existing-nakyvyys (:nakyvyys metadata)
        new-metadata (metadata-for-document organization tos-function document-type)
        processed-metadata (cond-> new-metadata
                                   existing-tila (assoc :tila (keyword existing-tila))
                                   (and (not (:nakyvyys new-metadata)) existing-nakyvyys) (assoc :nakyvyys existing-nakyvyys))]
    (assoc document :metadata processed-metadata)))

(defn- get-tos-toimenpide-for-application-state-from-toj [organization tos-function state]
  (if (and organization tos-function state)
    (try
      (let [url (build-url "/tiedonohjaus/api/org/" organization "/asiat/" tos-function "/toimenpide-for-state/" state)
            response (http/get url {:as :json
                                    :throw-exceptions false})]
        (if (= 200 (:status response))
          (:body response)
          {}))
      (catch Exception _
        {}))
    {}))

(def toimenpide-for-state
  (memo/ttl get-tos-toimenpide-for-application-state-from-toj
    :ttl/threshold 10000))

(defn- full-name [{:keys [lastName firstName]}]
  (str lastName " " firstName))

(defn- get-documents-from-application [application]
  [{:type :hakemus
    :category :document
    :ts (:created application)
    :user (:applicant application)}])

(defn- get-attachments-from-application [application]
  (reduce (fn [acc attachment]
            (if-let [versions (seq (:versions attachment))]
              (->> versions
                   (map (fn [ver]
                          {:type (:type attachment)
                           :category :attachment
                           :version (:version ver)
                           :ts (:created ver)
                           :contents (:contents attachment)
                           :user (full-name (:user ver))}))
                  (concat acc))
              acc))
    []
    (:attachments application)))

(defn generate-case-file-data [application]
  (let [documents (get-documents-from-application application)
        attachments (get-attachments-from-application application)
        all-docs (sort-by :ts (concat documents attachments))]
    (map (fn [[{:keys [state ts user]} next]]
           (let [api-response (toimenpide-for-state (:organization application) (:tosFunction application) state)
                 action-name (or (:name api-response) "Ei asetettu tiedonohjaussuunnitelmassa")]
             {:action action-name
              :start ts
              :user (full-name user)
              :documents (filter (fn [{doc-ts :ts}]
                                   (and (>= doc-ts ts) (or (nil? next) (< doc-ts (:ts next)))))
                           all-docs)}))
      (partition 2 1 nil (:history application)))))

(defn- change-document-metadata-state [{:keys [metadata] :as doc} from-state to-state now]
  (if (= from-state (keyword (:tila metadata)))
    (-> (assoc-in doc [:metadata :tila] to-state)
        (assoc :modified now))
    doc))

(defn change-app-and-attachments-metadata-state! [{:keys [created application] :as command} from-state to-state]
  (when (seq (:metadata application))
    (let [{{new-tila :tila} :metadata} (change-document-metadata-state application from-state to-state created)
          updated-attachments (map #(change-document-metadata-state % from-state to-state created) (:attachments application))]
      (action/update-application
        command
        {$set {:modified created
               :metadata.tila new-tila
               :attachments updated-attachments}}))))

(defn change-attachment-metadata-state! [application now attachment-id from-state to-state]
  (let [attachment (first (filter #(= (:id %) attachment-id) (:attachments application)))]
    (when (seq (:metadata attachment))
      (let [{{new-tila :tila} :metadata} (change-document-metadata-state attachment from-state to-state now)]
        (action/update-application
          (action/application->command application)
          {:attachments.id attachment-id}
          {$set {:modified now
                 :attachments.$.metadata.tila new-tila}})))))
