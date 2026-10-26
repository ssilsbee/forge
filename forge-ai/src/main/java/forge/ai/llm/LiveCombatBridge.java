package forge.ai.llm;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import forge.game.GameEntity;
import forge.game.card.Card;
import forge.game.combat.Combat;
import forge.game.combat.CombatUtil;
import forge.game.player.Player;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Enumerates bounded combat declarations on scratch combat objects, never on the live combat. */
public final class LiveCombatBridge {
    private static final int MAX_PLANS = 256;
    private static final int MAX_NODES = 16384;
    private LiveCombatBridge() { }
    private record Block(Card attacker, Card blocker) { }
    private static final class Limit extends RuntimeException { }
    private static final class Search {
        int nodes;
        void visit() { if (++nodes > MAX_NODES) { throw new Limit(); } }
        void add(int size) { if (size >= MAX_PLANS) { throw new Limit(); } }
    }

    public static void attackers(Player player, Combat combat) {
        if (!LiveDecisionBridge.enabled(player) || !combat.getAttackingPlayer().equals(player)) { return; }
        // Changing existing bands requires a separate band declaration contract.
        if (combat.getAttackingBands().stream().anyMatch(b -> b.getAttackers().size() > 1)) {
            LiveDecisionBridge.fallback("attackers", "banding declaration"); return;
        }
        Map<Card, GameEntity> baseline = new LinkedHashMap<>(combat.getAttackersAndDefenders());
        try {
            List<Card> creatures = new ArrayList<>();
            Map<Card, List<GameEntity>> targets = new LinkedHashMap<>();
            for (Card card : player.getCreaturesInPlay()) {
                List<GameEntity> legal = new ArrayList<>();
                for (GameEntity defender : combat.getDefenders()) {
                    if (CombatUtil.canAttack(card, defender)
                            && CombatUtil.getAttackCost(player.getGame(), card, defender) == null) { legal.add(defender); }
                }
                if (!legal.isEmpty()) { creatures.add(card); targets.put(card, legal); }
            }
            List<Map<Card, GameEntity>> plans = new ArrayList<>();
            plans.add(baseline);
            int minimum = combat.getAttackConstraints().getLegalAttackers().getRight();
            enumerateAttacks(combat, creatures, targets, 0, new LinkedHashMap<>(), minimum, plans, new Search());
            List<LiveDecisionBridge.Option> options = new ArrayList<>();
            JsonObject context = new JsonObject();
            JsonObject declarations = new JsonObject();
            for (int i = 0; i < plans.size(); i++) {
                String id = i == 0 ? "forge" : "attack-" + i;
                options.add(new LiveDecisionBridge.Option(id, describeAttacks(plans.get(i)), null));
                declarations.add(id, attackSnapshot(plans.get(i)));
            }
            context.add("declarations", declarations);
            String selected = LiveDecisionBridge.recommend(player, "declare_attackers",
                    "Choose one complete attacker declaration. Each listed creature attacks its listed player, planeswalker or battle. "
                    + "Unlisted creatures stay back. Forge's baseline may include attack costs; alternatives do not require paying attack costs. "
                    + "All alternative declarations satisfy the engine's attack requirements and restrictions.", options, "forge", context);
            if (selected.equals("forge")) {
                LiveDecisionBridge.diagnostic("Combat declarations", "Attackers: kept Forge declaration"); return;
            }
            Map<Card, GameEntity> plan = plans.get(Integer.parseInt(selected.substring(7)));
            if (!validAttacks(combat, plan)) { throw new IllegalStateException("Attack declaration changed."); }
            try {
                applyAttackers(combat, plan);
                if (!CombatUtil.validateAttackers(combat)) { throw new IllegalStateException("Attack validation failed."); }
                LiveDecisionBridge.diagnostic("Combat declarations", "Attackers: applied alternative");
            } catch (RuntimeException e) { applyAttackers(combat, baseline); throw e; }
        } catch (Limit e) {
            LiveDecisionBridge.fallback("attackers", "combat exceeds the bounded declaration search");
        } catch (RuntimeException e) {
            LiveDecisionBridge.fallback("attackers", e.getClass().getSimpleName());
        }
    }

