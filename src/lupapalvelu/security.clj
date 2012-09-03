(ns lupapalvelu.security
  (:use monger.operators)
  (:require [lupapalvelu.mongo :as mongo]))

(defn- non-private [party]
  (dissoc party :private))

(defn login [username password]
  "returns non-private information of first party with the username and password"
  (non-private (first (mongo/select mongo/partys {:username username 
                                                 "private.password" password}))))

(defn login-with-apikey [apikey]
  "returns non-private information of first party with the apikey"
  (and apikey (non-private (first (mongo/select mongo/partys {"private.apikey" apikey})))))

;; TODO: Use jBCrypt
(defn get-hash [password salt]
  password)

;; TODO: Use jBCrypt
(defn dispense-salt []
  "")