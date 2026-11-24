package myau.module.modules;

import myau.event.EventTarget;
import myau.event.types.EventType;
import myau.event.types.Priority;
import myau.events.*;
import myau.management.RotationState;
import myau.module.Module;
import myau.property.properties.BooleanProperty;
import myau.property.properties.FloatProperty;
import myau.property.properties.IntProperty;
import myau.property.properties.ModeProperty;
import myau.property.properties.PercentProperty;
import myau.util.MoveUtil;
import myau.util.HumanRotation;
import myau.util.RandomUtil;
import myau.util.RotationUtil;
import net.minecraft.block.Block;
import net.minecraft.client.Minecraft;
import net.minecraft.init.Blocks;
import net.minecraft.item.ItemBlock;
import net.minecraft.item.ItemStack;
import net.minecraft.util.*;

import java.awt.*;
import java.util.HashMap;
import java.util.Map;

/**
 * Seals the player in: feet ring, then head ring, then the roof.
 *
 * Placement picks, inside the current tier, the visible face point that is the smallest turn away from the
 * rotation last sent to the server, and keeps that point until the block is placed or the point becomes invalid.
 * Rotations go through a velocity / acceleration controller and are snapped to the mouse-sensitivity step, and
 * once there is nothing left to place the server-side head turns back to the camera instead of snapping.
 */
public class AutoBlockIn extends Module {
    private static final Minecraft mc = Minecraft.getMinecraft();
    private final Map<String, Integer> BLOCK_SCORE = new HashMap<>();
    private long lastPlaceTime = 0;

    public final FloatProperty range = new FloatProperty("range", 4.5f, 3.0f, 6.0f);
    public final IntProperty speed = new IntProperty("speed", 20, 5, 100);
    public final IntProperty placeDelay = new IntProperty("place-delay", 50, 0, 200);
    public final IntProperty rotationTolerance = new IntProperty("rotation-tolerance", 25, 5, 100);
    public final BooleanProperty itemSpoof = new BooleanProperty("item-spoof", true);
    public final BooleanProperty showProgress = new BooleanProperty("show-progress", true);
    public final ModeProperty moveFix = new ModeProperty("move-fix", 1, new String[]{"NONE", "SILENT", "STRICT"});
    public final PercentProperty smoothing = new PercentProperty("smoothing", 50);

    private static final int ROTATION_PRIORITY = 6;
    private static final int[][] DIRS = {{1, 0, 0}, {0, 0, 1}, {-1, 0, 0}, {0, 0, -1}};
    private static final double INSET = 0.05;
    /** Face samples stay away from the edges: a person clicks the middle of a face, not its rim. */
    private static final double UV_MIN = 0.2;
    private static final double UV_MAX = 0.8;
    private static final double UV_STEP = 0.15;
    /** The return-to-camera phase ends once both axes are this close. */
    private static final float RETURN_DONE = 0.35f;

    private float progress;
    private int lastSlot = -1;

    // rotation actually sent last tick, and what we send this tick
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

    public AutoBlockIn() {
        super("AutoBlockIn", false);

        BLOCK_SCORE.put("obsidian", 0);
        BLOCK_SCORE.put("end_stone", 1);
        BLOCK_SCORE.put("planks", 2);
        BLOCK_SCORE.put("log", 2);
        BLOCK_SCORE.put("glass", 3);
        BLOCK_SCORE.put("stained_glass", 3);
        BLOCK_SCORE.put("hardened_clay", 4);
        BLOCK_SCORE.put("stained_hardened_clay", 4);
        BLOCK_SCORE.put("cloth", 5);
    }

    @Override
    public void onEnabled() {
        progress = 0;
        lastPlaceTime = 0;
        clearLock();
        resetController();
        rotating = false;
        if (mc.thePlayer != null) {
            sentYaw = aimYaw = mc.thePlayer.rotationYaw;
            sentPitch = aimPitch = mc.thePlayer.rotationPitch;
            lastSlot = mc.thePlayer.inventory.currentItem;
        } else {
            lastSlot = -1;
        }
    }

