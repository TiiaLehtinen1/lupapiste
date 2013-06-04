(ns lupapalvelu.itest-util
  (:use [lupapalvelu.fixture.minimal :only [users]]
        [clojure.walk :only [keywordize-keys]]
        [midje.sweet])
  (:require [clj-http.client :as c]
            [lupapalvelu.logging]
            [cheshire.core :as json]))

(defn- find-user [username] (some #(when (= (:username %) username) %) users))
(defn- id-for [username] (:id (find-user username)))
(defn- apikey-for [username] (get-in (find-user username) [:private :apikey]))

(def pena        (apikey-for "pena"))
(def pena-id     (id-for "pena"))
(def mikko       (apikey-for "mikko@example.com"))
(def mikko-id    (id-for "mikko@example.com"))
(def teppo       (apikey-for "teppo@example.com"))
(def teppo-id    (id-for "teppo@example.com"))
(def veikko      (apikey-for "veikko"))
(def veikko-id   (id-for "veikko"))
;TODO should get this through organization
(def veikko-muni "837")
(def sonja       (apikey-for "sonja"))
(def sonja-id    (id-for "sonja"))
;TODO should get this through organization
(def sonja-muni  "753")
(def sipoo       (apikey-for "sipoo"))
(def dummy       (apikey-for "dummy"))

(defn server-address [] (System/getProperty "target_server" "http://localhost:8000"))

(defn decode-response [resp]
  (if (= (:status resp) 200)
      (-> (:body resp) (json/decode) (keywordize-keys))))

(defn printed [x] (println x) x)

(defn query [apikey query-name & args]
  (let [resp (c/get
               (str (server-address) "/api/query/" (name query-name))
               {:headers {"authorization" (str "apikey=" apikey)
                          "accepts" "application/json;charset=utf-8"}
                :query-params (apply hash-map args)})]
    (decode-response resp)))

(defn command [apikey command-name & args]
  (let [resp (c/post
               (str (server-address) "/api/command/" (name command-name))
               {:headers {"authorization" (str "apikey=" apikey)
                          "content-type" "application/json;charset=utf-8"}
                :body (json/encode (apply hash-map args))})]
    (decode-response resp)))

(defn apply-remote-fixture [fixture-name]
  (let [resp (query sonja "apply-fixture" :name fixture-name)]
    (assert (:ok resp))))

(def apply-remote-minimal (partial apply-remote-fixture "minimal"))

(defn create-app [apikey & args]
  (let [args (->> args
               (apply hash-map)
               (merge {:operation "asuinrakennus"
                       :propertyId "75312312341234"
                       :x 444444 :y 6666666
                       :address "foo 42, bar"
                       :municipality "753"})
               (mapcat seq))]
    (apply command apikey :create-application args)))

(defn success [resp]
  (fact (:text resp) => nil)
  (:ok resp))

(defn unauthorized [resp]
  (fact (:text resp) => "error.unauthorized")
  (= (:ok resp) false))

(defn invalid-csrf-token [resp]
  (fact (:text resp) => "error.invalid-csrf-token")
  (= (:ok resp) false))

;;
;; Test predicates: TODO: comment test, add facts to get real cause
;;

(defn invalid-csrf-token? [{:keys [ok text]}]
  (and (= ok false) (= text "error.invalid-csrf-token")))

(fact "invalid-csrf-token?"
  (invalid-csrf-token? {:ok false :text "error.invalid-csrf-token"}) => true
  (invalid-csrf-token? {:ok false :text "error.SOME_OTHER_REASON"}) => false
  (invalid-csrf-token? {:ok true}) => false)

(defn unauthorized? [{:keys [ok text]}]
  (and (= ok false) (= text "error.unauthorized")))

(fact "unauthorized?"
  (unauthorized? {:ok false :text "error.unauthorized"}) => true
  (unauthorized? {:ok false :text "error.SOME_OTHER_REASON"}) => false
  (unauthorized? {:ok true}) => false)

(defn in-state? [state]
  (fn [application] (= (:state application) (name state))))

(fact "in-state?"
  ((in-state? :open) {:state "open"}) => true
  ((in-state? :open) {:state "closed"}) => false)

(defn ok? [resp]
  (= (:ok resp) true))

(fact "ok?"
  (ok? {:ok true}) => true
  (ok? {:ok false}) => false)

(defn not-ok? [resp]
  ((comp not ok?) resp))

(fact "not-ok?"
  (not-ok? {:ok false}) => true
  (not-ok? {:ok true}) => false)

;;
;; DSLs
;;

(defn create-app-id [apikey & args]
  (let [resp (apply create-app apikey args)
        id   (:id resp)]
    resp => ok?
    id => truthy
    id))

(defn comment-application [id apikey]
  (fact "comment is added succesfully"
    (command apikey :add-comment :id id :text "hello" :target "application") => ok?))

(defn query-application [apikey id]
  (let [{application :application :as response} (query apikey :application :id id)]
    response => ok?
    application))
