package forge.llm;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import forge.LobbyPlayer;
import forge.ai.ComputerUtil;
import forge.ai.ComputerUtilCost;
import forge.ai.LobbyPlayerAi;
import forge.ai.PlayerControllerAi;
import forge.ai.llm.LandDecisionSnapshot;
import forge.ai.llm.PrivateFiles;
import forge.deck.Deck;
import forge.game.Game;
import forge.game.GameEntity;
import forge.game.GameObject;
import forge.game.GameRules;
import forge.game.GameStage;
import forge.game.GameType;
import forge.game.Match;
import forge.game.ability.ApiType;
import forge.game.card.Card;
import forge.game.phase.PhaseType;
import forge.game.player.DelayedReveal;
import forge.game.player.Player;
import forge.game.player.RegisteredPlayer;
import forge.game.spellability.SpellAbility;
import forge.game.zone.ZoneType;
import forge.model.FModel;
import forge.util.MyRandom;
import forge.util.collect.FCollectionView;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.TreeMap;
import java.util.UUID;

/** Offline full-engine turn progression. Only the fixture's supported decision families are model-controlled. */
public final class TurnSequenceExperiment {
    public static final String VERSION = "forge-turn-sequences-v1";
    private static final Gson JSON = new GsonBuilder().setPrettyPrinting().create();
    private static final long SEED = 20261025L;
    public enum Mode { FORGE, WITHOUT_PLAN, WITH_PLAN }
    private record Scenario(String id, boolean hexmage, String response, boolean protection) { }
    private static final List<Scenario> CASES = List.of(
            new Scenario("stage-clean", false, "none", false),
            new Scenario("hexmage-clean", true, "none", false),
            new Scenario("stage-stifle-protected", false, "stifle", true),
            new Scenario("hexmage-swords-protected", true, "swords", true),
            new Scenario("stage-public-karakas", false, "karakas", false),
            new Scenario("hexmage-hidden-stifle", true, "stifle", false));

    record Action(String id, String description, SpellAbility ability, GameObject target, Card keep) { }

    /** Test seam uses only detached inputs and a baseline ID; it cannot mutate game objects. */
    @FunctionalInterface
    interface Policy { String select(JsonObject question) throws IOException; }

    private TurnSequenceExperiment() { }

    public static Deck deck() throws IOException {
        LandDecisionExample.initialize();
        Deck deck = new Deck("Depths strategy experiment");
        for (String name : List.of("Dark Depths", "Thespian's Stage", "Vampire Hexmage", "Crop Rotation",
                "Sylvan Scrying", "Expedition Map", "Duress", "Thoughtseize", "Dispel", "Bayou",
                "Tropical Island", "Underground Sea", "Wasteland", "Swamp", "Island")) {
            var card = FModel.getMagicDb().getCommonCards().getCard(name);
            if (card == null) { throw new IOException("Missing deck card: " + name); }
            deck.getMain().add(card, 4);
        }
        return deck;
    }

    static List<String> scenarioIds() { return CASES.stream().map(Scenario::id).toList(); }

    static JsonObject simulate(String id, Mode mode, JsonObject plan, Policy policy) throws IOException {
        Scenario scenario = CASES.stream().filter(s -> s.id.equals(id)).findFirst().orElseThrow();
        Random previous = MyRandom.getRandom();
        try { return new Session(scenario, mode, plan, policy).run(); }
        catch (UncheckedIOException e) { throw e.getCause(); }
        finally { MyRandom.setRandom(previous); }
    }

    private static final class Session {
        final Scenario scenario;
        final Mode mode;
        final JsonObject plan;
        final Policy policy;
        final Game game;
        final Player ai;
        final Player opponent;
        final String deckHash;
        final JsonArray decisions = new JsonArray();
        final JsonArray events = new JsonArray();
        final JsonArray observed = new JsonArray();
        final List<String> executedSources = new ArrayList<>();
        int node;
        int legalChecks;
        int forcedPasses;
        boolean opponentResponded;
        boolean tokenEver;
        boolean stageActivated;
        final int stageId;
        final int depthsId;

