package myau.module.modules;

import myau.event.EventTarget;
import myau.event.types.EventType;
import myau.events.PacketEvent;
import myau.module.Module;
import myau.property.properties.BooleanProperty;
import myau.util.ChatUtil;
import myau.util.PacketUtil;
import net.minecraft.client.entity.EntityPlayerSP;
import net.minecraft.client.multiplayer.WorldClient;
import net.minecraft.entity.Entity;
import net.minecraft.entity.item.EntityItem;
import net.minecraft.network.Packet;
import net.minecraft.network.play.client.C19PacketResourcePackStatus;
import net.minecraft.network.play.server.S02PacketChat;
import net.minecraft.network.play.server.S03PacketTimeUpdate;
import net.minecraft.network.play.server.S06PacketUpdateHealth;
import net.minecraft.network.play.server.S08PacketPlayerPosLook;
import net.minecraft.network.play.server.S09PacketHeldItemChange;
import net.minecraft.network.play.server.S0APacketUseBed;
import net.minecraft.network.play.server.S0CPacketSpawnPlayer;
import net.minecraft.network.play.server.S0DPacketCollectItem;
import net.minecraft.network.play.server.S0EPacketSpawnObject;
import net.minecraft.network.play.server.S11PacketSpawnExperienceOrb;
import net.minecraft.network.play.server.S12PacketEntityVelocity;
import net.minecraft.network.play.server.S13PacketDestroyEntities;
import net.minecraft.network.play.server.S14PacketEntity;
import net.minecraft.network.play.server.S19PacketEntityStatus;
import net.minecraft.network.play.server.S22PacketMultiBlockChange;
import net.minecraft.network.play.server.S27PacketExplosion;
import net.minecraft.network.play.server.S2APacketParticles;
import net.minecraft.network.play.server.S2BPacketChangeGameState;
import net.minecraft.network.play.server.S2DPacketOpenWindow;
import net.minecraft.network.play.server.S33PacketUpdateSign;
import net.minecraft.network.play.server.S39PacketPlayerAbilities;
import net.minecraft.network.play.server.S43PacketCamera;
import net.minecraft.network.play.server.S48PacketResourcePackSend;
import net.minecraft.util.AxisAlignedBB;
import net.minecraft.util.BlockPos;
import net.minecraft.util.IChatComponent;

import java.net.URI;
import java.net.URISyntaxException;

/**
 * Drops incoming packets whose contents no honest server sends (crash / lag exploits):
 * out-of-world coordinates, absurd counts and sizes, packet floods.
 */
public class ClientFixes extends Module {
    public final BooleanProperty useBedPacket = new BooleanProperty("use-bed", true);
    public final BooleanProperty chatPacket = new BooleanProperty("chat", true);
    public final BooleanProperty multiBlockPacket = new BooleanProperty("multi-block-change", true);
    public final BooleanProperty spawnObjectPacket = new BooleanProperty("spawn-object", true);
    public final BooleanProperty spawnPlayerPacket = new BooleanProperty("spawn-player", true);
    public final BooleanProperty entityStatusPacket = new BooleanProperty("entity-status", true);
    public final BooleanProperty updateSignPacket = new BooleanProperty("update-sign", true);
    public final BooleanProperty destroyEntitiesPacket = new BooleanProperty("destroy-entities", true);
    public final BooleanProperty collectItemPacket = new BooleanProperty("collect-item", true);
    public final BooleanProperty cameraPacket = new BooleanProperty("camera", true);
    public final BooleanProperty entityPacket = new BooleanProperty("entity", true);
    public final BooleanProperty spawnExpOrbPacket = new BooleanProperty("spawn-experience-orb", true);
    public final BooleanProperty changeGameStatePacket = new BooleanProperty("change-game-state", true);
    public final BooleanProperty heldItemPacket = new BooleanProperty("held-item-change", true);
    public final BooleanProperty playerAbilitiesPacket = new BooleanProperty("player-abilities", true);
    public final BooleanProperty updateHealthPacket = new BooleanProperty("update-health", true);
    public final BooleanProperty playerPosLookPacket = new BooleanProperty("player-pos-look", true);
    public final BooleanProperty entityVelocityPacket = new BooleanProperty("entity-velocity", true);
    public final BooleanProperty particlesPacket = new BooleanProperty("particles", true);
    public final BooleanProperty explosionPacket = new BooleanProperty("explosion", true);
    public final BooleanProperty timePacket = new BooleanProperty("time-update", true);
    public final BooleanProperty openWindowPacket = new BooleanProperty("open-window", true);
    public final BooleanProperty resourcePackSendPacket = new BooleanProperty("resource-pack-send", true);
    public final BooleanProperty debug = new BooleanProperty("debug-message", true);

