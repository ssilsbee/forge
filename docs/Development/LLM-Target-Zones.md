# Disenchant against a pending Howling Mine

The reported position is reproducible in the released move generator. This is a target-enumeration
and validation bug in the LLM adapter, rather than evidence that the model needs stronger reasoning.
The original phone request/response was not supplied, so this investigation establishes the behavior
of the matching code path rather than the model's reasoning in that particular game.

## What the model was told

In a controlled engine reproduction, Howling Mine appears in the `stack` array with `isSpell: true`
and is absent from both players' `Battlefield` arrays. The same snapshot nevertheless offers a
Disenchant action whose target is the Mine's `stack-...` ID. The action contract tells the model to
choose a listed legal move. Thus the state location is correct but the offered action contradicts it.

## Why the invalid target passed

The adapter combines battlefield cards, players, and stack spell/ability objects in one target pool.
It calls Forge's generic `canTarget` helper for each item. For a stack object this dispatches to
`canTargetSpellAbility`, which checks restrictions against the spell's host card (an artifact in this
case). That helper assumes the caller has already selected a Stack-targeting contract. Unlike the
Card branch, this path does not itself enforce the target-zone restriction. Disenchant's target
restriction defaults to Battlefield, but our adapter did not check it before supplying stack objects.
The same helper was used again at pre-execution validation, so that check repeated the omission.
Broadening alternatives to removal spells exposed this boundary in the adapter.

Disenchant targeting an artifact spell is not a legal Magic play. Under rule 109.2, an unqualified
artifact/enchantment reference means a permanent on the battlefield; spell references instead refer
to stack objects (109.2b). Source: [Wizards' Comprehensive Rules](https://media.wizards.com/2026/downloads/MagicCompRules%2020260925.txt).

## General fix

Before offering or approving a spell/ability target, the adapter now checks that the targeting
ability permits the Stack zone and that the target is still present on the live stack. It then runs
the existing engine target checks. This uses each card's target restrictions, with no card-name
exceptions or removal-effect blacklist. Battlefield targets keep their normal engine checks.

Regression cases exercise:

- Disenchant excluded while Howling Mine is pending, while Counterspell remains offered.
- Disenchant offered after the Mine resolves, then cast and resolved to actually destroy it.
- Counterspell actually countering the pending Mine, which never reaches the battlefield.
- Stifle actually countering Prodigal Pyromancer's activated ability while leaving its battlefield
  source in play; Counterspell is correctly excluded against that ability.

The first case failed on the original code specifically because Disenchant was offered against the
pending artifact. No model call is required to reproduce the incorrect legal menu. A stronger model
might detect the contradiction, but deterministic legality checks should prevent the action from
being offered in the first place. This incident does not establish that the model misunderstood
the stack, and does not require accumulating card-specific rules reminders in the prompt.
