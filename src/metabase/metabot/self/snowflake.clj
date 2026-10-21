(ns metabase.metabot.self.snowflake
  "Snowflake Cortex REST API adapter.

  Talks to the OpenAI-compatible Chat Completions surface of a customer's Snowflake account,
  authenticating with a programmatic access token (PAT):

    - `POST https://{account}.snowflakecomputing.com/api/v2/cortex/v1/chat/completions`

  Cortex has no model listing endpoint, so the admin names the model the connection serves
  (`openai-gpt-5`, `claude-sonnet-4-5`, ...) and connecting verifies it with a one-line generation.

  The legacy `/api/v2/cortex/inference:complete` endpoint is deliberately not used: its tool calling
  speaks a dialect of its own (`tool_spec`, `content_list`), where this surface matches what
  [[chat-completions/request-body]] emits.

  https://docs.snowflake.com/en/user-guide/snowflake-cortex/cortex-rest-api"
  (:require
   [clojure.set :as set]
   [clojure.string :as str]
   [metabase.metabot.self.adapter :as adapter]
   [metabase.metabot.self.core :as core]
   [metabase.metabot.self.openai.chat-completions :as chat-completions]
   [metabase.util :as u]
   [metabase.util.i18n :refer [tru]]
   [metabase.util.json :as json]
   [metabase.util.malli :as mu]))

(set! *warn-on-reflection* true)

(def ^:private chat-completions-path "/chat/completions")

;;; ------------------------------------------------ Account -> URL ----------------------------------------------