    private static void enumerateAttacks(Combat combat, List<Card> creatures, Map<Card, List<GameEntity>> targets,
                                         int index, Map<Card, GameEntity> plan, int minimum,
                                         List<Map<Card, GameEntity>> plans, Search search) {
        search.visit();
        if (index == creatures.size()) {
            int violations = combat.getAttackConstraints().countViolations(plan);
            if (violations >= 0 && violations <= minimum && !plans.contains(plan)) {
                search.add(plans.size()); plans.add(new LinkedHashMap<>(plan));
            }
            return;
        }
        Card card = creatures.get(index);
        enumerateAttacks(combat, creatures, targets, index + 1, plan, minimum, plans, search);
        for (GameEntity target : targets.get(card)) {
            plan.put(card, target);
            enumerateAttacks(combat, creatures, targets, index + 1, plan, minimum, plans, search);
        }
        plan.remove(card);
    }

    private static boolean validAttacks(Combat combat, Map<Card, GameEntity> plan) {
        for (var entry : plan.entrySet()) {
            if (!CombatUtil.canAttack(entry.getKey(), entry.getValue()) || !combat.getDefenders().contains(entry.getValue())
                    || CombatUtil.getAttackCost(entry.getKey().getGame(), entry.getKey(), entry.getValue()) != null) { return false; }
        }
        int violations = combat.getAttackConstraints().countViolations(plan);
        return violations >= 0 && violations <= combat.getAttackConstraints().getLegalAttackers().getRight();
    }

    private static void applyAttackers(Combat combat, Map<Card, GameEntity> plan) {
        combat.clearAttackers();
        plan.forEach(combat::addAttacker);
    }

    public static void blockers(Player player, Player defender, Combat combat) {
        if (!player.equals(defender) || !LiveDecisionBridge.enabled(player)) { return; }
        if (combat.getAttackingBands().stream().anyMatch(b -> b.getAttackers().size() > 1)) {
            LiveDecisionBridge.fallback("blockers", "banding declaration"); return;
        }
        List<Block> baseline = blocks(combat, defender);
        try {
            Combat scratch = emptyBlocks(combat);
            List<Card> blockers = new ArrayList<>(defender.getCreaturesInPlay());
            List<Card> attackers = new ArrayList<>();
            for (Card attacker : combat.getAttackers()) {
                if (defender.equals(combat.getDefenderPlayerByAttacker(attacker))) { attackers.add(attacker); }
            }
            List<List<Block>> plans = new ArrayList<>(); plans.add(baseline);
            enumerateBlocks(scratch, defender, attackers, blockers, 0, 0, plans, new Search());
            List<LiveDecisionBridge.Option> options = new ArrayList<>();
            JsonObject context = new JsonObject(); JsonObject declarations = new JsonObject();
            for (int i = 0; i < plans.size(); i++) {
                String id = i == 0 ? "forge" : "block-" + i;
                String description = plans.get(i).isEmpty() ? "Do not block." : "Block: " + plans.get(i).stream()
                        .map(b -> name(b.blocker()) + " blocks " + name(b.attacker())).reduce((a, b) -> a + "; " + b).orElse("");
                options.add(new LiveDecisionBridge.Option(id, description, null));
                declarations.add(id, blockSnapshot(plans.get(i)));
            }
            context.add("declarations", declarations);
            String selected = LiveDecisionBridge.recommend(player, "declare_blockers",
                    "Choose one complete blocker declaration. Several creatures may block one attacker; creatures that can "
                    + "block multiple attackers may have several assignments. Unlisted creatures do not block. "
                    + "Forge's baseline may pay block costs; alternatives do not. Engine restrictions, including menace and mandatory blocks, are validated.",
                    options, "forge", context);
            if (selected.equals("forge")) {
                LiveDecisionBridge.diagnostic("Combat declarations", "Blockers: kept Forge declaration"); return;
            }
            List<Block> plan = plans.get(Integer.parseInt(selected.substring(6)));
            Combat check = emptyBlocks(combat);
            for (Block block : plan) { check.addBlocker(block.attacker(), block.blocker()); }
            if (!validBlocks(check, defender)) { throw new IllegalStateException("Block declaration changed."); }
            try {
                applyBlockers(combat, defender, plan);
                if (CombatUtil.validateBlocks(combat, defender) != null) { throw new IllegalStateException("Block validation failed."); }
                LiveDecisionBridge.diagnostic("Combat declarations", "Blockers: applied alternative");
            } catch (RuntimeException e) { applyBlockers(combat, defender, baseline); throw e; }
        } catch (Limit e) {
            LiveDecisionBridge.fallback("blockers", "combat exceeds the bounded declaration search");
        } catch (RuntimeException e) {
            LiveDecisionBridge.fallback("blockers", e.getClass().getSimpleName());
        }
    }