        Session(Scenario scenario, Mode mode, JsonObject plan, Policy policy) throws IOException {
            this.scenario = scenario;
            this.mode = mode;
            this.plan = plan;
            this.policy = policy;
            MyRandom.setRandom(new Random(SEED));
            Deck deck = deck();
            deckHash = DecisionBenchmark.digest(DeckStrategyPlanner.deckInput(deck).toString());
            if (mode == Mode.WITH_PLAN && (plan == null || !deckHash.equals(plan.get("deckHash").getAsString()))) {
                throw new IOException("A matching deck strategy is required for the planned arm.");
            }
            GameRules rules = new GameRules(GameType.Constructed);
            List<RegisteredPlayer> players = List.of(
                    new RegisteredPlayer(deck).setPlayer(new LobbyPlayerAi("AI", null)),
                    new RegisteredPlayer(new Deck()).setPlayer(new LobbyPlayerAi("Opponent", null)));
            game = new Game(players, rules, new Match(rules, players, "Turn sequence experiment"));
            ai = game.getPlayers().get(0);
            opponent = game.getPlayers().get(1);
            game.setAge(GameStage.Play);
            game.AI_FLAT_MONTE_CARLO = false;
            game.getPhaseHandler().devModeSet(PhaseType.MAIN1, ai, 4);
            game.getPhaseHandler().onStackResolved();
            ai.setLife(20, null);
            opponent.setLife(20, null);
            Card piece = add(scenario.hexmage ? "Vampire Hexmage" : "Thespian's Stage", ai, ZoneType.Battlefield);
            stageId = piece.getId();
            add("Island", ai, ZoneType.Battlefield);
            if (!scenario.hexmage) { add("Island", ai, ZoneType.Battlefield); }
            if (scenario.protection && !scenario.hexmage) { add("Island", ai, ZoneType.Battlefield); }
            if (scenario.hexmage) { add("Swamp", ai, ZoneType.Battlefield); }
            depthsId = add("Dark Depths", ai, ZoneType.Hand).getId();
            add("Swamp", ai, ZoneType.Hand);
            if (scenario.protection) { add("Dispel", ai, ZoneType.Hand); }
            if (scenario.response.equals("stifle")) {
                add("Island", opponent, ZoneType.Battlefield);
                add("Stifle", opponent, ZoneType.Hand);
            } else if (scenario.response.equals("swords")) {
                add("Plains", opponent, ZoneType.Battlefield);
                add("Swords to Plowshares", opponent, ZoneType.Hand);
            } else if (scenario.response.equals("karakas")) { add("Karakas", opponent, ZoneType.Battlefield); }
            fillOwnLibrary(deck);
            for (int i = 0; i < 10; i++) { add("Plains", opponent, ZoneType.Library); }
            game.getAction().checkStateEffects(true);
            LobbyPlayer aiLobby = ai.getLobbyPlayer();
            LobbyPlayer oppLobby = opponent.getLobbyPlayer();
            ai.dangerouslySetController(new ActorController(ai, aiLobby));
            opponent.dangerouslySetController(new ResponseController(opponent, oppLobby));
        }

        private Card add(String name, Player owner, ZoneType zone) throws IOException {
            var paper = FModel.getMagicDb().getCommonCards().getCard(name);
            if (paper == null) { throw new IOException("Missing sequence card: " + name); }
            Card card = Card.fromPaperCard(paper, owner);
            card.setGameTimestamp(game.getNextTimestamp());
            owner.getZone(zone).add(card);
            return card;
        }

