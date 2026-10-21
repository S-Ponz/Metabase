(ns metabase.metabot.self.snowflake-test
  (:require
   [clj-http.client :as http]
   [clojure.test :refer :all]
   [metabase.llm.provider :as llm.provider]
   [metabase.metabot.self.core :as self.core]
   [metabase.metabot.self.debug :as debug]
   [metabase.metabot.self.snowflake :as snowflake]
   [metabase.metabot.self.snowflake-mock :as snowflake-mock]
   [metabase.metabot.test-util :as metabot.tu]
   [metabase.util.json :as json]))

(set! *warn-on-reflection* true)

(def ^:private credentials
  {:account "MYORG-MY_ACCOUNT" :api-key "pat-test-token" :model-name "openai-gpt-5"})

(def ^:private input
  [{:role :user :content "hi"}])

(deftest ^:parallel base-url-test
  (testing "the account identifier becomes the hostname, lower-cased and with underscores as hyphens"
    (is (= "https://myorg-my-account.snowflakecomputing.com/api/v2/cortex/v1"
           (snowflake/base-url "MYORG-MY_ACCOUNT"))))
  (testing "anything that is not a bare account identifier is refused, so it cannot redirect the request"
    (doseq [account [nil "" "  " "https://myorg.snowflakecomputing.com" "evil.example.com/" "a@b" "a:443"]]
      (is (thrown-with-msg? clojure.lang.ExceptionInfo #"account name is missing or invalid"
                            (snowflake/base-url account))
          (pr-str account)))))

(deftest snowflake-raw-request-test
  (testing "a chat request goes to the account's Chat Completions endpoint with the PAT as a bearer token"
    (with-redefs [self.core/sse-reducible             identity
                  self.core/reducible-with-api-errors (fn [r _ _] r)
                  debug/capture-stream                (fn [r _] r)
                  http/request                        (fn [req] {:body req})]
      (let [req (snowflake/snowflake-raw {:model "openai-gpt-5" :input input :credentials credentials})]
        (is (=? {:method  :post
                 :url     "https://myorg-my-account.snowflakecomputing.com/api/v2/cortex/v1/chat/completions"
                 :headers {"Authorization"                        "Bearer pat-test-token"
                           "X-Snowflake-Authorization-Token-Type" "PROGRAMMATIC_ACCESS_TOKEN"}}
                req))
        (is (=? {:model "openai-gpt-5" :stream true :stream_options {:include_usage true}}
                (json/decode+kw (:body req)))))))
  (testing "a request with no model is refused before anything is sent"
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"No Snowflake Cortex model is set"
                          (snowflake/snowflake-raw {:input input :credentials credentials})))))

(deftest ^:parallel request-body-test
  (testing "the output cap is sent as max_completion_tokens, since Cortex rejects max_tokens"
    (let [body (snowflake/snowflake-request-body {:model "claude-sonnet-4-5" :input input :max-tokens 512})]
      (is (= 512 (:max_completion_tokens body)))
      (is (not (contains? body :max_tokens)))))
  (testing "a caller with no cap gets the chat cap, not Cortex's 4,096 default"
    (is (= 32000 (:max_completion_tokens (snowflake/snowflake-request-body {:model "openai-gpt-5" :input input})))))
  (testing "a forced tool call is never capped below the floor a reasoning model needs"
    (is (= 2048 (:max_completion_tokens (snowflake/snowflake-request-body {:model      "openai-gpt-5"
                                                                           :input      input
                                                                           :max-tokens 100
                                                                           :schema     {:type "object"}})))))
  (testing "temperature is dropped for the OpenAI reasoning families and kept for everything else"
    (is (not (contains? (snowflake/snowflake-request-body {:model "openai-gpt-5" :input input :temperature 0.3})
                        :temperature)))
    (is (= 0.3 (:temperature (snowflake/snowflake-request-body {:model       "claude-sonnet-4-5"
                                                                :input       input
                                                                :temperature 0.3})))))
  (testing "tools go out in the OpenAI function format"
    (is (=? {:tools       [{:type "function" :function {:name "get-time"}}]
             :tool_choice "auto"}
            (snowflake/snowflake-request-body {:model "openai-gpt-5"
                                               :input input
                                               :tools [(metabot.tu/get-time-tool)]})))))

