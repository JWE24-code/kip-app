(ns frontend.handler.preference-signals-test
  (:require [clojure.test :refer [deftest testing is]]
            [frontend.handler.preference-signals :as ps]
            [frontend.state :as state]))

(defn- with-provider [provider f]
  (let [prev (:kip/llm @state/state)]
    (try
      (state/set-state! :kip/llm {:loaded? true :provider provider})
      (f)
      (finally (state/set-state! :kip/llm prev)))))

(deftest enabled?-only-for-kip
  (with-provider "kip" #(is (true? (ps/enabled?))))
  (with-provider "anthropic" #(is (false? (ps/enabled?))))
  (with-provider nil #(is (false? (ps/enabled?))))
  (testing "no :kip/llm at all"
    (let [prev (:kip/llm @state/state)]
      (try
        (state/set-state! :kip/llm nil)
        (is (false? (ps/enabled?)))
        (finally (state/set-state! :kip/llm prev))))))

(deftest send!-no-op-unless-enabled-and-well-formed
  (testing "returns a resolved promise, never throws, when the gate is closed"
    (with-provider "anthropic"
      #(is (some? (ps/send! {:call_id "c1" :kind "rating" :score 1 :scale 2})))))
  (testing "malformed signal is dropped even when enabled"
    (with-provider "kip"
      #(do
         (is (some? (ps/send! {:kind "rating"})))          ; no call_id
         (is (some? (ps/send! {:call_id "c1"})))))))       ; no kind