    @Override
    public void onDisabled() {
        if (lastSlot != -1 && mc.thePlayer != null && mc.thePlayer.inventory.currentItem != lastSlot) {
            mc.thePlayer.inventory.currentItem = lastSlot;
        }
        progress = 0;
        clearLock();
        // `rotating` stays as it is: onUpdate keeps turning the server-side head back to the camera after disable
    }

    @EventTarget(Priority.HIGH)
    public void onUpdate(UpdateEvent event) {
        if (event.getType() != EventType.PRE) return;
        if (mc.thePlayer == null || mc.theWorld == null) {
            rotating = false;
            return;
        }
        sentYaw = event.getYaw();
        sentPitch = event.getPitch();

        if (!isEnabled()) {
            if (rotating) {
                returnToCamera(event);
            }
            return;
        }
        if (mc.currentScreen != null) {
            if (rotating) {
                returnToCamera(event);
            }
            return;
        }

        updateProgress();

        int blockSlot = findBestBlockSlot();
        if (blockSlot != -1 && mc.thePlayer.inventory.currentItem != blockSlot) {
            mc.thePlayer.inventory.currentItem = blockSlot;
        }

        ItemStack currentHeld = mc.thePlayer.inventory.getCurrentItem();
        boolean holdingBlock = currentHeld != null && currentHeld.getItem() instanceof ItemBlock;
        if (!holdingBlock) {
            clearLock();
        } else if (!isLockValid()) {
            clearLock();
            selectTarget();
        }

        if (lockSupport != null) {
            Vec3 hit = lockHitPos();
            float[] target = rotationsTo(mc.thePlayer.getPositionEyes(1.0f), hit);
            float[] next = stepTowards(target[0], target[1], true);
            aimYaw = next[0];
            aimPitch = next[1];
            event.setRotation(aimYaw, aimPitch, ROTATION_PRIORITY);
            event.setPervRotation(this.moveFix.getValue() != 0 ? aimYaw : mc.thePlayer.rotationYaw, ROTATION_PRIORITY);
            rotating = true;
        } else if (rotating) {
            returnToCamera(event);
        }
    }

    /** Turns the server-side rotation back to the camera with the same controller, then lets go. */
    private void returnToCamera(UpdateEvent event) {
        float camYaw = sentYaw + MathHelper.wrapAngleTo180_float(mc.thePlayer.rotationYaw - sentYaw);
        float camPitch = mc.thePlayer.rotationPitch;
        if (Math.abs(camYaw - sentYaw) < RETURN_DONE && Math.abs(camPitch - sentPitch) < RETURN_DONE) {
            rotating = false;
            resetController();
            return;
        }
        float[] next = stepTowards(camYaw, camPitch, false);
        aimYaw = next[0];
        aimPitch = next[1];
        event.setRotation(aimYaw, aimPitch, ROTATION_PRIORITY);
        event.setPervRotation(this.moveFix.getValue() != 0 ? aimYaw : mc.thePlayer.rotationYaw, ROTATION_PRIORITY);
    }

    /**
     * Follow one continuous mouse stroke with bounded acceleration and a shallow, per-target arc.
     */
    private float[] stepTowards(float targetYaw, float targetPitch, boolean aiming) {
        return rotation.step(aiming ? lockGoal : "camera", sentYaw, sentPitch, targetYaw, targetPitch,
                speed.getValue(), smoothing.getValue() / 100.0f, RotationUtil.gcd());
    }

    private void resetController() {
        rotation.reset();
    }

    @EventTarget
    public void onMove(MoveInputEvent event) {
        if (this.isEnabled() || rotating) {
            if (this.moveFix.getValue() == 1
                    && RotationState.isActived()
                    && RotationState.getPriority() == ROTATION_PRIORITY
                    && MoveUtil.isForwardPressed()) {
                MoveUtil.fixStrafe(RotationState.getSmoothedYaw());
            }
        }
    }