(deftest list-models-test
  (testing "a plain listing makes no request"
    (with-redefs [http/request (fn [_] (throw (ex-info "should not be called" {})))]
      (is (= {:models []} (snowflake/list-models {:credentials credentials :model "openai-gpt-5"})))))
  (testing "connecting probes the named model with one generation"
    (let [sent (atom nil)]
      (with-redefs [http/request (fn [req] (reset! sent req) {:status 200 :body {}})]
        (is (= {:models []}
               (snowflake/list-models {:credentials credentials :model "openai-gpt-5" :probe? true})))
        (is (=? {:method :post
                 :url    "https://myorg-my-account.snowflakecomputing.com/api/v2/cortex/v1/chat/completions"}
                @sent))
        (is (=? {:model "openai-gpt-5"} (json/decode+kw (:body @sent))))))))

(deftest ^:parallel provider-type-test
  (testing "both Snowflake types take the same three fields and name their model in the config"
    (doseq [type-name ["snowflake" "snowflake-mock"]]
      (is (= [:account :api-key :model-name]
             (mapv :key (:fields (llm.provider/provider-type type-name))))
          type-name)
      (is (= "openai-gpt-5" (llm.provider/connection-model type-name credentials)) type-name)
      (is (nil? (llm.provider/validate-config! type-name credentials)) type-name)))
  (testing "the PAT is treated as a secret"
    (is (= #{:api-key} (llm.provider/secret-field-keys "snowflake"))))
  (testing "a URL in place of the account name is rejected by the form"
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"not a valid Snowflake account name"
                          (llm.provider/validate-config!
                           "snowflake" (assoc credentials :account "https://myorg.snowflakecomputing.com"))))))

;;; ---------------------------------------------------- Mock ----------------------------------------------------

(defn- mock-chunks
  [opts]
  (into [] (snowflake-mock/snowflake-mock (merge {:model "openai-gpt-5" :input input :credentials credentials}
                                                 opts))))

(deftest ^:parallel mock-canned-response-test
  (testing "the mock streams its canned text as AISDK chunks, without any HTTP request"
    (let [chunks (mock-chunks nil)
          text   (apply str (keep #(when (= :text-delta (:type %)) (:delta %)) chunks))]
      (is (= :start (:type (first chunks))))
      (is (re-find #"canned response from the Snowflake Cortex test provider" text))
      (is (re-find #"Model: openai-gpt-5" text))
      (is (not (re-find #"pat-test-token" text)))
      (is (=? {:type :usage :finish-reason "stop"} (last chunks)))))
  (testing "a structured-output request gets a call of the structured-output tool that fits the schema"
    (let [chunks (mock-chunks {:schema {:type       "object"
                                        :properties {:title {:type "string"}
                                                     :count {:type "integer"}}
                                        :required   ["title" "count"]}})
          args   (some #(when (= :tool-input-delta (:type %)) (:inputTextDelta %)) chunks)]
      (is (some #(= {:type :tool-input-available :toolName "structured_output"}
                    (select-keys % [:type :toolName]))
                chunks))
      (is (= {:title "Canned response" :count 0} (json/decode+kw args)))))
  (testing "the mock refuses what the real adapter would: a bad account, a missing token, a missing model"
    (is (thrown? clojure.lang.ExceptionInfo (mock-chunks {:credentials (assoc credentials :account "a/b")})))
    (is (thrown? clojure.lang.ExceptionInfo (mock-chunks {:credentials (dissoc credentials :api-key)})))
    (is (thrown? clojure.lang.ExceptionInfo
                 (into [] (snowflake-mock/snowflake-mock {:input input :credentials credentials}))))))
