package myau.module.modules;

import myau.event.EventTarget;
import myau.event.types.EventType;
import myau.event.types.Priority;
import myau.events.LoadWorldEvent;
import myau.events.MoveInputEvent;
import myau.events.Render2DEvent;
import myau.events.SwapItemEvent;
import myau.events.UpdateEvent;
import myau.management.RotationState;
import myau.mixin.IAccessorPlayerControllerMP;
import myau.module.Module;
import myau.property.properties.BooleanProperty;
import myau.property.properties.FloatProperty;
import myau.property.properties.IntProperty;
import myau.property.properties.ModeProperty;
import myau.property.properties.PercentProperty;
import myau.ui.GlassTheme;
import myau.ui.glass.Island;
import myau.util.BlockUtil;
import myau.util.FallingPlayer;
import myau.util.HumanRotation;
import myau.util.ItemUtil;
import myau.util.MoveUtil;
import myau.util.PacketUtil;
import myau.util.RandomUtil;
import myau.util.RotationUtil;
import myau.util.sim.SimulatedPlayer;
import net.minecraft.client.Minecraft;
import net.minecraft.client.entity.EntityPlayerSP;
import net.minecraft.item.ItemStack;
import net.minecraft.network.play.client.C0APacketAnimation;
import net.minecraft.util.AxisAlignedBB;
import net.minecraft.util.BlockPos;
import net.minecraft.util.EnumFacing;
import net.minecraft.util.MathHelper;
import net.minecraft.util.MovingObjectPosition;
import net.minecraft.util.Vec3;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Saves the player from falling into the void by placing blocks under (or diagonally under) them.
 *
 * Triggers only when no landing spot is predicted below. Each tick it predicts the player's path, ranks the empty
 * cells around it by whether a block there would catch the player, and otherwise by how close it gets a chain of
 * blocks to the player's path. The chosen face point is aimed at with a silent, mouse-like rotation (PRE), and the
 * block is placed in POST, after the C03 carrying that rotation went out, along the rotation the server received.
 */
public class Clutch extends Module {
    private static final Minecraft mc = Minecraft.getMinecraft();

    public final FloatProperty range = new FloatProperty("range", 4.5F, 3.0F, 6.0F);
    public final IntProperty rotationSpeed = new IntProperty("rotation-speed", 70, 10, 180);
    public final PercentProperty smoothing = new PercentProperty("smoothing", 30);
    public final ModeProperty moveFix = new ModeProperty("move-fix", 1, new String[]{"NONE", "SILENT", "STRICT"});
    public final BooleanProperty itemSpoof = new BooleanProperty("item-spoof", true);
    public final BooleanProperty switchBack = new BooleanProperty("switch-back", true);
    public final BooleanProperty swing = new BooleanProperty("swing", true);
    public final BooleanProperty showStatus = new BooleanProperty("show-status", true);

    /** Above TargetStrafe (10), which would otherwise take the movement yaw used by the move-fix. */
    private static final int ROTATION_PRIORITY = 11;
    private static final int MOVE_FIX_SILENT = 1;
    /** Same horizon AntiVoid uses to call a fall "into the void". */
    private static final int VOID_SIM_TICKS = 60;
    private static final int PLAN_TICKS = 20;
    /** Ticks in a row with nothing to aim at (while falling) before giving up. */
    private static final int GIVE_UP_TICKS = 10;
    private static final int H_RADIUS = 4;
    private static final int V_DEPTH = 5;
    /** Candidates that get the expensive visibility check, best first. */
    private static final int VERIFY_LIMIT = 8;
    private static final double HALF_WIDTH = 0.3;
    private static final double EPS = 1.0E-3;
    private static final double INSET = 0.05;
    private static final double UV_MIN = 0.2;
    private static final double UV_MAX = 0.8;
    private static final double UV_STEP = 0.15;
    private static final float RETURN_DONE = 0.35F;

    private enum State {
        IDLE,
        /** Falling into the void: aiming and placing. */
        CLUTCHING,
        /** A landing spot showed up (usually our block): not aiming any more, waiting to land. */
        SAVED,
        /** Nothing could be done: waiting for the ground (or a new world) before trying again. */
        FAILED
    }

    private State state = State.IDLE;
    private int restoreSlot = -1;
    private int blockSlot = -1;
    private int placed;
    private int noLockTicks;

