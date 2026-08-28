(ns frontend.handler.llm-test
  (:require [clojure.test :refer [deftest testing is are]]
            [frontend.handler.llm :as llm]))

(deftest configured?-nil-or-empty
  (testing "no config file / empty map → not configured"
    (is (false? (llm/configured? nil)))
    (is (false? (llm/configured? {})))
    (is (false? (llm/configured? {:provider "anthropic"})))
    (is (false? (llm/configured? {:provider "anthropic" :providers {}})))))

(deftest configured?-anthropic
  (testing "anthropic needs an explicit api key — no ambient fallback"
    (is (true?  (llm/configured? {:provider "anthropic"
                                  :providers {:anthropic {:apiKey "sk-ant-xxx"}}})))
    (is (false? (llm/configured? {:provider "anthropic"
                                  :providers {:anthropic {:apiKey "" :model "claude-x"}}})))
    (is (false? (llm/configured? {:provider "anthropic"
                                  :providers {:anthropic {:apiKey "   "}}})))))

(deftest configured?-openai-deepseek
  (testing "openai/deepseek need key AND model"
    (are [x] (true? (llm/configured? x))
      {:provider "openai"   :providers {:openai   {:apiKey "k" :model "gpt-4o-mini"}}}
      {:provider "deepseek" :providers {:deepseek {:apiKey "k" :model "deepseek-chat"}}})
    (are [x] (false? (llm/configured? x))
      {:provider "openai"   :providers {:openai   {:apiKey "k" :model ""}}}
      {:provider "openai"   :providers {:openai   {:apiKey "" :model "gpt-4o-mini"}}}
      {:provider "deepseek" :providers {:deepseek {:model "deepseek-chat"}}})))

(deftest configured?-local
  (testing "local needs base URL AND model, no key"
    (is (true?  (llm/configured? {:provider "local"
                                  :providers {:local {:baseUrl "http://localhost:11434/v1" :model "llama3.1"}}})))
    (is (false? (llm/configured? {:provider "local"
                                  :providers {:local {:baseUrl "http://localhost:11434/v1"}}})))
    (is (false? (llm/configured? {:provider "local"
                                  :providers {:local {:model "llama3.1"}}})))))

(deftest configured?-other
  (testing "other needs key, model AND base URL"
    (is (true?  (llm/configured? {:provider "other"
                                  :providers {:other {:apiKey "k" :model "m" :baseUrl "https://x/v1"}}})))
    (is (false? (llm/configured? {:provider "other"
                                  :providers {:other {:apiKey "k" :model "m"}}})))))

(deftest configured?-ignores-other-providers-fields
  (testing "only the *selected* provider's fields matter"
    (is (false? (llm/configured? {:provider "anthropic"
                                  :providers {:anthropic {:apiKey ""}
                                              :deepseek  {:apiKey "k" :model "deepseek-chat"}}})))
    (is (true?  (llm/configured? {:provider "deepseek"
                                  :providers {:anthropic {:apiKey ""}
                                              :deepseek  {:apiKey "k" :model "deepseek-chat"}}})))))

(deftest configured?-unknown-provider
  (is (false? (llm/configured? {:provider "kip" :providers {:kip {:apiKey "" :model ""}}}))))

(deftest humanize-error-classification
  (are [raw expected-title] (= expected-title (:title (llm/humanize-error raw)))
    "api.deepseek.com request failed (401): Authentication Fails"  "The provider rejected your API key."
    "invalid x-api-key"                                            "The provider rejected your API key."
    "request failed (429): rate limit exceeded"                    "The provider is rate-limiting you."
    "insufficient_quota"                                           "The provider is rate-limiting you."
    "fetch failed"                                                 "Couldn't reach the LLM provider."
    "connect ECONNREFUSED 127.0.0.1:11434"                         "Couldn't reach the LLM provider."
    "getaddrinfo ENOTFOUND api.example"                            "Couldn't resolve the provider's address."
    "ETIMEDOUT"                                                    "The request timed out."
    "ANTHROPIC_API_KEY is required when PROVIDER=anthropic"        "The LLM provider isn't fully set up."
    "OPENAI_MODEL is required when PROVIDER=openai"                "The LLM provider isn't fully set up."
    "model gpt-9 does not exist or you do not have access"         "That model isn't available for this provider."
    "request failed (503): service unavailable"                    "The provider had a server error."))

(deftest humanize-error-unmatched-keeps-raw
  (let [{:keys [title hint raw]} (llm/humanize-error "something weird\n  at stack")]
    (is (nil? title))
    (is (nil? hint))
    (is (= "something weird\n  at stack" raw))))

(deftest humanize-error-always-has-raw
  (is (= "api.x request failed (401)" (:raw (llm/humanize-error "api.x request failed (401)"))))
  (is (= "" (:raw (llm/humanize-error nil)))))