    @EventTarget(Priority.HIGH)
    public void onTick(TickEvent event) {
        if (!isEnabled()) return;
        if (event.getType() != EventType.PRE) return;
        if (mc.thePlayer == null || mc.theWorld == null) return;
        if (mc.currentScreen != null) return;
        if (lockSupport == null || !rotating) return;

        // aimYaw / aimPitch is what the last C03 carried
        float[] wanted = rotationsTo(mc.thePlayer.getPositionEyes(1.0f), lockHitPos());
        float yawOff = Math.abs(MathHelper.wrapAngleTo180_float(wanted[0] - aimYaw));
        float pitchOff = Math.abs(wanted[1] - aimPitch);
        if (yawOff > rotationTolerance.getValue() || pitchOff > rotationTolerance.getValue()) {
            return;
        }

        long currentTime = System.currentTimeMillis();
        if (currentTime - lastPlaceTime < placeDelay.getValue()) {
            return;
        }

        MovingObjectPosition mop = rayTraceBlock(aimYaw, aimPitch, range.getValue());
        if (mop == null
                || mop.typeOfHit != MovingObjectPosition.MovingObjectType.BLOCK
                || !mop.getBlockPos().equals(lockSupport)
                || mop.sideHit != lockFace) {
            return;
        }
        ItemStack heldStack = mc.thePlayer.inventory.getCurrentItem();
        if (heldStack == null || !(heldStack.getItem() instanceof ItemBlock)) {
            return;
        }
        lastPlaceTime = currentTime;
        mc.playerController.onPlayerRightClick(mc.thePlayer, mc.theWorld, heldStack, lockSupport, lockFace, mop.hitVec);
        mc.thePlayer.swingItem();
        clearLock();
    }

    @EventTarget
    public void onSwap(SwapItemEvent event) {
        if (this.isEnabled()) {
            lastSlot = event.setSlot(lastSlot);
            event.setCancelled(true);
        }
    }

    @EventTarget
    public void onRender2D(Render2DEvent event) {
        if (!isEnabled() || mc.currentScreen != null) return;
        if (!showProgress.getValue()) return;
        if (mc.fontRendererObj == null) return;

        myau.ui.glass.Island.live("autoblockin", myau.ui.glass.Island.P_PROGRESS,
                String.format("Blocking §f%.0f%%", progress * 100.0F),
                0xff000000 | getProgressColor().getRGB(), progress);
    }

    // ── target selection ──

    private BlockPos feetPos() {
        return new BlockPos(mc.thePlayer.posX, mc.thePlayer.posY, mc.thePlayer.posZ);
    }

    private void selectTarget() {
        BlockPos feet = feetPos();
        Vec3 eye = mc.thePlayer.getPositionEyes(1.0f);
        double reach = range.getValue().doubleValue();

        BlockPos[] feetRing = new BlockPos[4];
        BlockPos[] headRing = new BlockPos[4];
        for (int i = 0; i < 4; i++) {
            feetRing[i] = feet.add(DIRS[i][0], 0, DIRS[i][2]);
            headRing[i] = feet.add(DIRS[i][0], 1, DIRS[i][2]);
        }

        if (pickBest(feetRing, eye, reach)) return;
        // a feet-ring cell hanging over air: put a block under it first (stays inside the 3x3 around the player)
        BlockPos[] under = new BlockPos[4];
        int n = 0;
        for (BlockPos goal : feetRing) {
            if (isAir(goal) && isAir(goal.down())) {
                under[n++] = goal.down();
            }
        }
        if (n > 0) {
            BlockPos[] trimmed = new BlockPos[n];
            System.arraycopy(under, 0, trimmed, 0, n);
            if (pickBest(trimmed, eye, reach)) return;
        }
        if (pickBest(headRing, eye, reach)) return;
        BlockPos roof = feet.up(2);
        if (pickBest(new BlockPos[]{roof}, eye, reach)) return;
        // the roof touches no head-ring block (those are one level lower): put one block beside the roof first,
        // on top of the head ring, and the roof can be placed against it next
        if (isAir(roof)) {
            BlockPos[] roofSides = new BlockPos[4];
            for (int i = 0; i < 4; i++) {
                roofSides[i] = roof.add(DIRS[i][0], 0, DIRS[i][2]);
            }
            pickBest(roofSides, eye, reach);
        }
    }