    // rotation last sent to the server, and what we send this tick
    private float sentYaw;
    private float sentPitch;
    private float aimYaw;
    private float aimPitch;
    private boolean rotating;
    private final HumanRotation rotation = new HumanRotation();

    // locked placement
    private BlockPos lockGoal;
    private BlockPos lockSupport;
    private EnumFacing lockFace;
    private double lockU;
    private double lockV;

    /** Cells a block may go into this tick (planned in PRE, checked again in POST). */
    private final Set<BlockPos> validGoals = new HashSet<>();

    public Clutch() {
        super("Clutch", false);
    }

    /** True while Clutch is aiming / placing: other modules should not take the slot or the rotation away. */
    public boolean isBusy() {
        return this.isEnabled() && this.state == State.CLUTCHING;
    }

    /** The slot to draw as held while item-spoof hides the block slot, or -1. */
    public int getDisplaySlot() {
        if (!this.isEnabled() || !this.itemSpoof.getValue()) return -1;
        if (this.state != State.CLUTCHING && this.state != State.SAVED) return -1;
        return this.restoreSlot;
    }

    @Override
    public String[] getSuffix() {
        return new String[]{this.moveFix.getModeString()};
    }

    @Override
    public void onEnabled() {
        this.resetState();
        if (mc.thePlayer != null) {
            this.sentYaw = this.aimYaw = mc.thePlayer.rotationYaw;
            this.sentPitch = this.aimPitch = mc.thePlayer.rotationPitch;
        }
    }

    @Override
    public void onDisabled() {
        if (mc.thePlayer != null && (this.state == State.CLUTCHING || this.state == State.SAVED)) {
            this.restoreSlot();
        }
        this.state = State.IDLE;
        this.clearLock();
        this.validGoals.clear();
        // `rotating` stays: onUpdate keeps turning the server-side head back to the camera after disable
    }

    @EventTarget
    public void onLoadWorld(LoadWorldEvent event) {
        this.resetState();
        this.rotating = false;
        this.rotation.reset();
    }

    private void resetState() {
        this.state = State.IDLE;
        this.restoreSlot = -1;
        this.blockSlot = -1;
        this.placed = 0;
        this.noLockTicks = 0;
        this.clearLock();
        this.validGoals.clear();
    }

    // ── PRE: trigger, plan, aim ──

    @EventTarget(Priority.HIGH)
    public void onUpdate(UpdateEvent event) {
        if (mc.thePlayer == null || mc.theWorld == null) {
            this.rotating = false;
            return;
        }
        if (event.getType() == EventType.POST) {
            this.onPost(event);
            return;
        }
        if (event.getType() != EventType.PRE) return;
        this.sentYaw = event.getYaw();
        this.sentPitch = event.getPitch();
        this.validGoals.clear();

        if (!this.isEnabled()) {
            if (this.rotating) this.returnToCamera(event);
            return;
        }
        EntityPlayerSP player = mc.thePlayer;

        if (this.isBlocked(player)) {
            // another block-placing module (or flying) takes over; leave the slot to it
            if (this.state != State.IDLE) this.resetState();
            if (this.rotating) this.returnToCamera(event);
            return;
        }

        if (player.onGround) {
            this.onLanded();
            if (this.rotating) this.returnToCamera(event);
            return;
        }

        boolean overVoid = !player.isInWater() && !player.isInLava() && !player.isOnLadder()
                && new FallingPlayer(player).findCollision(VOID_SIM_TICKS) == null;

        switch (this.state) {
            case IDLE:
                if (!overVoid || mc.currentScreen != null) break;
                int slot = this.findBlockSlot();
                if (slot == -1) break;
                this.state = State.CLUTCHING;
                this.restoreSlot = player.inventory.currentItem;
                this.placed = 0;
                this.noLockTicks = 0;
                this.selectSlot(slot);
                break;
            case CLUTCHING:
                if (!overVoid) {
                    this.state = State.SAVED;
                    this.clearLock();
                }
                break;
            case SAVED:
                if (overVoid) {
                    this.state = State.CLUTCHING;
                    this.noLockTicks = 0;
                }
                break;
            default:
                break;
        }

        if (this.state != State.CLUTCHING || mc.currentScreen != null) {
            if (this.rotating) this.returnToCamera(event);
            return;
        }

        // keep a block in hand (it may have run out, or something else switched)
        if (!ItemUtil.isBlock(player.inventory.getCurrentItem())) {
            int slot = this.findBlockSlot();
            if (slot == -1) {
                this.fail();
                if (this.rotating) this.returnToCamera(event);
                return;
            }
            this.selectSlot(slot);
        }

        this.plan(event, player);
    }

