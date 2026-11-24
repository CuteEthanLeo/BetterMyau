package myau.module.modules;

import myau.events.Render2DEvent;
import myau.event.EventTarget;
import myau.event.types.EventType;
import myau.events.BlockBBEvent;
import myau.events.JumpEvent;
import myau.events.LoadWorldEvent;
import myau.events.MoveEvent;
import myau.events.PacketEvent;
import myau.events.PlayerUpdateEvent;
import myau.events.Render3DEvent;
import myau.events.StepEvent;
import myau.events.StrafeEvent;
import myau.events.UpdateEvent;
import myau.module.Module;
import myau.module.mode.ModeList;
import myau.module.modules.flymodes.FlyMode;
import myau.module.modules.flymodes.FlyUtil;
import myau.module.modules.flymodes.aac.AAC1910;
import myau.module.modules.flymodes.aac.AAC305;
import myau.module.modules.flymodes.aac.AAC316;
import myau.module.modules.flymodes.aac.AAC3312;
import myau.module.modules.flymodes.aac.AAC3312Glide;
import myau.module.modules.flymodes.aac.AAC3313;
import myau.module.modules.flymodes.blocksmc.BlocksMC;
import myau.module.modules.flymodes.blocksmc.BlocksMC2;
import myau.module.modules.flymodes.hypixel.BoostHypixel;
import myau.module.modules.flymodes.hypixel.FreeHypixel;
import myau.module.modules.flymodes.hypixel.HypixelFlat;
import myau.module.modules.flymodes.hypixel.Hypixel;
import myau.module.modules.flymodes.ncp.NCP;
import myau.module.modules.flymodes.ncp.OldNCP;
import myau.module.modules.flymodes.other.Collide;
import myau.module.modules.flymodes.other.CubeCraft;
import myau.module.modules.flymodes.other.Flag;
import myau.module.modules.flymodes.other.HAC;
import myau.module.modules.flymodes.other.HawkEye;
import myau.module.modules.flymodes.other.Jetpack;
import myau.module.modules.flymodes.other.Jump;
import myau.module.modules.flymodes.other.KeepAlive;
import myau.module.modules.flymodes.other.MineSecure;
import myau.module.modules.flymodes.other.Minesucht;
import myau.module.modules.flymodes.other.NeruxVace;
import myau.module.modules.flymodes.other.PolarBoat;
import myau.module.modules.flymodes.other.Tatako;
import myau.module.modules.flymodes.other.WatchCat;
import myau.module.modules.flymodes.spartan.BugSpartan;
import myau.module.modules.flymodes.spartan.Spartan;
import myau.module.modules.flymodes.spartan.Spartan2;
import myau.module.modules.flymodes.vanilla.SmoothVanilla;
import myau.module.modules.flymodes.vanilla.Vanilla;
import myau.module.modules.flymodes.verus.Verus;
import myau.module.modules.flymodes.verus.VerusGlide;
import myau.module.modules.flymodes.vulcan.Vulcan;
import myau.module.modules.flymodes.vulcan.VulcanGhost;
import myau.module.modules.flymodes.vulcan.VulcanOld;
import myau.property.properties.BooleanProperty;
import myau.property.properties.FloatProperty;
import myau.property.properties.IntProperty;
import myau.property.properties.ModeProperty;
import myau.util.KeyBindUtil;
import myau.util.MoveUtil;
import myau.util.TimerUtil;
import net.minecraft.client.Minecraft;
import net.minecraft.util.AxisAlignedBB;