    // Only touched by the network thread
    private long lastExperience;
    private long lastExplosion;
    private long lastGuardian;
    private long lastArrow;
    private int invalidExperience;

    public ClientFixes() {
        super("ClientFixes", false);
    }

    @Override
    public void onEnabled() {
        this.lastExperience = 0L;
        this.lastExplosion = 0L;
        this.lastGuardian = 0L;
        this.lastArrow = 0L;
        this.invalidExperience = 0;
    }

    private static boolean isNotValid(double value, double max) {
        return value > max || value < -max;
    }

    private static boolean isNotValid(long value, long max) {
        return value > max || value < -max;
    }

    private static boolean isNotValidPosition(double x, double y, double z) {
        return isNotValid(x, 30000000) || isNotValid(y, 30000000) || isNotValid(z, 30000000);
    }

    private static boolean isNotValidPosition(BlockPos pos) {
        return pos == null || isNotValidPosition(pos.getX(), pos.getY(), pos.getZ());
    }

    private void reject(PacketEvent event, String what) {
        event.setCancelled(true);
        if (what != null && this.debug.getValue()) {
            final String message = "&4[ClientFixes]&e Invalid " + what + ".";
            // Network thread: the chat belongs to the client thread.
            mc.addScheduledTask(() -> ChatUtil.sendFormatted(message));
        }
    }

