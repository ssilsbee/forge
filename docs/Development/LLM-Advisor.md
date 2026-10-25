# LLM advisor: first shadow experiment

This prototype records a real AI land-drop decision and asks a model for a second opinion in a
separate process. The game always uses Forge's existing AI choice. Reviewing a snapshot never
executes a move, changes an AI profile, or calls a model from the game thread.

## Windows quick start

Build the desktop JAR with Java 17, then run these commands from the repository root. The helper
uses `JAVA_HOME`, `-JavaHome`, or the adjacent workspace Java 17 toolchain. It runs Forge from
`forge-gui`, where its assets live, so the generated EXE's system-Java discovery is not involved.

```powershell
# Generate a reproducible position and capture the existing AI's actual choice. No inference.
.\tools\llm-advisor.ps1 -Action sample

# Open the browser for this app's own ChatGPT registration and consent.
.\tools\llm-advisor.ps1 -Action login

# Discover models for the connected ChatGPT account; use a returned ID below.
.\tools\llm-advisor.ps1 -Action models -Provider chatgpt

# Review one snapshot; this consumes the connected account's plan allowance or available credits.
.\tools\llm-advisor.ps1 -Action review -Provider chatgpt -Model gpt-6-luna -ReasoningEffort high `
  -InputFile '.forge-llm\stage-depths.json' -OutputFile '.forge-llm\chatgpt-review.json'
```

For OpenRouter, supply a key locally. The secure prompt below avoids saving the key in command
history. The key lives in the process environment; the advisor does not store it in a config file.

```powershell
$env:OPENROUTER_API_KEY = [System.Net.NetworkCredential]::new('', (Read-Host 'OpenRouter API key' -AsSecureString)).Password
.\tools\llm-advisor.ps1 -Action models -Provider openrouter
.\tools\llm-advisor.ps1 -Action review -Provider openrouter -Model '<returned-model-id>' `
  -InputFile '.forge-llm\stage-depths.json' -OutputFile '.forge-llm\openrouter-review.json'
Remove-Item Env:OPENROUTER_API_KEY
```

`-Action play` opens the desktop client and records qualifying land choices during play. It uses
an isolated Forge profile under `.forge-llm/profile` and writes captures under
`.forge-llm/captures`. There are no model requests during play. This profile has its own preferences
and decks. All prototype state is ignored by Git. Use `-DataDir` to choose a different location.

The helper is a developer launcher for this milestone. A provider/account/model panel in Forge's
settings is future work.

## Connections

### ChatGPT plan

The connection implements OpenAI's documented Sign in with ChatGPT flow for local open-source
apps. Eligible accounts can authorize their ChatGPT plan for inference without an API key. A
completed inference request is required to verify actual account/model access; sign-in or a model
catalog alone does not prove entitlement. Plan limits and available credits still apply.

The browser shows **Continue with ChatGPT** for the app **Forge LLM Advisor**. Registration uses
PKCE, a random state and nonce, a persistent installation host ID, and a callback bound only to
`127.0.0.1`. The issued client ID is retained and reused. ID tokens are checked against OpenAI's
published RSA signing keys, issuer, audience, expiry and nonce. The granted
`chatgpt.tokens.use.direct` scope is required for inference. This app never reads Codex's credential
files or browser session cookies.

Profiles and rotating tokens are stored in owner-only files with atomic replacement. A filesystem
lock serializes refreshes across advisor processes. Files are protected by OS permissions; they
are not encrypted by this prototype. Keep the private data directory on a local filesystem that
supports ACLs/permissions and atomic renames.

```powershell
.\tools\llm-advisor.ps1 -Action profiles
.\tools\llm-advisor.ps1 -Action login -NewAccount
.\tools\llm-advisor.ps1 -Action login -Profile '<saved-profile-id>'
.\tools\llm-advisor.ps1 -Action models -Provider chatgpt -Profile '<saved-profile-id>'
.\tools\llm-advisor.ps1 -Action logout -Profile '<saved-profile-id>'
```

