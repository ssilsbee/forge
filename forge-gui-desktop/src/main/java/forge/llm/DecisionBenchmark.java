package forge.llm;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import forge.ai.ComputerUtil;
import forge.ai.ComputerUtilCost;
import forge.ai.LobbyPlayerAi;
import forge.ai.llm.LandDecisionSnapshot;
import forge.ai.llm.PrivateFiles;
import forge.deck.Deck;
import forge.game.Game;
import forge.game.GameObject;
import forge.game.GameRules;
import forge.game.GameStage;
import forge.game.GameType;
import forge.game.Match;
import forge.game.ability.ApiType;
import forge.game.card.Card;
import forge.game.phase.PhaseType;
import forge.game.player.Player;
import forge.game.player.RegisteredPlayer;
import forge.game.spellability.SpellAbility;
import forge.game.zone.ZoneType;
import forge.model.FModel;
import forge.util.MyRandom;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Random;
import java.util.UUID;

/** A curated, finite-horizon benchmark, not a generic legal-action enumerator or win-rate estimator. */
public final class DecisionBenchmark {
    public static final String VERSION = "forge-decisions-v1";
    private static final long SEED = 20261024L;
    private static final Gson JSON = new GsonBuilder().setPrettyPrinting().create();
    private enum Goal { TOKEN, LETHAL, EXILE, REMOVE_THREAT, CLEAR_BOARD, PRESERVE_BOARD, CONSERVE_SPELL }
    private record Spec(String id, String category, String objective, String[] board, String[] hand,
                        String[] enemy, String followup, Goal goal) { }
    private record Position(Game game, Player ai, Player opponent) { }
    private record Action(String id, String kind, int source, String target, String description) { }

    private DecisionBenchmark() { }

    private static String[] cards(String... names) { return names; }

    private static final List<Spec> CASES = List.of(
        new Spec("stage-depths", "combo", "Create Marit Lage through the scripted Stage activation after the land drop.",
                cards("Thespian's Stage", "Island", "Island"), cards("Dark Depths", "Swamp"), cards(), "stage", Goal.TOKEN),
        new Spec("hexmage-depths", "combo", "Create Marit Lage through the scripted Hexmage activation after the land drop.",
                cards("Vampire Hexmage", "Swamp", "Swamp"), cards("Dark Depths", "Mountain"), cards(), "hexmage", Goal.TOKEN),
        new Spec("red-source-lethal", "mana", "Enable and resolve Lightning Bolt for lethal damage this main phase.",
                cards("Island"), cards("Mountain", "Island", "Lightning Bolt"), cards(), "bolt", Goal.LETHAL),
        new Spec("untapped-red-lethal", "mana", "Enable and resolve Lightning Bolt for lethal damage this main phase.",
                cards("Island"), cards("Mountain", "Evolving Wilds", "Lightning Bolt"), cards(), "bolt", Goal.LETHAL),
        new Spec("white-source-exile", "mana", "Enable and resolve Swords to Plowshares on the opposing Dreadmaw.",
                cards("Island"), cards("Plains", "Island", "Swords to Plowshares"), cards("Colossal Dreadmaw"), "swords", Goal.EXILE),
        new Spec("second-white-wrath", "mana", "Enable Wrath of God and clear the opponent's creatures this main phase.",
                cards("Plains", "Island", "Island"), cards("Plains", "Island", "Wrath of God"),
                cards("Grizzly Bears", "Colossal Dreadmaw"), "wrath", Goal.CLEAR_BOARD),
        new Spec("bolt-face-lethal", "target", "Win immediately with Lightning Bolt; self-damage and other targets are alternatives.",
                cards("Mountain"), cards("Lightning Bolt"), cards("Grizzly Bears"), "", Goal.LETHAL),
        new Spec("bolt-wounded-threat", "target", "Remove the opposing 6/6 Dreadmaw that already has three damage marked.",
                cards("Mountain"), cards("Lightning Bolt"), cards("Colossal Dreadmaw", "Grizzly Bears"), "", Goal.REMOVE_THREAT),
        new Spec("exile-indestructible", "target", "Exile the opposing Darksteel Colossus, rather than the smaller creature.",
                cards("Plains"), cards("Swords to Plowshares"), cards("Darksteel Colossus", "Grizzly Bears"), "", Goal.EXILE),
        new Spec("wrath-preserve-board", "cast_or_hold", "Preserve both friendly Dreadmaws when the opponent has no creatures.",
                cards("Plains", "Plains", "Plains", "Plains", "Colossal Dreadmaw", "Colossal Dreadmaw"),
                cards("Wrath of God"), cards(), "", Goal.PRESERVE_BOARD),
        new Spec("wrath-behind-board", "cast_or_hold", "Clear the opponent's two creatures while controlling none yourself.",
                cards("Plains", "Plains", "Plains", "Plains"), cards("Wrath of God"),
                cards("Colossal Dreadmaw", "Grizzly Bears"), "", Goal.CLEAR_BOARD),
        new Spec("wrath-empty-board", "cast_or_hold", "Keep Wrath of God in hand when neither player has a creature.",
                cards("Plains", "Plains", "Plains", "Plains"), cards("Wrath of God"), cards(), "", Goal.CONSERVE_SPELL)
    );