    @EventTarget
    public void onPacket(PacketEvent event) {
        if (!this.isEnabled() || event.getType() != EventType.RECEIVE || event.isCancelled()) return;
        Packet<?> raw = event.getPacket();
        // Read once: both can be replaced by the client thread while this runs on the network thread.
        EntityPlayerSP player = mc.thePlayer;
        WorldClient world = mc.theWorld;
        long now = System.currentTimeMillis();

        if (raw instanceof S48PacketResourcePackSend) {
            if (!this.resourcePackSendPacket.getValue()) return;
            S48PacketResourcePackSend packet = (S48PacketResourcePackSend) raw;
            String url = packet.getURL();
            boolean invalid = false;
            boolean answer = false;
            try {
                String scheme = new URI(url).getScheme();
                boolean isLevelProtocol = "level".equalsIgnoreCase(scheme);
                if (!"http".equalsIgnoreCase(scheme) && !"https".equalsIgnoreCase(scheme) && !isLevelProtocol) {
                    invalid = true;
                } else if (isLevelProtocol && (url.contains("..") || !url.endsWith("/resources.zip"))) {
                    invalid = true;
                    answer = true;
                }
            } catch (URISyntaxException | NullPointerException e) {
                invalid = true;
                answer = true;
            }
            if (invalid) {
                if (answer && mc.getNetHandler() != null) {
                    PacketUtil.sendPacket(new C19PacketResourcePackStatus(packet.getHash(), C19PacketResourcePackStatus.Action.FAILED_DOWNLOAD));
                }
                this.reject(event, "resource pack packet");
            }
        } else if (raw instanceof S2DPacketOpenWindow) {
            if (!this.openWindowPacket.getValue()) return;
            // The largest vanilla container is a double chest
            if (((S2DPacketOpenWindow) raw).getSlotCount() > 54) {
                this.reject(event, "open window packet");
            }
        } else if (raw instanceof S12PacketEntityVelocity) {
            if (!this.entityVelocityPacket.getValue() || player == null) return;
            S12PacketEntityVelocity packet = (S12PacketEntityVelocity) raw;
            if (packet.getEntityID() != player.getEntityId()) return;
            if (isNotValid(packet.getMotionX(), 31201L) || isNotValid(packet.getMotionY(), 31201L) || isNotValid(packet.getMotionZ(), 31201L)) {
                this.reject(event, "entity velocity packet");
            }
        } else if (raw instanceof S2APacketParticles) {
            if (!this.particlesPacket.getValue()) return;
            S2APacketParticles packet = (S2APacketParticles) raw;
            if (isNotValidPosition(packet.getXOffset(), packet.getYOffset(), packet.getZOffset())
                    || isNotValidPosition(packet.getXCoordinate(), packet.getYCoordinate(), packet.getZCoordinate())
                    || isNotValid(packet.getParticleSpeed(), 3000.0)
                    || isNotValid(packet.getParticleCount(), 1200L)
                    || isNotValid(packet.getParticleArgs().length, 30000000L)) {
                this.reject(event, "particles packet");
            }
        } else if (raw instanceof S08PacketPlayerPosLook) {
            if (!this.playerPosLookPacket.getValue()) return;
            S08PacketPlayerPosLook packet = (S08PacketPlayerPosLook) raw;
            if (isNotValidPosition(packet.getX(), packet.getY(), packet.getZ())
                    || isNotValid(packet.getYaw(), 10800.0) || isNotValid(packet.getPitch(), 360.0)) {
                this.reject(event, "player pos look packet");
            }
        } else if (raw instanceof S06PacketUpdateHealth) {
            if (!this.updateHealthPacket.getValue()) return;
            S06PacketUpdateHealth packet = (S06PacketUpdateHealth) raw;
            double maxHealth = player == null ? 20.0 : player.getMaxHealth();
            if (isNotValid(packet.getHealth(), maxHealth + 1E3) || isNotValid(packet.getFoodLevel(), 1E5)
                    || isNotValid(packet.getSaturationLevel(), 512.0)) {
                this.reject(event, "update health packet");
            }
        } else if (raw instanceof S39PacketPlayerAbilities) {
            if (!this.playerAbilitiesPacket.getValue()) return;
            S39PacketPlayerAbilities packet = (S39PacketPlayerAbilities) raw;
            if (isNotValid(packet.getFlySpeed(), 1.0) || isNotValid(packet.getWalkSpeed(), 2.0)) {
                this.reject(event, "player abilities packet");
            }
        } else if (raw instanceof S2BPacketChangeGameState) {
            if (!this.changeGameStatePacket.getValue()) return;
            S2BPacketChangeGameState packet = (S2BPacketChangeGameState) raw;
            int state = packet.getGameState();
            if (state == 5) {
                // Demo screen
                this.reject(event, "change game state packet");
            } else if ((state == 7 || state == 8) && packet.func_149137_d() >= 100.0F) {
                // Rain / thunder strength
                this.reject(event, "change game state packet");
            } else if (state == 10) {
                // Elder guardian effect
                if (now - this.lastGuardian < 5L) {
                    this.reject(event, "change game state packet");
                }
                this.lastGuardian = now;
            }
        } else if (raw instanceof S09PacketHeldItemChange) {
            if (!this.heldItemPacket.getValue()) return;
            int index = ((S09PacketHeldItemChange) raw).getHeldItemHotbarIndex();
            if (index < 0 || index > 8) {
                this.reject(event, "held item change packet");
            }
        } else if (raw instanceof S11PacketSpawnExperienceOrb) {
            if (!this.spawnExpOrbPacket.getValue()) return;
            if (now - this.lastExperience <= 1L) {
                this.invalidExperience++;
                if (this.invalidExperience >= 2) {
                    this.reject(event, null);
                }
            } else {
                this.invalidExperience = 0;
            }
            this.lastExperience = now;
        } else if (raw instanceof S43PacketCamera) {
            if (!this.cameraPacket.getValue() || player == null || world == null) return;
            if (((S43PacketCamera) raw).getEntity(world) == player && !player.isSpectator()) {
                this.reject(event, "camera packet");
            }
        } else if (raw instanceof S33PacketUpdateSign) {
            if (!this.updateSignPacket.getValue()) return;
            IChatComponent[] lines = ((S33PacketUpdateSign) raw).getLines();
            int length = 0;
            if (lines != null) {
                for (IChatComponent line : lines) {
                    if (line != null) {
                        length += line.getUnformattedText().length();
                    }
                }
            }
            if (length > 2048) {
                this.reject(event, "sign packet");
            }
        } else if (raw instanceof S0DPacketCollectItem) {
            if (!this.collectItemPacket.getValue() || world == null) return;
            Entity item = world.getEntityByID(((S0DPacketCollectItem) raw).getCollectedItemEntityID());
            if (item instanceof EntityItem && item.getName().length() > 512) {
                this.reject(event, "collect item packet");
            }
        } else if (raw instanceof S14PacketEntity) {
            if (!this.entityPacket.getValue() || world == null) return;
            Entity entity = ((S14PacketEntity) raw).getEntity(world);
            AxisAlignedBB box = entity == null ? null : entity.getEntityBoundingBox();
            if (box != null && isNotValid((box.maxX - box.minX) * (box.maxY - box.minY) * (box.maxZ - box.minZ), 1000.0)) {
                this.reject(event, "entity packet");
            }
        } else if (raw instanceof S0APacketUseBed) {
            if (!this.useBedPacket.getValue()) return;
            if (isNotValidPosition(((S0APacketUseBed) raw).getBedPosition())) {
                this.reject(event, "bed position");
            }
        } else if (raw instanceof S27PacketExplosion) {
            if (!this.explosionPacket.getValue()) return;
            S27PacketExplosion packet = (S27PacketExplosion) raw;
            if (isNotValidPosition(packet.getX(), packet.getY(), packet.getZ())
                    || isNotValid(packet.getStrength(), 100.0)
                    || isNotValid(packet.func_149149_c(), 8.0)
                    || isNotValid(packet.func_149144_d(), 8.0)
                    || isNotValid(packet.func_149147_e(), 8.0)
                    || packet.getAffectedBlockPositions().size() > 3000
                    || now - this.lastExplosion <= 1L) {
                this.reject(event, "explosion packet");
            } else {
                this.lastExplosion = now;
            }
        } else if (raw instanceof S22PacketMultiBlockChange) {
            if (!this.multiBlockPacket.getValue()) return;
            S22PacketMultiBlockChange.BlockUpdateData[] changed = ((S22PacketMultiBlockChange) raw).getChangedBlocks();
            if (changed != null && changed.length > 300000000) {
                this.reject(event, "multi block change packet");
            }
        } else if (raw instanceof S02PacketChat) {
            if (!this.chatPacket.getValue()) return;
            IChatComponent component = ((S02PacketChat) raw).getChatComponent();
            if (component != null && component.getFormattedText().length() > 2048) {
                this.reject(event, "chat packet");
            }
        } else if (raw instanceof S0EPacketSpawnObject) {
            if (!this.spawnObjectPacket.getValue()) return;
            S0EPacketSpawnObject packet = (S0EPacketSpawnObject) raw;
            if (isNotValid(packet.getX(), 2.9999997E7) || isNotValid(packet.getY(), 2.9999997E7) || isNotValid(packet.getZ(), 2.9999997E7)
                    || isNotValid(packet.getYaw(), 10800L) || isNotValid(packet.getPitch(), 10800L)
                    || isNotValid(packet.getSpeedX(), 32767L) || isNotValid(packet.getSpeedY(), 32767L) || isNotValid(packet.getSpeedZ(), 32767L)) {
                this.reject(event, "spawn object packet");
            }
            if (packet.getType() == 60) {
                // Arrow flood
                if (now - this.lastArrow < 20L) {
                    this.reject(event, "arrow packet");
                }
                this.lastArrow = now;
            }
        } else if (raw instanceof S0CPacketSpawnPlayer) {
            if (!this.spawnPlayerPacket.getValue()) return;
            S0CPacketSpawnPlayer packet = (S0CPacketSpawnPlayer) raw;
            if (isNotValid(packet.getX(), 2.9999997E7) || isNotValid(packet.getY(), 2.9999997E7) || isNotValid(packet.getZ(), 2.9999997E7)
                    || isNotValid(packet.getYaw(), 10800L) || isNotValid(packet.getPitch(), 10800L)) {
                this.reject(event, "spawn player packet");
            }
        } else if (raw instanceof S19PacketEntityStatus) {
            if (!this.entityStatusPacket.getValue() || player == null || world == null) return;
            S19PacketEntityStatus packet = (S19PacketEntityStatus) raw;
            if (packet.getOpCode() == 3 && packet.getEntity(world) == player) {
                this.reject(event, "entity status packet");
            }
        } else if (raw instanceof S13PacketDestroyEntities) {
            if (!this.destroyEntitiesPacket.getValue() || player == null) return;
            int own = player.getEntityId();
            for (int id : ((S13PacketDestroyEntities) raw).getEntityIDs()) {
                if (id == own) {
                    this.reject(event, "destroy entities packet");
                    break;
                }
            }
        } else if (raw instanceof S03PacketTimeUpdate) {
            if (!this.timePacket.getValue()) return;
            if (isNotValid(((S03PacketTimeUpdate) raw).getTotalWorldTime(), Long.MAX_VALUE - 1L)) {
                this.reject(event, "time update packet");
            }
        }
    }
}