public class Fly extends Module {
    private static final Minecraft mc = Minecraft.getMinecraft();
    /** Modes whose logic lives in this class; they keep their index in front of the ported ones. */
    private static final String[] LEGACY = {"Myau"};
    // Must be declared before "mode": its names feed the selector.
    private final ModeList<Fly, FlyMode> modes = new ModeList<Fly, FlyMode>(
            new Vanilla(), new SmoothVanilla(),
            // NCP
            new NCP(), new OldNCP(),
            // AAC
            new AAC1910(), new AAC305(), new AAC316(), new AAC3312(), new AAC3312Glide(), new AAC3313(),
            // CubeCraft
            new CubeCraft(),
            // Hypixel
            new Hypixel(), new BoostHypixel(), new FreeHypixel(), new HypixelFlat(),
            // Other server specific flys
            new NeruxVace(), new Minesucht(), new BlocksMC(), new BlocksMC2(),
            // Spartan
            new Spartan(), new Spartan2(), new BugSpartan(),
            // Vulcan
            new Vulcan(), new VulcanOld(), new VulcanGhost(),
            // Verus
            new Verus(), new VerusGlide(),
            // Other anti-cheats
            new MineSecure(), new HawkEye(), new HAC(), new WatchCat(),
            // Other
            new Jetpack(), new KeepAlive(), new Collide(), new Jump(), new Flag(), new Tatako(), new PolarBoat()
    );
    private double verticalMotion = 0.0;
    public final ModeProperty mode = new ModeProperty("mode", 0, FlyUtil.concat(LEGACY, this.modes.names()));

    // Myau
    public final FloatProperty hSpeed = new FloatProperty("horizontal-speed", 1.0F, 0.0F, 100.0F, () -> this.mode.getValue() == 0);
    public final FloatProperty vSpeed = new FloatProperty("vertical-speed", 1.0F, 0.0F, 100.0F, () -> this.mode.getValue() == 0);

    // Vanilla
    public final FloatProperty vanillaSpeed = new FloatProperty(
            "vanilla-speed", 2.0F, 0.0F, 10.0F,
            () -> this.is("Vanilla") || this.is("KeepAlive") || this.is("MineSecure") || this.is("BugSpartan")
    );
    public final BooleanProperty vanillaKickBypass = new BooleanProperty(
            "vanilla-kick-bypass", false, () -> this.is("Vanilla") || this.is("SmoothVanilla")
    );
    public final FloatProperty ncpMotion = new FloatProperty("ncp-motion", 0.0F, 0.0F, 1.0F, () -> this.is("NCP"));

    // AAC
    public final FloatProperty aacSpeed = new FloatProperty("aac1910-speed", 0.3F, 0.0F, 1.0F, () -> this.is("AAC1.9.10"));
    public final BooleanProperty aacFast = new BooleanProperty("aac305-fast", true, () -> this.is("AAC3.0.5"));
    public final FloatProperty aacMotion = new FloatProperty("aac3312-motion", 10.0F, 0.1F, 10.0F, () -> this.is("AAC3.3.12"));
    public final FloatProperty aacMotion2 = new FloatProperty("aac3313-motion", 10.0F, 0.1F, 10.0F, () -> this.is("AAC3.3.13"));

    // Hypixel
    public final BooleanProperty hypixelBoost = new BooleanProperty("hypixel-boost", true, () -> this.is("Hypixel"));
    public final IntProperty hypixelBoostDelay = new IntProperty(
            "hypixel-boost-delay", 1200, 50, 2000, () -> this.is("Hypixel") && this.hypixelBoost.getValue()
    );
    public final FloatProperty hypixelBoostTimer = new FloatProperty(
            "hypixel-boost-timer", 1.0F, 0.1F, 5.0F, () -> this.is("Hypixel") && this.hypixelBoost.getValue()
    );

    // HypixelFlat (LiquidBounce Nextgen)
    public final FloatProperty hypixelFlatTimer = new FloatProperty("hypixelflat-timer", 1.0F, 0.1F, 1.0F, () -> this.is("HypixelFlat"));
    public final FloatProperty hypixelFlatSpeed = new FloatProperty("hypixelflat-speed", 1.66F, 0.8F, 2.0F, () -> this.is("HypixelFlat"));

    // Other
    public final IntProperty neruxVaceTicks = new IntProperty("neruxvace-ticks", 6, 2, 20, () -> this.is("NeruxVace"));

    // Verus
    public final BooleanProperty verusDamage = new BooleanProperty("verus-damage", false, () -> this.is("Verus"));
    public final BooleanProperty verusTimerSlow = new BooleanProperty("verus-timer-slow", true, () -> this.is("Verus"));
    public final IntProperty verusBoostTicks = new IntProperty("verus-boost-ticks", 20, 1, 30, () -> this.is("Verus"));
    public final FloatProperty verusBoostMotion = new FloatProperty("verus-boost-motion", 6.5F, 1.0F, 9.85F, () -> this.is("Verus"));
    public final FloatProperty verusYBoost = new FloatProperty("verus-y-boost", 0.42F, 0.0F, 10.0F, () -> this.is("Verus"));