    private static Position position(Spec spec) throws IOException {
        MyRandom.setRandom(new Random(SEED));
        GameRules rules = new GameRules(GameType.Constructed);
        List<RegisteredPlayer> players = List.of(
                new RegisteredPlayer(new Deck()).setPlayer(new LobbyPlayerAi("AI", null)),
                new RegisteredPlayer(new Deck()).setPlayer(new LobbyPlayerAi("Opponent", null)));
        Match match = new Match(rules, players, "Decision benchmark");
        Game game = new Game(players, rules, match);
        Player ai = game.getPlayers().get(0);
        Player opponent = game.getPlayers().get(1);
        game.setAge(GameStage.Play);
        game.AI_FLAT_MONTE_CARLO = false;
        game.getPhaseHandler().devModeSet(PhaseType.MAIN1, ai, 4);
        game.getPhaseHandler().onStackResolved();
        ai.setLife(20, null);
        opponent.setLife(spec.goal == Goal.LETHAL ? 3 : 20, null);
        for (String name : spec.board) { add(name, ai, ZoneType.Battlefield); }
        for (String name : spec.hand) { add(name, ai, ZoneType.Hand); }
        for (String name : spec.enemy) { add(name, opponent, ZoneType.Battlefield); }
        // Fixed hidden contents are never passed to the model or used by the scoring rules.
        add("Wrath of God", opponent, ZoneType.Hand);
        for (int i = 0; i < 10; i++) {
            add("Forest", ai, ZoneType.Library);
            add("Mountain", opponent, ZoneType.Library);
        }
        game.getAction().checkStateEffects(true);
        if (spec.id.equals("bolt-wounded-threat")) {
            find(opponent, ZoneType.Battlefield, "Colossal Dreadmaw").setDamage(3);
        }
        return new Position(game, ai, opponent);
    }

    private static Card add(String name, Player player, ZoneType zone) throws IOException {
        var paper = FModel.getMagicDb().getCommonCards().getCard(name);
        if (paper == null) { throw new IOException("Missing benchmark card: " + name); }
        Card card = Card.fromPaperCard(paper, player);
        card.setGameTimestamp(player.getGame().getNextTimestamp());
        player.getZone(zone).add(card);
        return card;
    }

    private static Card find(Player player, ZoneType zone, String name) {
        for (Card card : player.getCardsIn(zone)) { if (card.getName().equals(name)) { return card; } }
        return null;
    }

    private static String key(GameObject target, Position p) {
        if (target instanceof Player) { return target == p.ai ? "p0" : "p1"; }
        return "card-" + ((Card) target).getId();
    }

    private static GameObject target(String id, Position p) {
        if ("p0".equals(id)) { return p.ai; }
        if ("p1".equals(id)) { return p.opponent; }
        if (id != null && id.startsWith("card-")) {
            return p.game.getCardsIn(ZoneType.Battlefield).stream()
                    .filter(c -> ("card-" + c.getId()).equals(id)).findFirst().orElse(null);
        }
        return null;
    }

    private static void chooseTarget(SpellAbility ability, GameObject target) {
        ability.resetTargets();
        if (target instanceof Player player) { ability.getTargets().add(player); }
        else if (target instanceof Card card) { ability.getTargets().add(card); }
    }

