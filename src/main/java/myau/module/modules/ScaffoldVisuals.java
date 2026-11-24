package myau.module.modules;

import myau.mixin.IAccessorRenderManager;
import myau.ui.GlassTheme;
import myau.ui.glass.Anim;
import myau.ui.glass.Island;
import myau.ui.glass.WorldBoxes;
import myau.util.BlockUtil;
import net.minecraft.block.Block;
import net.minecraft.client.Minecraft;
import net.minecraft.item.ItemBlock;
import net.minecraft.item.ItemStack;
import net.minecraft.util.BlockPos;
import net.minecraft.util.MathHelper;

import java.util.ArrayList;
import java.util.List;

/**
 * Shared visuals for {@link Scaffold} and {@link LeaderScaffold}: glass boxes instead of the old full-screen blur.
 * Every placed block pops in with a small overshoot, glows briefly and fades out; a faint pulsing outline marks
 * where the next block will go. Drawn in one batch by WorldBoxes, the same renderer ChestESP/ESP use, so it
 * matches the rest of the glass UI. The block counter lives in the dynamic island.
 */
final class ScaffoldVisuals {
    private static final Minecraft mc = Minecraft.getMinecraft();
    private static final long TRAIL_LIFE = 1100L;
    private static final long TRAIL_POP = 240L;
    private static final long TRAIL_GLOW = 420L;
    private static final int MAX_TRAIL = 16;

    private static class PlacedBlock {
        private final BlockPos pos;
        private final long timestamp;

        PlacedBlock(BlockPos pos, long timestamp) {
            this.pos = pos;
            this.timestamp = timestamp;
        }
    }

    private final List<PlacedBlock> placedBlocks = new ArrayList<>();
    /** Where the next block goes, for the preview outline; refreshed every tick. */
    private BlockPos previewPos = null;
    /** Recent placement times, for blocks per second; and a counter the island watches to pulse on every block. */
    private final long[] placeTimes = new long[40];
    private int placeIndex, placeSerial;

    void recordPlace(BlockPos placed) {
        long now = System.currentTimeMillis();
        this.placeTimes[this.placeIndex] = now;
        this.placeIndex = (this.placeIndex + 1) % this.placeTimes.length;
        this.placeSerial++;
        this.placedBlocks.add(new PlacedBlock(placed, now));
        if (this.placedBlocks.size() > MAX_TRAIL) {
            this.placedBlocks.remove(0);
        }
    }

    void setPreview(BlockPos pos) {
        this.previewPos = pos;
    }

    void clearTrail() {
        this.placedBlocks.clear();
        java.util.Arrays.fill(this.placeTimes, 0L);
    }

    /**
     * @param trail   draw the place trail
     * @param preview draw the next-block preview (caller decides whether the module is enabled)
     * @param glow    glow pass for the trail
     */
    void render3D(boolean trail, boolean preview, boolean glow) {
        boolean drawTrail = trail && !this.placedBlocks.isEmpty();
        boolean drawPreview = preview && this.previewPos != null;
        if (!drawTrail && !drawPreview) return;

        long now = System.currentTimeMillis();
        this.placedBlocks.removeIf(pb -> now - pb.timestamp > TRAIL_LIFE);

        double renderX = ((IAccessorRenderManager) mc.getRenderManager()).getRenderPosX();
        double renderY = ((IAccessorRenderManager) mc.getRenderManager()).getRenderPosY();
        double renderZ = ((IAccessorRenderManager) mc.getRenderManager()).getRenderPosZ();

        WorldBoxes.begin();
        if (trail) {
            for (int i = 0; i < this.placedBlocks.size(); i++) {
                PlacedBlock pb = this.placedBlocks.get(i);
                long age = now - pb.timestamp;
                // Pop: grows from a smaller box to the block size with a slight overshoot.
                float pop = Anim.outBack(Anim.clamp01(age / (float) TRAIL_POP));
                double inset = 0.5 * (1.0 - (0.6 + 0.4 * pop));
                // Fade: full for the first third, then eases out.
                float life = age / (float) TRAIL_LIFE;
                float fade = life < 0.35F ? 1.0F : 1.0F - Anim.inCubic((life - 0.35F) / 0.65F);
                int color = GlassTheme.accent(pb.timestamp % 100000L) & 0xffffff;
                double x = pb.pos.getX() - renderX, y = pb.pos.getY() - renderY, z = pb.pos.getZ() - renderZ;
                WorldBoxes.add(x + inset, y + inset, z + inset, x + 1.0 - inset, y + 1.0 - inset, z + 1.0 - inset,
                        color, fade, age < TRAIL_GLOW);
            }
        }
        if (drawPreview) {
            BlockPos p = this.previewPos;
            float pulse = 0.28F + 0.14F * (float) Math.sin(now * 0.008);
            double x = p.getX() - renderX, y = p.getY() - renderY, z = p.getZ() - renderZ;
            WorldBoxes.add(x + 0.02, y + 0.02, z + 0.02, x + 0.98, y + 0.98, z + 0.98,
                    GlassTheme.accent() & 0xffffff, pulse, false);
        }
        WorldBoxes.draw(0.22F, 1.6F, glow);
    }

    /** Solid, non-interactable blocks in the hotbar. */
    static int countHotbarBlocks() {
        int count = 0;
        for (int i = 0; i < 9; i++) {
            ItemStack stack = mc.thePlayer.inventory.getStackInSlot(i);
            if (isPlaceable(stack)) count += stack.stackSize;
        }
        return count;
    }

    /** Pushes the block counter (block in hand, speed, tower state) into the dynamic island; returns the count. */
    int renderCounter(boolean towering) {
        int count = countHotbarBlocks();
        Island.scaffold(count, placingStack(), this.blocksPerSecond(), towering, this.placeSerial, buildDirection());
        return count;
    }

    /**
     * Where the player is moving (and so building) relative to the camera, in degrees: 0 ahead,
     * 90 right, -90 left, 180 behind. NaN while standing (almost) still.
     */
    private static float buildDirection() {
        double dx = mc.thePlayer.posX - mc.thePlayer.prevPosX;
        double dz = mc.thePlayer.posZ - mc.thePlayer.prevPosZ;
        if (dx * dx + dz * dz < 0.0004) return Float.NaN;
        // Minecraft yaw: 0 = +Z (south), growing clockwise seen from above, so +90 is to the right.
        float moveYaw = (float) Math.toDegrees(Math.atan2(-dx, dz));
        return MathHelper.wrapAngleTo180_float(moveYaw - mc.thePlayer.rotationYaw);
    }

    /** Blocks placed in the last second. */
    private float blocksPerSecond() {
        long now = System.currentTimeMillis();
        int n = 0;
        for (long t : this.placeTimes) {
            if (t != 0 && now - t <= 1000L) n++;
        }
        return n;
    }

    /** The block being placed: the one in hand, else the first usable block in the hotbar. */
    private static ItemStack placingStack() {
        ItemStack held = mc.thePlayer.getHeldItem();
        if (isPlaceable(held)) return held;
        for (int i = 0; i < 9; i++) {
            ItemStack stack = mc.thePlayer.inventory.getStackInSlot(i);
            if (isPlaceable(stack)) return stack;
        }
        return null;
    }

    private static boolean isPlaceable(ItemStack stack) {
        if (stack == null || stack.stackSize <= 0 || !(stack.getItem() instanceof ItemBlock)) return false;
        Block block = ((ItemBlock) stack.getItem()).getBlock();
        return !BlockUtil.isInteractable(block) && BlockUtil.isSolid(block);
    }
}
