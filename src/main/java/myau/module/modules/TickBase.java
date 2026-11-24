package myau.module.modules;

import myau.events.Render2DEvent;
import myau.Myau;
import myau.enums.DelayModules;
import myau.event.EventTarget;
import myau.event.types.EventType;
import myau.events.LoadWorldEvent;
import myau.events.MoveEvent;
import myau.events.PacketEvent;
import myau.events.PlayerTickEvent;
import myau.events.Render3DEvent;
import myau.events.TickEvent;
import myau.management.RotationState;
import myau.mixin.IAccessorRenderManager;
import myau.module.Module;
import myau.property.properties.BooleanProperty;
import myau.property.properties.ColorProperty;
import myau.property.properties.FloatProperty;
import myau.property.properties.IntProperty;
import myau.property.properties.ModeProperty;
import myau.property.properties.PercentProperty;
import myau.util.TargetUtil;
import myau.util.sim.SimulatedPlayer;
import net.minecraft.client.Minecraft;
import net.minecraft.client.entity.EntityPlayerSP;
import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityLivingBase;
import net.minecraft.network.Packet;
import net.minecraft.network.play.server.S08PacketPlayerPosLook;
import net.minecraft.util.Vec3;
import org.lwjgl.opengl.GL11;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/**
 * Shifts player ticks in time to close the distance to a target at once: either freezes for a
 * few ticks and then runs them all in one game tick ("Past"), or runs them ahead and freezes
 * afterwards ("Future").
 */
public class TickBase extends Module {
    private static final Minecraft mc = Minecraft.getMinecraft();
    private static final int IDLE = 0;
    private static final int PAST_WAIT = 1;
    private static final int FUTURE_WAIT = 2;

    public final ModeProperty mode = new ModeProperty("mode", 0, new String[]{"Past", "Future"});
    public final BooleanProperty onlyOnKillAura = new BooleanProperty("only-on-kill-aura", true);
    public final PercentProperty chance = new PercentProperty("chance", 100);
    public final IntProperty balanceMaxValue = new IntProperty("balance-max-value", 100, 1, 1000);
    public final FloatProperty balanceRecoveryIncrement = new FloatProperty("balance-recovery-increment", 0.1F, 0.01F, 10.0F);
    public final IntProperty maxTicksAtATime = new IntProperty("max-ticks-at-a-time", 20, 1, 100);
    public final FloatProperty minRangeToAttack = new FloatProperty("min-range-to-attack", 3.0F, 0.0F, 10.0F);
    public final FloatProperty maxRangeToAttack = new FloatProperty("max-range-to-attack", 5.0F, 0.0F, 10.0F);
    public final BooleanProperty forceGround = new BooleanProperty("force-ground", false);
    public final IntProperty pauseAfterTick = new IntProperty("pause-after-tick", 0, 0, 100);
    public final BooleanProperty pauseOnFlag = new BooleanProperty("pause-on-flag", true);
    public final BooleanProperty line = new BooleanProperty("line", true);
    public final ColorProperty lineColor = new ColorProperty("line-color", 0x00FF00, () -> this.line.getValue());

    private final Random random = new Random();
    private final List<TickData> tickBuffer = new ArrayList<>();
    private int ticksToSkip = 0;
    private volatile float tickBalance = 0.0F;
    private boolean reachedTheLimit = false;
    private boolean duringTickModification = false;
    private boolean modificationFlag = false;
    /** True while the extra player ticks are being run, so they are never skipped by ourselves. */
    private boolean runningExtraTicks = false;
    private int phase = IDLE;
    private int waitRemaining = 0;
    private int pendingTicks = 0;

    public TickBase() {
        super("TickBase", false);
    }

    public boolean isDuringTickModification() {
        return this.duringTickModification;
    }

    @Override
    public void onEnabled() {
        this.reset();
    }

    @Override
    public void onDisabled() {
        this.reset();
    }

    @EventTarget
    public void onLoadWorld(LoadWorldEvent event) {
        this.reset();
        this.tickBalance = 0.0F;
        this.reachedTheLimit = false;
    }