    private static List<Action> actions(Spec spec, Position p) {
        List<Action> actions = new ArrayList<>();
        boolean landCase = !spec.followup.isEmpty();
        for (Card card : p.ai.getCardsIn(ZoneType.Hand)) {
            SpellAbility ability = card.getFirstSpellAbility();
            ability.setActivatingPlayer(p.ai);
            if (landCase) {
                if (card.isLand() && p.ai.canPlayLand(card, false, ability)) {
                    actions.add(new Action("land-" + card.getId(), "land", card.getId(), null, "Play " + card.getName() + "."));
                }
            } else if (ability.usesTargeting()) {
                List<GameObject> targets = new ArrayList<>(p.game.getCardsIn(ZoneType.Battlefield));
                targets.add(p.ai);
                targets.add(p.opponent);
                for (GameObject target : targets) {
                    ability.resetTargets();
                    boolean legalTarget = ability.canTarget(target);
                    chooseTarget(ability, target);
                    if (legalTarget && ability.isTargetNumberValid() && ability.canPlay()
                            && ComputerUtilCost.canPayCost(ability, p.ai, false)) {
                        String description = target instanceof Card c ? c.getName() : (target == p.ai ? "yourself" : "opponent");
                        actions.add(new Action("cast-" + card.getId() + "-" + key(target, p), "cast", card.getId(),
                                key(target, p), "Cast " + card.getName() + " targeting " + description + "."));
                    }
                }
                ability.resetTargets();
            } else if (ability.canPlay() && ComputerUtilCost.canPayCost(ability, p.ai, false)) {
                actions.add(new Action("cast-" + card.getId(), "cast", card.getId(), null, "Cast " + card.getName() + "."));
            }
        }
        actions.add(new Action("pass", "pass", -1, null, "Pass priority, retaining the cards in hand."));
        return actions;
    }

    private static String baseline(Spec spec, List<Action> actions) throws IOException {
        Position p = position(spec);
        List<SpellAbility> selected = p.ai.getController().chooseSpellAbilityToPlay();
        if (selected == null) { return "pass"; }
        if (selected.size() != 1) { throw new IOException("Baseline outside benchmark scope: " + spec.id); }
        SpellAbility ability = selected.get(0);
        String target = null;
        if (!ability.getTargets().getTargetCards().isEmpty()) { target = key(ability.getTargets().getTargetCards().get(0), p); }
        for (Player player : ability.getTargets().getTargetPlayers()) { target = key(player, p); break; }
        for (Action action : actions) {
            if (action.source == ability.getHostCard().getId() && java.util.Objects.equals(action.target, target)) { return action.id; }
        }
        throw new IOException("Baseline action/targets are outside the declared candidates: " + spec.id);
    }

    private static void settle(Position p) throws IOException {
        for (int i = 0; i < 40; i++) {
            p.game.getAction().checkStateEffects(true);
            p.game.getTriggerHandler().runWaitingTriggers();
            p.game.getStack().addAllTriggeredAbilitiesToStack();
            if (p.game.isGameOver() || p.game.getStack().isEmpty()) { return; }
            p.game.getStack().resolveStack();
        }
        throw new IOException("Benchmark resolution exceeded its finite horizon.");
    }

    private static boolean cast(Position p, SpellAbility ability, GameObject target) throws IOException {
        ability.setActivatingPlayer(p.ai);
        ability.resetTargets();
        boolean legalTarget = !ability.usesTargeting() || ability.canTarget(target);
        chooseTarget(ability, target);
        if ((ability.usesTargeting() && (target == null || !legalTarget || !ability.isTargetNumberValid()))
                || !ability.canPlay() || !ComputerUtilCost.canPayCost(ability, p.ai, false)) { return false; }
        if (!ComputerUtil.playStack(ability, p.ai, p.game)) { return false; }
        settle(p);
        return true;
    }

