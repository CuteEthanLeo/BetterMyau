package myau.module.modules;

import com.mojang.authlib.GameProfile;
import myau.event.EventTarget;
import myau.event.types.EventType;
import myau.events.AttackEvent;
import myau.events.LoadWorldEvent;
import myau.events.PacketEvent;
import myau.events.UpdateEvent;
import myau.module.Module;
import myau.property.properties.BooleanProperty;
import myau.property.properties.FloatProperty;
import myau.property.properties.IntProperty;
import myau.property.properties.ModeProperty;
import net.minecraft.client.network.NetworkPlayerInfo;
import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityLivingBase;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.network.Packet;
import net.minecraft.network.play.server.S0BPacketAnimation;
import net.minecraft.network.play.server.S13PacketDestroyEntities;
import net.minecraft.network.play.server.S14PacketEntity;
import net.minecraft.network.play.server.S20PacketEntityProperties;
import net.minecraft.potion.Potion;
import net.minecraft.scoreboard.ScorePlayerTeam;
import net.minecraft.util.MathHelper;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;

/**
 * Decides whether a player entity is a server-side bot (anticheat NPC, lobby NPC, ...).
 * Data is only collected while the module is enabled; {@link #isBot(EntityPlayer)} is false
 * for everyone while it is disabled.
 */
public class AntiBot extends Module {
    private static final Pattern COLOR_CODE = Pattern.compile("(?i)§[0-9A-FK-OR]");

    public final BooleanProperty tab = new BooleanProperty("tab", true);
    public final ModeProperty tabMode = new ModeProperty("tab-mode", 1, new String[]{"EQUALS", "CONTAINS"}, () -> this.tab.getValue());
    public final BooleanProperty entityId = new BooleanProperty("entity-id", true);
    public final BooleanProperty invalidUuid = new BooleanProperty("invalid-uuid", true);
    public final BooleanProperty color = new BooleanProperty("color", false);
    public final BooleanProperty livingTime = new BooleanProperty("living-time", false);
    public final IntProperty livingTimeTicks = new IntProperty("living-time-ticks", 40, 1, 200, () -> this.livingTime.getValue());
    public final BooleanProperty capabilities = new BooleanProperty("capabilities", true);
    public final BooleanProperty ground = new BooleanProperty("ground", true);
    public final BooleanProperty air = new BooleanProperty("air", false);
    public final BooleanProperty invalidGround = new BooleanProperty("invalid-ground", true);
    public final BooleanProperty invalidSpeed = new BooleanProperty("invalid-speed", false);
    public final BooleanProperty swing = new BooleanProperty("swing", false);
    public final BooleanProperty health = new BooleanProperty("health", false);
    public final BooleanProperty derp = new BooleanProperty("derp", true);
    public final BooleanProperty wasInvisible = new BooleanProperty("was-invisible", false);
    public final BooleanProperty armor = new BooleanProperty("armor", false);
    public final BooleanProperty ping = new BooleanProperty("ping", false);
    public final BooleanProperty needHit = new BooleanProperty("need-hit", false);
    public final BooleanProperty duplicateInWorld = new BooleanProperty("duplicate-in-world", false);
    public final BooleanProperty duplicateInTab = new BooleanProperty("duplicate-in-tab", false);
    public final BooleanProperty duplicateProfile = new BooleanProperty("duplicate-profile", false);
    public final BooleanProperty propertiesCheck = new BooleanProperty("properties", false);
    public final BooleanProperty alwaysInRadius = new BooleanProperty("always-in-radius", false);
    public final FloatProperty alwaysInRadiusBlocks = new FloatProperty("always-in-radius-blocks", 20.0F, 3.0F, 30.0F, () -> this.alwaysInRadius.getValue());
    public final IntProperty alwaysInRadiusTick = new IntProperty("always-in-radius-tick", 50, 1, 100, () -> this.alwaysInRadius.getValue());
    public final BooleanProperty alwaysBehind = new BooleanProperty("always-behind", false);
    public final FloatProperty alwaysBehindRadiusBlocks = new FloatProperty("always-behind-radius-blocks", 10.0F, 3.0F, 30.0F, () -> this.alwaysBehind.getValue());
    public final FloatProperty behindRotationDiff = new FloatProperty("behind-rotation-diff-to-ignore", 90.0F, 1.0F, 180.0F, () -> this.alwaysBehind.getValue());