        private void fillOwnLibrary(Deck deck) throws IOException {
            TreeMap<String, Integer> remaining = new TreeMap<>();
            for (var entry : deck.getMain()) { remaining.merge(entry.getKey().getName(), entry.getValue(), Integer::sum); }
            for (ZoneType zone : List.of(ZoneType.Hand, ZoneType.Battlefield)) {
                for (Card card : ai.getCardsIn(zone)) {
                    remaining.compute(card.getName(), (name, count) -> count == null ? -1 : count - 1);
                }
            }
            List<String> library = new ArrayList<>();
            for (var entry : remaining.entrySet()) {
                if (entry.getValue() < 0) { throw new IOException("Fixture cards exceed its actual deck list."); }
                for (int i = 0; i < entry.getValue(); i++) { library.add(entry.getKey()); }
            }
            Collections.shuffle(library, new Random(SEED));
            for (String name : library) { add(name, ai, ZoneType.Library); }
        }

        private List<SpellAbility> stack() {
            List<SpellAbility> result = new ArrayList<>();
            for (var item : game.getStack()) { result.add(item.getSpellAbility()); }
            return result;
        }

        private String targetKey(GameObject target) {
            if (target == null) { return "none"; }
            if (target instanceof Card card) { return "card-" + card.getId(); }
            if (target instanceof Player) { return target == ai ? "p0" : "p1"; }
            if (target instanceof SpellAbility ability) {
                List<SpellAbility> stack = stack();
                for (int i = 0; i < stack.size(); i++) {
                    if (stack.get(i).getId() == ability.getId()) { return "stack-" + i; }
                }
            }
            throw new IllegalStateException("Unsupported or stale sequence target.");
        }

        private String abilityKey(SpellAbility ability, GameObject target) {
            return (ability.isLandAbility() ? "land" : ability.isSpell() ? "cast" : "activate") + "-"
                    + ability.getHostCard().getId() + "-" + ability.getApi() + "-" + targetKey(target);
        }

        private GameObject firstTarget(SpellAbility ability) {
            for (Card target : ability.getTargets().getTargetCards()) { return target; }
            for (Player target : ability.getTargets().getTargetPlayers()) { return target; }
            for (SpellAbility target : ability.getTargets().getTargetSpells()) { return target; }
            return null;
        }

        private boolean prepare(SpellAbility ability, GameObject target, Player actor) {
            ability.setActivatingPlayer(actor);
            ability.resetTargets();
            if (ability.usesTargeting()) {
                if (target == null || !ability.canTarget(target)) { return false; }
                ability.getTargets().add(target);
                if (!ability.isTargetNumberValid()) { return false; }
            }
            return ability.canPlay() && (ability.isLandAbility() || ComputerUtilCost.canPayCost(ability, actor, false));
        }

        private List<Action> actions() {
            List<Action> actions = new ArrayList<>();
            List<GameObject> targets = new ArrayList<>(game.getCardsIn(ZoneType.Battlefield));
            targets.add(ai);
            targets.add(opponent);
            targets.addAll(stack());
            List<Card> sources = new ArrayList<>(ai.getCardsIn(ZoneType.Hand));
            sources.addAll(ai.getCardsIn(ZoneType.Battlefield));
            for (Card source : sources) {
                for (SpellAbility original : source.getSpellAbilities()) {
                    boolean land = source.isInZone(ZoneType.Hand) && original.isLandAbility();
                    boolean spell = source.isInZone(ZoneType.Hand) && original.isSpell() && original.getApi() == ApiType.Counter;
                    boolean activated = source.isInZone(ZoneType.Battlefield) && original.isActivatedAbility()
                            && (original.getApi() == ApiType.Clone || original.getApi() == ApiType.RemoveCounter);
                    if (!land && !spell && !activated) { continue; }
                    if (original.usesTargeting()) {
                        for (GameObject target : targets) {
                            SpellAbility ability = original.copy(ai);
                            if (prepare(ability, target, ai)) {
                                actions.add(new Action(abilityKey(ability, target), describe(ability, target), ability, target, null));
                            }
                        }
                    } else {
                        SpellAbility ability = original.copy(ai);
                        if (prepare(ability, null, ai)) {
                            actions.add(new Action(abilityKey(ability, null), describe(ability, null), ability, null, null));
                        }
                    }
                }
            }
            actions.add(new Action("pass", "Pass priority.", null, null, null));
            return actions;
        }