    private static boolean followup(Spec spec, Position p) throws IOException {
        if (spec.followup.equals("stage") || spec.followup.equals("hexmage")) {
            Card source = find(p.ai, ZoneType.Battlefield, spec.followup.equals("stage") ? "Thespian's Stage" : "Vampire Hexmage");
            Card depths = find(p.ai, ZoneType.Battlefield, "Dark Depths");
            if (source == null || depths == null) { return false; }
            ApiType api = spec.followup.equals("stage") ? ApiType.Clone : ApiType.RemoveCounter;
            for (SpellAbility ability : source.getSpellAbilities()) {
                if (ability.getApi() == api) { return cast(p, ability, depths); }
            }
            return false;
        }
        String name = switch (spec.followup) {
            case "bolt" -> "Lightning Bolt";
            case "swords" -> "Swords to Plowshares";
            case "wrath" -> "Wrath of God";
            default -> throw new IOException("Unknown scripted follow-up.");
        };
        Card source = find(p.ai, ZoneType.Hand, name);
        GameObject target = spec.followup.equals("bolt") ? p.opponent
                : spec.followup.equals("swords") ? find(p.opponent, ZoneType.Battlefield, "Colossal Dreadmaw") : null;
        return source != null && cast(p, source.getFirstSpellAbility(), target);
    }

    private static JsonObject evaluate(Spec spec, Action action) throws IOException {
        Position p = position(spec);
        boolean executed = true;
        boolean followed = false;
        if (!action.kind.equals("pass")) {
            Card source = p.ai.getCardsIn(ZoneType.Hand).stream().filter(c -> c.getId() == action.source).findFirst().orElseThrow();
            SpellAbility ability = source.getFirstSpellAbility();
            ability.setActivatingPlayer(p.ai);
            if (action.kind.equals("land")) {
                executed = p.ai.canPlayLand(source, false, ability);
                if (executed) {
                    p.ai.playLand(source, ability);
                    settle(p);
                    followed = followup(spec, p);
                }
            } else { executed = cast(p, ability, target(action.target, p)); }
        }
        boolean passed = switch (spec.goal) {
            case TOKEN -> find(p.ai, ZoneType.Battlefield, "Marit Lage") != null;
            case LETHAL -> p.opponent.hasLost();
            case EXILE -> find(p.opponent, ZoneType.Exile, spec.id.equals("exile-indestructible") ? "Darksteel Colossus" : "Colossal Dreadmaw") != null;
            case REMOVE_THREAT -> find(p.opponent, ZoneType.Battlefield, "Colossal Dreadmaw") == null;
            case CLEAR_BOARD -> p.opponent.getCardsIn(ZoneType.Battlefield).stream().noneMatch(Card::isCreature);
            case PRESERVE_BOARD -> p.ai.getCardsIn(ZoneType.Battlefield).stream().filter(Card::isCreature).count() == 2;
            case CONSERVE_SPELL -> find(p.ai, ZoneType.Hand, "Wrath of God") != null;
        };
        JsonObject outcome = new JsonObject();
        outcome.addProperty("actionId", action.id);
        outcome.addProperty("description", action.description);
        outcome.addProperty("engineExecuted", executed);
        outcome.addProperty("scriptedFollowupExecuted", followed);
        outcome.addProperty("objectivePassed", executed && passed);
        outcome.addProperty("score", executed && passed ? 1 : 0);
        outcome.addProperty("aiLife", p.ai.getLife());
        outcome.addProperty("opponentLife", p.opponent.getLife());
        outcome.addProperty("opponentLost", p.opponent.hasLost());
        outcome.addProperty("aiCreatures", p.ai.getCardsIn(ZoneType.Battlefield).stream().filter(Card::isCreature).count());
        outcome.addProperty("opponentCreatures", p.opponent.getCardsIn(ZoneType.Battlefield).stream().filter(Card::isCreature).count());
        return outcome;
    }

    /** Exports each question before any inference; oracle scores stay in the separate manifest. */
    public static JsonObject export(Path directory) throws IOException {
        Random previousRandom = MyRandom.getRandom();
        try { return exportInternal(directory); }
        finally { MyRandom.setRandom(previousRandom); }
    }

