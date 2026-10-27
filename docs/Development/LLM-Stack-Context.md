# Announced X in stack context

Reported October 27, 2026: after a human cast Fireball for X = 4, the model noted that the stack did
not show X. Source inspection confirmed the serializer supplied Oracle text, ability description,
controller, effect and targets, but did not include the engine's recorded X value as a field.

Each stack entry now includes:

- `announcedX`, when Forge has recorded a value on that stack spell/ability. Chosen zero is included
  as the numeric value `0`; missing/unknown values are omitted, rather than invented as zero.
- `stackDescription`, the engine-rendered stack text, alongside the existing description and Oracle
  text. For a single-target Fireball at X = 4, this describes four damage and identifies the target.

The value comes directly from the stack ability's `getXManaCostPaid`, including a copied ability
that retains X. It is not inferred from available mana, card name, printed text, or total mana spent.
Model instructions distinguish announced X from total cost and final damage: target division,
modifiers and other effects still matter. The context does not attempt to predict all resolution
outcomes or enumerate variable-X alternatives for the model to cast.

All 85 targeted advisor regression checks passed. New engine tests actually cast and resolve Fireball
with X = 4 and X = 0, inspect the model-visible stack entry and rendered damage text, and verify the
resulting life total. Additional checks cover preserving X from a copied ability, omitting an unknown
value rather than reporting zero, and leaving ordinary non-X spells without an announcedX field.
No live model call was needed to establish or fix this serialization gap.