        private String describe(SpellAbility ability, GameObject target) {
            String text = (ability.isLandAbility() ? "Play " : ability.isSpell() ? "Cast " : "Activate ")
                    + ability.getHostCard().getName();
            if (!ability.isLandAbility()) { text += " [" + ability.getDescription() + "]; cost: " + ability.getPayCosts(); }
            if (target != null) { text += "; target " + targetKey(target); }
            return text;
        }

        private Action decide(List<Action> actions, String baseline, String scope) throws IOException {
            if (++node > 20) { throw new IOException("Sequence exceeded its 20-decision limit."); }
            JsonObject state = LandDecisionSnapshot.visibleState(ai);
            if (state == null) { throw new IOException("Unsupported visible state in sequence."); }
            state.addProperty("schemaVersion", 1);
            state.addProperty("decisionType", "sequence_action");
            state.addProperty("decisionId", UUID.nameUUIDFromBytes((VERSION + ":" + CASES.indexOf(scenario) + ":" + node)
                    .getBytes(java.nio.charset.StandardCharsets.UTF_8)).toString());
            state.addProperty("baselineActionId", baseline);
            state.addProperty("scope", scope);
            state.addProperty("priorityPlayer", game.getPhaseHandler().getPriorityPlayer() == ai ? "p0" : "p1");
            JsonArray stack = new JsonArray();
            int index = 0;
            for (SpellAbility ability : stack()) {
                JsonObject item = new JsonObject();
                item.addProperty("id", "stack-" + index++);
                item.addProperty("controller", ability.getActivatingPlayer() == ai ? "p0" : "p1");
                item.addProperty("source", ability.getHostCard().getName());
                item.addProperty("oracleText", ability.getHostCard().getOracleText());
                item.addProperty("description", ability.toString());
                stack.add(item);
            }
            state.add("stack", stack);
            state.add("observedActions", observed.deepCopy());
            JsonArray candidates = new JsonArray();
            for (Action action : actions) {
                JsonObject item = new JsonObject();
                item.addProperty("actionId", action.id);
                item.addProperty("description", action.description);
                item.addProperty("legalTimingNow", true);
                item.addProperty("payableNow", true);
                item.addProperty("targetsLegalNow", true);
                candidates.add(item);
            }
            state.add("candidates", candidates);
            if (mode == Mode.WITH_PLAN) { state = DeckStrategyPlanner.attach(state, plan, deckHash); }
            ShadowReview.validateSnapshot(state);
            String selected = mode == Mode.FORGE ? baseline : policy.select(state.deepCopy());
            String chosen = selected;
            Action action = actions.stream().filter(a -> a.id.equals(chosen)).findFirst()
                    .orElseThrow(() -> new IOException("Policy returned an action outside the current legal set."));
            JsonObject decision = new JsonObject();
            decision.add("question", state);
            decision.addProperty("selectedActionId", action.id);
            decisions.add(decision);
            return action;
        }

        private final class ActorController extends PlayerControllerAi {
            ActorController(Player player, LobbyPlayer lobby) { super(Session.this.game, player, lobby); }

            @Override public List<SpellAbility> chooseSpellAbilityToPlay() {
                if (game.getPhaseHandler().getTurn() != 4 || game.getPhaseHandler().getPlayerTurn() != ai) { return null; }
                try {
                    List<SpellAbility> normal = getAi().chooseSpellAbilityToPlay();
                    String baseline = normal == null ? "pass" : abilityKey(normal.get(0), firstTarget(normal.get(0)));
                    List<Action> actions = actions();
                    if (actions.size() == 1) { forcedPasses++; return null; }
                    if (actions.stream().noneMatch(a -> a.id.equals(baseline))) { throw new IOException("Forge baseline outside sequence action scope."); }
                    Action selected = decide(actions, baseline, "Choose one supported legal action at this priority window, or pass. "
                            + "Engine timing, affordability and targets have been checked for every listed action. "
                            + "Consider the public stack and exposed responses. An optional deck plan is advisory, not a command.");
                    if (selected.ability == null) { return null; }
                    if (!prepare(selected.ability, selected.target, ai)) { throw new IOException("Selected action failed its immediate legality/mana check."); }
                    legalChecks++;
                    return List.of(selected.ability);
                } catch (IOException e) { throw new UncheckedIOException(e); }
            }

