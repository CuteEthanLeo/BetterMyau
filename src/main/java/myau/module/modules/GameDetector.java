package myau.module.modules;

import myau.event.EventTarget;
import myau.event.types.EventType;
import myau.event.types.Priority;
import myau.events.LoadWorldEvent;
import myau.events.UpdateEvent;
import myau.module.Module;
import myau.property.properties.BooleanProperty;
import myau.property.properties.IntProperty;
import net.minecraft.entity.Entity;
import net.minecraft.entity.boss.IBossDisplayData;
import net.minecraft.entity.item.EntityArmorStand;
import net.minecraft.entity.player.PlayerCapabilities;
import net.minecraft.init.Items;
import net.minecraft.item.ItemStack;
import net.minecraft.potion.Potion;
import net.minecraft.potion.PotionEffect;
import net.minecraft.scoreboard.ScoreObjective;
import net.minecraft.scoreboard.ScorePlayerTeam;
import net.minecraft.scoreboard.Scoreboard;
import net.minecraft.scoreboard.Team;

/**
 * Tells a running game from a lobby / waiting room. Other modules ask {@link #isInGame()};
 * while this module is disabled the answer is always true.
 */
public class GameDetector extends Module {
    private static final String[] WHITELISTED_SUBSTRINGS = {":", "vazio!", "§6§lrumble box", "§5§ldivine drop"};
    private static final String[] LOBBY_SUBSTRINGS = {"lobby", "hub", "waiting", "loading", "starting"};

    // Player's gamemode is survival or adventure
    public final BooleanProperty gameMode = new BooleanProperty("game-mode-check", true);
    // Player has no unnatural capabilities
    public final BooleanProperty capabilities = new BooleanProperty("capabilities-check", true);
    // More than one player in the tab list
    public final BooleanProperty tabList = new BooleanProperty("tab-list-check", true);
    // More than one team, or friendly fire is on
    public final BooleanProperty teams = new BooleanProperty("teams-check", true);
    // Player does not have the infinite invisibility effect
    public final BooleanProperty invisibility = new BooleanProperty("invisibility-check", true);
    // Player has no compass (lobby selector item)
    public final BooleanProperty compass = new BooleanProperty("compass-check", false);
    public final BooleanProperty checkAllSlots = new BooleanProperty("check-all-slots", true, () -> this.compass.getValue());
    public final IntProperty slot = new IntProperty("slot", 1, 1, 9, () -> this.compass.getValue() && !this.checkAllSlots.getValue());
    // No hub-like boss bar or armor stand entities
    public final BooleanProperty entity = new BooleanProperty("entity-check", false);
    // No lobby / waiting words on the scoreboard (needed on Gamster)
    public final BooleanProperty scoreboard = new BooleanProperty("scoreboard-check", false);

    private boolean isPlaying;

    public GameDetector() {
        super("GameDetector", false);
    }

    public boolean isInGame() {
        return !this.isEnabled() || this.isPlaying;
    }

    @Override
    public void onEnabled() {
        this.isPlaying = false;
    }

    private static boolean containsAny(String text, String[] substrings) {
        if (text == null) return false;
        String lower = text.toLowerCase();
        for (String substring : substrings) {
            if (lower.contains(substring)) {
                return true;
            }
        }
        return false;
    }

    @EventTarget(Priority.HIGH)
    public void onUpdate(UpdateEvent event) {
        if (!this.isEnabled() || event.getType() != EventType.PRE) return;
        this.isPlaying = this.detect();
    }

    private boolean detect() {
        if (mc.thePlayer == null || mc.theWorld == null || mc.getNetHandler() == null || mc.playerController == null) {
            return false;
        }

        if (this.gameMode.getValue() && !mc.playerController.gameIsSurvivalOrAdventure()) {
            return false;
        }

        PlayerCapabilities abilities = mc.thePlayer.capabilities;
        if (this.capabilities.getValue() && (!abilities.allowEdit || abilities.allowFlying || abilities.isFlying || abilities.disableDamage)) {
            return false;
        }

        if (this.tabList.getValue() && mc.getNetHandler().getPlayerInfoMap().size() <= 1) {
            return false;
        }

        Scoreboard board = mc.theWorld.getScoreboard();

        if (this.teams.getValue()) {
            Team team = mc.thePlayer.getTeam();
            if (team != null && !team.getAllowFriendlyFire() && board.getTeams().size() == 1) {
                return false;
            }
        }

        if (this.invisibility.getValue()) {
            PotionEffect effect = mc.thePlayer.getActivePotionEffect(Potion.invisibility);
            if (effect != null && effect.getIsPotionDurationMax()) {
                return false;
            }
        }

        if (this.compass.getValue()) {
            if (this.checkAllSlots.getValue()) {
                if (mc.thePlayer.inventory.hasItem(Items.compass)) {
                    return false;
                }
            } else {
                ItemStack stack = mc.thePlayer.inventory.getStackInSlot(this.slot.getValue() - 1);
                if (stack != null && stack.getItem() == Items.compass) {
                    return false;
                }
            }
        }

        if (this.scoreboard.getValue()) {
            ScoreObjective sidebar = board.getObjectiveInDisplaySlot(1);
            if (sidebar != null && containsAny(sidebar.getDisplayName(), LOBBY_SUBSTRINGS)) {
                return false;
            }
            for (String name : board.getObjectiveNames()) {
                if (containsAny(name, LOBBY_SUBSTRINGS)) {
                    return false;
                }
            }
            for (ScorePlayerTeam team : board.getTeams()) {
                if (containsAny(team.getColorPrefix(), LOBBY_SUBSTRINGS)) {
                    return false;
                }
            }
        }

        if (this.entity.getValue()) {
            for (Entity loaded : mc.theWorld.loadedEntityList) {
                if (!(loaded instanceof IBossDisplayData) && !(loaded instanceof EntityArmorStand)) continue;
                // The first boss / armor stand decides: a whitelisted name (in-game hologram) passes,
                // anything else is taken for lobby decoration.
                if (containsAny(loaded.getCustomNameTag(), WHITELISTED_SUBSTRINGS)) {
                    break;
                }
                return false;
            }
        }

        return true;
    }

    @EventTarget
    public void onLoadWorld(LoadWorldEvent event) {
        if (!this.isEnabled()) return;
        this.isPlaying = false;
    }
}