(def ^:private account-host-pattern
  "What an account identifier may look like once lower-cased and hyphenated. It becomes a hostname label,
  so nothing that could end the host early (`/`, `:`, `@`, `?`, `#`) is allowed through."
  #"[a-z0-9][a-z0-9.-]*")

(defn base-url
  "The Cortex base URL for the Snowflake `account` identifier, e.g. `MYORG-MYACCOUNT`.

  Underscores become hyphens, the form Snowflake documents for URLs: an underscore is not a valid
  hostname character, and TLS hostname verification can refuse it. Throws for an identifier that is
  missing or is not a bare account identifier."
  [account]
  (let [host (some-> account str str/trim u/lower-case-en (str/replace "_" "-"))]
    (when-not (and host (re-matches account-host-pattern host))
      (throw (ex-info (tru "Snowflake account name is missing or invalid. Enter the account identifier, like MYORG-MYACCOUNT, not a URL.")
                      {:api-error   true
                       :status-code 400
                       :error-code  :snowflake-account-invalid})))
    (str "https://" host ".snowflakecomputing.com/api/v2/cortex/v1")))

;;; ------------------------------------------------ HTTP plumbing -----------------------------------------------

(defn- snowflake-auth
  "Snowflake's `:auth`. The URL is derived from the account rather than entered, and the PAT travels as a
  bearer token with the header that tells Snowflake which kind of token it is."
  [{:keys [slug display-name]} {:keys [credentials ai-proxy?]}]
  (core/resolve-auth slug display-name
                     (when-let [pat (not-empty (:api-key credentials))]
                       {:url     (base-url (:account credentials))
                        :headers {"Authorization"                        (str "Bearer " pat)
                                  "X-Snowflake-Authorization-Token-Type" "PROGRAMMATIC_ACCESS_TOKEN"}})
                     ai-proxy?))

(def ^:private provider
  (adapter/provider
   {:slug           "snowflake"
    :display-name   "Snowflake Cortex"
    :auth           snowflake-auth
    :headers        {"Accept" "application/json, text/event-stream"}
    :error-fallback #(tru "Snowflake Cortex API error (HTTP {0})" %)
    :errors         {400 #(tru "Snowflake Cortex rejected the request — check the model name")
                     401 #(tru "Snowflake rejected the programmatic access token — it may be expired, or blocked by a network policy")
                     402 #(tru "Snowflake Cortex budget exceeded")
                     403 #(tru "Snowflake denied access — the token''s default role needs SNOWFLAKE.CORTEX_USER, and the account must have the Cortex REST API enabled")
                     404 #(tru "Snowflake Cortex endpoint was not found — check the account name")
                     429 #(tru "Snowflake Cortex has rate limited us")
                     503 #(tru "Snowflake Cortex inference timed out")}}))

(defn- missing-model-ex []
  (ex-info (tru "No Snowflake Cortex model is set")
           {:api-error  true
            :error-code :model-missing}))

;;; ---------------------------------------------- Connect validation --------------------------------------------

(def ^:private probe-messages
  [{:role "user" :content "Reply with the single word: ok"}])

(mu/defn list-models :- adapter/ModelListing
  "Validate a Snowflake connection and return an empty model list.

  There is never a dropdown to populate — Cortex has no listing endpoint, and the model is free text on
  the connection — so the only work here is the connect-time check: with `:probe?`, one short
  generation against `:model` proves the account name, the PAT, its role's Cortex privilege and the
  model name all at once. A plain listing makes no request, since a generation on every admin page
  load would cost tokens for nothing.

  Opts: `:credentials` (`{:account ... :api-key ... :model-name ...}`), `:model`, `:probe?`, and
  `:ai-proxy?`, which is not supported for Snowflake and throws when true."
  ([] (list-models {}))
  ([{:keys [credentials model ai-proxy? probe?]} :- adapter/ListOpts]
   (adapter/reject-ai-proxy! provider ai-proxy?)
   (when-let [model (and probe? (not-empty model))]
     (try
       (adapter/request! provider {:method      :post
                                   :path        chat-completions-path
                                   :as          :json
                                   :credentials credentials
                                   :ai-proxy?   ai-proxy?
                                   :body        (json/encode {:model    model
                                                              :messages probe-messages})})
       (catch Exception e
         (adapter/rethrow! provider e))))
   {:models []}))

;;; --------------------------------------------------- Requests -------------------------------------------------

(defn- fixed-temperature-model?
  "Whether `model` is one of the OpenAI reasoning families (GPT-5 and later, the o-series), which only
  accept the default temperature. Cortex names them with an `openai-` prefix."
  [model]
  (boolean (re-find #"^openai-(gpt-[5-9]|o\d)" (str model))))

(mu/defn snowflake-request-body
  "Build the Chat Completions request body for an LLM request.

  Cortex's dialect matches what [[chat-completions/request-body]] emits, except:

  - The output cap is sent as `max_completion_tokens`; Cortex answers `max_tokens` with an error. A
    caller that names no `:max-tokens` gets [[core/chat-max-output-tokens]], since Cortex's own default
    is 4,096 — and reasoning tokens count against it. A forced tool call never gets less than
    [[adapter/forced-tool-call-token-floor]], for the same reason.
  - `temperature` is dropped for the OpenAI reasoning families, which reject anything but the default."
  [{:keys [model max-tokens schema tool_choice] :as opts} :- core/LLMRequestOpts]
  (let [forced? (or (some? schema) (= "required" tool_choice))
        cap     (cond-> (or max-tokens core/chat-max-output-tokens)
                  forced? (max adapter/forced-tool-call-token-floor))]
    (-> (chat-completions/request-body (assoc opts :max-tokens cap))
        (set/rename-keys {:max_tokens :max_completion_tokens})
        (cond-> (fixed-temperature-model? model) (dissoc :temperature)))))

(mu/defn snowflake-raw
  "Perform a streaming request to the Snowflake Cortex Chat Completions API.
  Opts map takes `:credentials` (`{:account ... :api-key ...}`) from the connection serving this
  request, and throws when they are missing. `:ai-proxy?` is not supported for Snowflake and throws
  when true."
  [{:keys [model] :as opts} :- core/LLMRequestOpts]
  (when (str/blank? model)
    (throw (missing-model-ex)))
  (adapter/stream! provider opts
                   {:path chat-completions-path
                    :body (snowflake-request-body opts)}))

(defn snowflake->aisdk-chunks-xf
  "Translates Cortex Chat Completions streaming chunks into AI SDK v5 protocol chunks. Cortex adds no
  `finish_reason` beyond OpenAI's, so it takes the base stop-reason table."
  []
  (chat-completions/chat-completions->aisdk-chunks-xf))

(defn snowflake
  "Call the Snowflake Cortex Chat Completions API, return AISDK stream."
  [& args]
  (let [raw (apply snowflake-raw args)]
    (eduction (snowflake->aisdk-chunks-xf) raw)))