    // BlocksMC
    public final BooleanProperty blocksmcStable = new BooleanProperty("blocksmc-stable", false, this::isBlocksMC);
    public final BooleanProperty blocksmcTimerSlowed = new BooleanProperty("blocksmc-timer-slowed", true, this::isBlocksMC);
    public final FloatProperty blocksmcBoostSpeed = new FloatProperty("blocksmc-boost-speed", 6.0F, 1.0F, 15.0F, this::isBlocksMC);
    public final FloatProperty blocksmcExtraSpeed = new FloatProperty("blocksmc-extra-speed", 1.0F, 0.0F, 2.0F, this::isBlocksMC);
    public final BooleanProperty blocksmcStopOnLanding = new BooleanProperty("blocksmc-stop-on-landing", true, this::isBlocksMC);
    public final BooleanProperty blocksmcStopOnNoMove = new BooleanProperty("blocksmc-stop-on-no-move", false, this::isBlocksMC);
    public final BooleanProperty blocksmcDebug = new BooleanProperty("blocksmc-debug", false, this::isBlocksMC);

    // Tatako
    public final FloatProperty tatakoHSpeed = new FloatProperty("tatako-horizontal-speed", 1.0F, 0.0F, 5.0F, () -> this.is("Tatako"));
    public final FloatProperty tatakoVSpeed = new FloatProperty("tatako-vertical-speed", 1.0F, 0.0F, 5.0F, () -> this.is("Tatako"));

    // PolarBoat
    public final FloatProperty polarBoatHSpeed = new FloatProperty("polar-boat-h-speed", 5.0F, 0.0F, 10.0F, () -> this.is("PolarBoat"));
    public final FloatProperty polarBoatVSpeed = new FloatProperty("polar-boat-v-speed", 3.0F, 0.0F, 10.0F, () -> this.is("PolarBoat"));

    // Visuals
    public final BooleanProperty mark = new BooleanProperty("mark", true, () -> this.mode.getValue() >= LEGACY.length);

    /** Height the Jump mode keeps its fake floor at. */
    public double jumpY = 0.0;
    private double startY = 0.0;
    private final TimerUtil groundTimer = new TimerUtil();
    private boolean wasFlying = false;
    /** Ported mode that currently receives events; null for the Myau mode and while a mode is being enabled. */
    private volatile FlyMode active;
    /** Selector index of the mode that was started, -1 while nothing is running. */
    private int runningIndex = -1;

    public Fly() {
        super("Fly", false);
        this.modes.bind(this);
    }

    private boolean is(String modeName) {
        return this.mode.getModeString().equals(modeName);
    }

    private boolean isBlocksMC() {
        return this.is("BlocksMC") || this.is("BlocksMC2");
    }

    /** Selected ported mode, null when a legacy (Myau) mode is selected. */
    private FlyMode cur() {
        int index = this.mode.getValue() - LEGACY.length;
        return index < 0 ? null : this.modes.get(index);
    }

    /** Y position of the player when the module (or the current mode) was started. */
    public double getStartY() {
        return this.startY;
    }

    // ── Myau mode (unchanged) ──

    @EventTarget
    public void onStrafe(StrafeEvent event) {
        if (this.isEnabled() && this.mode.getValue() == 0) {
            if (mc.thePlayer.posY % 1.0 != 0.0) {
                mc.thePlayer.motionY = this.verticalMotion;
            }
            MoveUtil.setSpeed(0.0);
            event.setFriction((float) MoveUtil.getBaseMoveSpeed() * this.hSpeed.getValue());
        }
    }