    // Written from the network thread (cheap packets) and the client thread, read on the client thread.
    private final Set<Integer> groundList = newSet();
    private final Set<Integer> airList = newSet();
    private final Map<Integer, Integer> invalidGroundList = new ConcurrentHashMap<>();
    private final Set<Integer> invalidSpeedList = newSet();
    private final Set<Integer> swingList = newSet();
    private final Set<Integer> invisibleList = newSet();
    private final Set<Integer> propertiesList = newSet();
    private final Set<Integer> hitList = newSet();
    private final Set<Integer> notAlwaysInRadiusList = newSet();
    private final Set<Integer> alwaysBehindList = newSet();
    private final Map<Integer, Integer> entityTickMap = new ConcurrentHashMap<>();
    private final Set<UUID> botIds = newSet();

    private float serverYaw;
    private boolean serverYawKnown;

    // Colour-stripped tab names, rebuilt at most once per client tick.
    private List<String> tabNames = Collections.emptyList();
    private int tabNamesTick = Integer.MIN_VALUE;
    private Object tabNamesWorld;

    public AntiBot() {
        super("AntiBot", false);
    }

    private static <T> Set<T> newSet() {
        return Collections.newSetFromMap(new ConcurrentHashMap<T, Boolean>());
    }

    private static String stripColor(String text) {
        return text == null ? "" : COLOR_CODE.matcher(text).replaceAll("");
    }

    private static String getFullName(NetworkPlayerInfo info) {
        if (info.getDisplayName() != null) {
            return info.getDisplayName().getFormattedText();
        }
        GameProfile profile = info.getGameProfile();
        String name = profile == null || profile.getName() == null ? "" : profile.getName();
        return ScorePlayerTeam.formatPlayerName(info.getPlayerTeam(), name);
    }

    @Override
    public void onEnabled() {
        this.clearAll();
    }

    @Override
    public void onDisabled() {
        this.clearAll();
    }

    /** @return true when the module is enabled and the player failed one of the enabled checks */
    public boolean isBot(EntityPlayer entity) {
        if (!this.isEnabled() || entity == null) {
            return false;
        }
        if (mc.thePlayer == null || mc.theWorld == null || mc.getNetHandler() == null || entity == mc.thePlayer) {
            return false;
        }
        int id = entity.getEntityId();

        if (this.color.getValue() && !entity.getDisplayName().getFormattedText().replace("§r", "").contains("§")) {
            return true;
        }
        if (this.livingTime.getValue() && entity.ticksExisted < this.livingTimeTicks.getValue()) {
            return true;
        }
        if (this.ground.getValue() && !this.groundList.contains(id)) {
            return true;
        }
        if (this.air.getValue() && !this.airList.contains(id)) {
            return true;
        }
        if (this.swing.getValue() && !this.swingList.contains(id)) {
            return true;
        }
        if (this.health.getValue() && (entity.getHealth() > 20.0F || entity.getHealth() < 0.0F)) {
            return true;
        }
        if (this.entityId.getValue() && (id >= 1000000000 || id <= 0)) {
            return true;
        }
        if (this.derp.getValue() && (entity.rotationPitch > 90.0F || entity.rotationPitch < -90.0F)) {
            return true;
        }
        if (this.wasInvisible.getValue() && this.invisibleList.contains(id)) {
            return true;
        }
        if (this.propertiesCheck.getValue() && !this.propertiesList.contains(id)) {
            return true;
        }
        if (this.armor.getValue()) {
            boolean naked = true;
            for (int i = 0; i < 4; i++) {
                if (entity.inventory.armorInventory[i] != null) {
                    naked = false;
                    break;
                }
            }
            if (naked) {
                return true;
            }
        }
        NetworkPlayerInfo info = mc.getNetHandler().getPlayerInfo(entity.getUniqueID());
        if (this.ping.getValue() && (info == null || info.getResponseTime() == 0)) {
            return true;
        }
        if (this.invalidUuid.getValue() && info == null) {
            return true;
        }
        if (this.capabilities.getValue() && (entity.isSpectator() || entity.capabilities.isFlying || entity.capabilities.allowFlying
                || entity.capabilities.disableDamage || entity.capabilities.isCreativeMode)) {
            return true;
        }
        if (this.invalidSpeed.getValue() && this.invalidSpeedList.contains(id)) {
            return true;
        }
        if (this.needHit.getValue() && !this.hitList.contains(id)) {
            return true;
        }
        if (this.invalidGround.getValue()) {
            Integer vl = this.invalidGroundList.get(id);
            if (vl != null && vl >= 10) {
                return true;
            }
        }
        if (this.alwaysInRadius.getValue() && !this.notAlwaysInRadiusList.contains(id)) {
            return true;
        }
        if (this.alwaysBehind.getValue() && this.alwaysBehindList.contains(id)) {
            return true;
        }
        String name = entity.getName();
        if (this.duplicateProfile.getValue()) {
            // Same name in the tab list under another UUID.
            GameProfile own = entity.getGameProfile();
            int others = 0;
            for (NetworkPlayerInfo other : mc.getNetHandler().getPlayerInfoMap()) {
                GameProfile profile = other == null ? null : other.getGameProfile();
                if (profile == null || own == null || profile.getName() == null) continue;
                if (profile.getName().equals(own.getName()) && profile.getId() != null && !profile.getId().equals(own.getId())) {
                    others++;
                }
            }
            if (others == 1) {
                return true;
            }
        }
        if (this.duplicateInWorld.getValue()) {
            int count = 0;
            for (EntityPlayer player : mc.theWorld.playerEntities) {
                if (player != null && name.equals(player.getName())) {
                    count++;
                }
            }
            if (count > 1) {
                return true;
            }
        }
        if (this.duplicateInTab.getValue()) {
            int count = 0;
            for (String tabName : this.getTabNames()) {
                if (tabName.equals(name)) {
                    count++;
                }
            }
            if (count > 1) {
                return true;
            }
        }
        if (this.tab.getValue()) {
            boolean equals = this.tabMode.getValue() == 0;
            String targetName = stripColor(entity.getDisplayName().getFormattedText());
            for (String tabName : this.getTabNames()) {
                if (equals ? targetName.equals(tabName) : targetName.contains(tabName)) {
                    return false;
                }
            }
            return true;
        }
        return name.isEmpty() || name.equals(mc.thePlayer.getName());
    }

