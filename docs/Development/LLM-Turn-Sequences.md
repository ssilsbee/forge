# Turn sequences and deck strategy

This offline experiment advances six prepared turn-four positions through Forge's real phase,
priority, stack, payment and state-based-action engine to the next turn. Three arms rebuild each
position: seeded heuristic Forge, a model without a deck plan, and that model with a deck plan.
It does not enable model moves in the ordinary Windows game UI.

The synthetic 60-card Depths deck contains four copies each of Dark Depths, Thespian's Stage,
Vampire Hexmage, Crop Rotation, Sylvan Scrying, Expedition Map, Duress, Thoughtseize, Dispel,
Bayou, Tropical Island, Underground Sea, Wasteland, Swamp and Island. This is a test deck, not an
optimized deck recommendation. The actual remaining library matches the registered list and is
shuffled with a fixed seed; its order is never exported.

## Deck plan

`DeckStrategyPlanner` sends the complete **unordered owner main deck**, including quantities,
mana costs, types and rules text, once. It requests a compact `strategy` (at most 1,800 characters)
and `cautions` (at most 700). The cache is bound to deck contents/rules text, planner version,
provider, model, reasoning effort and applicable output cap. Changing the deck invalidates it.
Starting hands, shuffled order and opponent decks are absent from this request.

Each planned decision begins with `deckStrategy`, then the fresh visible state, public stack,
recent public actions and legal candidates. The complete deck is not repeated. An explicit
advisory label makes current cards, timing and legal actions take precedence. This initial plan
is fixed during the turn; adaptive strategic memory is a later experiment. Schema/length checks
do not certify the strategic claims in model-generated prose.

## Cases and controls

1. Stage/Depths, no opponent answer.
2. Hexmage/Depths, no opponent answer.
3. Stage/Depths with Dispel and a hidden Stifle response.
4. Hexmage/Depths with Dispel and a hidden Swords to Plowshares response.
5. Stage/Depths facing visible untapped Karakas: preserve the combo and wait.
6. Hexmage/Depths with hidden Stifle and no protection: an **unscored** counterplay probe.

The opponent makes at most one scripted response, only when it has priority and can legally pay.
Its hand identity appears only after the spell is on the public stack. No score assumes knowledge
of the hidden Stifle. The other five objectives require a surviving token, or preservation of the
combo rather than activation into exposed Karakas. Objectives and case IDs are not model inputs.

The model controls this fixture's land plays, Clone/RemoveCounter activations and targets,
Dispel responses, legend-rule choices and passing. Candidates are enumerated and checked for
timing, target legality and affordability. Selected actions are checked again before execution.
Other combat, mana-source and trigger decisions use Forge. The baseline must fit the same action
scope or the run stops. This is not a general-purpose model player or a full-game win-rate test.

## Run

Build the desktop JAR, then use the existing authorized provider profile:

```powershell
.\tools\llm-advisor.ps1 -Action sequence-run -Provider chatgpt `
  -Model gpt-6-luna -ReasoningEffort high -TimeoutSeconds 60
```

`deck-plan` generates only the strategy for the experimental deck. OpenRouter uses the existing
local `OPENROUTER_API_KEY` configuration. ChatGPT output length is provider-managed; the client
does not send an unsupported token cap.

The default output is `.forge-llm/turn-sequences/`: one cached plan, per-decision final answers,
per-arm results with exact input traces, progress and `summary.json`. Completed matching work is
reused; changed settings or inputs fail rather than mixing runs. A provider error stops the run
without an automatic retry. Invalid answers fall back to Forge but are counted explicitly and
must not be reported as clean model successes. No credentials or hidden reasoning are recorded
in result files. Do not share the separate `connections/` folder.

Tests cover both combos, real opponent responses and counter-responses, turn completion,
baseline progression without model calls, hidden-information boundaries, matching 60-card
counts, invalid-action rejection, plan attachment/deck binding, and cached plan reuse.

```powershell
mvn -pl forge-gui-desktop -am `
  '-Dtest=LlmShadowCaptureTest,ProviderClientTest,ChatGptAuthTest,DecisionBenchmarkTest,TurnSequenceExperimentTest' `
  '-Dsurefire.failIfNoSpecifiedTests=false' '-Djava.awt.headless=true' package
```

A single run on these six constructed positions is diagnostic. Any benefit from a deck plan
needs varied decks, held-out states and repeated trials before being treated as reliable.

## Initial local result

GPT-6 Luna high completed all arms with no invalid answers: 4/5 scored objectives without a plan,
5/5 with one, versus Forge's 1/5. Both model arms completed both clean combos and protected the
combo from Stifle/Swords with Dispel. In the Karakas case, the unplanned arm recognized the threat
and passed once, then committed in a later priority window and lost the token. The planned arm
played Swamp, kept Depths in hand and preserved Stage throughout the turn. The hidden-Stifle
probe defeated both model arms, and remains unscored.

The unplanned arm used 18 requests (23,938 input / 5,615 output tokens), with median 6,340.5 ms
and nearest-rank p95 26,603 ms. The planned arm used 21 (38,258 / 5,916 tokens), median 6,988 ms,
p95 10,990 ms. Planning separately took about 29.1 seconds (903 / 1,217 tokens). Output totals include
reported reasoning tokens; no hidden reasoning was collected. All 18 full turns completed and
a matching cached rerun made zero model requests. All 28 focused tests and packaging passed.

The generated plan is useful context but uses imprecise wording about the legend rule. It is a
state-based action, not a trigger; its removal of the original Depths is not a sacrifice. Some
visible action explanations also confuse those terms. Successful actions do not certify prose.
These two model arms also differ in access to full-deck information through the summary: a
raw-deck-only control would help separate that information's value from strategic analysis.

## Next playable milestone and deferred work

Prioritize a normal Windows human-versus-AI match with an opt-in hybrid model controller and
a separate LLM Monitor window. Reuse the existing provider connection, deck planner and validated
decision contract. The monitor should show request status, exact input, final visible answer,
returned explanation, effective move, elapsed time and any fallback reason. It must remain
responsive during requests. Decision input includes the AI's hand, so the monitor should be
optional while playing. Start with supported decision families and keep Forge in control of
unsupported choices, timeouts and invalid responses; make that scope visible in the monitor.
Recheck legality before applying any model move to the current game state. Existing experiments
are sufficient motivation for this playable prototype, not proof of general playing strength.

Deferred at the user's request: expand the recent 12-public-action list into a structured last
two-to-three-turn history, track legitimately revealed information, and carry a short previous
intent note across decisions. Preserve the distinction between observed facts and strategic
guesses. Do not expand this memory/optimization work before the playable integration.