    /** Locks the cheapest-to-turn-to visible face point among these goals. */
    private boolean pickBest(BlockPos[] goals, Vec3 eye, double reach) {
        AxisAlignedBB playerBox = mc.thePlayer.getEntityBoundingBox();
        double bestCost = Double.MAX_VALUE;
        BlockPos bestGoal = null, bestSupport = null;
        EnumFacing bestFace = null;
        double bestU = 0.5, bestV = 0.5;

        for (BlockPos goal : goals) {
            if (goal == null || !isAir(goal)) continue;
            AxisAlignedBB cell = new AxisAlignedBB(goal.getX(), goal.getY(), goal.getZ(),
                    goal.getX() + 1, goal.getY() + 1, goal.getZ() + 1);
            if (cell.intersectsWith(playerBox)) continue; // the server would refuse a block inside us

            for (EnumFacing dir : EnumFacing.values()) {
                BlockPos support = goal.offset(dir);
                if (isAir(support)) continue;
                EnumFacing face = dir.getOpposite(); // face of the support that looks at the goal
                Vec3 center = new Vec3(support.getX() + 0.5, support.getY() + 0.5, support.getZ() + 0.5);
                if (eye.distanceTo(center) > reach + 1.0) continue;

                for (double v = UV_MIN; v <= UV_MAX + 1e-6; v += UV_STEP) {
                    for (double u = UV_MIN; u <= UV_MAX + 1e-6; u += UV_STEP) {
                        Vec3 hit = getHitPosOnFace(support, face, u, v);
                        if (eye.distanceTo(hit) > reach) continue;
                        float[] rot = rotationsTo(eye, hit);
                        double cost = angleCost(rot);
                        if (cost >= bestCost) continue;
                        if (!hitsFace(rot, support, face, reach)) continue;
                        bestCost = cost;
                        bestGoal = goal;
                        bestSupport = support;
                        bestFace = face;
                        bestU = u;
                        bestV = v;
                    }
                }
            }
        }
        if (bestSupport == null) {
            return false;
        }
        lockGoal = bestGoal;
        lockSupport = bestSupport;
        lockFace = bestFace;
        // one small personal offset per block, kept for the whole approach
        double u = MathHelper.clamp_double(bestU + RandomUtil.nextDouble(-0.07, 0.07), 0.15, 0.85);
        double v = MathHelper.clamp_double(bestV + RandomUtil.nextDouble(-0.07, 0.07), 0.15, 0.85);
        Vec3 jittered = getHitPosOnFace(bestSupport, bestFace, u, v);
        if (eye.distanceTo(jittered) <= reach && hitsFace(rotationsTo(eye, jittered), bestSupport, bestFace, reach)) {
            lockU = u;
            lockV = v;
        } else {
            lockU = bestU;
            lockV = bestV;
        }
        return true;
    }

    private boolean isLockValid() {
        if (lockSupport == null) return false;
        if (!isAir(lockGoal) || isAir(lockSupport)) return false;
        Vec3 eye = mc.thePlayer.getPositionEyes(1.0f);
        Vec3 hit = lockHitPos();
        double reach = range.getValue().doubleValue();
        if (eye.distanceTo(hit) > reach) return false;
        return hitsFace(rotationsTo(eye, hit), lockSupport, lockFace, reach);
    }

    private Vec3 lockHitPos() {
        return getHitPosOnFace(lockSupport, lockFace, lockU, lockV);
    }

    private void clearLock() {
        lockGoal = null;
        lockSupport = null;
        lockFace = null;
    }