    @EventTarget
    public void onUpdate(UpdateEvent event) {
        if (!this.isEnabled()) {
            return;
        }
        if (this.mode.getValue() == 0) {
            if (event.getType() == EventType.PRE) {
                this.verticalMotion = 0.0;
                if (mc.currentScreen == null) {
                    if (KeyBindUtil.isKeyDown(mc.gameSettings.keyBindJump.getKeyCode())) {
                        this.verticalMotion = this.verticalMotion + this.vSpeed.getValue().doubleValue() * 0.42F;
                    }
                    if (KeyBindUtil.isKeyDown(mc.gameSettings.keyBindSneak.getKeyCode())) {
                        this.verticalMotion = this.verticalMotion - this.vSpeed.getValue().doubleValue() * 0.42F;
                    }
                    KeyBindUtil.setKeyBindState(mc.gameSettings.keyBindSneak.getKeyCode(), false);
                }
            }
            return;
        }
        FlyMode current = this.active;
        if (current == null) {
            return;
        }
        if (event.getType() == EventType.PRE) {
            current.onUpdate(event);
        } else {
            current.onPostMotion();
        }
    }

    // ── ported modes ──

    @EventTarget
    public void onPlayerUpdate(PlayerUpdateEvent event) {
        if (!this.isEnabled()) {
            return;
        }
        FlyMode current = this.active;
        if (current != null) {
            current.onMotion();
        }
    }

    @EventTarget
    public void onMove(MoveEvent event) {
        if (!this.isEnabled()) {
            return;
        }
        FlyMode current = this.active;
        if (current != null) {
            current.onMove(event);
        }
    }

    @EventTarget
    public void onJump(JumpEvent event) {
        if (!this.isEnabled()) {
            return;
        }
        FlyMode current = this.active;
        if (current != null) {
            current.onJump(event);
        }
    }

    @EventTarget
    public void onStep(StepEvent event) {
        if (!this.isEnabled()) {
            return;
        }
        FlyMode current = this.active;
        if (current != null) {
            current.onStep(event);
        }
    }

    @EventTarget
    public void onBlockBB(BlockBBEvent event) {
        if (!this.isEnabled() || mc.thePlayer == null) {
            return;
        }
        FlyMode current = this.active;
        if (current != null) {
            current.onBB(event);
        }
    }

    @EventTarget
    public void onPacket(PacketEvent event) {
        if (!this.isEnabled() || mc.thePlayer == null) {
            return;
        }
        FlyMode current = this.active;
        if (current != null) {
            current.onPacket(event);
        }
    }

    @EventTarget
    public void onRender3D(Render3DEvent event) {
        if (!this.isEnabled() || mc.thePlayer == null) {
            return;
        }
        FlyMode current = this.active;
        if (current == null) {
            return;
        }
        if (!this.mark.getValue() || current.name.equals("Vanilla") || current.name.equals("SmoothVanilla")) {
            return;
        }
        double y = this.startY + 2.0 + (current.name.equals("BoostHypixel") ? 0.42 : 0.0);
        if (mc.thePlayer.getEntityBoundingBox().maxY < y) {
            FlyUtil.drawPlatform(y, 0, 255, 0, 90, 1.0);
        } else {
            FlyUtil.drawPlatform(y, 255, 0, 0, 90, 1.0);
        }
        current.onRender3D(event);
    }

    @EventTarget
    public void onLoadWorld(LoadWorldEvent event) {
        if (!this.isEnabled()) {
            return;
        }
        FlyMode current = this.active;
        if (current != null) {
            current.onWorld();
        }
    }

    // ── lifecycle ──

    @Override
    public void onEnabled() {
        if (this.runningIndex != -1) {
            // enabled again without a disable in between (config applied while running)
            this.stopMode();
        }
        this.startMode();
    }

    @Override
    public void onDisabled() {
        this.stopMode();
    }

    @Override
    public void verifyValue(String name) {
        if ("mode".equalsIgnoreCase(name)
                && this.isEnabled()
                && this.runningIndex != -1
                && this.runningIndex != this.mode.getValue()) {
            this.stopMode();
            this.startMode();
        }
    }

    private void startMode() {
        this.runningIndex = this.mode.getValue();
        FlyMode next = this.cur();
        if (next == null) {
            return;
        }
        if (mc.thePlayer != null) {
            this.startY = mc.thePlayer.posY;
            this.jumpY = mc.thePlayer.posY;
            this.wasFlying = mc.thePlayer.capabilities.isFlying;
            // Events reach the mode only after its onEnable (packets and jumps made there stay untouched).
            next.onEnable();
        }
        this.active = next;
    }