    private static JsonObject exportInternal(Path directory) throws IOException {
        LandDecisionExample.initialize();
        JsonObject manifest = new JsonObject();
        manifest.addProperty("suiteVersion", VERSION);
        manifest.addProperty("engineVersion", forge.util.BuildInfo.getVersionString());
        manifest.addProperty("seed", SEED);
        manifest.addProperty("horizon", "Selected action, then optional predeclared follow-up. No opponent responses or full-game rollout.");
        manifest.addProperty("baseline", "Forge heuristic AI, flat Monte Carlo disabled, fixed seed per rebuilt position.");
        JsonArray cases = new JsonArray();
        int index = 0;
        for (Spec spec : CASES) {
            Position p = position(spec);
            List<Action> actions = actions(spec, p);
            String baseline = baseline(spec, actions);
            JsonObject snapshot = LandDecisionSnapshot.visibleState(p.ai);
            if (snapshot == null) { throw new IOException("Unsupported benchmark state: " + spec.id); }
            snapshot.addProperty("schemaVersion", 1);
            snapshot.addProperty("decisionId", UUID.nameUUIDFromBytes((VERSION + ":" + (++index)).getBytes(StandardCharsets.UTF_8)).toString());
            snapshot.addProperty("decisionType", "benchmark_action");
            snapshot.addProperty("baselineActionId", baseline);
            snapshot.addProperty("scope", spec.followup.isEmpty()
                    ? "Choose whether to cast the listed spell now, with one listed target if required, or pass priority. No other actions are offered."
                    : "Choose a listed land to play now, or pass priority. Evaluate its immediate strategic possibilities. Spell and activated-ability decisions are outside the offered action set.");
            JsonArray candidates = new JsonArray();
            JsonArray outcomes = new JsonArray();
            int min = 1;
            int max = 0;
            for (Action action : actions) {
                JsonObject candidate = new JsonObject();
                candidate.addProperty("actionId", action.id);
                candidate.addProperty("description", action.description);
                if (action.source >= 0) { candidate.addProperty("source", "card-" + action.source); }
                if (action.target != null) { candidate.addProperty("target", action.target); }
                candidates.add(candidate);
                JsonObject outcome = evaluate(spec, action);
                if (!outcome.get("engineExecuted").getAsBoolean()) { throw new IOException("Candidate failed engine execution: " + spec.id + "/" + action.id); }
                min = Math.min(min, outcome.get("score").getAsInt());
                max = Math.max(max, outcome.get("score").getAsInt());
                outcomes.add(outcome);
            }
            if (actions.size() < 2 || min == max) { throw new IOException("Benchmark case has no verified distinguishing outcome: " + spec.id); }
            snapshot.add("candidates", candidates);
            ShadowReview.validateSnapshot(snapshot);
            String file = String.format(java.util.Locale.ROOT, "case-%02d.json", index);
            String content = JSON.toJson(snapshot);
            PrivateFiles.write(directory.resolve(file), content);
            JsonObject item = new JsonObject();
            item.addProperty("id", spec.id);
            item.addProperty("category", spec.category);
            item.addProperty("objective", spec.objective);
            item.addProperty("inputFile", file);
            item.addProperty("snapshotSha256", digest(content));
            item.addProperty("baselineActionId", baseline);
            item.add("engineOutcomes", outcomes);
            cases.add(item);
            System.out.println("Verified fixture " + spec.id + " (" + actions.size() + " candidates).");
        }
        manifest.add("cases", cases);
        PrivateFiles.write(directory.resolve("manifest.json"), JSON.toJson(manifest));
        return manifest;
    }

