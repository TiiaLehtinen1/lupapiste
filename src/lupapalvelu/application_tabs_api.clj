(ns lupapalvelu.application-tabs-api
  "Pseudo queries to handle application's tabs' visibility in UI"
  (:require [sade.core :refer :all]
            [lupapalvelu.action :as action :refer [defquery]]
            [lupapalvelu.foreman :as foreman]
            [lupapalvelu.states :as states]))

(defquery tasks-tab-visible
  {:parameters [id]
   :states states/post-verdict-states
   :user-roles #{:authority :applicant}
   :pre-checks [(fn [_ application]
                    (when (foreman/foreman-app? application)
                      (fail :error.foreman.no-tasks)))]}
  [_])

