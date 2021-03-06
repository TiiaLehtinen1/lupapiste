(ns lupapalvelu.actions-api
  (:require [sade.env :as env]
            [sade.core :refer :all]
            [lupapalvelu.action :refer [defquery] :as action]
            [lupapalvelu.authorization :as auth]))

;;
;; Default actions
;;

(defn foreach-action [web user data application]
  (map
    #(let [{type :type} (action/get-meta %)]
      (assoc
        (action/action % :type type :data data :user user)
        :application application
        :web web))
    (remove nil? (keys (action/get-actions)))))

(defn- validated [command]
  {(:action command) (action/validate command)})

(defquery actions
  {:user-roles #{:admin}
   :description "List of all actions and their meta-data."} [_]
  (ok :actions (action/serializable-actions)))

(defquery allowed-actions
  {:user-roles       #{:anonymous}
   :user-authz-roles auth/all-authz-roles
   :org-authz-roles  auth/reader-org-authz-roles}
  [{:keys [web data user application]}]
  (let [results  (map validated (foreach-action web user data application))
        filtered (if (env/dev-mode?)
                   results
                   (filter (comp :ok first vals) results))
        actions  (into {} filtered)]
    (ok :actions actions)))
