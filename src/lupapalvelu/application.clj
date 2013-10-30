(ns lupapalvelu.application
  (:require [taoensso.timbre :as timbre :refer [trace debug debugf info infof warn error fatal]]
            [clojure.string :refer [blank? join trim split]]
            [clj-time.core :refer [year]]
            [clj-time.local :refer [local-now]]
            [clj-time.format :as tf]
            [sade.http :as http]
            [monger.operators :refer :all]
            [monger.query :as query]
            [sade.env :as env]
            [sade.util :as util]
            [sade.strings :as ss]
            [sade.xml :as xml]
            [lupapalvelu.core :refer [ok fail fail! now]]
            [lupapalvelu.action :refer [defquery defcommand executed with-application update-application non-blank-parameters get-application-operation get-applicant-name without-system-keys]]
            [lupapalvelu.mongo :as mongo]
            [lupapalvelu.attachment :as attachment]
            [lupapalvelu.domain :as domain]
            [lupapalvelu.notifications :as notifications]
            [lupapalvelu.xml.krysp.reader :as krysp]
            [lupapalvelu.document.commands :as commands]
            [lupapalvelu.document.model :as model]
            [lupapalvelu.document.schemas :as schemas]
            [lupapalvelu.document.tools :as tools]
            [lupapalvelu.user :refer [with-user-by-email] :as user]
            [lupapalvelu.organization :as organization]
            [lupapalvelu.operations :as operations]
            [lupapalvelu.permit :as permit]
            [lupapalvelu.xml.krysp.application-as-krysp-to-backing-system :as mapping-to-krysp]
            [lupapalvelu.ktj :as ktj]
            [lupapalvelu.neighbors :as neighbors]
            [lupapalvelu.open-inforequest :as open-inforequest])
  (:import [java.net URL]))

;; Notificator

(defn notify [notification]
  (fn [command status]
    (notifications/notify! notification command)))

;; Validators

(defn not-open-inforequest-user-validator [{user :user} _]
  (when (:oir user)
    (fail :error.not-allowed-for-oir)))