    /** Bots by profile UUID, as of the last client tick. Covers players that are only known from the tab list. */
    public boolean isBotId(UUID id) {
        return this.isEnabled() && id != null && this.botIds.contains(id);
    }

    private List<String> getTabNames() {
        int tick = mc.thePlayer.ticksExisted;
        if (this.tabNamesTick != tick || this.tabNamesWorld != mc.theWorld) {
            List<String> names = new ArrayList<>();
            for (NetworkPlayerInfo info : mc.getNetHandler().getPlayerInfoMap()) {
                if (info != null) {
                    names.add(stripColor(getFullName(info)));
                }
            }
            this.tabNames = names;
            this.tabNamesTick = tick;
            this.tabNamesWorld = mc.theWorld;
        }
        return this.tabNames;
    }

    @EventTarget
    public void onUpdate(UpdateEvent event) {
        if (!this.isEnabled() || event.getType() != EventType.PRE) return;
        if (mc.thePlayer == null || mc.theWorld == null) return;
        this.serverYaw = event.getYaw();
        this.serverYawKnown = true;
        for (EntityPlayer player : mc.theWorld.playerEntities) {
            if (player == null || player == mc.thePlayer) continue;
            GameProfile profile = player.getGameProfile();
            if (profile == null || profile.getId() == null) continue;
            if (this.isBot(player)) {
                this.botIds.add(profile.getId());
            } else {
                this.botIds.remove(profile.getId());
            }
        }
    }

    @EventTarget
    public void onPacket(PacketEvent event) {
        if (!this.isEnabled() || event.getType() != EventType.RECEIVE) return;
        if (mc.thePlayer == null || mc.theWorld == null) return;
        Packet<?> packet = event.getPacket();
        if (packet instanceof S14PacketEntity) {
            // Needs the world: run on the client thread. The task is queued ahead of the packet's own
            // handling, so the entity is still in the state the previous movement packet left it in.
            final S14PacketEntity move = (S14PacketEntity) packet;
            mc.addScheduledTask(() -> this.handleEntityMove(move));
        } else if (packet instanceof S0BPacketAnimation) {
            S0BPacketAnimation animation = (S0BPacketAnimation) packet;
            if (animation.getAnimationType() == 0) {
                this.swingList.add(animation.getEntityID());
            }
        } else if (packet instanceof S20PacketEntityProperties) {
            this.propertiesList.add(((S20PacketEntityProperties) packet).getEntityId());
        } else if (packet instanceof S13PacketDestroyEntities) {
            for (int id : ((S13PacketDestroyEntities) packet).getEntityIDs()) {
                this.groundList.remove(id);
                this.airList.remove(id);
                this.invalidGroundList.remove(id);
                this.swingList.remove(id);
                this.invisibleList.remove(id);
                this.notAlwaysInRadiusList.remove(id);
                this.propertiesList.remove(id);
            }
        }
    }

