(ns frontend.handler.llm-test
  (:require [clojure.test :refer [deftest testing is are]]
            [frontend.handler.llm :as llm]))

;; A `listLlmProviders` result: [{:id :label :fields :source :ready} …].
(def ^:private registry
  [{:id "anthropic" :ready true}
   {:id "openai" :ready false}
   {:id "deepseek" :ready true}
   {:id "kip" :ready true :source "graph-local"}])

(deftest configured?-nil-or-empty
  (testing "no config / no provider → not configured"
    (is (false? (llm/configured? nil)))
    (is (false? (llm/configured? {})))
    (is (false? (llm/configured? {:provider ""})))
    (is (false? (llm/configured? nil registry)))
    (is (false? (llm/configured? {:provider "anthropic"} nil)))))

(deftest configured?-uses-the-registry-ready-flag
  (testing "with a providers list, configured? is just that provider's :ready"
    (is (true?  (llm/configured? {:provider "anthropic"} registry)))
    (is (true?  (llm/configured? {:provider "deepseek"} registry)))
    (is (false? (llm/configured? {:provider "openai"} registry))
        "openai's spec says it isn't ready (no key/model) — regardless of the file")
    (is (true?  (llm/configured? {:provider "kip"} registry))
        "an installed connector is configured when its spec says so")))

(deftest configured?-registry-wins-over-the-file
  (testing "the file's fields don't matter once the registry has spoken"
    (is (false? (llm/configured? {:provider "openai"
                                  :providers {:openai {:apiKey "k" :model "gpt-4o-mini"}}}
                                 registry)))
    (is (true?  (llm/configured? {:provider "anthropic" :providers {:anthropic {:apiKey ""}}}
                                 registry)))))

(deftest configured?-fallback-without-a-registry
  (testing "no registry (or an unknown provider) → explicit provider + non-blank apiKey in the file"
    (is (true?  (llm/configured? {:provider "kip" :providers {:kip {:apiKey "kip_x"}}})))
    (is (false? (llm/configured? {:provider "kip" :providers {:kip {:apiKey ""}}})))
    (is (false? (llm/configured? {:provider "kip" :providers {}})))
    (is (true?  (llm/configured? {:provider "anthropic" :providers {:anthropic {:apiKey "sk-ant-xxx"}}})))
    (is (false? (llm/configured? {:provider "anthropic" :providers {:anthropic {:apiKey "   "}}})))
    (testing "only the *selected* provider's block is looked at"
      (is (false? (llm/configured? {:provider "anthropic"
                                    :providers {:anthropic {:apiKey ""}
                                                :deepseek  {:apiKey "k"}}})))
      (is (true?  (llm/configured? {:provider "deepseek"
                                    :providers {:anthropic {:apiKey ""}
                                                :deepseek  {:apiKey "k"}}}))))
    (testing "a provider absent from the registry falls back too"
      (is (true?  (llm/configured? {:provider "kip" :providers {:kip {:apiKey "kip_x"}}}
                                   [{:id "anthropic" :ready true}]))))))

(deftest humanize-error-classification
  (are [raw expected-title] (= expected-title (:title (llm/humanize-error raw)))
    "api.deepseek.com request failed (401): Authentication Fails"  "The provider rejected your API key."
    "invalid x-api-key"                                            "The provider rejected your API key."
    "request failed (429): rate limit exceeded"                    "The provider is rate-limiting you."
    "you exceeded your quota (429)"                                "The provider is rate-limiting you."
    "request failed (402): budget exceeded for this key"           "You've hit a usage or billing limit."
    "insufficient_quota"                                           "You've hit a usage or billing limit."
    "Error: Payment Required"                                      "You've hit a usage or billing limit."
    "Your monthly plan limit is reached"                           "You've hit a usage or billing limit."
    "fetch failed"                                                 "Couldn't reach the LLM provider."
    "connect ECONNREFUSED 127.0.0.1:11434"                         "Couldn't reach the LLM provider."
    "getaddrinfo ENOTFOUND api.example"                            "Couldn't resolve the provider's address."
    "ETIMEDOUT"                                                    "The request timed out."
    "ANTHROPIC_API_KEY is required when PROVIDER=anthropic"        "The LLM provider isn't fully set up."
    "OPENAI_MODEL is required when PROVIDER=openai"                "The LLM provider isn't fully set up."
    "model gpt-9 does not exist or you do not have access"         "That model isn't available for this provider."
    "request failed (503): service unavailable"                    "The provider had a server error."))

(deftest humanize-error-402-beats-429
  (testing "insufficient_quota is billing-exhausted, not rate-limiting"
    (is (= "You've hit a usage or billing limit."
           (:title (llm/humanize-error "openai (429): You exceeded your current quota. insufficient_quota"))))))

(deftest humanize-error-unmatched-keeps-raw
  (let [{:keys [title hint raw]} (llm/humanize-error "something weird\n  at stack")]
    (is (nil? title))
    (is (nil? hint))
    (is (= "something weird\n  at stack" raw))))

(deftest humanize-error-always-has-raw
  (is (= "api.x request failed (401)" (:raw (llm/humanize-error "api.x request failed (401)"))))
  (is (= "" (:raw (llm/humanize-error nil)))))
