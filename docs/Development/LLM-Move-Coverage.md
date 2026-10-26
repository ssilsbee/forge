# Move coverage and corrective selection retry

## Candidate generation

The live action adapter no longer restricts alternatives to a short list of effect names. It scans
the AI's hand for spells and land plays, and its battlefield for non-mana activated abilities.
Structurally simple effects can be offered independently of Forge's proposed move, including
discard, draw, ritual mana, removal, pump and permanent casting. Forge's proposed move and passing
are still included.

The existing bounds remain: zero or one target, no divided targeting, no variable X cost, no modal
selection, no linked subability, no face-down/double-faced alternatives, and at most 48 alternatives.
Timing, target eligibility, affordability and summoning sickness are checked by Forge, then checked
again before returning the chosen action. Mana payment and unsupported resolution choices remain
Forge-controlled. This remains an incomplete menu, and both scope and model instructions explicitly
say that an omitted card may still be legally playable.

The reported Ritual/Hymn case now has a deterministic engine regression: one Swamp pays for Dark
Ritual; the Ritual resolves to three black mana; Hymn to Tourach appears independently while Forge's
baseline proposes Disrupting Scepter; Hymn is cast on the opponent, discards two cards, and leaves
one black mana. Scepter's own discard activation is also independently offered and pays three mana
plus its tap cost in a separate engine test.

## One corrective retry

An invalid/malformed response, an unlisted action ID, or an ordinary action that fails pre-cast
timing/target/cost revalidation can receive one corrective request. For ordinary actions, the
adapter rebuilds the state and candidates and drops a baseline that is no longer legal. The second
input contains a `correction` object with the rejection reason, retry number and maximum. It has
a fresh decision ID, and the monitor keeps both requests/results.

Rejected attempts discard their proposed notes and consultation policies. The retry counts toward
normal request limits. A second invalid response falls back to Forge. Connection failures, timeouts,
pausing, game end, and failures after payment/execution starts do not trigger this retry. Combat and
resolution menus also retry invalid IDs/responses once; later combat semantic validation still uses
the existing fallback rather than regenerating combat plans. Notes/policies only commit after the
appropriate acceptance callback.

The regression suite covers the Ritual/Hymn sequence, Scepter activation, ordinary draw/discard/pump
alternatives, retained structural exclusions, invalid-ID recovery, stale action removal, two-attempt
limits, no retry for a transport failure, provider-worker error propagation/recovery, rejected-memory
isolation, and resolution-choice recovery. Existing combat, tutor, scheduling, memory and provider
tests run alongside these checks. These are deterministic engine and mocked-provider tests, not a
new live-model strength evaluation.