    private void stopMode() {
        int index = this.runningIndex;
        this.runningIndex = -1;
        if (index == -1) {
            return;
        }
        if (index < LEGACY.length) {
            // Myau
            if (mc.thePlayer != null) {
                mc.thePlayer.motionY = 0.0;
                MoveUtil.setSpeed(0.0);
            }
            KeyBindUtil.updateKeyState(mc.gameSettings.keyBindSneak.getKeyCode());
            return;
        }
        FlyMode old = this.active;
        try {
            if (mc.thePlayer != null && old != null) {
                String name = old.name;
                if (!name.startsWith("AAC")
                        && !name.equals("Hypixel")
                        && !name.equals("VerusGlide")
                        && !name.equals("SmoothVanilla")
                        && !name.equals("Vanilla")
                        && !name.equals("Collide")
                        && !name.equals("Jump")) {
                    if (name.equals("CubeCraft")) {
                        MoveUtil.stopXZ();
                    } else {
                        MoveUtil.stop();
                    }
                }
                mc.thePlayer.capabilities.isFlying = this.wasFlying;
            }
            MoveUtil.setTimer(1.0F);
            if (mc.thePlayer != null) {
                FlyUtil.resetSpeedInAir();
            }
            // The mode still receives events while it disables (packets it sends here pass its own onPacket).
            if (old != null) {
                old.onDisable();
            }
        } finally {
            this.active = null;
        }
    }

    // ── shared by the vanilla modes ──

    public void handleVanillaKickBypass() {
        if (!this.vanillaKickBypass.getValue() || !this.groundTimer.hasTimeElapsed(1000L)) {
            return;
        }
        double ground = this.calculateGround() + 0.5;
        double posY = mc.thePlayer.posY;
        while (posY > ground) {
            FlyUtil.sendPosition(mc.thePlayer.posX, posY, mc.thePlayer.posZ, true);
            if (posY - 8.0 < ground) {
                break; // Prevent next step
            }
            posY -= 8.0;
        }
        FlyUtil.sendPosition(mc.thePlayer.posX, ground, mc.thePlayer.posZ, true);
        posY = ground;
        while (posY < mc.thePlayer.posY) {
            FlyUtil.sendPosition(mc.thePlayer.posX, posY, mc.thePlayer.posZ, true);
            if (posY + 8.0 > mc.thePlayer.posY) {
                break; // Prevent next step
            }
            posY += 8.0;
        }
        FlyUtil.sendPosition(mc.thePlayer.posX, mc.thePlayer.posY, mc.thePlayer.posZ, true);
        this.groundTimer.reset();
    }

    private double calculateGround() {
        AxisAlignedBB playerBoundingBox = mc.thePlayer.getEntityBoundingBox();
        double blockHeight = 0.05;
        double ground = mc.thePlayer.posY;
        while (ground > 0.0) {
            AxisAlignedBB customBox = AxisAlignedBB.fromBounds(
                    playerBoundingBox.maxX,
                    ground + blockHeight,
                    playerBoundingBox.maxZ,
                    playerBoundingBox.minX,
                    ground,
                    playerBoundingBox.minZ
            );
            if (mc.theWorld.checkBlockCollision(customBox)) {
                if (blockHeight <= 0.05) {
                    return ground + blockHeight;
                }
                ground += blockHeight;
                blockHeight = 0.05;
            }
            ground -= blockHeight;
        }
        return 0.0;
    }

    @Override
    public String[] getSuffix() {
        return new String[]{this.mode.getModeString()};
    }

    /** Height when Fly was switched on, for the island; NaN while off. */
    private double islandStartY = Double.NaN;

    // ---------------------------------------------------------------- dynamic island


    /** The fly mode and how high above the take-off point you are. */
    @EventTarget
    public void onIsland(Render2DEvent event) {
        if (!this.isEnabled() || mc.thePlayer == null) {
            this.islandStartY = Double.NaN;
            return;
        }
        if (Double.isNaN(this.islandStartY)) this.islandStartY = mc.thePlayer.posY;
        myau.ui.glass.Island.live("fly", myau.ui.glass.Island.P_INFO,
                "Fly §f" + this.mode.getModeString() + " §7" + String.format("%+.1f", mc.thePlayer.posY - this.islandStartY),
                myau.ui.GlassTheme.accent(), -1);
    }
}