    private boolean isBlocked(EntityPlayerSP player) {
        if (player.capabilities.isFlying || player.isRiding()) return true;
        if (LeaderScaffold.isAnyScaffoldEnabled()) return true;
        AutoBlockIn blockIn = (AutoBlockIn) myau.Myau.moduleManager.modules.get(AutoBlockIn.class);
        return blockIn != null && blockIn.isEnabled();
    }

    private void onLanded() {
        if (this.state == State.CLUTCHING || this.state == State.SAVED) {
            if (this.placed > 0 && this.showStatus.getValue()) {
                Island.event("Clutch", "自救成功 · 放置 " + this.placed + " 块", GlassTheme.OK, 1.6F);
            }
            this.restoreSlot();
        }
        this.resetState();
    }

    private void fail() {
        this.restoreSlot();
        this.state = State.FAILED;
        this.clearLock();
        this.validGoals.clear();
    }

    private void plan(UpdateEvent event, EntityPlayerSP player) {
        Vec3[] path = this.predictPath(player);
        Vec3 p1 = path[1];
        Vec3 eye = new Vec3(p1.xCoord, p1.yCoord + player.getEyeHeight(), p1.zCoord);
        double reach = this.range.getValue().doubleValue();
        AxisAlignedBB bodyAtPlace = new AxisAlignedBB(p1.xCoord - HALF_WIDTH, p1.yCoord, p1.zCoord - HALF_WIDTH,
                p1.xCoord + HALF_WIDTH, p1.yCoord + player.height, p1.zCoord + HALF_WIDTH);

        List<Candidate> candidates = this.collectCandidates(path, eye, reach, bodyAtPlace);
        for (Candidate c : candidates) {
            this.validGoals.add(c.goal);
        }

        if (candidates.isEmpty()) {
            this.clearLock();
        } else {
            Candidate best = candidates.get(0);
            Candidate locked = null;
            if (this.lockGoal != null) {
                for (Candidate c : candidates) {
                    if (c.goal.equals(this.lockGoal)) {
                        locked = c;
                        break;
                    }
                }
            }
            boolean keep = locked != null
                    && locked.tier() >= best.tier()
                    && this.isLockVisible(eye, reach);
            if (!keep) {
                this.clearLock();
                this.selectTarget(candidates, eye, reach);
            }
        }

        if (this.lockSupport == null) {
            if (++this.noLockTicks >= GIVE_UP_TICKS && player.motionY < 0.0) {
                this.fail();
                if (this.rotating) this.returnToCamera(event);
                return;
            }
            if (this.rotating) {
                // nothing to aim at yet: hold the head still rather than snapping back to the camera
                event.setRotation(this.sentYaw, this.sentPitch, ROTATION_PRIORITY);
                event.setPervRotation(this.moveFix.getValue() != 0 ? this.sentYaw : player.rotationYaw, ROTATION_PRIORITY);
            }
            return;
        }
        this.noLockTicks = 0;

        float[] target = this.rotationsTo(eye, this.lockHitPos());
        float[] next = this.rotation.step(this.lockGoal, this.sentYaw, this.sentPitch, target[0], target[1],
                this.rotationSpeed.getValue(), this.smoothing.getValue() / 100.0F, RotationUtil.gcd());
        this.aimYaw = next[0];
        this.aimPitch = next[1];
        event.setRotation(this.aimYaw, this.aimPitch, ROTATION_PRIORITY);
        event.setPervRotation(this.moveFix.getValue() != 0 ? this.aimYaw : player.rotationYaw, ROTATION_PRIORITY);
        this.rotating = true;
    }

    /** Feet positions: [0] now, [t] after t more ticks; [1] is where this tick's C03 and the POST placement happen. */
    private Vec3[] predictPath(EntityPlayerSP player) {
        Vec3[] path = new Vec3[PLAN_TICKS + 1];
        path[0] = new Vec3(player.posX, player.posY, player.posZ);
        SimulatedPlayer sim = SimulatedPlayer.fromClientPlayer(player.movementInput);
        // SILENT keeps the keys pointing where the camera looks; otherwise movement follows the server-side yaw
        sim.rotationYaw = RotationState.isActived() && this.moveFix.getValue() != MOVE_FIX_SILENT
                ? RotationState.getSmoothedYaw() : player.rotationYaw;
        for (int t = 1; t <= PLAN_TICKS; t++) {
            sim.tick();
            path[t] = new Vec3(sim.posX, sim.posY, sim.posZ);
        }
        return path;
    }

