package myau.module.modules;

import com.google.common.base.CaseFormat;
import myau.Myau;
import myau.enums.BlinkModules;
import myau.event.EventTarget;
import myau.event.types.EventType;
import myau.event.types.Priority;
import myau.events.JumpEvent;
import myau.events.MoveEvent;
import myau.events.PacketEvent;
import myau.events.TickEvent;
import myau.events.UpdateEvent;
import myau.mixin.IAccessorC03PacketPlayer;
import myau.mixin.IAccessorMinecraft;
import myau.module.Module;
import myau.module.mode.ModeList;
import myau.module.modules.nofallmodes.NoFallMode;
import myau.module.modules.nofallmodes.aac.AAC;
import myau.module.modules.nofallmodes.aac.AAC3311;
import myau.module.modules.nofallmodes.aac.AAC3315;
import myau.module.modules.nofallmodes.aac.LAAC;
import myau.module.modules.nofallmodes.other.Cancel;
import myau.module.modules.nofallmodes.other.CubeCraft;
import myau.module.modules.nofallmodes.other.Hypixel;
import myau.module.modules.nofallmodes.other.HypixelTimer;
import myau.module.modules.nofallmodes.other.MLG;
import myau.module.modules.nofallmodes.other.Packet2;
import myau.module.modules.nofallmodes.other.Spartan;
import myau.module.modules.nofallmodes.other.SpoofGround;
import myau.module.modules.nofallmodes.other.VulcanFast288;
import myau.util.*;
import myau.property.properties.FloatProperty;
import myau.property.properties.BooleanProperty;
import myau.property.properties.ModeProperty;
import myau.property.properties.IntProperty;
import net.minecraft.block.BlockLiquid;
import net.minecraft.client.Minecraft;
import net.minecraft.network.Packet;
import net.minecraft.network.play.client.C03PacketPlayer;
import net.minecraft.network.play.server.S08PacketPlayerPosLook;
import net.minecraft.util.AxisAlignedBB;
import net.minecraft.util.BlockPos;

public class NoFall extends Module {
    private static final Minecraft mc = Minecraft.getMinecraft();
    /** The original modes (indices 0-3); their logic lives in this class. */
    private static final String[] LEGACY = {"PACKET", "BLINK", "NO_GROUND", "SPOOF"};
    private final TimerUtil packetDelayTimer = new TimerUtil();
    private final TimerUtil scoreboardResetTimer = new TimerUtil();
    private boolean slowFalling = false;
    private boolean lastOnGround = false;
    /** Modes ported from xw, selected by mode index {@code LEGACY.length + i}. Must be declared before {@link #mode}. */
    private final ModeList<NoFall, NoFallMode> modes = new ModeList<NoFall, NoFallMode>(
            new SpoofGround(), new Packet2(), new Cancel(),
            new AAC(), new LAAC(), new AAC3311(), new AAC3315(),
            new Hypixel(), new HypixelTimer(),
            new VulcanFast288(),
            new Spartan(), new CubeCraft(),
            new MLG()
    );
    public final ModeProperty mode = new ModeProperty("mode", 0, concat(LEGACY, this.modes.names()));
    public final FloatProperty distance = new FloatProperty("distance", 3.0F, 0.0F, 20.0F, this::isLegacyMode);
    public final IntProperty delay = new IntProperty("delay", 0, 0, 10000, this::isLegacyMode);
    // MLG (LeaderClient)
    public final FloatProperty mlgDistance = new FloatProperty("mlg-distance", 3.0F, 0.0F, 20.0F, () -> "MLG".equals(this.mode.getModeString()));
    public final BooleanProperty mlgRotation = new BooleanProperty("mlg-rotation", false, () -> "MLG".equals(this.mode.getModeString()));
    /** The xw mode that is currently running; null while an original mode is selected or the module is off. */
    private NoFallMode active;
    /** onGround of the last movement packet that left this handler uncancelled (xw: MovementUtils.serverOnGround). */
    private boolean serverOnGround = false;

    private static String[] concat(String[] first, String[] second) {
        String[] result = new String[first.length + second.length];
        System.arraycopy(first, 0, result, 0, first.length);
        System.arraycopy(second, 0, result, first.length, second.length);
        return result;
    }

    private NoFallMode cur() {
        int index = this.mode.getValue() - LEGACY.length;
        return index < 0 ? null : this.modes.get(index);
    }

