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

(deftest turn->message-carries-enrichment
  (testing "a settled answer keeps its citations/sources/ids so the widgets stay wired"
    (let [msg (sidecar/turn->message
               {:turnId "t1" :reason "complete" :answer "Because [[acme]]."
                :callId "c1" :arenaId "a1"
                :citedSlugs ["acme"] :candidateSlugs ["acme" "beta"]
                :deadCitations ["ghost"] :lintWarnings [{:slug "acme" :kind "orphan"}]
                :sources [{:slug "acme"}] :webSource {:filename "x.md"}}
               "" [{:skill "web-search" :ok true :ms 1200}])]
      (is (= :assistant (:role msg)))
      (is (= "Because [[acme]]." (:text msg)))
      (is (:answer? msg))
      (is (= "c1" (:call-id msg)))
      (is (= "a1" (:arena-id msg)))
      (is (= ["acme"] (:cited-slugs msg)))
      (is (= ["acme" "beta"] (:candidate-slugs msg)))
      (is (= ["ghost"] (:dead-citations msg)))
      (is (= [{:slug "acme" :kind "orphan"}] (:lint-warnings msg)))
      (is (= [{:slug "acme"}] (:sources msg)))
      (is (= {:filename "x.md"} (:web-source msg)))
      (is (= 1200 (-> msg :steps first :ms))))))

(deftest turn->message-uses-streamed-text
  (testing "when turn.end carries no final text, the streamed deltas are the answer"
    (is (= "streamed" (:text (sidecar/turn->message {:reason "complete"} "streamed" []))))))

(deftest turn->message-statement
  (testing "a statement turn renders the learned card, not an empty assistant bubble"
    (let [learned (sidecar/turn->message
                   {:intent "statement" :learned true :note "Acme moved."
                    :pages [{:action "create" :slug "acme"}]} "" [])
          note (sidecar/turn->message {:intent "statement" :note "Already knew that."} "" [])]
      (is (= :learned (:role learned)))
      (is (= "Acme moved." (:text learned)))
      (is (= [{:action "create" :slug "acme"}] (:pages learned)))
      (is (= :assistant (:role note)))
      (is (= "Already knew that." (:text note))))))

(deftest turn->message-cancelled-and-empty
  (testing "cancel is not an error, and a genuinely empty turn says so"
    (let [cancelled (sidecar/turn->message {:reason "cancelled" :text "partial"} "" [])]
      (is (= "Cancelled." (:text cancelled)))
      (is (:empty? cancelled)))
    (is (:empty? (sidecar/turn->message {:reason "complete"} "" [])))))

(deftest settle-turn-dedupes-panels
  (testing "only the first panel to settle a turn id appends; nil always settles"
    (is (true? (sidecar/settle-turn! "turn-x")))
    (is (false? (sidecar/settle-turn! "turn-x")))
    (is (true? (sidecar/settle-turn! "turn-y")))
    (is (true? (sidecar/settle-turn! nil)))))