An explicit `-Profile` on review selects that saved registration. Otherwise the last successfully
connected profile is used. Reconnecting verifies the saved account's identity before replacing its
credentials. Logout attempts remote refresh-token revocation and clears local tokens, retaining
the registration for a later reconnect. If revocation cannot be confirmed, the command says so.
Users can manage access and usage in [ChatGPT Settings](https://chatgpt.com/settings/usage).

Inference uses `https://api.openai.com/v1/responses`, array input messages, `store: false`,
`stream: true`, and no tools. This subscription route rejects `max_output_tokens`; its output
budget is managed by the provider. The client still applies its total request deadline. Users can
also manage app usage through ChatGPT Settings.
Only `response.completed` with completed status counts as success. Failed, incomplete or interrupted
streams produce a failure report even if some text was already received. The client never uses
ChatGPT's private backend endpoints. The auth implementation currently supports RS256 ID tokens;
other signing algorithms fail closed.

The subscription stream can finish with a metadata-only/empty output array. The parser preserves
the streamed answer in that case, while still requiring a completed terminal event. It never
collects or stores reasoning-event contents.

References:

- [Registration and sign-in](https://developers.openai.com/siwc/token-sharing-open-source/sign-in)
- [Accounts and sessions](https://developers.openai.com/siwc/token-sharing-open-source/profiles-and-sessions)
- [Models and inference](https://developers.openai.com/siwc/token-sharing-open-source/models-and-inference)
- [Token reference](https://developers.openai.com/siwc/token-sharing-open-source/token-reference)

### OpenRouter

Requests use `https://openrouter.ai/api/v1/chat/completions` with bearer authentication and an
explicit model slug. Models come from `/api/v1/models`. The provider must return a completed text
response; length truncation and tool-call responses are rejected. OpenRouter usage is billed
through OpenRouter, independently of ChatGPT plan usage. See the
[OpenRouter API guide](https://openrouter.ai/docs/quickstart).

Both providers share the same prompt and decision contract. Automatic retries and silent provider
fallback are disabled, so one review makes at most one inference request and cannot switch billing
providers unexpectedly. The default inference deadline is 60 seconds; `-TimeoutSeconds` accepts
1–300. Authentication renewal can require a separate request before inference.

The initial requested configuration is **GPT-6 Luna, high reasoning** (`gpt-6-luna`,
`reasoning.effort: high`). The report records the requested effort. No model or reasoning-effort
substitution occurs on failure. Other accounts/models can use `-Model` and `-ReasoningEffort`.
For OpenRouter, the output budget defaults to 8192 tokens, including reasoning, and can be set with
`-MaxOutputTokens` (256–32768). A short visible answer may still require a larger reasoning budget.
This option is rejected for ChatGPT plan usage rather than silently ignored. An exhausted provider
budget produces an incomplete report, never an accepted partial suggestion. The
[GPT-6 Luna model reference](https://developers.openai.com/api/docs/models/gpt-6-luna) documents
its supported reasoning settings.

## Reproducible decision

The sample command constructs this engine position and asks the real AI for its choice:

- AI battlefield: Thespian's Stage and two untapped Islands.
- AI hand: Dark Depths and Swamp.
- Opponent battlefield: Plains; opponent hand and libraries remain hidden.
- AI has a land drop available in its main phase, with an empty stack.

Playing Dark Depths establishes the well-known Stage/Depths interaction: Stage can copy Depths
without receiving its entry counters, potentially creating Marit Lage. Stage is tagged
`AI:RemoveDeck:All` in Forge's card script. This is a useful strategy probe, not an assertion that
the baseline always picks Swamp or that choosing Depths alone proves the model can execute the
entire combo. The sample records whichever land Forge actually selects.

The first slice chooses among normal, face-up lands in the AI's hand that pass the engine's
`canPlayLand` check. At least two different land names must be available, and the actual baseline
must belong to that set. Passing, spell casting, activated abilities, target selection, alternate
faces and playing from other zones are outside this slice. Such choices are skipped rather than
represented as a complete action space. Simulation copies and games marked with no GUI user are
skipped as well. Positions with restricted floating mana are skipped until its restrictions can be
represented faithfully.

## Information and execution boundary

`PlayerControllerAi.chooseSpellAbilityToPlay` invokes the recorder after the AI's thinking timer
has been disarmed, and before the engine plays the chosen move. The recorder copies visible
values into JSON on that thread. Only detached text reaches a bounded worker that writes files.
Queue pressure and capture failures drop observations, never replace the AI's chosen move.

The snapshot includes the AI's own hand, current public permanent stats/counters and printed card
text, visible graveyards/exile/command zones, player life totals, floating mana by color, land-play
limits and zone counts. Players are
identified by anonymous position IDs. Opponent hands and all libraries are represented by counts
only; face-down identities are omitted. This is conservatively less information than some game
effects might reveal. Printed Oracle text is not a full account of continuous effects.

Snapshots are capped at 256 visible cards and 512,000 characters; oversized positions are skipped
rather than silently truncated. The worker queue holds 16 observations and captures at most 100
decisions per process by default. `-Dforge.llm.captureLimit=N` adjusts that limit when launching Java
directly. Recordings contain the AI's hand, so treat them as private game data.

## Review reports and validation

The model receives a detached snapshot and returns only:

```json
{"actionId":"land-123","reason":"A brief strategic explanation."}
```

The report accepts an ID only if it appears exactly in the captured candidate set. Malformed JSON,
unknown IDs, missing fields, overlong explanations, timeouts and provider errors retain the baseline
as the report's effective recommendation. Errors expose safe codes rather than raw provider bodies
or credential-bearing exceptions. Invalid-parameter errors may include a short credential-redacted
diagnostic. Reports include model, provider, elapsed time, available token
usage and agreement with the baseline. They always label strategic quality as **unmeasured**.
Reports also retain the bounded visible answer to diagnose format errors; this is the final answer,
not hidden reasoning.

A candidate ID is valid for the captured position only. No review result is applied to a current
or future game, and agreement/disagreement is not a win-rate or quality measurement. After checking
data completeness and several human-reviewed cases, the next milestone can evaluate complete lines
in reproducible engine games and design an optional, validated live decision override.

Focused offline verification (mock providers; no billable model calls):

```powershell
mvn -pl forge-gui-desktop -am '-Dtest=LlmShadowCaptureTest,ProviderClientTest,ChatGptAuthTest' `
  '-Dsurefire.failIfNoSpecifiedTests=false' '-Djava.awt.headless=true' test
```

## First live verification

On October 24, 2026 (Pacific), this Windows build successfully registered its own ChatGPT
connection, discovered `gpt-6-luna` in the account's catalog, and completed the example with
`reasoning.effort: high`. Forge chose Swamp; Luna chose Dark Depths and described the Stage/Depths
interaction. The validated review took 6926 ms. The saved successful report includes its exact
token usage.

The live run verified sign-in, model discovery, completed streaming inference and candidate
validation. OpenRouter is covered by local protocol tests and requires a user's own key for a live
check. Renewal and revocation have not been exercised against the live service in this milestone.