    // ── candidates ──

    private static final class Candidate {
        final BlockPos goal;
        final List<EnumFacing> supportDirs;
        final boolean feasible;
        final boolean catches;
        final double gap;
        final double angle;

        Candidate(BlockPos goal, List<EnumFacing> supportDirs, boolean feasible, boolean catches, double gap, double angle) {
            this.goal = goal;
            this.supportDirs = supportDirs;
            this.feasible = feasible;
            this.catches = catches;
            this.gap = gap;
            this.angle = angle;
        }

        /** 3: reachable in time and catches the player; 2: catches but probably too late; 1: feasible chain step; 0: rest. */
        int tier() {
            return this.catches ? (this.feasible ? 3 : 2) : (this.feasible ? 1 : 0);
        }
    }

    private List<Candidate> collectCandidates(Vec3[] path, Vec3 eye, double reach, AxisAlignedBB body) {
        List<Candidate> list = new ArrayList<>();
        Vec3 p1 = path[1];
        int baseX = MathHelper.floor_double(p1.xCoord);
        int baseZ = MathHelper.floor_double(p1.zCoord);
        int topY = MathHelper.floor_double(p1.yCoord + EPS) - 1; // highest cell whose top is not above the feet
        float stepPerTick = Math.max(4.0F,
                this.rotationSpeed.getValue() * (1.0F - 0.45F * this.smoothing.getValue() / 100.0F) * 0.5F);

        for (int y = topY; y > topY - V_DEPTH; y--) {
            if (y < 0) break;
            for (int dx = -H_RADIUS; dx <= H_RADIUS; dx++) {
                for (int dz = -H_RADIUS; dz <= H_RADIUS; dz++) {
                    BlockPos goal = new BlockPos(baseX + dx, y, baseZ + dz);
                    Vec3 center = new Vec3(goal.getX() + 0.5, goal.getY() + 0.5, goal.getZ() + 0.5);
                    if (eye.distanceTo(center) > reach + 1.0) continue;
                    if (!BlockUtil.isReplaceable(goal)) continue;
                    if (cellBox(goal).intersectsWith(body)) continue;

                    List<EnumFacing> supports = new ArrayList<>(2);
                    for (EnumFacing dir : EnumFacing.values()) {
                        BlockPos support = goal.offset(dir);
                        if (!BlockUtil.isReplaceable(support) && !BlockUtil.isInteractable(support)) {
                            supports.add(dir);
                        }
                    }
                    if (supports.isEmpty()) continue;

                    double top = goal.getY() + 1.0;
                    int reachTick = PLAN_TICKS + 1;
                    for (int t = 1; t <= PLAN_TICKS; t++) {
                        if (path[t].yCoord < top - EPS) {
                            reachTick = t;
                            break;
                        }
                    }
                    if (reachTick < 2) continue; // the feet pass this level before any block could be placed
                    // the vertical collision of a tick uses the box from before that tick's horizontal move
                    Vec3 at = path[Math.min(reachTick - 1, PLAN_TICKS)];
                    double gap = footprintGap(at, goal);
                    boolean catches = gap <= 0.0;

                    float[] rot = this.rotationsTo(eye, center);
                    double angle = this.angleCost(rot);
                    int ticksNeeded = 1 + (int) Math.ceil(angle / stepPerTick);
                    int extraBlocks = catches ? 0 : (int) Math.ceil(gap);
                    boolean feasible = ticksNeeded + extraBlocks <= reachTick - 1;

                    list.add(new Candidate(goal, supports, feasible, catches, gap, angle));
                }
            }
        }

        list.sort((a, b) -> {
            if (a.tier() != b.tier()) return Integer.compare(b.tier(), a.tier());
            if (!a.catches) {
                // coarse buckets, so height and turn still decide between nearly equal gaps
                int ga = (int) Math.round(a.gap * 4.0);
                int gb = (int) Math.round(b.gap * 4.0);
                if (ga != gb) return Integer.compare(ga, gb);
            }
            if (a.goal.getY() != b.goal.getY()) return Integer.compare(b.goal.getY(), a.goal.getY());
            return Double.compare(a.angle, b.angle);
        });
        return list;
    }