            @Override public boolean playChosenSpellAbility(SpellAbility ability) { return execute(ai, ability); }

            @Override public <T extends GameEntity> T chooseSingleEntityForEffect(FCollectionView<T> options,
                    DelayedReveal reveal, SpellAbility ability, String title, boolean optional, Player targetPlayer, Map<String, Object> params) {
                T normal = super.chooseSingleEntityForEffect(options, reveal, ability, title, optional, targetPlayer, params);
                if (ability.getApi() != ApiType.InternalLegendaryRule || options.size() < 2) { return normal; }
                try {
                    List<Action> actions = new ArrayList<>();
                    for (T option : options) {
                        if (!(option instanceof Card card)) { throw new IOException("Unsupported legend choice."); }
                        actions.add(new Action("keep-" + card.getId(), "Keep card-" + card.getId() + " (" + card.getName()
                                + "); other same-name legends go to the graveyard.", null, null, card));
                    }
                    Action selected = decide(actions, "keep-" + ((Card) normal).getId(),
                            "Legend rule: keep exactly one listed legendary permanent. The other same-name legends go to the graveyard. Compare their counters and current states.");
                    for (T option : options) { if (option == selected.keep) { return option; } }
                    throw new IOException("Legend choice became stale.");
                } catch (IOException e) { throw new UncheckedIOException(e); }
            }
        }

        private final class ResponseController extends PlayerControllerAi {
            ResponseController(Player player, LobbyPlayer lobby) { super(Session.this.game, player, lobby); }

            @Override public List<SpellAbility> chooseSpellAbilityToPlay() {
                if (opponentResponded || game.getPhaseHandler().getTurn() != 4) { return null; }
                SpellAbility response = null;
                GameObject target = null;
                if (scenario.response.equals("stifle")) {
                    Card card = find(opponent, ZoneType.Hand, "Stifle");
                    for (SpellAbility ability : stack()) {
                        if (ability.isActivatedAbility() && ability.getActivatingPlayer() == ai
                                && (ability.getHostCard().getName().equals("Thespian's Stage")
                                || ability.getHostCard().getName().equals("Vampire Hexmage"))) {
                            response = card == null ? null : card.getFirstSpellAbility().copy(opponent);
                            target = ability;
                            break;
                        }
                    }
                } else if (scenario.response.equals("swords")) {
                    Card card = find(opponent, ZoneType.Hand, "Swords to Plowshares");
                    target = find(ai, ZoneType.Battlefield, "Marit Lage");
                    if (card != null && target != null) { response = card.getFirstSpellAbility().copy(opponent); }
                } else if (scenario.response.equals("karakas")) {
                    Card card = find(opponent, ZoneType.Battlefield, "Karakas");
                    target = find(ai, ZoneType.Battlefield, "Marit Lage");
                    if (card != null && target != null) {
                        for (SpellAbility ability : card.getSpellAbilities()) {
                            if (ability.getApi() == ApiType.ChangeZone) { response = ability.copy(opponent); break; }
                        }
                    }
                }
                if (response != null && prepare(response, target, opponent)) { return List.of(response); }
                return null;
            }

            @Override public boolean playChosenSpellAbility(SpellAbility ability) {
                boolean played = execute(opponent, ability);
                if (played) { opponentResponded = true; }
                return played;
            }
        }

