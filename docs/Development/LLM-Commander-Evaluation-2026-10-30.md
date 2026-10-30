# Two-player Commander live evaluation — October 30, 2026

The LLM-controlled Goreclaw deck completed a full game against Forge's Isamaru deck and won. This is a working integration check, not evidence of comparative playing strength.

- Model: GPT-6 Luna, high reasoning, through the existing ChatGPT connection.
- Match: ordinary opening setup through a natural engine-reported game end; one LLM-advised AI seat and one ordinary Forge AI seat.
- Decks: singleton 100-card Commander decks, 37 basic lands each, using Forge's printed-card Commander filter and deck conformance validation, with funny sets, custom cards and unknown editions excluded. The creature lists are simple test decks, not tuned competitive decks.
- Result: Goreclaw won on global turn 31 (its 16th turn). Final life totals were 14 and -8; Forge reported loss by life total. Game wall time: 16m 28s.
- Requests: one deck plan plus 89 decisions, all reported as legal recommendations. The advisor remained enabled. No provider failures, corrective retries or request-budget exhaustion were recorded.

## What was exercised

The model received the separate commander section during deck analysis and command-zone state during play. It selected Goreclaw casts at `{3}{G}`, `{5}{G}`, and `{7}{G}` as commander tax increased from 0 to 2 to 4. It made two decisions to return Goreclaw to the command zone. The engine log confirms all three casts and the completed game.

The 89 decisions comprised 71 ordinary action choices, 12 attacker declarations, four blocker declarations and two commander-return decisions. The engine recorded 27 played advisor-selected actions: 14 alternatives and 13 prepared Forge moves. Other engine choices, mana payment, combat damage assignment and unsupported menus remained with Forge. Five blocking searches exceeded the bounded combat search across the two seats; the shared diagnostic counter does not identify which seat those belonged to.

## Timing and next target

Decision replies totaled 15m 51s; the separate deck plan took 28.9s. These timings overlap with game wait/worker-queue measurements and must not be added to those categories.

44 action replies selected pass, consuming 6m 46s, or 42.7% of decision reply time. The scheduler skipped another 34 routine pass windows. A concrete remaining inefficiency was repeated Forge proposals to activate Argothian Elder with no useful mana sink, and Beacon Behemoth vigilance activations outside useful combat windows. The model repeatedly declined them.

The next latency experiment should avoid repeated reviews of materially equivalent declined actions under explicit, engine-validated conditions. Invalidate any such continuation when the board, hand, stack, payment options or relevant timing changes. This run does not implement that optimization.

## Fixes made during this work

- Hardcoded Luna status messages now say LLM, including engine-played messages and pause/retry text. The desktop monitor title still identifies the actual selected model and reasoning effort. The connection default remains `gpt-6-luna`.
- Every ordinary non-land action candidate now includes engine-adjusted `manaCostNow`, as command-zone casts already did. Instructions explain that a blank legacy cost description is not a free spell, and that non-mana costs still matter. The stricter run showed Baloth Pup as `{1}{G}`, for example.
- Added an opt-in complete-match harness and reproduction documentation. Ordinary regression runs skip the live-account test.

The initial pilot finished in a commander-damage win but included a silver-border creature admitted by the broader deck check. It is excluded from the clean result above. It revealed the ambiguous blank-cost context that prompted the mana-cost fix.

## Validation and limits

93 targeted regression tests passed, with the live-account test skipped in that regression run. The subsequent complete live-match test passed separately. These checks cover the shared advisor implementation; the complete match was a desktop test harness run, not a human-on-phone playthrough. Two-player support does not establish multiplayer Commander coverage or full support for every complex card/choice.

The accompanying CSV contains visible final recommendation reasons, choice kinds and timings. It omits authentication data. Generated `.dck` files are included for reproducing the decks.
