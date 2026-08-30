(ns frontend.handler.preference-signals-test
  (:require [clojure.test :refer [deftest testing is]]
            [electron.ipc :as ipc]
            [frontend.handler.preference-signals :as ps]
            [frontend.state :as state]))

(deftest behavior!-builds-a-content-free-signal
  (let [sent (atom nil)]
    (with-redefs [ps/send! (fn [sig] (reset! sent sig) sig)]
      (testing "regenerated — no edit bucket"
        (ps/behavior! "call_1" "regenerated")
        (is (= {:call_id "call_1" :kind "behavior" :behavior "regenerated"} @sent)))
      (testing "edited — carries the client-side edit bucket only"
        (ps/behavior! "call_2" "edited" 3)
        (is (= {:call_id "call_2" :kind "behavior" :behavior "edited" :edit_bucket 3} @sent)))
      (testing "nil edit bucket is dropped, not sent as null"
        (ps/behavior! "call_3" "copied" nil)
        (is (= {:call_id "call_3" :kind "behavior" :behavior "copied"} @sent))))))

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

(deftest verdict!-fires-only-for-kip-and-a-valid-winner
  (let [calls (atom [])]
    (with-redefs [ipc/ipc (fn [& args] (swap! calls conj args) (js/Promise.resolve nil))]
      (testing "well-formed verdict on the kip provider hits the :kipArena channel"
        (reset! calls [])
        (with-provider "kip" #(ps/verdict! "arena_1" "b"))
        (is (= 1 (count @calls)))
        (is (= "kipArena" (ffirst @calls)))
        (is (= ["arena_1" "b"] (drop 2 (first @calls)))))
      (testing "no IPC when the gate is closed"
        (reset! calls [])
        (with-provider "anthropic" #(ps/verdict! "arena_1" "b"))
        (is (empty? @calls)))
      (testing "no IPC for a bad winner or a blank arena id"
        (reset! calls [])
        (with-provider "kip"
          #(do (ps/verdict! "arena_1" "best")
               (ps/verdict! "" "a")))
        (is (empty? @calls))))))
