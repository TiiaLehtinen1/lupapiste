(ns lupapalvelu.user
  (:use [monger.operators]
        [lupapalvelu.core]
        [lupapalvelu.log])
  (:require [lupapalvelu.mongo :as mongo]
            [lupapalvelu.security :as security]
            [lupapalvelu.util :as util]))

(defcommand "change-passwd"
  {:parameters [:oldPassword :newPassword]
   :roles      [:applicant :authority]}
  [{{:keys [oldPassword newPassword]} :data user :user}]
  (let [user-id (:id user)
        user-data (mongo/by-id mongo/users user-id)]
    (if (security/check-password oldPassword (-> user-data :private :password))
      (do
        (debug "Password change: user-id=%s" user-id)
        (security/change-password (:email user) newPassword)
        (ok))
      (do
        (warn "Password change: failed: old password does not match: user-id=%s" user-id)
        (fail :old-password-does-not-match)))))

(defquery "get-user-info"
  {:roles [:applicant :authority]}
  [{user :user}]
  (ok :user user))

(defcommand "save-user-info"
  {:parameters [:firstName :lastName :street :city :zip :phone]
   :roles      [:applicant :authority]}
  [{data :data user :user}]
  (let [user-id (:id user)]
    (mongo/update-by-id
      mongo/users
      user-id
      {$set (util/sub-map data [:firstName :lastName :street :city :zip :phone])})
    (ok)))
