# Two-player Commander advisor

Commander variants launched through the Constructed lobby and explicit Commander game types can
use the advisor with exactly two players. Multiplayer remains gated. Existing action-complexity,
privacy, cancellation and request-budget limits apply.

Deck analysis includes a separate `commanders` array with each commander's name, mana cost, types
and Oracle text. The main-deck count excludes these cards. Instructions ask for commander synergies,
command-zone access and recast planning. Deck-plan version v2 and the expanded input hash ensure a
commander change cannot reuse a plan for a different commander configuration.

During play, snapshots identify the format and player count, mark visible cards with `isCommander`,
and expose each owner's commander IDs, current zones, command-zone cast counts and standard generic
tax. Per-player `commanderDamageReceived` entries identify damage from each individual commander,
with the Commander damage threshold. Existing public Command arrays include card rules text; hidden
hands and libraries retain their prior visibility limits.

The alternative-action generator includes the player's own commanders in the command zone. It uses
Forge's may-play spell options to prepare those casts, then its existing target, timing and cost
checks, including commander tax. Command-zone cast candidates include `castFrom`, the standard
`commanderTaxGeneric` surcharge, and engine-adjusted `manaCostNow` (mana component, not a prediction
of every additional non-mana cost). Casts from hand are ordinary hand casts and incur no commander
tax. Modal, linked, variable-X and other unsupported structures can still be reviewed through Forge's
prepared move instead of independent enumeration.

Commander return decisions route through the bounded `commander_return` choice contract: both the
graveyard/exile state-based prompt and the optional hand/library replacement prompt can reach the
model. The owner chooses `command` or `keep`; unavailable/hidden/failed choices retain Forge's
original result. These use the engine's existing legal prompts, not new replacement rules.

All 93 targeted advisor checks passed. Eight Commander tests cover an actual command-zone cast and
taxed recast with real mana payment, tax-driven unaffordability, casting from hand without tax,
public commander/damage context without opponent hand leakage, separate planner commander inputs
and distinct cache hashes, declining a graveyard return, replacing a hand bounce with a command-zone
move, and preserving the multiplayer gate. These are engine and captured-context tests; no live-model
Commander match or playing-strength benchmark has been performed.
