(ns lupapalvelu.attachment-itest
  (:require [lupapalvelu.factlet :refer [facts*]]
            [lupapalvelu.attachment :refer :all]
            [lupapalvelu.itest-util :refer :all]
            [sade.util :as util]
            [midje.sweet :refer :all]
            [sade.env :as env]))

(apply-remote-minimal)

(defn- get-attachment-by-id [apikey application-id attachment-id]
  (get-attachment-info (query-application apikey application-id) attachment-id))

(defn- approve-attachment [application-id attachment-id]
  (command veikko :approve-attachment :id application-id :attachmentId attachment-id) => ok?
  (get-attachment-by-id veikko application-id attachment-id) => (in-state? "ok"))

(defn- reject-attachment [application-id attachment-id]
  (command veikko :reject-attachment :id application-id :attachmentId attachment-id) => ok?
  (get-attachment-by-id veikko application-id attachment-id) => (in-state? "requires_user_action"))

(facts "attachments"
  (let [{application-id :id :as response} (create-app pena :propertyId tampere-property-id :operation "kerrostalo-rivitalo")]

    response => ok?

    (comment-application pena application-id true) => ok?

    (facts "by default 4 attachments exist"
      (let [application (query-application pena application-id)
            op-id (-> application :primaryOperation :id)]
        (fact "counting all attachments"
          (count (:attachments application)) => 4)
        (fact "only pohjapiirros is related to operation 'kerrostalo-rivitalo'"
          (map :type (get-attachments-by-operation application op-id)) => [{:type-group "paapiirustus" :type-id "pohjapiirustus"}])
        (fact "the attachments have 'required', 'notNeeded' and 'requestedByAuthority' flags correctly set"
          (every? (fn [a]
                    (every? #{"required" "notNeeded" "requestedByAuthority"} a) => truthy
                    (:required a) => true
                    (:notNeeded a) => false
                    (:requestedByAuthority a) => false)
                  (:attachments application)) => truthy
          )))

    (let [resp (command veikko
                        :create-attachments
                        :id application-id
                        :attachmentTypes [{:type-group "paapiirustus" :type-id "asemapiirros"}
                                          {:type-group "paapiirustus" :type-id "pohjapiirustus"}])
          attachment-ids (:attachmentIds resp)]

      (fact "Veikko can create an attachment"
        (success resp) => true)

      (fact "Two attachments were created in one call"
        (fact (count attachment-ids) => 2))

      (fact "attachment has been saved to application"
        (get-attachment-by-id veikko application-id (first attachment-ids)) => (contains
                                                                                 {:type                 {:type-group "paapiirustus" :type-id "asemapiirros"}
                                                                                  :state                "requires_user_action"
                                                                                  :requestedByAuthority true
                                                                                  :versions             []})
        (get-attachment-by-id veikko application-id (second attachment-ids)) => (contains
                                                                                  {:type                 {:type-group "paapiirustus" :type-id "pohjapiirustus"}
                                                                                   :state                "requires_user_action"
                                                                                   :requestedByAuthority true
                                                                                   :versions             []}))

      (fact "uploading files"
        (let [application (query-application pena application-id)
              _ (upload-attachment-to-all-placeholders pena application)
              application (query-application pena application-id)]

          (facts "Each attachment has Pena's auth"
            (let [p-id pena-id]
              (doseq [{auth :auth} (:attachments application)]
                (fact "Pena as uploader"
                  (some #(when (#{p-id} (:id %)) (:role %)) auth) => "uploader"))))

          (fact "download all"
            (let [resp (raw pena "download-all-attachments" :id application-id)]
              resp => http200?
              (get-in resp [:headers "content-disposition"]) => "attachment;filename=\"liitteet.zip\"")
            (fact "p\u00e5 svenska"
              (get-in (raw pena "download-all-attachments" :id application-id :lang "sv") [:headers "content-disposition"])
              => "attachment;filename=\"bilagor.zip\""))

          (fact "pdf export"
            (raw pena "pdf-export" :id application-id) => http200?)

          (doseq [attachment-id (get-attachment-ids application)
                  :let [file-id (attachment-latest-file-id application attachment-id)]]

            (fact "view-attachment anonymously should not be possible"
              (raw nil "view-attachment" :attachment-id file-id) => http401?)

            (fact "view-attachment as pena should be possible"
              (raw pena "view-attachment" :attachment-id file-id) => http200?)

            (fact "download-attachment anonymously should not be possible"
              (raw nil "download-attachment" :attachment-id file-id) => http401?)

            (fact "download-attachment as pena should be possible"
              (raw pena "download-attachment" :attachment-id file-id) => http200?))))

      (fact "Veikko can approve attachment"
        (approve-attachment application-id (first attachment-ids)))

      (fact "Veikko can reject attachment"
        (reject-attachment application-id (first attachment-ids)))

      (fact "Pena submits the application"
        (command pena :submit-application :id application-id) => ok?
        (:state (query-application veikko application-id)) => "submitted")

      (fact "Veikko can still approve attachment"
        (approve-attachment application-id (first attachment-ids)))

      (fact "Veikko can still reject attachment"
        (reject-attachment application-id (first attachment-ids)))

      (fact "Pena signs attachments"
        (fact "meta" attachment-ids => seq)

        (fact "Signing fails if password is incorrect"
          (command pena :sign-attachments :id application-id :attachmentIds attachment-ids :password "not-pena") => (partial expected-failure? "error.password"))

        (fact "Signing succeeds if password is correct"
          (command pena :sign-attachments :id application-id :attachmentIds attachment-ids :password "pena") => ok?)

        (fact "Signature is set"
          (let [application (query-application pena application-id)
                attachments (get-attachments-infos application attachment-ids)]
            (doseq [{signatures :signatures latest :latestVersion} attachments]
              (count signatures) => 1
              (let [{:keys [user created version]} (first signatures)]
                (:username user) => "pena"
                (:id user) => pena-id
                (:firstName user) => "Pena"
                (:lastName user) => "Panaani"
                created => pos?
                version => (:version latest))))))


      (fact "Pena change attachment metadata"

        (fact "Pena can change operation"
          (command pena :set-attachment-meta :id application-id :attachmentId (first attachment-ids) :meta {:op {:id "foo" :name "bar"}}) => ok?)
        (fact "Pena can change contents"
          (command pena :set-attachment-meta :id application-id :attachmentId (first attachment-ids) :meta {:contents "foobart"}) => ok?)
        (fact "Pena can change size"
          (command pena :set-attachment-meta :id application-id :attachmentId (first attachment-ids) :meta {:size "A4"}) => ok?)
        (fact "Pena can change scale"
          (command pena :set-attachment-meta :id application-id :attachmentId (first attachment-ids) :meta {:scale "1:500"}) => ok?)

        (fact "Metadata is set"
          (let [application (query-application pena application-id)
                attachment (get-attachment-info application (first attachment-ids))
                op (:op attachment)
                contents (:contents attachment)
                size (:size attachment)
                scale (:scale attachment)]
            (:id op) => "foo"
            (:name op) => "bar"
            contents => "foobart"
            size => "A4"
            scale => "1:500")))

      (let [versioned-attachment (first (:attachments (query-application veikko application-id)))]
        (last-email) ; Inbox zero

        (fact "Meta"
          (get-in versioned-attachment [:latestVersion :version :major]) => 1
          (get-in versioned-attachment [:latestVersion :version :minor]) => 0)

        (fact "Veikko uploads a new version"
          (upload-attachment veikko application-id versioned-attachment true)
          (let [updated-attachment (get-attachment-by-id veikko application-id (:id versioned-attachment))]
            (get-in updated-attachment [:latestVersion :version :major]) => 1
            (get-in updated-attachment [:latestVersion :version :minor]) => 1

           (fact "upload has Veikko's auth"
             (get-in updated-attachment [:auth 1 :id]) => veikko-id)

            (fact "Pena receives email pointing to comment page"
              (let [emails (sent-emails)
                    email  (first emails)
                    pena-email  (email-for "pena")]
                (count emails) => 1
                email => (partial contains-application-link-with-tab? application-id "conversation" "applicant")
                (:to email) => (contains pena-email)))

            (fact "Delete version"
              (command veikko
                       :delete-attachment-version
                       :id application-id
                       :attachmentId (:id versioned-attachment)
                       :fileId (get-in updated-attachment [:latestVersion :fileId])
                       :originalFileId (get-in updated-attachment [:latestVersion :originalFileId])) => ok?
              (let [ver-del-attachment (get-attachment-by-id veikko application-id (:id versioned-attachment))]
                (get-in ver-del-attachment [:latestVersion :version :major]) => 1
                (get-in ver-del-attachment [:latestVersion :version :minor]) => 0))

            (fact "Applicant cannot delete attachment that is required"
              (command pena :delete-attachment :id application-id :attachmentId (:id versioned-attachment)) => (contains {:ok false :text "error.unauthorized"}))
           )))

      (let [versioned-attachment (first (:attachments (query-application pena application-id)))]
        (fact "Pena upload new version"
          (upload-attachment pena application-id versioned-attachment true))
        (fact "Pena signs the attachment version"
          (command pena :sign-attachments :id application-id :attachmentIds [(:id versioned-attachment)] :password "pena") => ok?)
        (let [signed-attachment (get-attachment-by-id pena application-id (:id versioned-attachment))]

          (fact "Pena has only one auth entry, although has many versions uploaded"
            (count (filter #(= (-> % :user :id) (id-for-key pena)) (:versions signed-attachment))) => 2
            (count (filter #(= (:id %) (id-for-key pena)) (:auth signed-attachment))) => 1)

          (fact "Attachment is signed"
            (count (:signatures signed-attachment)) => 1)

          (fact "Delete version and its signature"
            (command veikko
                       :delete-attachment-version
                       :id application-id
                       :attachmentId (:id versioned-attachment)
                       :fileId (get-in signed-attachment [:latestVersion :fileId])
                       :originalFileId (get-in signed-attachment [:latestVersion :originalFileId]))=> ok?
                     (fact (count (:signatures (get-attachment-by-id veikko application-id (:id versioned-attachment)))) => 0))

          (fact "Deleting the last version clears attachment auth"
                (let [attachment (get-attachment-by-id veikko application-id (:id versioned-attachment))]
                  (count (:versions attachment )) => 1
                  (command veikko
                       :delete-attachment-version
                       :id application-id
                       :attachmentId (:id attachment)
                       :fileId (get-in attachment [:latestVersion :fileId])
                       :originalFileId (get-in attachment [:latestVersion :originalFileId])) => ok?)
                (:auth (get-attachment-by-id veikko application-id (:id versioned-attachment))) => empty?)

          (fact "Authority deletes attachment"
            (command veikko :delete-attachment :id application-id :attachmentId (:id versioned-attachment)) => ok?
            (get-attachment-by-id veikko application-id (:id versioned-attachment)) => nil?))
          ))))

(facts* "Signing signs corrrect attachments"
  (let [{application-id :id :as response} (create-app pena :propertyId tampere-property-id :operation "kerrostalo-rivitalo")
        _ (comment-application pena application-id true) => ok? ; visible to Veikko
        application (query-application pena application-id)
        old-attachment (-> application :attachments last)
        old-id         (:id old-attachment)
        _ (upload-attachment-to-all-placeholders pena application)
        _ (count (:attachments application)) => 4
        resp       (command veikko
                            :create-attachments
                            :id application-id
                            :attachmentTypes [{:type-group "muut" :type-id "muu"}
                                              {:type-group "paapiirustus" :type-id "pohjapiirustus"}]) => ok?
        attachment-ids (:attachmentIds resp)
        hidden-id (first attachment-ids)
        visible-id (second attachment-ids)
        _ (upload-attachment veikko application-id {:id hidden-id :type {:type-group "muut" :type-id "muu"}} true)
        _ (upload-attachment pena   application-id {:id visible-id :type {:type-group "paapiirustus" :type-id "pohjapiirustus"}} true)
        _ (command veikko :set-attachment-visibility
                   :id application-id
                   :attachmentId hidden-id
                   :value "viranomainen") => ok?
        ]

    (fact "error if user doesn't have access to requested attachment ids"
      (command pena :sign-attachments :id application-id :attachmentIds [hidden-id] :password "pena") => (partial expected-failure? "error.unknown-attachment"))
    (fact "Signing works if some of the attachments are hidden for user"
      (command pena :sign-attachments :id application-id :attachmentIds [old-id visible-id] :password "pena") => ok?
      (let [{attachments :attachments} (query-application veikko application-id)
            hidden      (util/find-first #(= (:id %) hidden-id) attachments)
            visible     (util/find-first #(= (:id %) visible-id) attachments)
            old     (util/find-first #(= (:id %) old-id) attachments)]
        (fact "Hidden doesn't have signatures"
          (count (:signatures hidden)) => 0)
        (fact "Only visible and old have signatures"
          (map :id (filter #(seq (:signatures %)) attachments)) => (just [visible-id old-id] :in-any-order)
          (count (:signatures visible)) => 1
          (count (:signatures old)) => 1)))))

(facts* "Post-verdict attachments"
  (let [{application-id :id :as response} (create-app pena :propertyId sipoo-property-id :operation "kerrostalo-rivitalo")
        application (query-application pena application-id)
        _ (upload-attachment-to-all-placeholders pena application)
        _ (command pena :submit-application :id application-id)
        _ (command sonja :check-for-verdict :id application-id) => ok?
        application (query-application pena application-id)
        attachment1 (-> application :attachments first)]
    (:state application) => "verdictGiven"
    (count (:attachments application)) => 5
    (fact "Uploading versions to pre-verdict attachment is not possible"
      (upload-attachment pena application-id attachment1 false :filename "dev-resources/test-pdf.pdf"))
    (fact "Uploading new post-verdict attachment is possible"
      (upload-attachment pena application-id {:id "" :type {:type-group "selvitykset" :type-id "energiatodistus"}} true :filename "dev-resources/test-pdf.pdf"))

    (count (:attachments (query-application pena application-id))) => 6))

(fact "pdf works with YA-lupa"
  (let [{application-id :id :as response} (create-app pena :propertyId sipoo-property-id :operation "ya-katulupa-vesi-ja-viemarityot")
        application (query-application pena application-id)]
    response => ok?
    (:organization application) => "753-YA"
    pena => (allowed? :pdf-export :id application-id)
    (raw pena "pdf-export" :id application-id) => http200?))

(facts "Rotate PDF"
  (let [application (create-and-submit-application sonja :propertyId sipoo-property-id)
        application-id (:id application)
        attachment1 (first (:attachments application))
        attachment2 (last (:attachments application))]

    attachment1 =not=> attachment2

    (upload-attachment sonja application-id attachment1 true :filename "dev-resources/test-pdf.pdf")
    (upload-attachment sonja application-id attachment2 true :filename "dev-resources/test-attachment.txt")

    (fact "Can rotate PDF"
      (command sonja :rotate-pdf :id application-id :attachmentId (:id attachment1) :rotation 90) => ok?)

    (fact "Can not rotate PDF 0 degrees"
      (command sonja :rotate-pdf :id application-id :attachmentId (:id attachment1) :rotation 0) => fail?)

    (fact "Can not rotate txt"
      (command sonja :rotate-pdf :id application-id :attachmentId (:id attachment2) :rotation 90) => fail?)))

(facts "Rotate PDF - versions and files"
  (let [application (create-and-submit-application sonja :propertyId sipoo-property-id)
        application-id (:id application)
        attachment (first (:attachments application))
        attachment-id (:id attachment)]

    (upload-attachment sonja application-id attachment true :filename "dev-resources/test-pdf.pdf")

    (let [v1 (-> (get-attachment-by-id sonja application-id attachment-id)
                 :versions
                 first)]
      (fact "fileID is set" (:fileId v1) => truthy)
      (fact "fileID is set" (:originalFileId v1) => truthy)
      (fact "File is original before rotation" (:fileId v1) => (:originalFileId v1))

      (fact "version number is set" (:version v1) => {:major 0 :minor 1})

      (command sonja :rotate-pdf :id application-id :attachmentId attachment-id :rotation 90) => ok?

      (let [v2 (->> (get-attachment-by-id sonja application-id  attachment-id)
                    :versions
                    first)]
        (fact "File is changed" (:fileId v2) =not=> (:fileId v1))
        (fact "Original file is the same" (:originalFileId v1) => (:originalFileId v1))

        (fact "version number is not changed" (:version v1) => {:major 0 :minor 1})

        (command sonja :rotate-pdf :id application-id :attachmentId attachment-id :rotation 90) => ok?

        (let [v3 (->> (get-attachment-by-id sonja application-id  attachment-id)
                      :versions
                      first)]
          (fact "File is changed again" (:fileId v3) =not=> (:fileId v2))
          (fact "Original file is still the same" (:originalFileId v3) => (:originalFileId v1)))))))

(defn- poll-job [id version limit]
  (when (pos? limit)
    (let [resp (query sonja :stamp-attachments-job :job-id id :version version)]
      (when-not (= (get-in resp [:job :status]) "done")
        (Thread/sleep 200)
        (poll-job id (get-in resp [:job :version]) (dec limit))))))

(facts "Stamping"
  (let [application (create-and-submit-application sonja :propertyId sipoo-property-id)
        application-id (:id application)
        attachment (first (:attachments application))
        _ (upload-attachment sonja application-id attachment true :filename "dev-resources/test-pdf.pdf")
        application (query-application sonja application-id)
        comments (:comments application)
        {job :job :as resp} (command
                              sonja
                              :stamp-attachments
                              :id application-id
                              :timestamp ""
                              :lang "fi"
                              :text "OK"
                              :organization ""
                              :files [(:id attachment)]
                              :xMargin 0
                              :yMargin 0
                              :page "first"
                              :extraInfo ""
                              :includeBuildings true
                              :kuntalupatunnus ""
                              :section "")
        file-id (get-in (:value job) [(-> job :value keys first) :fileId])]

    (fact "not stamped by default"
      (get-in (get-attachment-info application (:id attachment)) [:latestVersion :stamped]) => falsey)

    (fact "Attachment state is not ok"
      (:state (get-attachment-info application (:id attachment))) =not=> "ok")

    resp => ok?
    (fact "Job id is returned" (:id job) => truthy)
    (fact "FileId is returned" file-id => truthy)

    ; Poll for 5 seconds
    (when-not (= "done" (:status job)) (poll-job (:id job) (:version job) 25))

    (let [attachment (get-attachment-by-id sonja application-id (:id attachment))
          comments-after (:comments (query-application sonja application-id))]

      (fact "Attachment has stamp and no new comments"
        (get-in attachment [:latestVersion :stamped]) => true
        comments-after => comments)

       (fact "Attachment has Sonja's stamper auth"
         (get-in attachment [:auth 1 :id]) => sonja-id
         (get-in attachment [:auth 1 :role]) => "stamper")

      (fact "Attachment state is ok"
        (:state attachment) => "ok")

      (fact "New fileid is in response" (get-in attachment [:latestVersion :fileId]) =not=> file-id)

      (facts "re-stamp"
        (let [{job :job :as resp} (command
                              sonja
                              :stamp-attachments
                              :id application-id
                              :timestamp ""
                              :lang "fi"
                              :text "OK"
                              :organization ""
                              :files [(:id attachment)]
                              :xMargin 0
                              :yMargin 0
                              :page "all"
                              :extraInfo ""
                              :includeBuildings false
                              :kuntalupatunnus ""
                              :section "")]
          resp => ok?
          ; Poll for 5 seconds
          (when-not (= "done" (:status job)) (poll-job (:id job) (:version job) 25))

          (fact "Latest version has chaned"
            (let [attachment-after-restamp (get-attachment-by-id sonja application-id (:id attachment))]
             (:latestVersion attachment) =not=> (:latestVersion attachment-after-restamp)
             (get-in attachment [:latestVersion :stamped]) => true)))))))

(facts* "Attachments visibility"
  (let [{application-id :id :as response} (create-app pena :propertyId tampere-property-id :operation "kerrostalo-rivitalo")
        {attachments :attachments :as application} (query-application pena application-id)
        _ (command pena :set-attachment-visibility
                   :id application-id
                   :attachmentId (get-in attachments [0 :id])
                   :value "viranomainen") => (partial expected-failure? :error.attachment.no-versions)
        _ (upload-attachment-to-all-placeholders pena application)
        {attachments :attachments :as application} (query-application pena application-id)
        user-has-auth? (fn [username {auth :auth}]
                         (= username (:username (first auth))))
        att1 (first attachments)
        att2 (second attachments)
        aid1 (get-in attachments [0 :id])
        aid2 (get-in attachments [1 :id])]

    (fact "each attachment has Pena's auth"
      (every? (partial user-has-auth? "pena") attachments) => true)

    (fact "Can't set unknown visibility value"
      (command pena :set-attachment-visibility :id application-id :attachmentId aid1 :value "testi") => (partial expected-failure? :error.invalid-nakyvyys-value))
    (fact "Set attachment as public"
      (command pena :set-attachment-visibility :id application-id :attachmentId aid1 :value "julkinen") => ok?)

       ; authorize mikko
    (command pena :invite-with-role
             :id application-id
             :email "mikko@example.com"
             :text  ""
             :documentName ""
             :documentId ""
             :path ""
             :role "writer") => ok?
    (command mikko :approve-invite :id application-id) => ok?
    (fact "Mikko sees all uploaded attachments, one of them has visibility 'julkinen'"
      (let [{attachments :attachments} (query-application mikko application-id)]
        (count attachments) => 4
        (count (filter #(contains? (:metadata %) :nakyvyys) attachments)) => 1
        (util/find-by-id aid1 attachments) => (contains {:metadata {:nakyvyys "julkinen"}})))

    (facts "Mikko uploads personal CV"
      (upload-attachment mikko application-id {:id "" :type {:type-group "osapuolet" :type-id "cv"}} true) => true
      (let [{attachments :attachments} (query-application mikko application-id)
            mikko-att (last attachments)]
        (fact "Mikko has auth"
          (user-has-auth? "mikko@example.com" mikko-att) => true)
        (fact "Mikko hides attachment from other parties (visibility: 'viranomainen')"
          (command mikko :set-attachment-visibility :id application-id :attachmentId (:id mikko-att) :value "viranomainen") => ok?)
        (fact "Mikko can download, but Pena can't"
          (raw mikko "download-attachment" :attachment-id (:fileId (:latestVersion mikko-att))) => http200?
          (raw pena "download-attachment" :attachment-id (:fileId (:latestVersion mikko-att))) => http404?
          (raw nil "download-attachment" :attachment-id (:fileId (:latestVersion mikko-att))) => http401?)
        (fact "Mikko can access attachment, Pena's app query doesn't contain attachment"
          (util/find-by-id (:id mikko-att) (:attachments (query-application pena application-id))) => nil
          (util/find-by-id (:id mikko-att) (:attachments (query-application mikko application-id))) => map?)))

    (facts "Pena sets attachment visiblity to parties ('asiakas-ja-viranomainen')"
     (command pena :set-attachment-visibility :id application-id :attachmentId aid2 :value "asiakas-ja-viranomainen") => ok?
     (fact "Parties can download"
       (raw mikko "download-attachment" :attachment-id (:fileId (:latestVersion att2))) => http200?
       (raw pena "download-attachment" :attachment-id (:fileId (:latestVersion att2))) => http200?)
     (fact "Parties have attachment available in query"
       (util/find-by-id aid2 (:attachments (query-application mikko application-id))) => (contains {:metadata {:nakyvyys "asiakas-ja-viranomainen"}})
       (util/find-by-id aid2 (:attachments (query-application pena application-id))) => (contains {:metadata {:nakyvyys "asiakas-ja-viranomainen"}})))

    (command pena :submit-application :id application-id) => ok?

    (fact "Veikko uploads only-authority attachment"
      (upload-attachment veikko application-id {:id "" :type {:type-group "ennakkoluvat_ja_lausunnot"
                                                              :type-id "elyn_tai_kunnan_poikkeamapaatos"}} true) => true)
    (let [{attachments :attachments} (query-application veikko application-id)
          veikko-att (last attachments)]
      (count attachments) => 6
      (fact "Veikko sets his attachment authority-only"
        (command veikko :set-attachment-visibility :id application-id :attachmentId (:id veikko-att) :value "viranomainen") => ok?)
      (fact "Pena sees 4 attachments"                     ; does not see Mikkos CV or Veikkos attachment
        (let [attachments (:attachments (query-application pena application-id))]
          (count attachments) => 4
          (map :id attachments) =not=> (contains (:id veikko-att))))
      (fact "Mikko sees 5 attachments"                     ; does not see Veikkos attachment
        (let [attachments (:attachments (query-application mikko application-id))]
          (count attachments) => 5
          (map :id attachments) =not=> (contains (:id veikko-att)))))

    (fact "Veikko uploads attachment for parties"
      (upload-attachment veikko application-id {:id "" :type {:type-group "ennakkoluvat_ja_lausunnot"
                                                                  :type-id "naapurin_suostumus"}} true) => true)

    (let [{attachments :attachments} (query-application veikko application-id)
          veikko-att-id (:id (last attachments))
          _ (command veikko :set-attachment-visibility :id application-id :attachmentId veikko-att-id :value "asiakas-ja-viranomainen") => ok?
          _ (upload-attachment pena application-id {:id veikko-att-id :type {:type-group "ennakkoluvat_ja_lausunnot"
                                                                             :type-id "naapurin_suostumus"}} true) => true
          mikko-app (query-application mikko application-id)
          latest-attachment (last (:attachments mikko-app))]
      (fact "Mikko sees Veikko's/Pena's attachment"
        (:id latest-attachment) => veikko-att-id
        (-> latest-attachment :latestVersion :user :username) => "pena")
      (fact "Mikko can't change visibility (not authed to attachment)"
        (command mikko :set-attachment-visibility
                 :id application-id
                 :attachmentId veikko-att-id
                 :value "julkinen") => (partial expected-failure? :error.attachment.no-auth))
      (fact "Veikko sets visibility to only authority"
        (command veikko :set-attachment-visibility :id application-id :attachmentId veikko-att-id :value "viranomainen") => ok?)
      (fact "Mikko can't see attachment anymore"
        (map :id (get (query-application mikko application-id) :attachments)) =not=> (contains veikko-att-id))
      (fact "Pena can see attachment, because he is authed"
        (map :id (get (query-application pena application-id) :attachments)) => (contains veikko-att-id))
      (fact "Pena can change visibility, as he is authed"
        (command pena :set-attachment-visibility :id application-id :attachmentId veikko-att-id :value "julkinen") => ok?)
      )))

(facts "Uploading PDF should not create duplicate comments"
  (let [application    (create-and-submit-application pena :propertyId jarvenpaa-property-id)
        application-id (:id application)
        _ (upload-attachment pena application-id {:type {:type-group "osapuolet" :type-id "cv"}} true :filename "dev-resources/invalid-pdfa.pdf")
        {attachments :attachments comments :comments} (query-application pena application-id)
        pdf-attachment (first attachments)]
    (fact "is PDF"
      (-> pdf-attachment :latestVersion :contentType) => "application/pdf")

    (when (string? (env/value :pdf2pdf :license-key)) ; test PDF/A when pdf2pdf converter is enabled
      (facts "PDF/A conversion"
        (fact "Original version and PDF/A version exists"
          (count (:versions pdf-attachment)) => 2)
        (fact "Is archivable"
          (-> pdf-attachment :latestVersion :archivable) => true)))

    (fact "After upload comments count is + 1"
      (count (:comments application)) => 0 ; before upload
      (count comments) => 1)))