    private void reset() {
        this.phase = IDLE;
        this.waitRemaining = 0;
        this.pendingTicks = 0;
        this.ticksToSkip = 0;
        this.duringTickModification = false;
        this.modificationFlag = false;
        this.runningExtraTicks = false;
        this.tickBuffer.clear();
        this.releaseInbound();
    }

    private boolean isBlocked(EntityPlayerSP player) {
        if (player.ridingEntity != null) {
            return true;
        }
        Blink blink = (Blink) Myau.moduleManager.modules.get(Blink.class);
        return blink != null && blink.isEnabled();
    }

    @EventTarget
    public void onPlayerTick(PlayerTickEvent event) {
        if (!this.isEnabled() || this.runningExtraTicks) {
            return;
        }
        EntityPlayerSP player = mc.thePlayer;
        if (player == null || this.isBlocked(player)) {
            return;
        }
        if (this.ticksToSkip > 0) {
            this.ticksToSkip--;
            event.setCancelled(true);
        }
    }

    @EventTarget
    public void onTick(TickEvent event) {
        if (!this.isEnabled()) {
            return;
        }
        if (event.getType() == EventType.POST) {
            if (this.modificationFlag) {
                this.modificationFlag = false;
                this.duringTickModification = false;
                this.releaseInbound();
            }
            return;
        }
        EntityPlayerSP player = mc.thePlayer;
        if (player == null) {
            return;
        }

        // A modification in progress (the part after waitTicks in xw)
        if (this.phase != IDLE) {
            if (--this.waitRemaining <= 0) {
                if (this.phase == PAST_WAIT) {
                    this.runExtraTicks(this.pendingTicks);
                }
                this.phase = IDLE;
                this.modificationFlag = true;
            }
            return;
        }

        if (this.isBlocked(player)) {
            return;
        }

        if (this.duringTickModification || this.tickBuffer.isEmpty()) {
            return;
        }

        EntityLivingBase nearbyEnemy = this.getNearestEntityInRange();
        if (nearbyEnemy == null) {
            return;
        }
        Vec3 enemyPos = nearbyEnemy.getPositionVector();
        double currentDistance = player.getPositionVector().distanceTo(enemyPos);
        float minRange = Math.min(this.minRangeToAttack.getValue(), this.maxRangeToAttack.getValue());
        float maxRange = Math.max(this.minRangeToAttack.getValue(), this.maxRangeToAttack.getValue());

        int firstPossible = -1;
        int firstCritical = -1;
        for (int index = 0; index < this.tickBuffer.size(); index++) {
            TickData tick = this.tickBuffer.get(index);
            double tickDistance = tick.position.distanceTo(enemyPos);
            if (tickDistance < currentDistance && tickDistance >= minRange && tickDistance <= maxRange
                    && !tick.isCollidedHorizontally && (!this.forceGround.getValue() || tick.onGround)) {
                if (firstPossible == -1) {
                    firstPossible = index;
                }
                if (tick.fallDistance > 0.0F) {
                    firstCritical = index;
                    break;
                }
            }
        }

        int bestTick = firstCritical != -1 ? firstCritical : firstPossible;
        if (bestTick <= 0) {
            return;
        }

        if (this.random.nextInt(100) > this.chance.getValue() || this.onlyOnKillAura.getValue() && !this.hasKillAuraTarget()) {
            this.ticksToSkip = 0;
            return;
        }

        this.duringTickModification = true;
        if (Myau.delayManager.getDelayModule() == DelayModules.NONE) {
            Myau.delayManager.setDelayState(true, DelayModules.TICK_BASE);
        }

        int skipTicks = Math.min(bestTick + this.pauseAfterTick.getValue(), this.maxTicksAtATime.getValue() + this.pauseAfterTick.getValue());
        this.pendingTicks = skipTicks;

        if (this.mode.getValue() == 0) {
            this.ticksToSkip = skipTicks;
            this.phase = PAST_WAIT;
        } else {
            this.runExtraTicks(skipTicks);
            this.ticksToSkip = skipTicks;
            this.phase = FUTURE_WAIT;
        }
        // xw resumes its waitTicks(n) n + 1 game ticks after the tick that started it.
        this.waitRemaining = skipTicks + 1;
    }