    private boolean isLegacyMode() {
        return this.mode.getValue() < LEGACY.length;
    }

    public boolean isServerOnGround() {
        return this.serverOnGround;
    }

    private boolean canTrigger() {
        return this.scoreboardResetTimer.hasTimeElapsed(3000) && this.packetDelayTimer.hasTimeElapsed(this.delay.getValue().longValue());
    }

    /** xw BlockUtils.collideBlock: every block column under the player's box at the given height is a liquid. */
    private boolean collideLiquid(double minY) {
        AxisAlignedBB box = mc.thePlayer.getEntityBoundingBox();
        int y = (int) minY;
        for (int x = (int) box.minX; x < (int) box.maxX + 1; x++) {
            for (int z = (int) box.minZ; z < (int) box.maxZ + 1; z++) {
                if (!(mc.theWorld.getBlockState(new BlockPos(x, y, z)).getBlock() instanceof BlockLiquid)) {
                    return false;
                }
            }
        }
        return true;
    }

    /** The xw modes do nothing in onUpdate / onMove while the player is in or right above a liquid. */
    private boolean isOverLiquid() {
        double minY = mc.thePlayer.getEntityBoundingBox().minY;
        return this.collideLiquid(minY) || this.collideLiquid(minY - 0.01);
    }

    public NoFall() {
        super("NoFall", false);
        this.modes.bind(this);
    }

    @EventTarget(Priority.HIGH)
    public void onPacket(PacketEvent event) {
        if (event.getType() == EventType.RECEIVE && event.getPacket() instanceof S08PacketPlayerPosLook) {
            this.resetLegacy();
        } else if (this.isEnabled() && event.getType() == EventType.SEND && !event.isCancelled()) {
            if (event.getPacket() instanceof C03PacketPlayer) {
                C03PacketPlayer packet = (C03PacketPlayer) event.getPacket();
                switch (this.mode.getValue()) {
                    case 0:
                        if (this.slowFalling) {
                            this.slowFalling = false;
                            ((IAccessorMinecraft) mc).getTimer().timerSpeed = 1.0F;
                        } else if (!packet.isOnGround()) {
                            AxisAlignedBB aabb = mc.thePlayer.getEntityBoundingBox().expand(2.0, 0.0, 2.0);
                            if (PlayerUtil.canFly(this.distance.getValue())
                                    && !PlayerUtil.checkInWater(aabb)
                                    && this.canTrigger()) {
                                this.packetDelayTimer.reset();
                                this.slowFalling = true;
                                ((IAccessorMinecraft) mc).getTimer().timerSpeed = 0.5F;
                            }
                        }
                        break;
                    case 1:
                        boolean allowed = !mc.thePlayer.isOnLadder() && !mc.thePlayer.capabilities.allowFlying && mc.thePlayer.hurtTime == 0;
                        if (Myau.blinkManager.getBlinkingModule() != BlinkModules.NO_FALL) {
                            if (this.lastOnGround
                                    && !packet.isOnGround()
                                    && allowed
                                    && PlayerUtil.canFly(this.distance.getValue().intValue())
                                    && mc.thePlayer.motionY < 0.0) {
                                Myau.blinkManager.setBlinkState(false, Myau.blinkManager.getBlinkingModule());
                                Myau.blinkManager.setBlinkState(true, BlinkModules.NO_FALL);
                            }
                        } else if (!allowed) {
                            Myau.blinkManager.setBlinkState(false, BlinkModules.NO_FALL);
                            ChatUtil.sendFormatted(String.format("%s%s: &cFailed player check!&r", Myau.clientName, this.getName()));
                        } else if (PlayerUtil.checkInWater(mc.thePlayer.getEntityBoundingBox().expand(2.0, 0.0, 2.0))) {
                            Myau.blinkManager.setBlinkState(false, BlinkModules.NO_FALL);
                            ChatUtil.sendFormatted(String.format("%s%s: &cFailed void check!&r", Myau.clientName, this.getName()));
                        } else if (packet.isOnGround()) {
                            for (Packet<?> blinkedPacket : Myau.blinkManager.blinkedPackets) {
                                if (blinkedPacket instanceof C03PacketPlayer) {
                                    ((IAccessorC03PacketPlayer) blinkedPacket).setOnGround(true);
                                }
                            }
                            Myau.blinkManager.setBlinkState(false, BlinkModules.NO_FALL);
                            this.packetDelayTimer.reset();
                        }
                        this.lastOnGround = packet.isOnGround() && allowed && this.canTrigger();
                        break;
                    case 2:
                        ((IAccessorC03PacketPlayer) packet).setOnGround(false);
                        break;
                    case 3:
                        if (!packet.isOnGround()) {
                            AxisAlignedBB aabb = mc.thePlayer.getEntityBoundingBox().expand(2.0, 0.0, 2.0);
                            if (PlayerUtil.canFly(this.distance.getValue())
                                    && !PlayerUtil.checkInWater(aabb)
                                    && this.canTrigger()) {
                                this.packetDelayTimer.reset();
                                ((IAccessorC03PacketPlayer) packet).setOnGround(true);
                                mc.thePlayer.fallDistance = 0.0F;
                            }
                        }
                }
            }
        }
        // Modes ported from xw (both directions, like xw's PacketEvent).
        if (this.isEnabled() && this.active != null && mc.thePlayer != null) {
            this.active.onPacket(event);
        }
        if (event.getType() == EventType.SEND && !event.isCancelled() && event.getPacket() instanceof C03PacketPlayer) {
            this.serverOnGround = ((C03PacketPlayer) event.getPacket()).isOnGround();
        }
    }

