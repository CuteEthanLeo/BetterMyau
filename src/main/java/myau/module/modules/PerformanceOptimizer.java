package myau.module.modules;

import myau.event.EventTarget;
import myau.event.types.EventType;
import myau.events.LoadWorldEvent;
import myau.events.TickEvent;
import myau.module.Module;
import myau.property.properties.BooleanProperty;
import myau.property.properties.IntProperty;
import net.minecraft.client.Minecraft;
import net.minecraft.entity.Entity;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.tileentity.TileEntityChest;
import net.minecraft.tileentity.TileEntitySign;
import net.minecraft.util.AxisAlignedBB;
import net.minecraft.util.Vec3;

/**
 * Client-side render optimisations. Every setting listed here is wired to an actual
 * implementation:
 * <ul>
 *   <li>entity-culling / tile-culling: skip rendering of entities and chests/signs that are
 *       outside the real camera frustum (MixinRendererLivingEntity, MixinTileEntityRendererDispatcher)</li>
 *   <li>aggressive-culling: additionally drop far-away entities (32 blocks) and tile entities (30 blocks)</li>
 *   <li>async-chunk: shrink the main-thread chunk upload time budget (MixinRenderGlobal)</li>
 *   <li>limit-particles / particle-cap / disable-particles: drop particles at spawn time (MixinEffectRenderer)</li>
 *   <li>entity-shadow-skip: skip entity shadow rendering (MixinRender)</li>
 *   <li>dynamic-optimize / fps-target: raise an optimisation tier while FPS stays below the target</li>
 * </ul>
 * World lighting is never touched: the World class is shared with the integrated server.
 */
public class PerformanceOptimizer extends Module {
    private static final Minecraft mc = Minecraft.getMinecraft();
    // Half-angle beyond which an object is certainly off screen at any reachable field of view.
    private static final double BEHIND_CAMERA_RAD = Math.toRadians(100.0);
    private static final int RECOVER_CHECKS = 3;

    public final BooleanProperty entityCulling = new BooleanProperty("entity-culling", true);
    public final BooleanProperty tileEntityCulling = new BooleanProperty("tile-culling", true);
    public final BooleanProperty aggressiveCulling = new BooleanProperty("aggressive-culling", false);
    public final BooleanProperty asyncChunk = new BooleanProperty("async-chunk", true);
    public final BooleanProperty limitParticles = new BooleanProperty("limit-particles", true);
    public final IntProperty particleCap = new IntProperty("particle-cap", 150, 0, 1000, () -> this.limitParticles.getValue());
    public final BooleanProperty disableParticles = new BooleanProperty("disable-particles", false);
    public final BooleanProperty entityShadowSkip = new BooleanProperty("entity-shadow-skip", true);
    public final BooleanProperty dynamicOptimize = new BooleanProperty("dynamic-optimize", true);
    public final IntProperty fpsTarget = new IntProperty("fps-target", 60, 30, 240, () -> this.dynamicOptimize.getValue());

    // FPS tracking (30 samples at 20 tps ~ 1.5 s)
    private final int[] fpsSamples = new int[30];
    private int fpsSampleIndex = 0;
    private int fpsSampleCount = 0;
    private float rollingFPS = 60f;
    private float lowestFPS = 60f;
    private long lowFPSTimestamp = 0L;

    // Dynamic tier: 0 = off, 1 = light, 2 = aggressive, 3 = extreme
    private int dynamicTier = 0;
    private int recoverStreak = 0;
    private long lastDynamicCheck = 0L;

    public PerformanceOptimizer() {
        super("PerformanceOptimizer", true);
    }

    public float getFPS() {
        return this.rollingFPS;
    }

    public float getLowestFPS() {
        return this.lowestFPS;
    }