    /** Angular distance from the rotation last sent to the server; pitch counts a bit more (people turn sideways more easily). */
    private double angleCost(float[] rot) {
        float dy = Math.abs(MathHelper.wrapAngleTo180_float(rot[0] - sentYaw));
        float dp = Math.abs(rot[1] - sentPitch);
        return Math.sqrt(dy * dy + 1.3 * dp * dp);
    }

    private boolean hitsFace(float[] rot, BlockPos support, EnumFacing face, double reach) {
        MovingObjectPosition mop = rayTraceBlock(rot[0], rot[1], reach);
        return mop != null
                && mop.typeOfHit == MovingObjectPosition.MovingObjectType.BLOCK
                && mop.getBlockPos().equals(support)
                && mop.sideHit == face;
    }

    // ── helpers ──

    private int findBestBlockSlot() {
        int bestSlot = -1;
        int bestScore = Integer.MAX_VALUE;

        for (int slot = 0; slot <= 8; slot++) {
            ItemStack stack = mc.thePlayer.inventory.getStackInSlot(slot);
            if (stack == null || stack.stackSize == 0) continue;

            if (stack.getItem() instanceof ItemBlock) {
                Block block = ((ItemBlock) stack.getItem()).getBlock();
                String blockName = block.getUnlocalizedName().replace("tile.", "");

                Integer score = BLOCK_SCORE.get(blockName);
                if (score != null && score < bestScore) {
                    bestScore = score;
                    bestSlot = slot;
                    if (score == 0) break;
                }
            }
        }

        return bestSlot;
    }

    private Vec3 getHitPosOnFace(BlockPos block, EnumFacing face, double u, double v) {
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

    private boolean isAir(BlockPos pos) {
        Block block = mc.theWorld.getBlockState(pos).getBlock();
        return block == Blocks.air
                || block == Blocks.water
                || block == Blocks.flowing_water
                || block == Blocks.lava
                || block == Blocks.flowing_lava
                || block == Blocks.fire;
    }

    private void updateProgress() {
        BlockPos feet = feetPos();
        int filled = 0;
        int total = 9;

        if (!isAir(feet.up(2))) {
            filled++;
        }
        for (int[] d : DIRS) {
            if (!isAir(feet.add(d[0], 0, d[2]))) {
                filled++;
            }
            if (!isAir(feet.add(d[0], 1, d[2]))) {
                filled++;
            }
        }
        progress = (float) filled / (float) total;
    }

    private Color getProgressColor() {
        if (progress <= 0.33f) {
            return new Color(255, 85, 85);
        } else if (progress <= 0.66f) {
            return new Color(255, 255, 85);
        } else {
            return new Color(85, 255, 85);
        }
    }

    private MovingObjectPosition rayTraceBlock(float yaw, float pitch, double range) {
        float yawRad = (float) Math.toRadians(yaw);
        float pitchRad = (float) Math.toRadians(pitch);

        double x = -Math.sin(yawRad) * Math.cos(pitchRad);
        double y = -Math.sin(pitchRad);
        double z = Math.cos(yawRad) * Math.cos(pitchRad);

        Vec3 start = mc.thePlayer.getPositionEyes(1.0f);
        Vec3 end = start.addVector(x * range, y * range, z * range);

        return mc.theWorld.rayTraceBlocks(start, end);
    }

    /** Rotation from eye to point, with the yaw continued from the last sent yaw (no ±180 wrap jumps). */
    private float[] rotationsTo(Vec3 eye, Vec3 point) {
        double dx = point.xCoord - eye.xCoord;
        double dy = point.yCoord - eye.yCoord;
        double dz = point.zCoord - eye.zCoord;
        double hd = Math.sqrt(dx * dx + dz * dz);
        float yaw = (float) Math.toDegrees(Math.atan2(dz, dx)) - 90.0f;
        float pitch = (float) Math.toDegrees(-Math.atan2(dy, hd));
        yaw = sentYaw + MathHelper.wrapAngleTo180_float(yaw - sentYaw);
        return new float[]{yaw, pitch};
    }

    public int getSlot() {
        return lastSlot;
    }
}
