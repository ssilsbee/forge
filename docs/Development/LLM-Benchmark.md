# Repeatable Forge decision benchmark

The first suite contains 12 curated positions. It uses real Forge cards, legal target checks, mana
and sacrifice payment, stack resolution, triggers and state-based actions. Every offered action is
executed in a fresh engine position before any model request. The suite includes passing priority,
single-target spell choices and land sequencing. Live capture still supports its original land-only
slice; the broader choices here belong to this offline fixture runner.

## Run it

Build the desktop JAR with Java 17, then run from the repository root:

```powershell
# Rebuild every position and verify the short-horizon outcomes; no model requests.
.\tools\llm-advisor.ps1 -Action benchmark-export

# One review per position, using the existing Forge ChatGPT connection.
.\tools\llm-advisor.ps1 -Action benchmark-run -Provider chatgpt `
  -Model gpt-6-luna -ReasoningEffort high
```

The suite is stored under `.forge-llm/benchmark-suite`; reviews and summaries are under
`.forge-llm/benchmark-results`. `-InputFile` selects a manifest and `-OutputFile` selects a result
directory. Use a different result directory for another model/configuration. The same command
resumes matching saved results without additional inference. Questions, the entire scoring manifest,
model, provider, effort, token setting and deadline are fingerprinted. Changed questions or mismatched
cached settings are rejected. A cached failure is also reused, never silently retried. To explicitly
retry one failed request, archive its individual JSON report before rerunning; preserve successful
reports to avoid charging for them again. Provider errors stop the run and leave a partial summary.

OpenRouter uses the same commands with `-Provider openrouter`, an explicit model slug and a locally
supplied `OPENROUTER_API_KEY`. See [the advisor guide](LLM-Advisor.md) for connection details.

## Predeclared objectives

| Position | Category | Engine-checked objective |
|---|---|---|
| Stage / Depths | Combo | Land drop followed by a scripted, paid Stage activation creates Marit Lage. |
| Hexmage / Depths | Combo | Land drop followed by sacrificing Hexmage creates Marit Lage. |
| Red source | Mana | The land enables an immediately lethal Lightning Bolt. |
| Untapped red source | Mana | An untapped Mountain enables Bolt now; Evolving Wilds does not. |
| White source | Mana | The land enables Swords to Plowshares to exile a Dreadmaw. |
| Second white source | Mana | The land enables paying 2WW for Wrath to clear the enemy board. |
| Bolt lethal target | Target | Bolt kills the opponent immediately, rather than hitting another target. |
| Bolt wounded threat | Target | Bolt removes a Dreadmaw that already has three damage marked. |
| Exile indestructible | Target | Swords exiles Darksteel Colossus rather than a smaller creature. |
| Preserve a board | Cast or hold | Passing retains both friendly Dreadmaws against an empty enemy board. |
| Wrath while behind | Cast or hold | Wrath clears both enemy creatures while the AI controls none. |
| Empty board | Cast or hold | Passing preserves Wrath in hand when neither player has creatures. |

These are operational objectives, not claims that each outcome is uniquely optimal over an entire
game. For example, the Colossus case scores threat removal without pricing every consequence of the
opponent's life gain. Land cases assess the chosen land followed by one predefined line. The model
chooses the initial action only; subsequent line construction is scripted, with remaining small
engine choices handled by Forge's existing controller. No opponent responses are simulated. Passing
means declining the immediate line; later phases and later opportunities are outside the horizon.

## Controls and scoring

- Forge's baseline is the heuristic AI with flat Monte Carlo disabled, a fixed RNG seed and a
  rebuilt position for each choice. This is not a comparison against every available Forge AI mode.
- The same conservative view as the first experiment supplies the AI's hand and public information.
  Opponent hands and library identities are omitted. Personal player names are replaced by position IDs.
- Questions use neutral decision identifiers. Scoring objectives, expected outcomes, descriptive case
  IDs and the oracle results stay in a separate manifest and are never sent to the model.
- Export fails if a candidate cannot be executed, if the baseline does not fit the declared action
  set, or if the engine finds no distinguishing outcome. The legal-action set is complete only
  within each fixture's explicitly limited scope, not for all possible Magic decisions.
- Scores are binary objective passes. Wins/ties/losses compare valid model choices with baseline
  choices for that objective. Invalid, failed and missing reviews remain unscored and never inherit
  a baseline success as a model success. Pass totals use the complete suite denominator.
- One model call per curated position is an initial diagnostic, not a statistical performance
  estimate. There is no confidence interval or claim of improved win rate.
- Reports retain final visible answers, legality/format status, action IDs, engine outcomes, latency
  and reported token use. They do not collect hidden reasoning. Median and nearest-rank p95 latency
  include attempted requests; missing requests have no invented latency. Currency cost is not
  supplied by this ChatGPT subscription route, so token usage is reported without a dollar estimate.

## Verification

The focused tests execute all candidate lines, check deterministic rebuilds and baselines, verify
that scoring labels/hidden information stay out of questions, reject changed inputs/settings, and
prove cached reruns make zero additional model calls. Provider protocol, privacy and sign-in tests
from the first milestone remain included.

```powershell
mvn -pl forge-gui-desktop -am `
  '-Dtest=LlmShadowCaptureTest,ProviderClientTest,ChatGptAuthTest,DecisionBenchmarkTest' `
  '-Dsurefire.failIfNoSpecifiedTests=false' '-Djava.awt.headless=true' test
```

The follow-up [turn-sequence experiment](LLM-Turn-Sequences.md) adds full turn continuations,
opponent responses and a deck-plan A/B comparison. Held-out positions and repeated calls remain
future work. Neither suite enables live model-controlled moves in the ordinary game UI.

## Initial local run

GPT-6 Luna with high reasoning, through the authorized ChatGPT plan connection, returned 12 valid
recommendations. Its choices passed 12/12 fixture objectives; the seeded heuristic baseline passed
9/12. There were three objective improvements, nine ties and no objective regressions. Improvements
were Stage/Depths, Hexmage/Depths and exiling Colossus. Median request latency was 4274.5 ms;
nearest-rank p95 was 9022 ms. Reported usage was 10,918 input and 1,671 output tokens, including
1,007 reported reasoning tokens.

Inspection of the visible answers found an engine-backed rationale error: in `second-white-wrath`,
Luna selected Plains correctly but said neither land enabled Wrath immediately. The engine replay
paid for and resolved Wrath after the Plains drop in the current main phase. Correct action scores
therefore do not imply every explanation is correct. Explanations are not automatically scored.

The Colossus baseline passed priority. Its zero measures the objective of exiling the threat
immediately; later instant timing is outside the benchmark, so this is not proof that passing is a
strategic error over a complete turn. Both combo objectives were verified by actual token creation,
with normal mana/sacrifice payment and state-based actions.