        private boolean execute(Player actor, SpellAbility ability) {
            String description = (actor == ai ? "p0: " : "p1: ") + describe(ability, firstTarget(ability));
            String source = ability.getHostCard().getName();
            boolean played;
            if (ability.isLandAbility()) {
                played = ability.canPlay();
                if (played) { ability.resolve(); }
            } else {
                played = ability.canPlay() && ComputerUtilCost.canPayCost(ability, actor, false)
                        && ComputerUtil.handlePlayingSpellAbility(actor, ability, null);
            }
            if (!played) { throw new UncheckedIOException(new IOException("Engine rejected a selected sequence action.")); }
            if (actor == ai) {
                executedSources.add(source);
                if (ability.getHostCard().getId() == stageId && ability.getApi() == ApiType.Clone) { stageActivated = true; }
            }
            events.add(description);
            observed.add(description);
            if (observed.size() > 12) { observed.remove(0); }
            return true;
        }

        JsonObject run() throws IOException {
            int steps = 0;
            while (!game.isGameOver() && game.getPhaseHandler().getTurn() == 4 && game.getPhaseHandler().getPlayerTurn() == ai) {
                if (++steps > 160) { throw new IOException("Sequence exceeded its full-turn step limit."); }
                game.getPhaseHandler().mainLoopStep();
                tokenEver |= find(ai, ZoneType.Battlefield, "Marit Lage") != null;
            }
            boolean token = find(ai, ZoneType.Battlefield, "Marit Lage") != null;
            boolean pieces = hasPiece(stageId) && hasPiece(depthsId);
            JsonObject result = new JsonObject();
            result.addProperty("caseId", scenario.id);
            result.addProperty("mode", mode.name());
            result.addProperty("fullTurnCompleted", game.getPhaseHandler().getTurn() != 4 || game.getPhaseHandler().getPlayerTurn() != ai);
            result.addProperty("tokenEverObserved", tokenEver);
            result.addProperty("tokenAtEnd", token);
            result.addProperty("opponentResponseExecuted", opponentResponded);
            result.addProperty("dispelCast", executedSources.contains("Dispel"));
            result.addProperty("comboPiecesPreserved", pieces);
            result.addProperty("stageActivated", stageActivated);
            result.addProperty("scored", !scenario.id.equals("hexmage-hidden-stifle"));
            boolean passed = scenario.response.equals("karakas") ? pieces && !stageActivated && !token : token;
            result.addProperty("objectivePassed", passed);
            result.addProperty("objective", scenario.response.equals("karakas")
                    ? "Preserve both combo pieces and wait instead of activating into exposed untapped Karakas."
                    : scenario.id.equals("hexmage-hidden-stifle") ? "Unscored counterplay probe: the hidden answer is not known before it is played."
                    : "Keep Marit Lage through the end of this turn, including the scripted opponent's response.");
            result.addProperty("decisions", node);
            result.addProperty("immediateLegalityChecks", legalChecks);
            result.addProperty("forcedPasses", forcedPasses);
            result.addProperty("aiLife", ai.getLife());
            result.addProperty("turnSteps", steps);
            result.add("events", events);
            result.add("decisionTrace", decisions);
            return result;
        }

        private boolean hasPiece(int id) {
            for (ZoneType zone : List.of(ZoneType.Hand, ZoneType.Battlefield)) {
                for (Card card : ai.getCardsIn(zone)) { if (card.getId() == id) { return true; } }
            }
            return false;
        }
    }

    private static Card find(Player player, ZoneType zone, String name) {
        for (Card card : player.getCardsIn(zone)) { if (card.getName().equals(name)) { return card; } }
        return null;
    }