    private void runExtraTicks(int ticks) {
        this.runningExtraTicks = true;
        try {
            for (int i = 0; i < ticks; i++) {
                EntityPlayerSP player = mc.thePlayer;
                if (player == null || mc.theWorld == null || !this.isEnabled()) {
                    break;
                }
                player.onUpdate();
                this.tickBalance -= 1.0F;
            }
        } finally {
            this.runningExtraTicks = false;
        }
    }

    private boolean hasKillAuraTarget() {
        KillAura killAura = (KillAura) Myau.moduleManager.modules.get(KillAura.class);
        return killAura != null && killAura.isEnabled() && killAura.getTarget() != null;
    }

    @EventTarget
    public void onMove(MoveEvent event) {
        if (!this.isEnabled()) {
            return;
        }
        EntityPlayerSP player = mc.thePlayer;
        if (player == null || this.isBlocked(player)) {
            return;
        }

        this.tickBuffer.clear();

        SimulatedPlayer simulatedPlayer = SimulatedPlayer.fromClientPlayer(player.movementInput);
        simulatedPlayer.rotationYaw = RotationState.isActived() ? RotationState.getSmoothedYaw() : player.rotationYaw;

        if (this.tickBalance <= 0.0F) {
            this.reachedTheLimit = true;
        }
        if (this.tickBalance > (float) (this.balanceMaxValue.getValue() / 2)) {
            this.reachedTheLimit = false;
        }
        if (this.tickBalance <= (float) this.balanceMaxValue.getValue()) {
            this.tickBalance += this.balanceRecoveryIncrement.getValue();
        }

        if (this.reachedTheLimit) {
            return;
        }

        int ticks = Math.min((int) this.tickBalance, this.maxTicksAtATime.getValue() * (this.mode.getValue() == 0 ? 2 : 1));
        for (int i = 0; i < ticks; i++) {
            simulatedPlayer.tick();
            this.tickBuffer.add(new TickData(
                    simulatedPlayer.getPos(),
                    simulatedPlayer.fallDistance,
                    simulatedPlayer.onGround,
                    simulatedPlayer.isCollidedHorizontally
            ));
        }
    }

    @EventTarget
    public void onPacket(PacketEvent event) {
        if (!this.isEnabled()) {
            return;
        }
        if (event.getType() == EventType.RECEIVE && event.getPacket() instanceof S08PacketPlayerPosLook && this.pauseOnFlag.getValue()) {
            this.tickBalance = 0.0F;
        }
    }

    /**
     * Lets the inbound packets held during the modification through. Held packets never reach
     * PacketEvent, so a teleport among them is looked for here.
     */
    private void releaseInbound() {
        if (Myau.delayManager == null || Myau.delayManager.getDelayModule() != DelayModules.TICK_BASE) {
            return;
        }
        if (this.pauseOnFlag.getValue()) {
            for (Packet<?> packet : Myau.delayManager.delayedPacket) {
                if (packet instanceof S08PacketPlayerPosLook) {
                    this.tickBalance = 0.0F;
                    break;
                }
            }
        }
        try {
            Myau.delayManager.setDelayState(false, DelayModules.TICK_BASE);
        } catch (Exception e) {
            Myau.delayManager.delayedPacket.clear();
        }
    }

