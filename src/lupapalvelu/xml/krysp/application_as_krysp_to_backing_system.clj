(ns lupapalvelu.xml.krysp.application-as-krysp-to-backing-system
  (:require [sade.env :as env]
            [lupapalvelu.organization :as organization]
            [lupapalvelu.permit :as permit]
            ;; Make sure all the mappers are registered
            [lupapalvelu.xml.krysp.rakennuslupa-mapping :as rl-mapping]
            [lupapalvelu.xml.krysp.poikkeamis-mapping]
            [lupapalvelu.xml.krysp.yleiset-alueet-mapping :as ya-mapping]))

(defn- get-output-directory [permit-type organization]
  (let [sftp-user (get-in organization [:krysp (keyword permit-type) :ftpUser])]
    (str (env/value :outgoing-directory) "/" sftp-user (permit/get-sftp-directory permit-type))))

(defn- get-begin-of-link [permit-type]
  (str (env/value :fileserver-address) (permit/get-sftp-directory permit-type) "/"))

(defn resolve-output-directory [application]
  (let [permit-type (permit/permit-type application)
        organization (organization/get-organization (:organization application))]
    (get-output-directory permit-type organization)))

(defn save-application-as-krysp [application lang submitted-application organization]
  (assert (= (:id application) (:id submitted-application)) "Not same application ids.")
  (let [permit-type   (permit/permit-type application)
        krysp-fn      (permit/get-application-mapper permit-type)
        output-dir    (get-output-directory permit-type organization)
        begin-of-link (get-begin-of-link permit-type)]
    (krysp-fn application lang submitted-application output-dir begin-of-link)))

(defn save-review-as-krysp [application task user lang]
  (let [permit-type   (permit/permit-type application)
        organization  (organization/get-organization (:organization application))
        krysp-fn      (permit/get-review-mapper permit-type)
        output-dir    (get-output-directory permit-type organization)
        begin-of-link (get-begin-of-link permit-type)]
    (krysp-fn application task user lang output-dir begin-of-link)))

(defn save-unsent-attachments-as-krysp [application lang organization]
  (let [permit-type   (permit/permit-type application)
        output-dir    (get-output-directory permit-type organization)
        begin-of-link (get-begin-of-link permit-type)]
    (assert (= permit/R permit-type)
      (str "Sending unsent attachments to backing system is not supported for " (name permit-type) " type of permits."))
    (rl-mapping/save-unsent-attachments-as-krysp application lang output-dir begin-of-link)))

(defn save-jatkoaika-as-krysp [application lang organization]
  (let [permit-type   (permit/permit-type application)
        output-dir    (get-output-directory permit-type organization)
        begin-of-link (get-begin-of-link permit-type)]
    (assert (= permit/YA permit-type)
      (str "Saving jatkoaika as krysp is not supported for " (name permit-type) " type of permits."))
    (ya-mapping/save-jatkoaika-as-krysp application lang organization output-dir begin-of-link)))