    private void handleEntityMove(S14PacketEntity packet) {
        if (!this.isEnabled() || mc.thePlayer == null || mc.theWorld == null) return;
        Entity raw = packet.getEntity(mc.theWorld);
        if (!(raw instanceof EntityPlayer) || raw == mc.thePlayer) return;
        EntityPlayer entity = (EntityPlayer) raw;
        int id = entity.getEntityId();

        if (entity.onGround) {
            this.groundList.add(id);
        } else {
            this.airList.add(id);
        }

        // On the ground while still moving vertically raises the level; time in the air halves it.
        if (entity.onGround) {
            if (entity.prevPosY != entity.posY) {
                Integer vl = this.invalidGroundList.get(id);
                this.invalidGroundList.put(id, (vl == null ? 0 : vl) + 1);
            }
        } else {
            Integer vl = this.invalidGroundList.get(id);
            int halved = (vl == null ? 0 : vl) / 2;
            if (halved <= 0) {
                this.invalidGroundList.remove(id);
            } else {
                this.invalidGroundList.put(id, halved);
            }
        }

        if (entity.isInvisible() || entity.isInvisibleToPlayer(mc.thePlayer)) {
            this.invisibleList.add(id);
        }

        if (this.alwaysInRadius.getValue()) {
            float distance = mc.thePlayer.getDistanceToEntity(entity);
            Integer current = this.entityTickMap.get(id);
            int ticks = distance < this.alwaysInRadiusBlocks.getValue() ? (current == null ? 0 : current) + 1 : 0;
            this.entityTickMap.put(id, ticks);
            if (ticks >= this.alwaysInRadiusTick.getValue()) {
                this.notAlwaysInRadiusList.remove(id);
            } else {
                this.notAlwaysInRadiusList.add(id);
            }
        }

        if (this.alwaysBehind.getValue()) {
            float distance = mc.thePlayer.getDistanceToEntity(entity);
            double dx = entity.posX - mc.thePlayer.posX;
            double dz = entity.posZ - mc.thePlayer.posZ;
            float yawToEntity = (float) (Math.atan2(dz, dx) * 180.0 / Math.PI) - 90.0F;
            float ownYaw = this.serverYawKnown ? this.serverYaw : mc.thePlayer.rotationYaw;
            float difference = Math.abs(MathHelper.wrapAngleTo180_float(yawToEntity - ownYaw));
            if (distance < this.alwaysBehindRadiusBlocks.getValue() && difference > this.behindRotationDiff.getValue()) {
                this.alwaysBehindList.add(id);
            } else {
                this.alwaysBehindList.remove(id);
            }
        }

        if (this.invalidSpeed.getValue()) {
            double deltaX = entity.posX - entity.prevPosX;
            double deltaZ = entity.posZ - entity.prevPosZ;
            double speed = Math.sqrt(deltaX * deltaX + deltaZ * deltaZ);
            if (speed >= 0.45 && speed <= 0.46 && (!entity.isSprinting() || entity.getActivePotionEffect(Potion.moveSpeed) == null)) {
                this.invalidSpeedList.add(id);
            }
        }
    }

    @EventTarget
    public void onAttack(AttackEvent event) {
        if (!this.isEnabled()) return;
        Entity target = event.getTarget();
        if (target instanceof EntityLivingBase) {
            this.hitList.add(target.getEntityId());
        }
    }

    @EventTarget
    public void onLoadWorld(LoadWorldEvent event) {
        if (!this.isEnabled()) return;
        this.clearAll();
    }

    private void clearAll() {
        this.hitList.clear();
        this.swingList.clear();
        this.groundList.clear();
        this.airList.clear();
        this.invalidGroundList.clear();
        this.invalidSpeedList.clear();
        this.invisibleList.clear();
        this.propertiesList.clear();
        this.notAlwaysInRadiusList.clear();
        this.alwaysBehindList.clear();
        this.entityTickMap.clear();
        this.botIds.clear();
        this.tabNames = Collections.emptyList();
        this.tabNamesTick = Integer.MIN_VALUE;
        this.tabNamesWorld = null;
        this.serverYawKnown = false;
    }
}