    /** Horizontal distance between the player's footprint at {@code feet} and the cell (0 when they overlap). */
    private static double footprintGap(Vec3 feet, BlockPos cell) {
        double dx = Math.max(0.0, Math.max(cell.getX() - (feet.xCoord + HALF_WIDTH), (feet.xCoord - HALF_WIDTH) - (cell.getX() + 1.0)) + EPS);
        double dz = Math.max(0.0, Math.max(cell.getZ() - (feet.zCoord + HALF_WIDTH), (feet.zCoord - HALF_WIDTH) - (cell.getZ() + 1.0)) + EPS);
        return Math.sqrt(dx * dx + dz * dz);
    }

    private static AxisAlignedBB cellBox(BlockPos pos) {
        return new AxisAlignedBB(pos.getX(), pos.getY(), pos.getZ(), pos.getX() + 1, pos.getY() + 1, pos.getZ() + 1);
    }

    /** Locks the cheapest-to-turn-to visible face point of the best candidate that has one. */
    private void selectTarget(List<Candidate> candidates, Vec3 eye, double reach) {
        int checked = 0;
        for (Candidate c : candidates) {
            if (checked++ >= VERIFY_LIMIT) return;
            if (this.pickFacePoint(c, eye, reach)) return;
        }
    }

    private boolean pickFacePoint(Candidate c, Vec3 eye, double reach) {
        double bestCost = Double.MAX_VALUE;
        BlockPos bestSupport = null;
        EnumFacing bestFace = null;
        double bestU = 0.5, bestV = 0.5;

        for (EnumFacing dir : c.supportDirs) {
            BlockPos support = c.goal.offset(dir);
            EnumFacing face = dir.getOpposite(); // the face of the support that looks at the goal
            for (double v = UV_MIN; v <= UV_MAX + 1.0E-6; v += UV_STEP) {
                for (double u = UV_MIN; u <= UV_MAX + 1.0E-6; u += UV_STEP) {
                    Vec3 hit = getHitPosOnFace(support, face, u, v);
                    if (eye.distanceTo(hit) > reach) continue;
                    float[] rot = this.rotationsTo(eye, hit);
                    double cost = this.angleCost(rot);
                    if (cost >= bestCost) continue;
                    if (!hitsFace(eye, rot, support, face, reach)) continue;
                    bestCost = cost;
                    bestSupport = support;
                    bestFace = face;
                    bestU = u;
                    bestV = v;
                }
            }
        }
        if (bestSupport == null) return false;

        this.lockGoal = c.goal;
        this.lockSupport = bestSupport;
        this.lockFace = bestFace;
        // one small personal offset per block, kept for the whole approach
        double u = MathHelper.clamp_double(bestU + RandomUtil.nextDouble(-0.07, 0.07), 0.15, 0.85);
        double v = MathHelper.clamp_double(bestV + RandomUtil.nextDouble(-0.07, 0.07), 0.15, 0.85);
        Vec3 jittered = getHitPosOnFace(bestSupport, bestFace, u, v);
        if (eye.distanceTo(jittered) <= reach && hitsFace(eye, this.rotationsTo(eye, jittered), bestSupport, bestFace, reach)) {
            this.lockU = u;
            this.lockV = v;
        } else {
            this.lockU = bestU;
            this.lockV = bestV;
        }
        return true;
    }

    private boolean isLockVisible(Vec3 eye, double reach) {
        if (this.lockSupport == null) return false;
        if (BlockUtil.isReplaceable(this.lockSupport)) return false;
        Vec3 hit = this.lockHitPos();
        if (eye.distanceTo(hit) > reach) return false;
        return hitsFace(eye, this.rotationsTo(eye, hit), this.lockSupport, this.lockFace, reach);
    }

    private Vec3 lockHitPos() {
        return getHitPosOnFace(this.lockSupport, this.lockFace, this.lockU, this.lockV);
    }

    private void clearLock() {
        this.lockGoal = null;
        this.lockSupport = null;
        this.lockFace = null;
    }

    // ── POST: place along the rotation the server just received ──

