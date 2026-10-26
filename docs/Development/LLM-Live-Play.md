# Playable hybrid AI and LLM Monitor

Launch the Windows prototype with the existing authorized provider connection:

```powershell
.\tools\llm-advisor.ps1 -Action play-live -Provider chatgpt `
  -Model gpt-6-luna -ReasoningEffort high
```

Ordinary `forge.exe` startup and the earlier `play` action retain Forge's normal AI. The explicit
`play-live` action opens Forge plus a separate LLM Monitor. The script supplies the same Java 17
compatibility options as Forge's packaged launcher, and uses the isolated `.forge-llm/profile`
profile. An existing OpenRouter connection can be used with `-Provider openrouter -Model MODEL`
and the locally configured `OPENROUTER_API_KEY`. No OpenRouter live verification was performed.

## Play

Start a local **Constructed** game with a human and an AI. Select a deck for each player as usual.
The launcher copies the two synthetic 60-card decks in `tools/llm-demo-decks/` into the isolated
profile without overwriting existing decks. Select them from **Custom User Decks**. White is a convenient
human deck and Red a convenient AI deck; they are demonstration lists, not tuned recommendations.

The first supported decision prepares a compact strategy from the AI's actual registered main
deck. Subsequent decisions prepend the cached strategy to fresh state. Deck analysis may take
longer than an individual move. No opponent hand identities or shuffled library order are sent.
Recent-turn memory improvements remain deferred.

The monitor has a request list and tabs for exact input, the final visible answer/full report,
and moves/status. It shows the selected move, latency, fallback messages and whether the engine
played it. The input tab shows the AI's hand, so minimize the monitor to play without that view.
Closing its window minimizes it; restore it from the taskbar. **Use Forge / cancel request**
pauses Luna and cancels a pending request. **Enable Luna** resumes it. Already completed model
requests may still count toward provider usage even if their move is cancelled.

## Deliberately limited control

`LiveDecisionBridge` is an opt-in hook after the normal Forge thinking deadline. Forge's prepared
move is offered along with normal hand land drops, supported simple permanent spells, selected
one-target damage/counter/zone-change spells, Stage/Hexmage-style Clone/RemoveCounter abilities,
and passing. The model also selects which same-name legendary permanent to keep. Some spells
and abilities are excluded: multiple/divided targets, alternate faces, unsupported chains and
oversized action sets. This is a conservative enumeration, not a complete Magic action space.

Forge still controls mulligans, combat, mana sources, most triggered/resolution choices and
unsupported actions. A normal prepared Forge move may involve choices beyond the alternative
enumerator. The monitor labels this hybrid scope; it is not an end-to-end LLM Magic player.

Only two-player Constructed games in play are eligible; simulation copies/no-GUI games are
excluded. The ordinary startup installs no adapter. All game/card inspection happens on the
game thread, and only detached JSON reaches the provider worker. The game waits at the decision
while the display thread remains responsive. The result must identify a current offered action;
timing, targets and payment are rechecked on the game thread before returning the move to Forge.
The engine reports whether the subsequent play succeeded. A concession/game end during a
request prevents a model move from being applied.

Invalid answers, failed requests, unsupported views and timeouts return the original Forge
choice. Three consecutive failed requests pause Luna until explicitly enabled. Limits of 20
decisions per turn and 150 per player/game keep an unattended prototype bounded. The provider
deadline defaults to 60 seconds; the outer wait allows a deck-plan request plus a decision request
and a short margin. There are no automatic provider retries. Fallback preserves Forge's choice,
not a guarantee that the original choice remains playable after an unrelated state change.

## Local records and verification

Owner-only reports under `.forge-llm/live/session-*/` retain the exact decision input, visible final
answer, recommendation, validation status, usage and latency. Cached plans live separately under
`live-deck-plans/`. Engine execution and cancellation notices appear in the monitor's Moves and
status tab; a saved recommendation is not itself proof that the move executed. Credentials remain
in the separate `connections/` folder and are not displayed. Hidden reasoning is never collected.
The monitor retains at most 60 request entries and a bounded status history.

The six new offline tests verify an actual land drop and paid Stage activation, invalid/stale
selection fallback, disabled/simulation isolation, opponent-card privacy, real mocked provider
I/O with a single deck plan and saved inputs, pause control, and the three-error circuit breaker.
Together with the existing suites, all 34 tests passed and the Windows build packaged.

```powershell
mvn -pl forge-gui-desktop -am `
  '-Dtest=LlmShadowCaptureTest,ProviderClientTest,ChatGptAuthTest,DecisionBenchmarkTest,TurnSequenceExperimentTest,LiveHybridTest' `
  '-Dsurefire.failIfNoSpecifiedTests=false' '-Djava.awt.headless=true' package
```

The first Windows smoke test used GPT-6 Luna high through ChatGPT in a real human-versus-AI match.
Luna generated a red Goblin deck plan (25,078 ms), chose Mountain (4,751 ms), then cast Goblin
Arsonist (3,196 ms) after Forge's baseline passed. The human could respond to the spell, let it
resolve and play a Plains on the following turn. This verifies the live path, not general
playing strength or full-game completion. Further smoke checks should exercise normal targeting,
combat progression, and the monitor's pause/resume/cancellation controls.

## Consultation scheduling

Supported action menus are filtered locally before queuing provider work. Forge's proposed actions,
combat declarations, and resolution choices remain reviewed. A built-in pass is reviewed at the
first useful own main-phase window, after a new card enters the AI's hand, or when an opposing stack
action targets the AI/its board or has a recognized board-wide removal/damage effect. These are
conservative triggers, not a complete threat assessment. Existing legal-action scope limits still apply.

Each normal reply can include an optional policy alongside `actionId`, `reason`, and strategy notes:

```json
{"actionId":"pass","reason":"Hold up a counter.","consultationPolicy":{"watch":["counterspell_window","opponent_end_step"]}}
```

The watch rules cover opposing spells (`opponent_spell`), opposing abilities (`opponent_ability`),
an opposing stack action with a legal affordable counter candidate (`counterspell_window`), an empty
opposing end step (`opponent_end_step`), own postcombat main phase (`own_second_main`), opposing
battlefield changes (`opponent_board_change`), or every changed action window (`every_action_window`).
An aggressive deck can return an empty watch list. Mandatory reviews above cannot be disabled.
An otherwise passing window that exactly matches the immediately previous accepted review is skipped,
even under `every_action_window`; decision UUIDs and advisory memory are excluded from this comparison.

Policies are committed synchronously only after engine acceptance of the associated choice, expire
at the start of the AI's next own turn, and are isolated by game/player. Omission retains the current
policy; a malformed optional policy is ignored without discarding a legal move. Without an explicit
policy, legal counter windows and opposing end steps with a draw candidate receive conservative
coverage. Policy state is bounded and contains detached JSON only.

No extra model call decides whether to consult. Skips consume no request budget and create no fake
model response entries. Status and Insights report why each window was asked or skipped. Reasoning
effort is unchanged (the initial live evaluation uses GPT-6 Luna high), so scheduling can be evaluated
independently of model quality or effort changes. The scheduler and mocked live-provider tests cover
quiet aggro play, reactive control, expiry, rejected policies, mandatory reviews, unchanged windows,
and the absence of network calls for skipped passes.
