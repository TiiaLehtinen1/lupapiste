(ns lupapalvelu.mongo-test
  (:use clojure.test
        midje.sweet
        lupapalvelu.mongo))

(def valid-id "502770568de2282ae6fbb0be")
(def invalid-id "123")

(deftest a-test
  (testing "invalid id returns nil"
           (is (= nil (string-to-objectid invalid-id))))
  (testing "string id can be converted to objectid and back"
           (is (= valid-id (-> valid-id string-to-objectid objectid-to-string))))
  (testing "make-objectid returns string"
           (is (string? (make-objectid)))))

(facts "Facts about with-objectid"
  (against-background
    (string-to-objectid "foo") => "FOO")
  (fact (with-objectid nil) => nil)
  (fact (with-objectid {:data "data"}) => {:data "data"})
  (fact (with-objectid {:id "foo" :data "data"}) => {:_id "FOO" :data "data"}))

(facts "Facts about with-id"
  (against-background
    (objectid-to-string "foo") => "FOO")
  (fact (with-id nil) => nil)
  (fact (with-id {:data "data"}) => {:data "data"})
  (fact (with-id {:_id "foo" :data "data"}) => {:id "FOO" :data "data"}))
