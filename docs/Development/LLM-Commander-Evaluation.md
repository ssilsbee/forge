# Full two-player Commander integration evaluation

`LiveCommanderMatchTest` is an explicit live-account test. It runs the ordinary game loop from opening setup to a winner, with the live advisor controlling one AI seat and Forge controlling the other. It deliberately leaves `noGUIUser` unset because that flag bypasses the live bridge. No test fixture injects a mid-game position.

The test generates two singleton 100-card decks: Goreclaw, Terror of Qal Sisma and Isamaru, Hound of Konda. Each has 37 basic lands and 62 short-text, mono-color creatures. The pool uses Forge's Commander printed-card legality filter, excludes unknown editions, funny sets and custom cards, and checks Commander deck conformance. These are integration decks, not tuned competitive lists.

Run from the repository root with Java 17 and Maven:

```powershell
mvn -pl forge-gui-desktop -am test `
  -Dtest=LiveCommanderMatchTest `
  -Dsurefire.failIfNoSpecifiedTests=false `
  -Dforge.llm.liveEvaluation=true `
  -Dforge.llm.dataDir=C:/path/to/private/llm-data `
  -Dforge.llm.evaluation.output=C:/path/to/private/evaluation-output
```

The private data directory must already contain a working ChatGPT connection under `connections`. The harness uses GPT-6 Luna with high reasoning, the existing production request budgets and scheduler, and a 40-minute test deadline. Without the opt-in property the test skips and makes no model calls. Do not run live evaluations concurrently against the same private connection/cache directory.

The output directory contains both decks, visible model inputs and replies, the engine game log, the final visible state, and aggregate Insights. Treat this as private working data: it contains both test decks and game information. It does not intentionally export credentials. Copy only reviewed summaries and generated decks into public artifacts.

A successful test proves the game completed and the advisor was consulted. Inspect Insights and the engine log to distinguish valid recommendations from actions actually executed, and to check for budget exhaustion, provider failures or fallback. Some engine diagnostic counters observe both seats; do not attribute every Forge action or bounded-combat fallback to the LLM seat. Timing categories overlap and must not be added together.

A single game is an integration check. It does not measure relative playing strength, multiplayer support, arbitrary Commander complexity, or interaction with a human/Android UI.

## Pilot finding: repeated mana-enabling actions

The first complete pilot ended in a commander-damage win on global turn 23, with 42 decision replies and one deck plan. It is excluded from the validated deck result because the original generic pool admitted a silver-border card. That pilot also exposed a blank legacy cost description being interpreted as a free creature spell. Candidates now include the engine-adjusted `manaCostNow` for ordinary non-land actions as well as commander casts; instructions explicitly distinguish a blank legacy description from a zero mana cost.

In the stricter run, repeated proposals to activate Argothian Elder produced model passes while the owner had no hand or useful mana sink. This is a concrete future scheduler case: the existing rule reviews Forge-proposed actions even when the model previously declined a materially equivalent action. A safe continuation would need engine-validated state conditions and invalidate on relevant changes, rather than blindly reuse a pass across phases, targets or new cards. This evaluation does not implement that optimization.

User-facing messages that named Luna irrespective of the selected model now say LLM. The desktop title retains its actual configured model and reasoning effort; `gpt-6-luna` remains a connection default.