(defn- property-id? [^String s]
  (and s (re-matches #"^[0-9]{14}$" s)))

(defn property-id-parameters [params command]
  (when-let [invalid (seq (filter #(not (property-id? (get-in command [:data %]))) params))]
    (info "invalid property id parameters:" (join ", " invalid))
    (fail :error.invalid-property-id :parameters (vec invalid))))

(defn- validate-owner-or-writer
  "Validator: current user must be owner or writer.
   To be used in commands' :validators vector."
  [command application]
  (when-not (domain/owner-or-writer? application (-> command :user :id))
    (fail :error.unauthorized)))

(defn- validate-x [{{:keys [x]} :data}]
  (when (and x (not (< 10000 (util/->double x) 800000)))
    (fail :error.illegal-coordinates)))

(defn- validate-y [{{:keys [y]} :data}]
  (when (and y (not (<= 6610000 (util/->double y) 7779999)))
    (fail :error.illegal-coordinates)))

(defn count-unseen-comment [user app]
  (let [last-seen (get-in app [:_comments-seen-by (keyword (:id user))] 0)]
    (count (filter (fn [comment]
                     (and (> (:created comment) last-seen)
                          (not= (get-in comment [:user :id]) (:id user))
                          (not (blank? (:text comment)))))
                   (:comments app)))))

(defn count-unseen-statements [user app]
  (if-not (:infoRequest app)
    (let [last-seen (get-in app [:_statements-seen-by (keyword (:id user))] 0)]
      (count (filter (fn [statement]
                       (and (> (or (:given statement) 0) last-seen)
                            (not= (ss/lower-case (get-in statement [:person :email])) (ss/lower-case (:email user)))))
                     (:statements app))))
    0))

(defn count-unseen-verdicts [user app]
  (if (and (= (:role user) "applicant") (not (:infoRequest app)))
    (let [last-seen (get-in app [:_verdicts-seen-by (keyword (:id user))] 0)]
      (count (filter (fn [verdict] (> (or (:timestamp verdict) 0) last-seen)) (:verdicts app))))
    0))

(defn count-attachments-requiring-action [user app]
  (if-not (:infoRequest app)
    (let [count-attachments (fn [state] (count (filter #(and (= (:state %) state) (seq (:versions %))) (:attachments app))))]
      (case (keyword (:role user))
        :applicant (count-attachments "requires_user_action")
        :authority (count-attachments "requires_authority_action")
        0))
    0))

(defn count-document-modifications-per-doc [user app]
  (if (and (env/feature? :docIndicators) (= (:role user) "authority") (not (:infoRequest app)))
    (into {} (map (fn [doc] [(:id doc) (model/modifications-since-approvals doc)]) (:documents app)))
    {}))


(defn count-document-modifications [user app]
  (if (and (env/feature? :docIndicators) (= (:role user) "authority") (not (:infoRequest app)))
    (reduce + 0 (vals (:documentModificationsPerDoc app)))
    0))

(defn indicator-sum [_ app]
  (reduce + (map (fn [[k v]] (if (#{:documentModifications :unseenStatements :unseenVerdicts :attachmentsRequiringAction} k) v 0)) app)))

(def meta-fields [{:field :applicant :fn get-applicant-name}
                  {:field :neighbors :fn neighbors/normalize-neighbors}
                  {:field :documentModificationsPerDoc :fn count-document-modifications-per-doc}
                  {:field :documentModifications :fn count-document-modifications}
                  {:field :unseenComments :fn count-unseen-comment}
                  {:field :unseenStatements :fn count-unseen-statements}
                  {:field :unseenVerdicts :fn count-unseen-verdicts}
                  {:field :attachmentsRequiringAction :fn count-attachments-requiring-action}
                  {:field :indicators :fn indicator-sum}])

(defn with-meta-fields [user app]
  (reduce (fn [app {field :field f :fn}] (assoc app field (f user app))) app meta-fields))

;;
;; Query application:
;;

(defn- app-post-processor [user]
  (comp without-system-keys (partial with-meta-fields user)))

(defquery applications {:authenticated true :verified true} [{user :user}]
  (ok :applications (map (app-post-processor user) (mongo/select :applications (domain/application-query-for user)))))

(defn find-authorities-in-applications-organization [app]
  (mongo/select :users {:organizations (:organization app) :role "authority" :enabled true} {:firstName 1 :lastName 1}))

(defquery application
  {:authenticated true
   :parameters [:id]}
  [{app :application user :user}]
  (if app
    (ok :application ((app-post-processor user) app)
        :authorities (find-authorities-in-applications-organization app)
        :permitSubtypes (permit/permit-subtypes (:permitType app)))
    (fail :error.not-found)))

;; Gets an array of application ids and returns a map for each application that contains the
;; application id and the authorities in that organization.
(defquery authorities-in-applications-organization
  {:parameters [:id]
   :authenticated true}
  [{app :application}]
  (ok :authorityInfo (find-authorities-in-applications-organization app)))

(defn filter-repeating-party-docs [schema-version schema-names]
  (let [schemas (schemas/get-schemas schema-version)]
  (filter
      (fn [schema-name]
        (let [schema-info (get-in schemas [schema-name :info])]
          (and (:repeating schema-info) (= (:type schema-info) :party))))
      schema-names)))

(def ktj-format (tf/formatter "yyyyMMdd"))
(def output-format (tf/formatter "dd.MM.yyyy"))

(defn- autofill-rakennuspaikka [application time]
   (when (and (= "R" (:permitType application)) (not (:infoRequest application)))
     (when-let [rakennuspaikka (domain/get-document-by-name application "rakennuspaikka")]
       (when-let [ktj-tiedot (ktj/rekisteritiedot-xml (:propertyId application))]
         (let [updates [[[:kiinteisto :tilanNimi]        (or (:nimi ktj-tiedot) "")]
                        [[:kiinteisto :maapintaala]      (or (:maapintaala ktj-tiedot) "")]
                        [[:kiinteisto :vesipintaala]     (or (:vesipintaala ktj-tiedot) "")]
                        [[:kiinteisto :rekisterointipvm] (or (try
                                                               (tf/unparse output-format (tf/parse ktj-format (:rekisterointipvm ktj-tiedot)))
                                                               (catch Exception e (:rekisterointipvm ktj-tiedot))) "")]]]
           (commands/persist-model-updates
             (:id application)
             rakennuspaikka
             updates
             time))))))

(defquery party-document-names
  {:parameters [:id]
   :authenticated true}
  [command]
  (with-application command
    (fn [application]
      (let [documents (:documents application)
            initialOp (:name (first (:operations application)))
            original-schema-names (:required ((keyword initialOp) operations/operations))
            original-party-documents (filter-repeating-party-docs (:schema-version application) original-schema-names)]
        (ok :partyDocumentNames (conj original-party-documents "hakija"))))))

;;
;; Invites
;;

(defquery invites
  {:authenticated true
   :verified true}
  [{{:keys [id]} :user}]
  (let [filter     {:auth {$elemMatch {:invite.user.id id}}}
        projection (assoc filter :_id 0)
        data       (mongo/select :applications filter projection)
        invites    (map :invite (mapcat :auth data))]
    (ok :invites invites)))

(defcommand invite
  {:parameters [id email title text documentName documentId path]
   :input-validators [(partial non-blank-parameters [:email :documentName :documentId])]
   :roles      [:applicant :authority]
   :on-success (notify "invite")
   :verified   true}
  [{:keys [created user application]}]
  (let [email (ss/lower-case email)]
    (if (domain/invited? application email)
      (fail :invite.already-invited)
      (let [invited (user/get-or-create-user-by-email email)
            invite  {:title        title
                     :application  id
                     :text         text
                     :path         path
                     :documentName documentName
                     :documentId   documentId
                     :created      created
                     :email        email
                     :user         (user/summary invited)
                     :inviter      (user/summary user)}
            writer  (user/user-in-role invited :writer)
            auth    (assoc writer :invite invite)]
        (if (domain/has-auth? application (:id invited))
          (fail :invite.already-has-auth)
          (mongo/update
            :applications
            {:_id id
             :auth {$not {$elemMatch {:invite.user.username email}}}}
            {$push {:auth auth}}))))))

(defcommand approve-invite
  {:parameters [id]
   :roles      [:applicant]
   :verified   true}
  [{user :user application :application :as command}]
  (when-let [my-invite (domain/invite application (:email user))]
    (executed "set-user-to-document"
      (-> command
        (assoc-in [:data :documentId] (:documentId my-invite))
        (assoc-in [:data :path]       (:path my-invite))
        (assoc-in [:data :userId]     (:id user))))
    (mongo/update :applications
      {:_id id :auth {$elemMatch {:invite.user.id (:id user)}}}
      {$set  {:auth.$ (user/user-in-role user :writer)}})))

(defcommand remove-invite
  {:parameters [id email]
   :roles      [:applicant :authority]
   :validators [validate-owner-or-writer]}
  [_]
  (let [email (ss/lower-case email)]
    (with-user-by-email email
      (mongo/update-by-id :applications id
        {$pull {:auth {$and [{:username email}
                             {:type {$ne :owner}}]}}}))))

(defcommand remove-auth
  {:parameters [:id email]
   :roles      [:applicant :authority]}
  [command]
  (update-application command
    {$pull {:auth {$and [{:username (ss/lower-case email)}
                         {:type {$ne :owner}}]}}}))

(defn applicant-cant-set-to [{{:keys [to]} :data user :user} _]
  (when (and to (not (user/authority? user)))
    (fail :error.to-settable-only-by-authority)))

(defquery can-target-comment-to-authority {:roles [:authority]
                                           :validators  [not-open-inforequest-user-validator]})

(defcommand add-comment
  {:parameters [:id :text :target]
   :roles      [:applicant :authority]
   :validators [applicant-cant-set-to]
   :on-success  (notify "new-comment")}
  [{{:keys [text target to mark-answered] :or {mark-answered true}} :data :keys [user created] :as command}]
  (with-application command
    (fn [{:keys [id state] :as application}]
      (let [to-user   (and to (or (user/get-user-by-id to)
                                  (fail! :to-is-not-id-of-any-user-in-system)))
            from-user (user/summary user)]
        (update-application command
          {$set  {:modified created}
           $push {:comments {:text    text
                             :target  target
                             :created created
                             :to      (user/summary to-user)
                             :user    from-user}}})

        (condp = (keyword state)

          ;; LUPA-XYZ (was: open-application)
          :draft  (when (not (blank? text))
                    (update-application command
                      {$set {:modified created
                             :state    :open
                             :opened   created}}))

          ;; LUPA-371, LUPA-745
          :info (when (and mark-answered (user/authority? user))
                  (update-application command
                    {$set {:state    :answered
                           :modified created}}))

          ;; LUPA-371 (was: mark-inforequest-answered)
          :answered (when (user/applicant? user)
                      (update-application command
                        {$set {:state :info
                               :modified created}}))

          nil)

        ;; LUPA-407
        (when to-user
          (notifications/send-notifications-on-new-targetted-comment! application (:email to-user) (env/value :host)))))))

(defcommand mark-seen
  {:parameters [:id :type]
   :input-validators [(fn [{{type :type} :data}] (when-not (#{"comments" "statements" "verdicts"} type) (fail :error.unknown-type)))]
   :authenticated true}
  [{:keys [data user created] :as command}]
  (update-application command {$set {(str "_" (:type data) "-seen-by." (:id user)) created}}))

(defcommand set-user-to-document
  {:parameters [id documentId userId path]
   :authenticated true}
  [{:keys [user created application] :as command}]
  (let [document     (domain/get-document-by-id application documentId)
        schema-name  (get-in document [:schema-info :name])
        schema       (schemas/get-schema (:schema-version application) schema-name)
        subject      (user/get-user-by-id userId)
        with-hetu    (and
                       (domain/has-hetu? (:body schema) [path])
                       (user/same-user? user subject))
        person       (tools/unwrapped (domain/->henkilo subject :with-hetu with-hetu))
        model        (if-not (blank? path)
                       (assoc-in {} (map keyword (split path #"\.")) person)
                       person)
        updates      (tools/path-vals model)]
    (when-not document (fail! :error.document-not-found))
    (when-not schema (fail! :error.schema-not-found))
        (debugf "merging user %s with best effort into %s %s" model schema-name documentId)
    (commands/persist-model-updates id document updates created)))

;;
;; Assign
;;

(defcommand assign-to-me
  {:parameters [:id]
   :roles      [:authority]}
  [{user :user :as command}]
  (update-application command
    {$set {:authority (user/summary user)}}))

(defcommand assign-application
  {:parameters  [:id assigneeId]
   :validators  [not-open-inforequest-user-validator]
   :roles       [:authority]}
  [{user :user :as command}]
  (let [assignee (mongo/select-one :users {:_id assigneeId :enabled true})]
    (if (or assignee (nil? assigneeId))
      (update-application command
                          (if assignee
                            {$set   {:authority (user/summary assignee)}}
                            {$unset {:authority ""}}))
      (fail "error.user.not.found" :id assigneeId))))

;;
;;
;;

(defcommand cancel-application
  {:parameters [:id]
   :roles      [:applicant]
   :on-success (notify "state-change")
   :states     [:draft :info :open :submitted]}
  [{:keys [created] :as command}]
  (update-application command
    {$set {:modified  created
           :state     :canceled}}))

(defcommand request-for-complement
  {:parameters [:id]
   :roles      [:authority]
   :on-success (notify "state-change")
   :states     [:sent]}
  [{:keys [created] :as command}]
  (update-application command
    {$set {:modified  created
           :state :complement-needed}}))

(defcommand approve-application
  {:parameters [:id lang]
   :roles      [:authority]
   :on-success (notify "state-change")
   :states     [:submitted :complement-needed]}
  [command]
  (with-application command
    (fn [application]
      (let [application-id (:id application)
            submitted-application (mongo/by-id :submitted-applications (:id application))
            organization (mongo/by-id :organizations (:organization application))]
        (if (nil? (:authority application))
          (executed "assign-to-me" command))
        (try (mapping-to-krysp/save-application-as-krysp application lang submitted-application organization)
          (mongo/update
            :applications {:_id (:id application) :state {$in ["submitted" "complement-needed"]}}
            {$set {:state :sent}})
          (catch org.xml.sax.SAXParseException e
            (info e "Invalid KRYSM XML message")
            (fail (.getMessage e))))))))

(defcommand submit-application
  {:parameters [:id]
   :roles      [:applicant :authority]
   :states     [:draft :info :open :complement-needed]
   :on-success (notify "state-change")
   :validators [validate-owner-or-writer]}
  [{:keys [created] :as command}]
  (with-application command
    (fn [{:keys [id opened] :as application}]
      (mongo/update
        :applications
        {:_id id}
        {$set {:state     :submitted
               :opened    (or opened created)
               :submitted created}})
      (try
        (mongo/insert
          :submitted-applications
          (assoc (dissoc application :id) :_id id))
        (catch com.mongodb.MongoException$DuplicateKey e
          ; This is ok. Only the first submit is saved.
            )))))

(defcommand refresh-ktj
  {:parameters [:id]
   :roles      [:authority]
   :states     [:draft :open :submitted :complement-needed]
   :validators [validate-owner-or-writer]}
  [{:keys [application]}]
  (try (autofill-rakennuspaikka application (now))
    (catch Exception e (error e "KTJ data was not updated"))))

(defcommand save-application-shape
  {:parameters [:id shape]
   :roles      [:applicant :authority]
   :states     [:draft :open :submitted :complement-needed :info]}
  [command]
  (update-application command
    {$set {:shapes [shape]}}))

(defn make-attachments [created operation organization-id & {:keys [target]}]
  (let [organization (organization/get-organization organization-id)]
    (for [[type-group type-id] (organization/get-organization-attachments-for-operation organization operation)]
      (attachment/make-attachment created target false operation {:type-group type-group :type-id type-id}))))

(defn- schema-data-to-body [schema-data application]
  (reduce
    (fn [body [data-path data-value]]
      (let [path (if (= :value (last data-path)) data-path (conj (vec data-path) :value))
            val (if (fn? data-value) (data-value application) data-value)]
        ; FIXME: why not assoc-in?
        (update-in body path (constantly val))))
    {} schema-data))

;; TODO: permit-type splitting.
(defn- make-documents [user created op application]
  (let [op-info               (operations/operations (keyword (:name op)))
        existing-documents    (:documents application)
        permit-type           (keyword (permit/permit-type application))
        schema-version        (:schema-version application)
        make                  (fn [schema-name] {:id (mongo/create-id)
                                                 :schema-info (:info (schemas/get-schema schema-version schema-name))
                                                 :created created
                                                 :data (tools/timestamped
                                                         (if (= schema-name (:schema op-info))
                                                           (schema-data-to-body (:schema-data op-info) application)
                                                           {})
                                                         created)})
        existing-schema-names (set (map (comp :name :schema-info) existing-documents))
        required-schema-names (remove existing-schema-names (:required op-info))
        required-docs         (map make required-schema-names)
        op-schema-name        (:schema op-info)
        ;;The merge below: If :removable is set manually in schema's info, do not override it to true.
        op-doc                (update-in (make op-schema-name) [:schema-info] #(merge {:op op :removable true} %))
        new-docs              (cons op-doc required-docs)]
    (if-not user
      new-docs
      (let [hakija (condp = permit-type
                     :YA (assoc-in (make "hakija-ya") [:data :_selected :value] "yritys")
                         (assoc-in (make "hakija") [:data :_selected :value] "henkilo"))
            hakija (assoc-in hakija [:data :henkilo] (tools/timestamped (domain/->henkilo user :with-hetu true) created))
            hakija (assoc-in hakija [:data :yritys]  (tools/timestamped (domain/->yritys user) created))]
        (conj new-docs hakija)))))

(defn- ->location [x y]
  {:x (util/->double x) :y (util/->double y)})

(defn- make-application-id [municipality]
  (let [year           (str (year (local-now)))
        sequence-name  (str "applications-" municipality "-" year)
        counter        (format "%05d" (mongo/get-next-sequence-value sequence-name))]
    (str "LP-" municipality "-" year "-" counter)))

(defn- make-op [op-name created]
  {:id (mongo/create-id)
   :name (keyword op-name)
   :created created})

(defn user-is-authority-in-organization? [user-id organization-id]
  (mongo/any? :users {$and [{:organizations organization-id} {:_id user-id}]}))

(defn- operation-validator [{{operation :operation} :data}]
  (when-not (operations/operations (keyword operation)) (fail :error.unknown-type)))

;; TODO: separate methods for inforequests & applications for clarity.
(defcommand create-application
  {:parameters [:operation :x :y :address :propertyId :municipality]
   :roles      [:applicant :authority]
   :input-validators [(partial non-blank-parameters [:operation :address :municipality])
                      (partial property-id-parameters [:propertyId])
                      operation-validator]}
  [{{:keys [operation x y address propertyId municipality infoRequest messages]} :data :keys [user created] :as command}]
  (let [permit-type       (operations/permit-type-of-operation operation)
        organization      (organization/resolve-organization municipality permit-type)
        organization-id   (:id organization)
        info-request?     (boolean infoRequest)
        open-inforequest? (and info-request? (:open-inforequest organization))]
    (when-not (or (user/applicant? user) (user-is-authority-in-organization? (:id user) organization-id))
      (fail! :error.unauthorized))
    (when-not organization-id
      (fail! :error.missing-organization :municipality municipality :permit-type permit-type :operation operation))
    (if info-request?
      (when-not (:inforequest-enabled organization)
        (fail! :error.inforequests-disabled))
      (when-not (:new-application-enabled organization)
        (fail! :error.new-applications-disabled)))
    (let [id            (make-application-id municipality)
          owner         (user/user-in-role user :owner :type :owner)
          op            (make-op operation created)
          state         (cond
                          info-request?              :info
                          (user/authority? user) :open
                          :else                      :draft)
          make-comment  (partial assoc {:target {:type "application"}
                                        :created created
                                        :user (user/summary user)} :text)
          application   {:id               id
                         :created          created
                         :opened           (when (#{:open :info} state) created)
                         :modified         created
                         :permitType       permit-type
                         :permitSubtype (first (permit/permit-subtypes permit-type))
                         :infoRequest      info-request?
                         :openInfoRequest  open-inforequest?
                         :operations       [op]
                         :state            state
                         :municipality     municipality
                         :location         (->location x y)
                         :organization     organization-id
                         :address          address
                         :propertyId       propertyId
                         :title            address
                         :auth             [owner]
                         :comments         (map make-comment messages)
                         :schema-version   (schemas/get-latest-schema-version)}
          application   (merge application
                          (if info-request?
                            {:attachments            []
                             :allowedAttachmentTypes [[:muut [:muu]]]
                             :documents              []}
                            {:attachments            (make-attachments created op organization-id)
                             :allowedAttachmentTypes (attachment/get-attachment-types-by-permit-type permit-type)
                             :documents              (make-documents user created op application)}))

          application   (domain/set-software-version application)]

      (mongo/insert :applications application)
      (when open-inforequest?
        (open-inforequest/new-open-inforequest! application))
      (try
        (autofill-rakennuspaikka application created)
        (catch Exception e (error e "KTJ data was not updated")))
      (ok :id id))))

(defcommand add-operation
  {:parameters [id operation]
   :roles      [:applicant :authority]
   :states     [:draft :open :complement-needed :submitted]
   :input-validators [operation-validator]
   :validators [(permit/validate-permit-type-is permit/R)]}
  [{:keys [created] :as command}]
  (with-application command
    (fn [application]
      (let [op-id      (mongo/create-id)
            op         (make-op operation created)
            new-docs   (make-documents nil created op application)]
        (mongo/update-by-id :applications id {$push {:operations op}
                                              $pushAll {:documents new-docs
                                                        :attachments (make-attachments created op (:organization application))}
                                              $set {:modified created}})))))

(defcommand change-permit-sub-type
  {:parameters [id permitSubtype]
   :roles      [:applicant :authority]
   :states     [:draft :open :complement-needed :submitted]
   :validators [permit/validate-permit-has-subtypes]}
  [{:keys [application created]}]
  (if-let [validation-errors (permit/is-valid-subtype (keyword permitSubtype) application)]
    validation-errors
    (mongo/update-by-id :applications id {$set {:permitSubtype permitSubtype
                                                :modified      created}})))

(defcommand change-location
  {:parameters [id x y address propertyId]
   :roles      [:applicant :authority]
   :states     [:draft :info :answered :open :complement-needed :submitted]
   :input-validators [(partial non-blank-parameters [:address])
                      (partial property-id-parameters [:propertyId])
                      validate-x validate-y]}
  [{:keys [created application]}]
  (if (= (:municipality application) (organization/municipality-by-propertyId propertyId))
    (do
      (mongo/update-by-id :applications id {$set {:location      (->location x y)
                                                  :address       (trim address)
                                                  :propertyId    propertyId
                                                  :title         (trim address)
                                                  :modified      created}})
      (try (autofill-rakennuspaikka (mongo/by-id :applications id) (now))
        (catch Exception e (error e "KTJ data was not updated."))))
    (fail :error.property-in-other-muinicipality)))

(defn- validate-new-applications-enabled [command {:keys [organization]}]
  (let [org (mongo/by-id :organizations organization {:new-application-enabled 1})]
    (if (= (:new-application-enabled org) true)
      nil
      (fail :error.new-applications.disabled))))

(defcommand convert-to-application
  {:parameters [id]
   :roles      [:applicant]
   :states     [:draft :info :answered]
   :validators [validate-new-applications-enabled]}
  [{:keys [user created application] :as command}]
  (let [op          (first (:operations application))
        permit-type (permit/permit-type application)]
    (mongo/update-by-id :applications id
                        {$set {:infoRequest false
                               :state :open
                               :allowedAttachmentTypes (attachment/get-attachment-types-by-permit-type permit-type)
                               :documents (make-documents user created op application)
                               :modified created}
           $pushAll {:attachments (make-attachments created op (:organization application))}})))

;;
;; Verdicts
;;

(defn- validate-status [{{:keys [status]} :data}]
  (when (or (< status 1) (> status 42))
    (fail :error.false.status.out.of.range.when.giving.verdict)))

(defcommand give-verdict
  {:parameters [id verdictId status name given official]
   :input-validators [validate-status]
   :states     [:submitted :complement-needed :sent]
   :on-success (notify "verdict")
   :roles      [:authority]}
  [{:keys [created] :as command}]
  (update-application command
    {$set {:modified created
           :state    :verdictGiven}
     $push {:verdicts (domain/->paatos
                        {:id verdictId      ; Kuntalupatunnus
                         :timestamp created ; tekninen Lupapisteen aikaleima
                         :name name         ; poytakirja[] / paatoksentekija
                         :given given       ; paivamaarat / antoPvm
                         :status status     ; poytakirja[] / paatoskoodi
                         :official official ; paivamaarat / lainvoimainenPvm
                         })}}))

(defn- get-verdicts-with-attachments [{:keys [id organization]} user timestamp]
  (let [legacy   (organization/get-legacy organization)
        xml      (krysp/application-xml legacy id)
        verdicts (krysp/->verdicts xml)]
    (map
      (fn [verdict]
        (assoc verdict
          :timestamp timestamp
          :paatokset (map
                       (fn [paatos]
                         (assoc paatos :poytakirjat
                           (map
                             (fn [pk]
                               (if-let [url (get-in pk [:liite :linkkiliitteeseen])]
                                 (let [file-name       (-> url (URL.) (.getPath) (ss/suffix "/"))
                                       resp            (http/get url :as :stream)
                                       content-length  (util/->int (get-in resp [:headers "content-length"] 0))
                                       urlhash         (digest/sha1 url)
                                       attachment-id   urlhash
                                       attachment-type {:type-group "muut" :type-id "muu"}
                                       target          {:type "verdict" :id urlhash}
                                       locked          true
                                       attachment-time (get-in pk [:liite :muokkausHetki] timestamp)]
                                   ; If the attachment-id, i.e., hash of the URL matches
                                   ; any old attachment, a new version will be added
                                   (attachment/attach-file! id file-name content-length (:body resp) attachment-id attachment-type target locked user attachment-time)
                                   (-> pk (assoc :urlHash urlhash) (dissoc :liite)))
                                 pk))
                             (:poytakirjat paatos))))
                       (:paatokset verdict))))
      verdicts)))

(defcommand check-for-verdict
  {:description "Fetches verdicts from municipality backend system.
                 If the command is run more than once, existing verdicts are
                 replaced by the new ones."
   :parameters [:id]
   :states     [:submitted :complement-needed :sent :verdictGiven] ; states reviewed 2013-09-17
   :roles      [:authority]
   :on-success  (notify     "verdict")}
  [{:keys [user created application] :as command}]
  (if-let [verdicts-with-attachments (seq (get-verdicts-with-attachments application user created))]
    (do (update-application command
      {$set {:verdicts verdicts-with-attachments
             :modified created
             :state    :verdictGiven}})
      (ok :verdictCount (count verdicts-with-attachments)))
    (fail :info.no-verdicts-found-from-backend)))

;;
;; krysp enrichment
;;

(defn add-value-metadata [m meta-data]
  (reduce (fn [r [k v]] (assoc r k (if (map? v) (add-value-metadata v meta-data) (assoc meta-data :value v)))) {} m))

(defcommand "merge-details-from-krysp"
  {:parameters [:id :documentId :buildingId]
   :roles      [:applicant :authority]}
  [{{:keys [id documentId buildingId]} :data created :created :as command}]
  (with-application command
    (fn [{:keys [organization propertyId] :as application}]
      (if-let [legacy (organization/get-legacy organization)]
        (let [document     (domain/get-document-by-id application documentId)
              kryspxml     (krysp/building-xml legacy propertyId)
              updates      (-> (or (krysp/->rakennuksen-tiedot kryspxml buildingId) {}) tools/unwrapped tools/path-vals)]
          (infof "merging data into %s %s" (get-in document [:schema-info :name]) (:id document))
          (commands/persist-model-updates id document updates created :source "krysp")
          (ok))
        (fail :no-legacy-available)))))

(defcommand get-building-info-from-legacy
  {:parameters [id]
   :roles      [:applicant :authority]}
  [command]
  (with-application command
    (fn [{:keys [organization propertyId] :as application}]
      (if-let [legacy   (organization/get-legacy organization)]
        (let [kryspxml  (krysp/building-xml legacy propertyId)
              buildings (krysp/->buildings kryspxml)]
          (ok :data buildings))
        (fail :no-legacy-available)))))

;;
;; Service point for jQuery dataTables:
;;

(def col-sources [(fn [app] (if (:infoRequest app) "inforequest" "application"))
                  (juxt :address :municipality)
                  get-application-operation
                  :applicant
                  :submitted
                  :indicators
                  :unseenComments
                  :modified
                  :state
                  :authority])

(def order-by (assoc col-sources
                     0 :infoRequest
                     1 :address
                     2 nil
                     3 nil
                     5 nil
                     6 nil))

(def col-map (zipmap col-sources (map str (range))))

(defn add-field [application data [app-field data-field]]
  (assoc data data-field (app-field application)))

(defn make-row [application]
  (let [base {"id" (:_id application)
              "kind" (if (:infoRequest application) "inforequest" "application")}]
    (reduce (partial add-field application) base col-map)))

(defn make-query [query {:keys [filter-search filter-kind filter-state filter-user]}]
  (merge
    query
    (condp = filter-kind
      "applications" {:infoRequest false}
      "inforequests" {:infoRequest true}
      "both"         nil)
    (condp = filter-state
      "all"       {:state {$ne "canceled"}}
      "active"    {:state {$nin ["draft" "canceled" "answered" "verdictGiven"]}}
      "canceled"  {:state "canceled"})
    (when-not (contains? #{nil "0"} filter-user)
      {$or [{"auth.id" filter-user}
            {"authority.id" filter-user}]})
    (when-not (blank? filter-search)
      {:address {$regex filter-search $options "i"}})))

(defn make-sort [params]
  (let [col (get order-by (:iSortCol_0 params))
        dir (if (= "asc" (:sSortDir_0 params)) 1 -1)]
    (if col {col dir} {})))

(defn applications-for-user [user params]
  (println user)
  (println params)
  (let [user-query  (domain/basic-application-query-for user)
        user-total  (mongo/count :applications user-query)
        query       (make-query user-query params)
        query-total (mongo/count :applications query)
        skip        (params :iDisplayStart)
        limit       (params :iDisplayLength)
        apps        (query/with-collection "applications"
                      (query/find query)
                      (query/sort (make-sort params))
                      (query/skip skip)
                      (query/limit limit))
        rows        (map (comp make-row (partial with-meta-fields user)) apps)
        echo        (str (util/->int (str (params :sEcho))))] ; Prevent XSS
    {:aaData                rows
     :iTotalRecords         user-total
     :iTotalDisplayRecords  query-total
     :sEcho                 echo}))

(defcommand "applications-for-datatables"
  {:parameters [:params]
   :verified true}
  [{user :user {params :params} :data}]
  (ok :data (applications-for-user user params)))

;;
;; Query that returns number of applications or info-requests user has:
;;

(defquery applications-count
  {:parameters [kind]
   :authenticated true
   :verified true}
  [{:keys [user]}]
  (let [base-query (domain/application-query-for user)
        query (condp = kind
                "inforequests" (assoc base-query :infoRequest true)
                "applications" (assoc base-query :infoRequest false)
                "both"         base-query
                {:_id -1})]
    (ok :data (mongo/count :applications query))))