    public static JsonObject run(Path directory, ProviderClient provider, String model, String effort,
                                 Duration timeout, int maxTokens) throws IOException {
        LandDecisionExample.initialize();
        JsonObject plan = DeckStrategyPlanner.plan(deck(), directory.resolve("deck-plans"), provider, model, effort, timeout, maxTokens);
        JsonArray results = new JsonArray();
        String deckHash = plan.get("deckHash").getAsString();
        for (Scenario scenario : CASES) {
            for (Mode mode : Mode.values()) {
                Path arm = directory.resolve(scenario.id).resolve(mode.name().toLowerCase(java.util.Locale.ROOT));
                String fingerprint = DecisionBenchmark.digest(VERSION + ":" + scenario.id + ":" + mode + ":"
                        + deckHash + ":" + provider.name() + ":" + model + ":" + effort + ":" + timeout
                        + ":" + (provider.supportsOutputLimit() ? maxTokens : "provider_managed")
                        + ":" + (mode == Mode.WITH_PLAN ? plan.toString() : ""));
                Path resultFile = arm.resolve("result.json");
                JsonObject result;
                if (Files.exists(resultFile)) {
                    result = LlmHttp.object(Files.readString(resultFile));
                    if (!fingerprint.equals(result.get("fingerprint").getAsString())) { throw new IOException("Sequence cache differs; choose a new output directory."); }
                    System.out.println("Reused sequence " + scenario.id + " / " + mode + ".");
                } else {
                    final int[] node = {0};
                    JsonArray reviews = new JsonArray();
                    Policy policy = question -> {
                        String hash = DecisionBenchmark.digest(question.toString() + ":" + fingerprint);
                        Path file = arm.resolve("decision-" + (++node[0]) + ".json");
                        JsonObject review;
                        if (Files.exists(file)) {
                            review = LlmHttp.object(Files.readString(file));
                            if (!hash.equals(review.get("questionFingerprint").getAsString())) { throw new IOException("Partial sequence cache differs; choose a new output directory."); }
                        } else {
                            review = ShadowReview.review(provider, model, question, timeout, effort, maxTokens);
                            review.addProperty("questionFingerprint", hash);
                            PrivateFiles.write(file, JSON.toJson(review));
                            System.out.println("Reviewed " + scenario.id + " / " + mode + " step " + node[0] + ": " + review.get("status"));
                        }
                        if ("provider_error".equals(review.get("status").getAsString())) { throw new IOException("Sequence stopped after provider error: " + review.get("error").getAsString()); }
                        reviews.add(review);
                        return review.get("recommendedActionId").getAsString();
                    };
                    result = simulate(scenario.id, mode, plan, policy);
                    result.addProperty("fingerprint", fingerprint);
                    result.add("modelReviews", reviews);
                    result.addProperty("invalidModelResponses", reviews.asList().stream().filter(r ->
                            !"legal_recommendation".equals(r.getAsJsonObject().get("status").getAsString())).count());
                    PrivateFiles.write(resultFile, JSON.toJson(result));
                    System.out.println("Finished " + scenario.id + " / " + mode + ": token=" + result.get("tokenAtEnd")
                            + ", response=" + result.get("opponentResponseExecuted") + ", decisions=" + result.get("decisions"));
                }
                results.add(result);
                PrivateFiles.write(directory.resolve("progress.json"), JSON.toJson(results));
            }
        }
        JsonObject summary = new JsonObject();
        summary.addProperty("version", VERSION);
        summary.addProperty("model", model);
        summary.addProperty("reasoningEffort", effort);
        summary.addProperty("deckHash", deckHash);
        summary.add("deckPlan", plan);
        summary.add("results", results);
        summary.addProperty("scope", "Prepared turn-four states progressed to the next turn by Forge's phase/priority engine. "
                + "Model controls supported land, combo activation/target, Dispel response and legend-rule choices. "
                + "Other combat, mana-source and trigger choices use Forge. Scripted opponent has at most one answer. "
                + "Hidden-Stifle probe is unscored; no expected hidden-card foreknowledge. Curated single-run A/B, not a win-rate estimate.");
        PrivateFiles.write(directory.resolve("summary.json"), JSON.toJson(summary));
        return summary;
    }
}
