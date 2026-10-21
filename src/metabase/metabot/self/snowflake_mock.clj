(ns metabase.metabot.self.snowflake-mock
  "A stand-in for [[metabase.metabot.self.snowflake]] that never contacts Snowflake.

  It takes the same connection fields and answers every request with a canned response, so the whole
  path around the adapter — the connection form, the model reference, the agent loop, the streamed
  answer in the Metabot UI — can be exercised before a Snowflake account is ready.

  The canned response is built as Cortex Chat Completions chunks and run through the real adapter's
  [[snowflake/snowflake->aisdk-chunks-xf]], so the stream translation is the one production uses. Only
  the HTTP round trip is missing.

  Not for production use: a connection of this type answers every question with the same text."
  (:require
   [clojure.string :as str]
   [metabase.metabot.self.adapter :as adapter]
   [metabase.metabot.self.core :as core]
   [metabase.metabot.self.openai.chat-completions :as chat-completions]
   [metabase.metabot.self.snowflake :as snowflake]
   [metabase.util.i18n :refer [tru]]
   [metabase.util.json :as json]
   [metabase.util.malli :as mu]))

(set! *warn-on-reflection* true)

(def ^:private provider
  (adapter/provider
   {:slug         "snowflake-mock"
    :display-name "Snowflake Cortex (test)"}))

(defn- check-credentials!
  "Hold the mock connection to what the real one needs: an account name that makes a valid URL, and a
  token. The token is not used, so any non-blank value passes."
  [{:keys [account api-key]}]
  (snowflake/base-url account)
  (when (str/blank? api-key)
    (throw (core/missing-api-key-ex (:display-name provider)))))

(mu/defn list-models :- adapter/ModelListing
  "Check the connection's fields the way [[snowflake/list-models]] would, without making a request, and
  return an empty model list. `:ai-proxy?` is not supported and throws when true."
  ([] (list-models {}))
  ([{:keys [credentials ai-proxy? probe?]} :- adapter/ListOpts]
   (adapter/reject-ai-proxy! provider ai-proxy?)
   (when probe?
     (check-credentials! credentials))
   {:models []}))

;;; ------------------------------------------------ Canned response ---------------------------------------------

(defn- canned-text
  [{:keys [model input tools credentials]}]
  (str "This is a canned response from the Snowflake Cortex test provider. "
       "No request was sent to Snowflake.\n\n"
       "- Account: " (:account credentials) "\n"
       "- Model: " model "\n"
       "- Messages received: " (count input) "\n"
       "- Tools offered: " (count tools) "\n\n"
       "If you can read this in Metabot, the connection, model selection and streaming all work."))

(defn- example-value
  "A placeholder value of the JSON Schema `type`."
  [{:keys [type]}]
  (case type
    ("number" "integer") 0
    "boolean"            false
    "array"              []
    "object"             {}
    "Canned response"))

(defn- schema-example
  "An object satisfying the structured-output `schema`, every property filled with a placeholder."
  [{:keys [properties]}]
  (into {} (map (fn [[k leaf]] [(name k) (example-value leaf)])) properties))

(defn- canned-chunks
  "The Chat Completions chunks Cortex would stream for this request: the canned text in a few deltas,
  or — for a `:schema` request, which every adapter expresses as a forced tool call — one call of the
  structured-output tool. Usage arrives on a final chunk with no choices, as Cortex sends it."
  [{:keys [model schema] :as opts}]
  (let [id      (str "mock-" (random-uuid))
        chunk   (fn [m] (merge {:id id :object "chat.completion.chunk" :model model} m))
        delta   (fn [d] (chunk {:choices [{:index 0 :delta d}]}))
        text    (canned-text opts)
        content (if schema
                  [(delta {:role       "assistant"
                           :tool_calls [{:index    0
                                         :id       (str "call-" (random-uuid))
                                         :type     "function"
                                         :function {:name      chat-completions/structured-output-tool-name
                                                    :arguments (json/encode (schema-example schema))}}]})]
                  (map #(delta {:role "assistant" :content (apply str %)})
                       (partition-all 24 text)))]
    (concat content
            [(chunk {:choices [{:index 0 :delta {} :finish_reason (if schema "tool_calls" "stop")}]})
             (chunk {:choices []
                     :usage   {:prompt_tokens 0 :completion_tokens 0 :total_tokens 0}})])))

(mu/defn snowflake-mock-raw
  "The canned Chat Completions chunks for an LLM request. Throws where the real adapter would refuse the
  request before sending it: a proxied call, a missing model, or incomplete credentials."
  [{:keys [model credentials ai-proxy?] :as opts} :- core/LLMRequestOpts]
  (adapter/reject-ai-proxy! provider ai-proxy?)
  (when (str/blank? model)
    (throw (ex-info (tru "No Snowflake Cortex model is set")
                    {:api-error  true
                     :error-code :model-missing})))
  (check-credentials! credentials)
  (canned-chunks opts))

(defn snowflake-mock
  "Answer an LLM request with the canned response, return AISDK stream."
  [& args]
  (let [raw (apply snowflake-mock-raw args)]
    (eduction (snowflake/snowflake->aisdk-chunks-xf) raw)))