    /**
     * Current dynamic optimisation tier. Always 0 while the module or the dynamic switch is off,
     * so every consumer follows the same switch.
     */
    public int getDynamicTier() {
        if (!this.isEnabled() || !this.dynamicOptimize.getValue()) {
            return 0;
        }
        return this.dynamicTier;
    }

    public boolean shouldSkipEntityShadow() {
        return this.isEnabled() && this.entityShadowSkip.getValue();
    }

    /**
     * @param currentCount number of particles currently alive
     */
    public boolean shouldDropParticle(int currentCount) {
        if (!this.isEnabled()) {
            return false;
        }
        if (this.disableParticles.getValue() || this.getDynamicTier() >= 3) {
            return true;
        }
        return this.limitParticles.getValue() && currentCount >= this.particleCap.getValue();
    }

    // ------------------------------------------------------------------
    // Visibility tests based on the real camera (render view entity, FOV, aspect ratio)
    // ------------------------------------------------------------------

    public boolean shouldRenderEntity(Entity entity) {
        if (!this.isEnabled() || !this.entityCulling.getValue() || entity == null) {
            return true;
        }
        Entity view = mc.getRenderViewEntity();
        if (view == null || entity == view || entity == mc.thePlayer
                || entity.riddenByEntity == view || entity.ridingEntity == view) {
            return true;
        }
        AxisAlignedBB bb = entity.getEntityBoundingBox();
        double cx = (bb.minX + bb.maxX) * 0.5;
        double cy = (bb.minY + bb.maxY) * 0.5;
        double cz = (bb.minZ + bb.maxZ) * 0.5;
        double dx = cx - view.posX;
        double dy = cy - (view.posY + view.getEyeHeight());
        double dz = cz - view.posZ;
        double distSq = dx * dx + dy * dy + dz * dz;

        boolean aggressive = this.aggressiveCulling.getValue();
        if (distSq < (aggressive ? 9.0 : 16.0)) {
            return true;
        }
        if (aggressive && distSq > 1024.0) {
            return false;
        }
        double radius = 0.5 * Math.sqrt(
                sq(bb.maxX - bb.minX) + sq(bb.maxY - bb.minY) + sq(bb.maxZ - bb.minZ));
        return this.isInsideView(view, dx, dy, dz, distSq, radius);
    }

    public boolean shouldRenderTileEntity(TileEntity te) {
        if (!this.isEnabled() || !this.tileEntityCulling.getValue() || te == null) {
            return true;
        }
        if (!(te instanceof TileEntityChest || te instanceof TileEntitySign)) {
            return true;
        }
        // The chest drawn as an item (hotbar, hand, inventory) is a dummy tile entity with no
        // world and position (0, 0, 0); it must never be culled by distance.
        if (!te.hasWorldObj()) {
            return true;
        }
        Entity view = mc.getRenderViewEntity();
        if (view == null) {
            return true;
        }
        double dx = te.getPos().getX() + 0.5 - view.posX;
        double dy = te.getPos().getY() + 0.5 - (view.posY + view.getEyeHeight());
        double dz = te.getPos().getZ() + 0.5 - view.posZ;
        double distSq = dx * dx + dy * dy + dz * dz;

        boolean aggressive = this.aggressiveCulling.getValue();
        if (distSq < (aggressive ? 36.0 : 64.0)) {
            return true;
        }
        if (aggressive && distSq > 900.0) {
            return false;
        }
        // double chests span two blocks; 1.5 covers their diagonal
        return this.isInsideView(view, dx, dy, dz, distSq, 1.5);
    }

