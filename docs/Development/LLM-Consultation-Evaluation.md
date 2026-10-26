# Consultation scheduling evaluation — October 26, 2026

The advisor now lets the model request specific future consultation windows through a structured watch list returned with its ordinary move. Aggressive decks can request quiet play; reactive decks can request legal counterspell windows, opposing spells/abilities, end steps, board changes, or postcombat planning. No extra routing request is needed.

Forge's proposed actions, first useful own main-phase planning, new cards in the AI's hand, recognized targeted/board-wide threats, combat declarations, and supported resolution choices remain reviewed. Identical consecutive pass windows already accepted by the model are skipped. Policies expire on the AI's next own turn, including when that turn had no supported action windows. Invalid optional policies are ignored; rejected moves cannot commit a policy. Unsupported game decisions continue through Forge as before.

## Controlled live comparison

Both runs used ChatGPT with GPT-6 Luna and high reasoning effort. These are scripted engine positions, not complete matches or a phone UI test. Counts exclude deck planning.

| Scenario | Earlier requests | Scheduled requests | Skipped pass windows | Earlier response time | Scheduled response time |
|---|---:|---:|---:|---:|---:|
| White Equipment | 3 | 2 | 1 | 21.745 s | 20.456 s |
| Red Abilities | 3 | 1 | 2 | 22.259 s | 13.627 s |
| Total | 6 | 3 | 3 | 44.004 s | 34.083 s |

The red deck returned `{"watch":[]}`, then Forge skipped its subsequent own main-phase pass and opposing end-step pass. The equipment deck requested opposing board changes and end steps, equipped a creature, skipped the idle own-main window, and still reviewed its attack declaration. All three model recommendations were valid and the equipment/activation actually executed in the engine.

This is a 50% request-count reduction in these two positions. Response latency varied substantially between runs; the observed response-time reduction of about 23% is not a stable performance estimate. Deck planning is excluded, and the comparison does not establish playing strength or a full-game speedup. The equipment target also differed between runs, so this is a behavioral smoke comparison rather than a deterministic benchmark.

## Diagnostics

The existing Status and Insights tabs now show asked/skipped counts with reasons, plus accepted policy updates. A skipped window does not produce an Input/Output history entry or consume a model request budget. The exact model input includes the current policy, supported rules, reason for consultation, and policy expiry.

The offline regression suite covers quiet play, reactive counter availability using a real Counterspell candidate and opposing spell on the engine stack, mandatory decisions, new-card triggers, threats, policy expiry/isolation, malformed policies, rejected choices, and a mocked provider path proving skipped windows make no network calls. Existing combat, tutor, activated-ability, memory, and provider checks are included.