    private void onPost(UpdateEvent event) {
        if (!this.isEnabled() || this.state != State.CLUTCHING || this.validGoals.isEmpty()) return;
        if (mc.currentScreen != null) return;
        EntityPlayerSP player = mc.thePlayer;

        ItemStack held = player.inventory.getCurrentItem();
        if (!ItemUtil.isBlock(held)) return;

        MovingObjectPosition mop = RotationUtil.rayTrace(event.getYaw(), event.getPitch(), this.range.getValue(), 1.0F);
        if (mop == null || mop.typeOfHit != MovingObjectPosition.MovingObjectType.BLOCK) return;
        BlockPos support = mop.getBlockPos();
        BlockPos goal = support.offset(mop.sideHit);
        // the locked cell, or any other cell that was worth a block this tick
        if (!this.validGoals.contains(goal)) return;
        if (BlockUtil.isReplaceable(support) || BlockUtil.isInteractable(support)) return;
        if (!BlockUtil.isReplaceable(goal)) return;
        if (goal.getY() + 1.0 > player.posY + EPS) return;
        if (cellBox(goal).intersectsWith(player.getEntityBoundingBox())) return;

        if (mc.playerController.onPlayerRightClick(player, mc.theWorld, held, support, mop.sideHit, mop.hitVec)) {
            if (this.swing.getValue()) {
                player.swingItem();
            } else {
                PacketUtil.sendPacket(new C0APacketAnimation());
            }
            if (held.stackSize == 0) {
                player.inventory.mainInventory[player.inventory.currentItem] = null;
            }
            this.placed++;
            this.clearLock();
        }
    }

    // ── move-fix (same as KillAura's SILENT) ──

    @EventTarget
    public void onMove(MoveInputEvent event) {
        if (this.isEnabled() || this.rotating) {
            if (this.moveFix.getValue() == MOVE_FIX_SILENT
                    && RotationState.isActived()
                    && RotationState.getPriority() == (float) ROTATION_PRIORITY
                    && MoveUtil.isForwardPressed()) {
                MoveUtil.fixStrafe(RotationState.getSmoothedYaw());
            }
        }
    }

    // ── slots ──

    @EventTarget
    public void onSwap(SwapItemEvent event) {
        if (this.isEnabled() && (this.state == State.CLUTCHING || this.state == State.SAVED) && this.restoreSlot != -1) {
            // the wheel moves the slot you get back after landing, not the block in hand
            this.restoreSlot = event.setSlot(this.restoreSlot);
            event.setCancelled(true);
        }
    }

    /** Hotbar slot of the biggest stack of placeable blocks; the current slot wins a tie. */
    private int findBlockSlot() {
        EntityPlayerSP player = mc.thePlayer;
        int best = -1;
        int bestCount = 0;
        for (int i = 0; i < 9; i++) {
            ItemStack stack = player.inventory.getStackInSlot(i);
            if (stack == null || stack.stackSize <= 0 || !ItemUtil.isBlock(stack)) continue;
            int count = stack.stackSize + (i == player.inventory.currentItem ? 1 : 0);
            if (count > bestCount) {
                bestCount = count;
                best = i;
            }
        }
        return best;
    }

    private void selectSlot(int slot) {
        this.blockSlot = slot;
        if (mc.thePlayer.inventory.currentItem != slot) {
            mc.thePlayer.inventory.currentItem = slot;
            ((IAccessorPlayerControllerMP) mc.playerController).callSyncCurrentPlayItem();
        }
    }

    /** Back to the slot held before the clutch, unless something else already moved off our block slot. */
    private void restoreSlot() {
        if (!this.switchBack.getValue() || mc.thePlayer == null || this.restoreSlot < 0) return;
        if (mc.thePlayer.inventory.currentItem != this.blockSlot || this.restoreSlot == this.blockSlot) return;
        mc.thePlayer.inventory.currentItem = this.restoreSlot;
        if (mc.playerController != null) {
            ((IAccessorPlayerControllerMP) mc.playerController).callSyncCurrentPlayItem();
        }
    }

    // ── island ──

    @EventTarget
    public void onRender2D(Render2DEvent event) {
        if (!this.isEnabled() || !this.showStatus.getValue() || mc.thePlayer == null) return;
        if (this.state != State.CLUTCHING) return;
        Island.live("clutch", Island.P_ALERT, "Clutch §f已放 " + this.placed + " 块", GlassTheme.WARN, -1);
    }

    // ── rotation ──

