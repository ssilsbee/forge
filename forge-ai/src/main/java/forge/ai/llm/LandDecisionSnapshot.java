package forge.ai.llm;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import forge.card.MagicColor;
import forge.game.Game;
import forge.game.card.Card;
import forge.game.player.Player;
import forge.game.zone.ZoneType;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/** A deliberately narrow, detached view of a land drop. Never serialize a Game or a Card directly. */
public final class LandDecisionSnapshot {
    private static final ZoneType[] PUBLIC_ZONES = {
        ZoneType.Battlefield, ZoneType.Graveyard, ZoneType.Exile, ZoneType.Command
    };

    private LandDecisionSnapshot() { }

    /** Returns null outside the supported slice, including simulated games and single-choice drops. */
    public static JsonObject capture(Player viewer, Card baseline) {
        Game game = viewer.getGame();
        if (game.getMaingame() != null || game.isNoGUIUser() || game.isGameOver() || !game.getStack().isEmpty()
                || !game.getPhaseHandler().getPhase().isMain()
                || !viewer.equals(game.getPhaseHandler().getPlayerTurn())) {
            return null;
        }
        List<Card> choices = new ArrayList<>();
        for (Card card : viewer.getCardsIn(ZoneType.Hand)) {
            // Alternate faces, hidden playable zones and choices involving targets are later slices.
            if (card.isLand() && !card.isFaceDown() && !card.isDoubleFaced()
                    && viewer.canPlayLand(card, false, card.getFirstSpellAbility())) {
                choices.add(card);
            }
        }
        if (!choices.contains(baseline) || choices.stream().map(Card::getName).distinct().count() < 2) {
            return null;
        }
        JsonObject root = new JsonObject();
        root.addProperty("schemaVersion", 1);
        root.addProperty("decisionId", UUID.randomUUID().toString());
        root.addProperty("capturedAt", Instant.now().toString());
        root.addProperty("decisionType", "land_from_hand");
        root.addProperty("scope", "Choose one listed land to play now. Passing, spells, targets and other zones are outside this experiment.");
        root.addProperty("baselineActionId", actionId(baseline));
        JsonArray candidates = new JsonArray();
        for (Card card : choices) {
            JsonObject candidate = new JsonObject();
            candidate.addProperty("actionId", actionId(card));
            candidate.addProperty("action", "play_land");
            candidate.add("card", visibleCard(card, viewer));
            candidates.add(candidate);
        }
        root.add("candidates", candidates);
        JsonObject state = visibleState(viewer);
        if (state == null) { return null; }
        state.entrySet().forEach(entry -> root.add(entry.getKey(), entry.getValue()));
        return root;
    }

    /** Shared, conservative player view for detached experiments; never includes scoring objectives. */
    public static JsonObject visibleState(Player viewer) {
        Game game = viewer.getGame();
        JsonObject root = new JsonObject();
        root.addProperty("turn", game.getPhaseHandler().getTurn());
        root.addProperty("phase", game.getPhaseHandler().getPhase().name());
        root.addProperty("viewer", playerId(game, viewer));
        root.addProperty("informationPolicy", "Own hand plus visible public cards; opponent hands and all libraries are counts only. Face-down identities are omitted. Oracle text is printed text; current types, stats and counters are included. Battlefield creatures include summoningSick, accounting for haste. Summoning sickness does not prevent blocking.");
        JsonArray players = new JsonArray();
        int publicCardCount = 0;
        for (Player player : game.getPlayers()) {
            JsonObject state = new JsonObject();
            state.addProperty("id", playerId(game, player));
            state.addProperty("self", player.equals(viewer));
            state.addProperty("opponent", player.isOpponentOf(viewer));
            state.addProperty("life", player.getLife());
            state.addProperty("poison", player.getPoisonCounters());
            state.addProperty("landsPlayedThisTurn", player.getLandsPlayedThisTurn());
            state.addProperty("unlimitedLandPlays", player.getMaxLandPlaysInfinite());
            state.addProperty("maxLandPlays", player.getMaxLandPlays());
            JsonObject floating = new JsonObject();
            byte[] colors = {MagicColor.COLORLESS, MagicColor.WHITE, MagicColor.BLUE,
                MagicColor.BLACK, MagicColor.RED, MagicColor.GREEN};
            String[] symbols = {"C", "W", "U", "B", "R", "G"};
            for (int i = 0; i < colors.length; i++) {
                floating.addProperty(symbols[i], player.getManaPool().getAmountOfColor(colors[i]));
            }
            boolean restricted = false;
            for (var mana : player.getManaPool()) { restricted |= mana.isRestricted(); }
            // The first serializer does not describe mana-use restrictions. Skip these positions.
            if (restricted) { return null; }
            state.add("floatingMana", floating);
            state.addProperty("handCount", player.getCardsIn(ZoneType.Hand).size());
            state.addProperty("libraryCount", player.getCardsIn(ZoneType.Library).size());
            if (player.equals(viewer)) {
                JsonArray hand = new JsonArray();
                for (Card card : player.getCardsIn(ZoneType.Hand)) {
                    hand.add(visibleCard(card, viewer));
                    publicCardCount++;
                }
                state.add("hand", hand);
            }
            for (ZoneType zone : PUBLIC_ZONES) {
                JsonArray visible = new JsonArray();
                int hidden = 0;
                for (Card card : player.getCardsIn(zone)) {
                    if (card.getView().canBeShownTo(viewer.getView())) {
                        visible.add(visibleCard(card, viewer));
                        publicCardCount++;
                    } else {
                        hidden++;
                    }
                }
                state.add(zone.name(), visible);
                state.addProperty(zone.name() + "HiddenCount", hidden);
            }
            players.add(state);
        }
        // Skip oversized positions rather than silently remove strategically relevant information.
        if (publicCardCount > 256) {
            return null;
        }
        root.add("players", players);
        return root;
    }

    private static String actionId(Card card) {
        return "land-" + card.getId();
    }

    private static String playerId(Game game, Player player) {
        return "p" + game.getPlayers().indexOf(player);
    }

    static JsonObject visibleCard(Card card, Player viewer) {
        JsonObject result = new JsonObject();
        result.addProperty("id", "card-" + card.getId());
        result.addProperty("controller", playerId(viewer.getGame(), card.getController()));
        result.addProperty("tapped", card.isTapped());
        result.addProperty("faceDown", card.isFaceDown());
        if (card.isFaceDown()) {
            result.addProperty("name", "Face-down card");
        } else {
            result.addProperty("name", card.getName());
            result.addProperty("types", card.getType().toString());
            result.addProperty("manaCost", card.getManaCost().toString());
            result.addProperty("oracleText", card.getOracleText());
            JsonArray keywords = new JsonArray();
            for (var keyword : card.getKeywords()) { keywords.add(keyword.getOriginal()); }
            result.add("currentKeywords", keywords);
        }
        if (card.isCreature()) {
            if (card.isInZone(ZoneType.Battlefield)) {
                result.addProperty("summoningSick", card.isSick());
            }
            result.addProperty("power", card.getNetPower());
            result.addProperty("toughness", card.getNetToughness());
            result.addProperty("damage", card.getDamage());
        }
        JsonObject counters = new JsonObject();
        for (var entry : card.getCounters().entrySet()) {
            counters.addProperty(entry.getElement().toString(), entry.getCount());
        }
        result.add("counters", counters);
        return result;
    }
}