    private static void enumerateBlocks(Combat scratch, Player defender, List<Card> attackers, List<Card> blockers,
                                        int b, int a, List<List<Block>> plans, Search search) {
        search.visit();
        if (b == blockers.size()) {
            if (validBlocks(scratch, defender)) {
                List<Block> plan = blocks(scratch, defender);
                if (!plans.contains(plan)) { search.add(plans.size()); plans.add(plan); }
            }
            return;
        }
        if (a == attackers.size()) {
            enumerateBlocks(scratch, defender, attackers, blockers, b + 1, 0, plans, search); return;
        }
        enumerateBlocks(scratch, defender, attackers, blockers, b, a + 1, plans, search);
        Card attacker = attackers.get(a), blocker = blockers.get(b);
        if (CombatUtil.canBlock(attacker, blocker)
                && CombatUtil.canBlockMoreCreatures(blocker, scratch.getAttackersBlockedBy(blocker))
                && CombatUtil.getBlockCost(defender.getGame(), blocker, attacker) == null) {
            scratch.addBlocker(attacker, blocker);
            try { enumerateBlocks(scratch, defender, attackers, blockers, b, a + 1, plans, search); }
            finally { scratch.removeBlockAssignment(attacker, blocker); }
        }
    }

    private static boolean validBlocks(Combat scratch, Player defender) {
        for (Block block : blocks(scratch, defender)) {
            scratch.removeBlockAssignment(block.attacker(), block.blocker());
            boolean legal;
            try {
                legal = block.blocker().isInPlay() && block.blocker().getController().equals(defender)
                        && CombatUtil.canBlock(block.attacker(), block.blocker(), scratch)
                        && CombatUtil.getBlockCost(defender.getGame(), block.blocker(), block.attacker()) == null;
            } finally { scratch.addBlocker(block.attacker(), block.blocker()); }
            if (!legal) { return false; }
        }
        return CombatUtil.validateBlocks(scratch, defender) == null;
    }

    private static Combat emptyBlocks(Combat source) {
        Combat scratch = new Combat(source.getAttackingPlayer());
        source.getAttackersAndDefenders().forEach(scratch::addAttacker);
        return scratch;
    }

    private static List<Block> blocks(Combat combat, Player defender) {
        List<Block> result = new ArrayList<>();
        for (Card attacker : combat.getAttackers()) {
            for (Card blocker : combat.getBlockers(attacker)) {
                if (blocker.getController().equals(defender)) { result.add(new Block(attacker, blocker)); }
            }
        }
        return result;
    }

    private static void applyBlockers(Combat combat, Player defender, List<Block> plan) {
        for (Card blocker : new ArrayList<>(combat.getAllBlockers())) {
            if (blocker.getController().equals(defender)) { combat.undoBlockingAssignment(blocker); }
        }
        for (Block block : plan) { combat.addBlocker(block.attacker(), block.blocker()); }
    }

    private static String name(Card card) { return "card-" + card.getId() + " (" + (card.isFaceDown() ? "Face-down card" : card.getName()) + ")"; }
    private static String entityId(GameEntity entity) {
        return entity instanceof Card card ? "card-" + card.getId()
                : "p" + ((Player) entity).getGame().getPlayers().indexOf(entity);
    }
    private static String describeAttacks(Map<Card, GameEntity> plan) {
        return plan.isEmpty() ? "Do not attack." : "Attack: " + plan.entrySet().stream()
                .map(e -> name(e.getKey()) + " attacks " + entityId(e.getValue()))
                .reduce((a, b) -> a + "; " + b).orElse("");
    }
    private static JsonArray attackSnapshot(Map<Card, GameEntity> plan) {
        JsonArray result = new JsonArray();
        plan.forEach((card, defender) -> {
            JsonObject entry = new JsonObject(); entry.addProperty("attacker", "card-" + card.getId());
            entry.addProperty("defender", entityId(defender)); result.add(entry);
        }); return result;
    }
    private static JsonArray blockSnapshot(List<Block> plan) {
        JsonArray result = new JsonArray();
        for (Block block : plan) {
            JsonObject entry = new JsonObject(); entry.addProperty("attacker", "card-" + block.attacker().getId());
            entry.addProperty("blocker", "card-" + block.blocker().getId()); result.add(entry);
        } return result;
    }
    static JsonObject snapshot(Combat combat) {
        JsonObject result = new JsonObject(); result.add("attackers", attackSnapshot(combat.getAttackersAndDefenders()));
        JsonArray blocks = new JsonArray();
        for (Player player : combat.getDefendingPlayers()) {
            for (var entry : blockSnapshot(blocks(combat, player))) { blocks.add(entry); }
        }
        result.add("blocks", blocks); return result;
    }
}