    @EventTarget
    public void onRender3D(Render3DEvent event) {
        if (!this.isEnabled() || !this.line.getValue() || this.tickBuffer.isEmpty()) {
            return;
        }
        int color = this.lineColor.getValue();
        double renderPosX = ((IAccessorRenderManager) mc.getRenderManager()).getRenderPosX();
        double renderPosY = ((IAccessorRenderManager) mc.getRenderManager()).getRenderPosY();
        double renderPosZ = ((IAccessorRenderManager) mc.getRenderManager()).getRenderPosZ();

        GL11.glPushMatrix();
        GL11.glDisable(GL11.GL_TEXTURE_2D);
        GL11.glBlendFunc(GL11.GL_SRC_ALPHA, GL11.GL_ONE_MINUS_SRC_ALPHA);
        GL11.glEnable(GL11.GL_LINE_SMOOTH);
        GL11.glEnable(GL11.GL_BLEND);
        GL11.glDisable(GL11.GL_DEPTH_TEST);
        mc.entityRenderer.disableLightmap();
        GL11.glColor4f((color >> 16 & 0xFF) / 255.0F, (color >> 8 & 0xFF) / 255.0F, (color & 0xFF) / 255.0F, 1.0F);
        GL11.glBegin(GL11.GL_LINE_STRIP);
        for (TickData tick : this.tickBuffer) {
            GL11.glVertex3d(tick.position.xCoord - renderPosX, tick.position.yCoord - renderPosY, tick.position.zCoord - renderPosZ);
        }
        GL11.glEnd();
        GL11.glColor4f(1.0F, 1.0F, 1.0F, 1.0F);
        GL11.glEnable(GL11.GL_DEPTH_TEST);
        GL11.glDisable(GL11.GL_LINE_SMOOTH);
        GL11.glDisable(GL11.GL_BLEND);
        GL11.glEnable(GL11.GL_TEXTURE_2D);
        GL11.glPopMatrix();
    }

    private EntityLivingBase getNearestEntityInRange() {
        if (mc.thePlayer == null || mc.theWorld == null) {
            return null;
        }
        EntityLivingBase nearest = null;
        float best = Float.MAX_VALUE;
        for (Entity entity : mc.theWorld.loadedEntityList) {
            if (!TargetUtil.isSelected(entity)) {
                continue;
            }
            float distance = mc.thePlayer.getDistanceToEntity(entity);
            if (distance < best) {
                best = distance;
                nearest = (EntityLivingBase) entity;
            }
        }
        return nearest;
    }

    @Override
    public void verifyValue(String name) {
        switch (name) {
            case "min-range-to-attack":
                if (this.minRangeToAttack.getValue() > this.maxRangeToAttack.getValue()) {
                    this.maxRangeToAttack.setValue(this.minRangeToAttack.getValue());
                }
                break;
            case "max-range-to-attack":
                if (this.minRangeToAttack.getValue() > this.maxRangeToAttack.getValue()) {
                    this.minRangeToAttack.setValue(this.maxRangeToAttack.getValue());
                }
                break;
        }
    }

    @Override
    public String[] getSuffix() {
        return new String[]{this.mode.getModeString()};
    }

    private static final class TickData {
        final Vec3 position;
        final float fallDistance;
        final boolean onGround;
        final boolean isCollidedHorizontally;

        TickData(Vec3 position, float fallDistance, boolean onGround, boolean isCollidedHorizontally) {
            this.position = position;
            this.fallDistance = fallDistance;
            this.onGround = onGround;
            this.isCollidedHorizontally = isCollidedHorizontally;
        }
    }

    // ---------------------------------------------------------------- dynamic island


    /** The tick balance while it is not full, and the shift itself while one is running. */
    @EventTarget
    public void onIsland(Render2DEvent event) {
        if (!this.isEnabled() || mc.thePlayer == null) return;
        float max = Math.max(1, this.balanceMaxValue.getValue());
        if (this.phase != 0) {
            float done = 1.0F - this.waitRemaining / (float) (this.pendingTicks + 1);
            myau.ui.glass.Island.live("tickbase", myau.ui.glass.Island.P_TIMER, "TickBase §f" + this.mode.getModeString() + " §7" + this.pendingTicks + "t",
                    myau.ui.GlassTheme.accent(), Math.max(0.0F, Math.min(1.0F, done)));
        } else if (this.reachedTheLimit || this.tickBalance < max) {
            myau.ui.glass.Island.live("tickbase", myau.ui.glass.Island.P_INFO,
                    "Balance §f" + (int) this.tickBalance + "§7/" + (int) max + (this.reachedTheLimit ? " §ccharging" : ""),
                    this.reachedTheLimit ? myau.ui.GlassTheme.WARN : myau.ui.GlassTheme.accent(), Math.max(0.0F, Math.min(1.0F, this.tickBalance / max)));
        }
    }
}
