# Advisor evaluation decks

These are 60-card test decks for exercising the advisor, rather than tuned competitive lists.

- **Blue Resources:** Azure Mage and Jayemdae Tome both need four mana to draw. A prepared scenario puts Counterspell in hand to test whether the advisor preserves interaction.
- **Blue Efficient Draw:** replaces Azure Mage with Archivist. A ready Archivist can draw without spending the Islands reserved for Counterspell.
- **White Equipment:** Bonesplitter and small creatures test equipment targeting, attachment context and attacks into a blocker.
- **Red Abilities:** Prodigal Pyromancer and Fanatical Firebrand test repeatable damage, sacrificing a creature, and summoning sickness.
- **Green Opponent:** uncomplicated creatures, combat tricks and artifact removal for manual games against the other decks.
- **Artifact Search:** a single Tinker and varied artifact targets test the dedicated library-search resolution prompt. Tinker is restricted in Vintage and unavailable in some other formats; use an appropriate test match.

For desktop play, `tools/llm-advisor.ps1 -Action play-live` copies new `.dck` files into its isolated constructed-deck folder. For Android, copy the extracted `.dck` files into the Forge asset folder's `data/decks/constructed` directory and restart Forge; select them under Custom User Decks. Do not overwrite personal deck files.

## Reproducible live scenarios

`forge-gui-desktop/src/test/java/forge/llm/LiveAdvisorScenarioTest.java` builds controlled positions from these actual decks and uses the current shared advisor, engine payment, stack resolution and combat declarations. It is **not** a complete-match or win-rate benchmark. The Tinker scenario deliberately casts Tinker to isolate the model's search choice.

Normal tests skip this class's network evaluation. To run it explicitly, use the project's Java 17/Maven environment with:

```text
mvn -pl forge-gui-desktop -am -Dtest=LiveAdvisorScenarioTest -Dsurefire.failIfNoSpecifiedTests=false -Dforge.llm.liveEvaluation=true -Dforge.llm.model=gpt-6-luna -Dforge.llm.reasoningEffort=high -Dforge.llm.dataDir=<absolute advisor data directory> -Dforge.llm.evaluation.output=<absolute output directory> test
```

The data directory must already contain a ChatGPT connection in `connections/`. Authentication files are read locally and never copied into the results. This run uses the connected account's allowance. No account credentials should be added to a deck or committed to Git.

To select one case, add `-Dforge.llm.evaluation.scenarios=Artifact Search` (quote the entire argument in a shell). Multiple names can be separated by `|`. Reports include exact visible inputs, final answers, notes, timings and actual engine events; preserve them privately if using personal decks.

The fixtures assert that the opponent's hidden Giant Growth is absent from engine-provided context. Model-authored hypotheses in notes are not treated as evidence of an engine information leak. They also assert legal attacks, bounded stack resolution and no tap activation from the sick pinger.
