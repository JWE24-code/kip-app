(ns frontend.handler.sidecar-test
  (:require [clojure.test :refer [deftest testing is]]
            [frontend.handler.sidecar :as sidecar]))

(deftest make-envelope-shape
  (testing "the envelope carries the protocol version, a type, a fresh id and ts"
    (let [env (sidecar/make-envelope "chat.send" {:text "hi"})]
      (is (= sidecar/protocol-version (:v env)))
      (is (= "chat.send" (:type env)))
      (is (string? (:id env)))
      (is (number? (:ts env)))
      (is (= {:text "hi"} (:payload env))))))

(deftest make-envelope-nil-payload
  (testing "an omitted payload becomes an empty object the server accepts"
    (is (= {} (:payload (sidecar/make-envelope "ping" nil))))))

(deftest envelope-ids-are-unique
  (is (not= (:id (sidecar/make-envelope "ping" {}))
            (:id (sidecar/make-envelope "ping" {})))))

(deftest encode-decode-round-trips
  (testing "camelCase payload keys survive the JSON round trip"
    (let [env (sidecar/make-envelope "chat.respond" {:toolCallId "c1" :value "yes"})
          parsed (sidecar/decode (sidecar/encode env))]
      (is (= "chat.respond" (:type parsed)))
      (is (= "c1" (get-in parsed [:payload :toolCallId])))
      (is (= "yes" (get-in parsed [:payload :value]))))))

(deftest decode-invalid-returns-nil
  (is (nil? (sidecar/decode "not json")))
  (is (nil? (sidecar/decode "{bad"))))
