# Forge LLM advisor: live evaluation

Model: **GPT-6 Luna, high reasoning**. The model and reasoning setting stayed fixed throughout.

## What was exercised

Five repeatable positions used real model calls through the current shared advisor and actual Forge costs, stack resolution and combat declarations. A separate Android emulator game exercised the opening turns with the red ability deck against a manually played green deck. These were **not completed matches or a comparative win-rate study**, and no physical phone was controlled.

Six 60-card test decks and an opt-in scenario runner were added. The runner never calls a provider during ordinary tests; an explicit live-evaluation flag and an existing local ChatGPT connection are required. The Artifact Search position deliberately casts Tinker to isolate its resolution choice.

## Controlled results

| Scenario | Live decisions | Average reply | Observed behavior |
|---|---:|---:|---|
| Blue Resources | 2 | 11.67 s | Held Counterspell mana rather than spend all four Islands on either draw ability. Also passed at the opponent's end step. |
| Blue Efficient Draw | 3 | 9.92 s | Activated Archivist without spending mana, actually drew Unsummon, then revised its notes to account for the new card. |
| White Equipment | 3 | 7.25 s | Equipped Silvercoat Lion, declined a redundant equipment move, then attacked with both creatures into one Wall of Wood. |
| Red Abilities | 3 | 7.42 s | Used the ready Prodigal Pyromancer to kill Llanowar Elves, preserved Fanatical Firebrand, and never tapped the sick Pyromancer. |
| Artifact Search | 1 | 5.47 s | At 6 life, chose Wurmcoil Engine from 30 eligible artifacts during Tinker resolution. Forge put it onto the battlefield. |

All **12 recommendations** named valid offered choices. The selected draw, equipment and damage activations were paid and resolved by the engine; the attack declaration passed engine validation. Accepted strategy notes appeared in **7 subsequent requests**. The red case also exercised an explicit empty note, clearing the prior memo. No extra model calls were made solely to update notes.

Example of useful continuity: after drawing Unsummon with Archivist, the next note reserved blue mana for Counterspell while considering Unsummon as a response to an attack or a targeted threat. The equipment notes retained the chosen attachment and gave conditions for reconsidering it.

The blue end-step passes were conservative. They are worth revisiting, but this sample does not prove that notes caused a bad decision: the fixture did not explicitly provide a preceding opponent-pass event, and no run without memory was compared.

## Timing findings

- Mean model reply: **8.55 seconds**, range **4.89–14.15 seconds** across the 12 controlled decisions.
- The **7 pass recommendations** consumed **73.45 of 102.57 seconds** of model response time: about **72%** of the total.
- These are a small set of selected positions, not a general model performance benchmark. Reported reply time includes the provider response and local parsing, rather than network time measured separately.

The strongest next latency experiment is reducing the cost of routine pass decisions. Compare a cheaper reasoning setting on the same captured positions, or investigate a validated conditional continuation that can pass after an agreed action when nothing relevant changes. A pass must not be reused across changed board state, timing or legal options without revalidation.

## Android observations

The current notes/abilities APK was installed in the Android 15 emulator. A real local match opened with the red ability deck against the green deck. The AI developed its mana and creature board, removed the human's Llanowar Elves and attacked. The human then cast Grizzly Bears. The advisor was paused during the opening while investigating the reported tutor gap; this game was not played to completion.

The **22.03-second initial deck analysis finished during mulligan selection**, before any turn-decision request. At the captured checkpoint, Insights showed **8 valid model replies**, **6 engine-played advisor actions**, and **7 accepted note updates**. The model-reply averages were **7.17 seconds** for action choices and **5.85 seconds** for attacker choices. These measurements are separate from the controlled-scenario totals above. A validated recommendation is not by itself proof of execution, particularly around a manual pause.

Screenshots and the full aggregate Insights text accompany this report. The emulator is left with the advisor paused; its temporary orientation override was removed.

## Tinker gap found and fixed

The reported issue was real: Tinker resolves through `chooseSingleCardForZoneChange`, which bypassed the existing generic entity-choice bridge. That dedicated callback now offers the model the legal search candidates.

Hidden library identities can be included only when this search's explicit engine reveal contains those exact cards, with matching library zone and owner. The offered cards are sorted independently of library order. The bridge does not grant permanent viewing permissions or expose other library cards in normal turn context. Missing permission, blind searches and oversized pools retain Forge's prepared choice. This is bounded single-card search support; subsequent picks without a fresh reveal can still fall back to Forge.

All **54 targeted regression tests passed**, including actual Tinker casting and resolution with its artifact-sacrifice cost, missing/blind reveal permission, unrelated hidden cards, and Aven Mindcensor's top-four restriction. The live Tinker check then confirmed that the new prompt reached the model and that its selected Wurmcoil Engine entered play.

## Recommended next work

1. Measure a faster policy for routine passes against these captured decisions, checking quality as well as speed.
2. Add a short engine-generated event history, especially who last passed priority and what actually resolved. Keep it distinct from model-authored intent notes.
3. Extend coverage to modes, X values, multiple targets and multi-card searches, preserving engine-defined legal choices and the existing fallback.
4. Continue full-match testing on the user's phone before drawing conclusions about overall playing strength.

The results JSON and CSV contain visible final answers, notes and timings without authentication material. The deck ZIP includes installation guidance and the reproducible scenario instructions.
