(ns lupapalvelu.web-itest
  (:require [lupapalvelu.itest-util :refer :all]
            [clojure.walk :refer [keywordize-keys]]
            [clj-http.client :as client]
            [midje.sweet :refer :all]
            [cheshire.core :as json])
  (:import org.apache.http.client.CookieStore
           org.apache.http.cookie.Cookie))

(facts
  (fact "ping"
    (command pena :ping) => not-ok?
    (query pena :ping) => ok?
    (raw pena :ping) => (contains {:status 404}))
  (fact "ping-raw"
    (command pena :ping-raw) => not-ok?
    (query pena :ping-raw) => not-ok?
    (raw pena :ping-raw) => (contains {:body "pong" :status 200}))
  (fact "ping!"
    (command pena :ping!) => ok?
    (query pena :ping!) => not-ok?
    (raw pena :ping!) => (contains {:status 404})))

(defn ->cookie-store [store]
  (proxy [org.apache.http.client.CookieStore] []
    (getCookies []       (or (vals @store) []))
    (addCookie [cookie]  (swap! store assoc (.getName cookie) cookie))
    (clear []            (reset! store {}))
    (clearExpired [])))

(facts "facts about hashbang functionality"
  (let [store (atom {})
        params {:cookie-store (->cookie-store store)
                :follow-redirects false
                :throw-exceptions false}
        resp (client/get (str (server-address) "/app/fi/applicant" "?hashbang=/foo/bar") params)]
    (:status resp) => 302
    (:headers resp) => (contains {"location" "/app/fi/welcome"})
    (let [resp (client/get (str (server-address) "/api/hashbang") params)]
      (:status resp) => 200
      (json/parse-string (:body resp)) => (contains {"bang" "foo/bar"}))))