    /** Turns the server-side rotation back to the camera with the same controller, then lets go. */
    private void returnToCamera(UpdateEvent event) {
        float camYaw = this.sentYaw + MathHelper.wrapAngleTo180_float(mc.thePlayer.rotationYaw - this.sentYaw);
        float camPitch = mc.thePlayer.rotationPitch;
        if (Math.abs(camYaw - this.sentYaw) < RETURN_DONE && Math.abs(camPitch - this.sentPitch) < RETURN_DONE) {
            this.rotating = false;
            this.rotation.reset();
            return;
        }
        float[] next = this.rotation.step("camera", this.sentYaw, this.sentPitch, camYaw, camPitch,
                this.rotationSpeed.getValue(), this.smoothing.getValue() / 100.0F, RotationUtil.gcd());
        this.aimYaw = next[0];
        this.aimPitch = next[1];
        event.setRotation(this.aimYaw, this.aimPitch, ROTATION_PRIORITY);
        event.setPervRotation(this.moveFix.getValue() != 0 ? this.aimYaw : mc.thePlayer.rotationYaw, ROTATION_PRIORITY);
    }

    /** Angular distance from the rotation last sent to the server; pitch counts a bit more. */
    private double angleCost(float[] rot) {
        float dy = Math.abs(MathHelper.wrapAngleTo180_float(rot[0] - this.sentYaw));
        float dp = Math.abs(rot[1] - this.sentPitch);
        return Math.sqrt(dy * dy + 1.3 * dp * dp);
    }

    /** Rotation from eye to point, with the yaw continued from the last sent yaw (no ±180 wrap jumps). */
    private float[] rotationsTo(Vec3 eye, Vec3 point) {
        double dx = point.xCoord - eye.xCoord;
        double dy = point.yCoord - eye.yCoord;
        double dz = point.zCoord - eye.zCoord;
        double hd = Math.sqrt(dx * dx + dz * dz);
        float yaw = (float) Math.toDegrees(Math.atan2(dz, dx)) - 90.0F;
        float pitch = (float) Math.toDegrees(-Math.atan2(dy, hd));
        yaw = this.sentYaw + MathHelper.wrapAngleTo180_float(yaw - this.sentYaw);
        return new float[]{yaw, pitch};
    }

    private static boolean hitsFace(Vec3 eye, float[] rot, BlockPos support, EnumFacing face, double reach) {
        MovingObjectPosition mop = rayTraceFrom(eye, rot[0], rot[1], reach);
        return mop != null
                && mop.typeOfHit == MovingObjectPosition.MovingObjectType.BLOCK
                && mop.getBlockPos().equals(support)
                && mop.sideHit == face;
    }

    private static MovingObjectPosition rayTraceFrom(Vec3 eye, float yaw, float pitch, double range) {
        float yawRad = (float) Math.toRadians(yaw);
        float pitchRad = (float) Math.toRadians(pitch);
        double x = -Math.sin(yawRad) * Math.cos(pitchRad);
        double y = -Math.sin(pitchRad);
        double z = Math.cos(yawRad) * Math.cos(pitchRad);
        Vec3 end = eye.addVector(x * range, y * range, z * range);
        return mc.theWorld.rayTraceBlocks(eye, end);
    }

    private static Vec3 getHitPosOnFace(BlockPos block, EnumFacing face, double u, double v) {
        double x = block.getX() + 0.5;
        double y = block.getY() + 0.5;
        double z = block.getZ() + 0.5;
        switch (face) {
            case DOWN:
                y = block.getY() + INSET;
                x = block.getX() + u;
                z = block.getZ() + v;
                break;
            case UP:
                y = block.getY() + 1.0 - INSET;
                x = block.getX() + u;
                z = block.getZ() + v;
                break;
            case NORTH:
                z = block.getZ() + INSET;
                x = block.getX() + u;
                y = block.getY() + v;
                break;
            case SOUTH:
                z = block.getZ() + 1.0 - INSET;
                x = block.getX() + u;
                y = block.getY() + v;
                break;
            case WEST:
                x = block.getX() + INSET;
                z = block.getZ() + u;
                y = block.getY() + v;
                break;
            case EAST:
                x = block.getX() + 1.0 - INSET;
                z = block.getZ() + u;
                y = block.getY() + v;
                break;
        }
        return new Vec3(x, y, z);
    }
}