    @EventTarget(Priority.HIGHEST)
    public void onTick(TickEvent event) {
        if (this.isEnabled() && event.getType() == EventType.PRE) {
            if (ServerUtil.hasPlayerCountInfo()) {
                this.scoreboardResetTimer.reset();
            }
            if (this.mode.getValue() == 0 && this.slowFalling) {
                PacketUtil.sendPacketNoEvent(new C03PacketPlayer(true));
                mc.thePlayer.fallDistance = 0.0F;
            }
            if (this.active != null) {
                this.active.onTick();
            }
        }
    }

    @EventTarget
    public void onUpdate(UpdateEvent event) {
        if (this.isEnabled() && this.active != null) {
            this.active.onUpdateAlways(event);
        }
        if (this.isEnabled() && event.getType() == EventType.PRE && this.active != null) {
            if (this.isOverLiquid()) {
                return;
            }
            this.active.onUpdate(event);
        }
    }

    @EventTarget
    public void onMove(MoveEvent event) {
        if (this.isEnabled() && this.active != null) {
            if (this.isOverLiquid()) {
                return;
            }
            this.active.onMove(event);
        }
    }

    /** Like xw (handler(always = true)): LAAC tracks jumps even while the module is off. */
    @EventTarget
    public void onJump(JumpEvent event) {
        NoFallMode current = this.isEnabled() ? this.active : this.cur();
        if (current != null) {
            current.onJump(event);
        }
    }

    /** State of the original modes: also cleared whenever the server teleports the player. */
    private void resetLegacy() {
        this.lastOnGround = false;
        Myau.blinkManager.setBlinkState(false, BlinkModules.NO_FALL);
        if (this.slowFalling) {
            this.slowFalling = false;
            ((IAccessorMinecraft) mc).getTimer().timerSpeed = 1.0F;
        }
    }

    @Override
    public void onEnabled() {
        this.active = this.cur();
        if (this.active != null) {
            this.active.onEnable();
        }
    }

    @Override
    public void onDisabled() {
        this.resetLegacy();
        NoFallMode previous = this.active;
        this.active = null;
        if (previous != null) {
            previous.onDisable();
        }
    }

    /** Any changed value resets the original modes; a changed mode also swaps the running xw mode. */
    @Override
    public void verifyValue(String mode) {
        if (this.isEnabled()) {
            this.resetLegacy();
            NoFallMode next = this.cur();
            if (this.active != next) {
                NoFallMode previous = this.active;
                this.active = next;
                if (previous != null) {
                    previous.onDisable();
                }
                if (next != null) {
                    next.onEnable();
                }
            }
        }
    }

    @Override
    public String[] getSuffix() {
        String mode = this.mode.getModeString();
        if (!this.isLegacyMode()) {
            return new String[]{mode};
        }
        return new String[]{CaseFormat.UPPER_UNDERSCORE.to(CaseFormat.UPPER_CAMEL, mode)};
    }
}