    static String digest(String value) throws IOException {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8))); }
        catch (java.security.NoSuchAlgorithmException e) { throw new IOException("SHA-256 is unavailable."); }
    }

    public static JsonObject run(Path manifestPath, Path output, ProviderClient provider, String model,
                                 String effort, Duration timeout, int tokens) throws IOException {
        JsonObject manifest = LlmHttp.object(Files.readString(manifestPath));
        if (!VERSION.equals(manifest.get("suiteVersion").getAsString())) { throw new IOException("Unsupported benchmark suite."); }
        JsonArray reports = new JsonArray();
        for (JsonElement element : manifest.getAsJsonArray("cases")) {
            JsonObject item = element.getAsJsonObject();
            String id = item.get("id").getAsString();
            if (!id.matches("[a-z0-9-]+")) { throw new IOException("Invalid case ID."); }
            Path input = manifestPath.toAbsolutePath().getParent().resolve(item.get("inputFile").getAsString()).normalize();
            if (!input.getParent().equals(manifestPath.toAbsolutePath().getParent().normalize())) { throw new IOException("Benchmark input escapes suite directory."); }
            String content = Files.readString(input);
            String hash = digest(content);
            if (!hash.equals(item.get("snapshotSha256").getAsString())) { throw new IOException("Benchmark input changed after engine verification."); }
            String fingerprint = digest(digest(manifest.toString()) + ":" + hash + ":" + provider.name() + ":" + model + ":" + effort + ":" + tokens + ":" + timeout);
            Path reportFile = output.resolve(id + ".json");
            JsonObject report;
            if (Files.exists(reportFile)) {
                report = LlmHttp.object(Files.readString(reportFile));
                if (!report.has("benchmarkFingerprint") || !fingerprint.equals(report.get("benchmarkFingerprint").getAsString())) {
                    throw new IOException("Cached benchmark settings differ. Choose a new output directory.");
                }
                System.out.println("Reused " + id + "; no model request.");
            } else {
                report = ShadowReview.review(provider, model, LlmHttp.object(content), timeout, effort, tokens);
                report.addProperty("benchmarkFingerprint", fingerprint);
                report.addProperty("caseId", id);
                PrivateFiles.write(reportFile, JSON.toJson(report));
                System.out.println("Reviewed " + id + ": " + report.get("status").getAsString());
            }
            reports.add(report);
            // Preserve partial results and stop on connection/quota errors instead of spending across a broken run.
            if ("provider_error".equals(report.get("status").getAsString())) { break; }
        }
        JsonObject summary = summarize(manifest, reports);
        PrivateFiles.write(output.resolve("summary.json"), JSON.toJson(summary));
        PrivateFiles.write(output.resolve("summary.md"), markdown(summary));
        return summary;
    }

    private static int score(JsonObject item, String action) throws IOException {
        for (JsonElement element : item.getAsJsonArray("engineOutcomes")) {
            JsonObject outcome = element.getAsJsonObject();
            if (action.equals(outcome.get("actionId").getAsString())) { return outcome.get("score").getAsInt(); }
        }
        throw new IOException("Action was not verified by the engine.");
    }

    static JsonObject summarize(JsonObject manifest, JsonArray reports) throws IOException {
        JsonObject summary = new JsonObject();
        summary.addProperty("suiteVersion", VERSION);
        summary.add("engineVersion", manifest.get("engineVersion"));
        summary.add("horizon", manifest.get("horizon"));
        if (!reports.isEmpty()) {
            JsonObject first = reports.get(0).getAsJsonObject();
            for (String field : List.of("provider", "model", "reasoningEffort")) {
                if (first.has(field)) { summary.add(field, first.get(field)); }
            }
        }
        summary.addProperty("caseCount", manifest.getAsJsonArray("cases").size());
        summary.addProperty("attempted", reports.size());
        JsonArray rows = new JsonArray();
        int baselinePass = 0, modelPass = 0, valid = 0, wins = 0, losses = 0, ties = 0;
        long inputTokens = 0, outputTokens = 0, reasoningTokens = 0;
        int usageReports = 0;
        List<Long> latencies = new ArrayList<>();
        for (int i = 0; i < manifest.getAsJsonArray("cases").size(); i++) {
            JsonObject item = manifest.getAsJsonArray("cases").get(i).getAsJsonObject();
            int baseline = score(item, item.get("baselineActionId").getAsString());
            baselinePass += baseline;
            JsonObject row = new JsonObject();
            row.add("id", item.get("id"));
            row.add("category", item.get("category"));
            row.add("objective", item.get("objective"));
            row.addProperty("baselineScore", baseline);
            row.add("baselineActionId", item.get("baselineActionId"));
            if (i < reports.size()) {
                JsonObject report = reports.get(i).getAsJsonObject();
                row.add("status", report.get("status"));
                row.add("elapsedMillis", report.get("elapsedMillis"));
                latencies.add(report.get("elapsedMillis").getAsLong());
                if ("legal_recommendation".equals(report.get("status").getAsString())) {
                    int modelScore = score(item, report.get("recommendedActionId").getAsString());
                    modelPass += modelScore;
                    valid++;
                    if (modelScore > baseline) { wins++; } else if (modelScore < baseline) { losses++; } else { ties++; }
                    row.addProperty("modelScore", modelScore);
                    row.add("modelActionId", report.get("recommendedActionId"));
                    row.add("reason", report.get("reason"));
                }
                if (report.has("usage")) {
                    usageReports++;
                    JsonObject usage = report.getAsJsonObject("usage");
                    inputTokens += count(usage, "input_tokens", "prompt_tokens");
                    outputTokens += count(usage, "output_tokens", "completion_tokens");
                    if (usage.has("output_tokens_details")) { reasoningTokens += count(usage.getAsJsonObject("output_tokens_details"), "reasoning_tokens", "reasoning_tokens"); }
                }
            } else { row.addProperty("status", "not_run"); }
            rows.add(row);
        }
        summary.addProperty("baselineObjectivePasses", baselinePass);
        summary.addProperty("modelObjectivePasses", modelPass);
        summary.addProperty("validRecommendations", valid);
        summary.addProperty("wins", wins);
        summary.addProperty("losses", losses);
        summary.addProperty("ties", ties);
        summary.addProperty("inputTokens", inputTokens);
        summary.addProperty("outputTokens", outputTokens);
        summary.addProperty("reasoningTokensReported", reasoningTokens);
        summary.addProperty("usageReports", usageReports);
        summary.addProperty("monetaryCost", "Not reported for this subscription connection; token usage is not a dollar estimate.");
        if (!latencies.isEmpty()) {
            latencies.sort(Long::compare);
            summary.addProperty("medianMillis", (latencies.get((latencies.size() - 1) / 2)
                    + latencies.get(latencies.size() / 2)) / 2.0);
            summary.addProperty("p95Millis", latencies.get((int) Math.ceil(latencies.size() * .95) - 1));
        }
        summary.addProperty("interpretation", "One call per curated position. Scores are predeclared short-horizon fixture objectives, not win rates. Scripted follow-ups assume no opponent responses. Failed/missing reviews are not model successes. No live move overrides.");
        summary.add("rows", rows);
        return summary;
    }

    private static long count(JsonObject object, String first, String alternate) {
        return object.has(first) ? object.get(first).getAsLong() : object.has(alternate) ? object.get(alternate).getAsLong() : 0;
    }

    static String markdown(JsonObject summary) {
        StringBuilder text = new StringBuilder("# Forge decision benchmark\n\n");
        if (summary.has("model")) {
            text.append("Model: **").append(summary.get("model").getAsString()).append("**; reasoning: **")
                    .append(summary.get("reasoningEffort").getAsString()).append("**; connection: ")
                    .append(summary.get("provider").getAsString()).append(".\n\n");
        }
        text.append("Engine-objective passes: Forge **").append(summary.get("baselineObjectivePasses").getAsInt())
                .append('/').append(summary.get("caseCount").getAsInt()).append("**, model **")
                .append(summary.get("modelObjectivePasses").getAsInt()).append('/').append(summary.get("caseCount").getAsInt())
                .append("**. Valid recommendations: ").append(summary.get("validRecommendations").getAsInt()).append(".\n\n")
                .append("Wins / ties / losses against baseline (valid pairs only): ")
                .append(summary.get("wins")).append(" / ").append(summary.get("ties")).append(" / ").append(summary.get("losses")).append(".\n\n")
                .append(summary.get("interpretation").getAsString()).append("\n\n")
                .append("| Position | Category | Forge objective | Model objective | Review status | Seconds |\n")
                .append("|---|---|---:|---:|---|---:|\n");
        for (JsonElement element : summary.getAsJsonArray("rows")) {
            JsonObject row = element.getAsJsonObject();
            text.append('|').append(row.get("id").getAsString()).append('|').append(row.get("category").getAsString())
                    .append('|').append(row.get("baselineScore")).append('|')
                    .append(row.has("modelScore") ? row.get("modelScore").toString() : "unscored").append('|')
                    .append(row.get("status").getAsString()).append('|')
                    .append(row.has("elapsedMillis") ? String.format(java.util.Locale.ROOT, "%.2f", row.get("elapsedMillis").getAsLong() / 1000.0) : "—")
                    .append("|\n");
        }
        text.append("\nToken usage: ").append(summary.get("inputTokens")).append(" input, ")
                .append(summary.get("outputTokens")).append(" output, available for ").append(summary.get("usageReports"))
                .append('/').append(summary.get("attempted")).append(" reviews. ")
                .append(summary.get("monetaryCost").getAsString()).append("\n");
        if (summary.has("medianMillis")) {
            text.append("\nMedian latency: ").append(summary.get("medianMillis")).append(" ms; nearest-rank p95: ")
                    .append(summary.get("p95Millis")).append(" ms.\n");
        }
        return text.toString();
    }
}