    /**
     * Only objects clearly behind the camera are culled here. Everything in front is left to
     * the game's own frustum culling, which knows the real field of view: sprinting, speed
     * effects, zoom and camera modules all change it, and an earlier cone test based on the
     * FOV setting made players vanish near the screen edges whenever the real FOV was wider.
     * In third-person views the camera is not at the view entity's eyes, so nothing is culled.
     */
    private boolean isInsideView(Entity view, double dx, double dy, double dz, double distSq, double radius) {
        if (mc.gameSettings.thirdPersonView != 0) {
            return true;
        }
        double dist = Math.sqrt(distSq);
        if (dist <= radius) {
            return true;
        }
        // The view direction is the same for every entity of a frame; look it up once.
        long frame = myau.util.TeamUtil.renderFrame();
        if (frame != this.lookFrame || view != this.lookView) {
            Vec3 look = view.getLook(1.0F);
            this.lookX = look.xCoord;
            this.lookY = look.yCoord;
            this.lookZ = look.zCoord;
            this.lookFrame = frame;
            this.lookView = view;
        }
        double dot = (this.lookX * dx + this.lookY * dy + this.lookZ * dz) / dist;
        double angle = Math.acos(Math.max(-1.0, Math.min(1.0, dot)));
        double angularRadius = Math.asin(Math.min(1.0, radius / dist));
        return angle <= BEHIND_CAMERA_RAD + angularRadius;
    }

    private long lookFrame = -1;
    private Entity lookView;
    private double lookX, lookY, lookZ;

    private static double sq(double v) {
        return v * v;
    }

    // ------------------------------------------------------------------
    // FPS tracking and dynamic tier
    // ------------------------------------------------------------------

    @EventTarget
    public void onTick(TickEvent event) {
        if (event.getType() != EventType.PRE || mc.theWorld == null) {
            return;
        }

        int debugFPS = Minecraft.getDebugFPS();
        if (debugFPS > 0 && debugFPS < 1000) {
            this.fpsSamples[this.fpsSampleIndex] = debugFPS;
            this.fpsSampleIndex = (this.fpsSampleIndex + 1) % this.fpsSamples.length;
            if (this.fpsSampleCount < this.fpsSamples.length) {
                this.fpsSampleCount++;
            }
            int total = 0;
            for (int i = 0; i < this.fpsSampleCount; i++) {
                total += this.fpsSamples[i];
            }
            this.rollingFPS = (float) total / this.fpsSampleCount;

            long now = System.currentTimeMillis();
            if (this.rollingFPS < this.lowestFPS || now - this.lowFPSTimestamp > 5000L) {
                this.lowestFPS = this.rollingFPS;
                this.lowFPSTimestamp = now;
            }
        }

        if (!this.isEnabled() || !this.dynamicOptimize.getValue()) {
            this.resetDynamic();
            return;
        }

        long now = System.currentTimeMillis();
        if (now - this.lastDynamicCheck < 1000L) {
            return;
        }
        this.lastDynamicCheck = now;

        int target = this.fpsTarget.getValue();
        int wanted;
        if (this.rollingFPS < target * 0.5f) {
            wanted = 3;
        } else if (this.rollingFPS < target * 0.7f) {
            wanted = 2;
        } else if (this.rollingFPS < target * 0.85f) {
            wanted = 1;
        } else {
            wanted = 0;
        }

        if (wanted > this.dynamicTier) {
            this.dynamicTier = wanted;
            this.recoverStreak = 0;
        } else if (this.dynamicTier > 0 && this.rollingFPS >= target * 0.95f) {
            // Recovery threshold sits below the target so a capped frame rate (VSync at 60 with
            // target 60) can still step the tier back down after a few stable checks.
            this.recoverStreak++;
            if (this.recoverStreak >= RECOVER_CHECKS) {
                this.dynamicTier--;
                this.recoverStreak = 0;
            }
        } else {
            this.recoverStreak = 0;
        }
    }

    @EventTarget
    public void onLoadWorld(LoadWorldEvent event) {
        this.resetDynamic();
        this.fpsSampleCount = 0;
        this.fpsSampleIndex = 0;
    }

    @Override
    public void onDisabled() {
        this.resetDynamic();
    }

    private void resetDynamic() {
        this.dynamicTier = 0;
        this.recoverStreak = 0;
        this.lastDynamicCheck = 0L;
    }
}
