package myau.module.modules;

import myau.Myau;
import myau.event.EventTarget;
import myau.events.LoadWorldEvent;
import myau.events.Render3DEvent;
import myau.mixin.IAccessorMinecraft;
import myau.mixin.IAccessorRenderManager;
import myau.module.Module;
import myau.property.properties.BooleanProperty;
import myau.property.properties.ColorProperty;
import myau.property.properties.IntProperty;
import myau.property.properties.PercentProperty;
import myau.ui.glass.WorldBoxes;
import myau.util.RenderUtil;
import net.minecraft.block.Block;
import net.minecraft.block.BlockChest;
import net.minecraft.entity.Entity;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.tileentity.TileEntityChest;
import net.minecraft.tileentity.TileEntityEnderChest;
import net.minecraft.util.AxisAlignedBB;
import net.minecraft.util.BlockPos;
import net.minecraft.util.Vec3;

import java.awt.Color;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Marks chests through walls: a tinted box that fades upwards, a crisp outline that is
 * brighter where the chest is actually in view, and a soft halo around it. Chests that have
 * already been opened are greyed out.
 */
public class ChestESP extends Module {
    public final ColorProperty chest = new ColorProperty("chest", new Color(255, 170, 0).getRGB());
    public final ColorProperty trappedChest = new ColorProperty("trapped-chest", new Color(255, 43, 0).getRGB());
    public final ColorProperty enderChest = new ColorProperty("ender-chest", new Color(170, 92, 255).getRGB());
    public final BooleanProperty glow = new BooleanProperty("glow", true);
    public final PercentProperty fill = new PercentProperty("fill", 22);
    public final IntProperty range = new IntProperty("range", 96, 16, 256);
    public final BooleanProperty markOpened = new BooleanProperty("mark-opened", true);
    public final BooleanProperty tracers = new BooleanProperty("tracers", false);

    private static final int OPENED_COLOR = 0x8d93a0;
    private static final int REBUILD_TICKS = 10;

    /** One marker: a single chest, or both halves of a double chest. */
    private static final class Marker {
        AxisAlignedBB box;
        BlockPos first, second;
        int type; // 0 chest, 1 trapped, 2 ender
        boolean opened;
    }

    private static final Set<BlockPos> opened = new HashSet<>();
    private static int openedVersion;

    private final List<Marker> markers = new ArrayList<>();
    private Object builtWorld;
    private int builtTick = Integer.MIN_VALUE, builtOpenedVersion = -1;

    public ChestESP() {
        super("ChestESP", false);
    }

    /** A chest the player has opened in this world; such chests are drawn greyed out. */
    public static void markOpened(BlockPos pos) {
        if (pos != null && opened.add(pos)) openedVersion++;
    }

    @EventTarget
    public void onLoadWorld(LoadWorldEvent event) {
        opened.clear();
        openedVersion++;
        this.markers.clear();
        this.builtWorld = null;
    }

    /**
     * Collects the chests of the world into markers. Looking up block states to pair double
     * chests is the expensive part, so this runs a couple of times a second, not every frame.
     */
    private void rebuild() {
        this.markers.clear();
        List<TileEntity> tiles = mc.theWorld.loadedTileEntityList;
        for (int t = 0; t < tiles.size(); t++) {
            TileEntity tile = tiles.get(t);
            boolean ender = tile instanceof TileEntityEnderChest;
            if (!ender && !(tile instanceof TileEntityChest)) continue;
            BlockPos pos = tile.getPos();
            double minX = pos.getX() + 0.0625, minZ = pos.getZ() + 0.0625;
            double maxX = pos.getX() + 0.9375, maxZ = pos.getZ() + 0.9375;
            Marker marker = new Marker();
            marker.first = pos;
            if (ender) {
                marker.type = 2;
            } else {
                Block block = mc.theWorld.getBlockState(pos).getBlock();
                if (!(block instanceof BlockChest)) continue;
                marker.type = block.canProvidePower() ? 1 : 0;
                // A double chest is two tile entities; the half with the smaller coordinate
                // draws one box for both and the other half draws nothing.
                if (mc.theWorld.getBlockState(pos.west()).getBlock() == block || mc.theWorld.getBlockState(pos.north()).getBlock() == block) {
                    continue;
                }
                if (mc.theWorld.getBlockState(pos.east()).getBlock() == block) {
                    maxX += 1;
                    marker.second = pos.east();
                } else if (mc.theWorld.getBlockState(pos.south()).getBlock() == block) {
                    maxZ += 1;
                    marker.second = pos.south();
                }
            }
            marker.box = new AxisAlignedBB(minX, pos.getY(), minZ, maxX, pos.getY() + 0.875, maxZ);
            this.markers.add(marker);
        }
        this.builtWorld = mc.theWorld;
        this.builtTick = mc.thePlayer.ticksExisted;
        this.builtOpenedVersion = -1;
    }

    private void refreshOpened() {
        if (this.builtOpenedVersion == openedVersion) return;
        this.builtOpenedVersion = openedVersion;
        for (Marker marker : this.markers) {
            marker.opened = opened.contains(marker.first) || (marker.second != null && opened.contains(marker.second));
        }
    }

    @EventTarget
    public void onRender(Render3DEvent event) {
        if (!this.isEnabled() || mc.theWorld == null || mc.thePlayer == null) return;
        Entity view = mc.getRenderViewEntity();
        if (view == null) return;

        int tick = mc.thePlayer.ticksExisted;
        if (this.builtWorld != mc.theWorld || tick - this.builtTick >= REBUILD_TICKS || tick < this.builtTick) this.rebuild();
        if (this.markers.isEmpty()) return;
        this.refreshOpened();

        double renderX = ((IAccessorRenderManager) mc.getRenderManager()).getRenderPosX();
        double renderY = ((IAccessorRenderManager) mc.getRenderManager()).getRenderPosY();
        double renderZ = ((IAccessorRenderManager) mc.getRenderManager()).getRenderPosZ();
        double limit = this.range.getValue(), fadeFrom = limit * 0.8;
        boolean dim = this.markOpened.getValue(), lines = this.tracers.getValue();
        int chestColor = this.chest.getValue() & 0xffffff, trappedColor = this.trappedChest.getValue() & 0xffffff,
                enderColor = this.enderChest.getValue() & 0xffffff;

        WorldBoxes.begin();
        int traced = 0;
        for (int i = 0; i < this.markers.size(); i++) {
            Marker marker = this.markers.get(i);
            AxisAlignedBB box = marker.box;
            double dx = (box.minX + box.maxX) * 0.5 - renderX, dy = (box.minY + box.maxY) * 0.5 - renderY, dz = (box.minZ + box.maxZ) * 0.5 - renderZ;
            double distance = Math.sqrt(dx * dx + dy * dy + dz * dz);
            if (distance > limit) continue;
            if (!RenderUtil.isInViewFrustum(box)) continue;
            float fade = distance <= fadeFrom ? 1.0F : (float) (1.0 - (distance - fadeFrom) / (limit - fadeFrom));
            boolean used = dim && marker.opened;
            int color = used ? OPENED_COLOR : marker.type == 0 ? chestColor : marker.type == 1 ? trappedColor : enderColor;
            WorldBoxes.add(box.minX - renderX, box.minY - renderY, box.minZ - renderZ,
                    box.maxX - renderX, box.maxY - renderY, box.maxZ - renderZ,
                    color, used ? fade * 0.45F : fade, !used);
            if (lines && !used) {
                // Reuse the front of the list as the set of chests that get a tracer.
                this.markers.set(i, this.markers.get(traced));
                this.markers.set(traced++, marker);
            }
        }
        WorldBoxes.draw(this.fill.getValue() / 100.0F, 1.5F, this.glow.getValue());

        if (traced > 0) this.drawTracers(traced, chestColor, trappedColor, enderColor);
    }

    /** Lines from the crosshair to the first {@code count} markers; one camera setup for all. */
    private void drawTracers(int count, int chestColor, int trappedColor, int enderColor) {
        float partial = ((IAccessorMinecraft) mc).getTimer().renderPartialTicks;
        Vec3 start;
        if (mc.gameSettings.thirdPersonView == 0) {
            start = new Vec3(0.0, 0.0, 1.0)
                    .rotatePitch((float) -Math.toRadians(RenderUtil.lerpFloat(mc.getRenderViewEntity().rotationPitch, mc.getRenderViewEntity().prevRotationPitch, partial)))
                    .rotateYaw((float) -Math.toRadians(RenderUtil.lerpFloat(mc.getRenderViewEntity().rotationYaw, mc.getRenderViewEntity().prevRotationYaw, partial)));
        } else {
            start = new Vec3(0.0, 0.0, 0.0)
                    .rotatePitch((float) -Math.toRadians(RenderUtil.lerpFloat(mc.thePlayer.cameraPitch, mc.thePlayer.prevCameraPitch, partial)))
                    .rotateYaw((float) -Math.toRadians(RenderUtil.lerpFloat(mc.thePlayer.cameraYaw, mc.thePlayer.prevCameraYaw, partial)));
        }
        start = new Vec3(start.xCoord, start.yCoord + (double) mc.getRenderViewEntity().getEyeHeight(), start.zCoord);
        float opacity = (float) ((Tracers) Myau.moduleManager.modules.get(Tracers.class)).opacity.getValue() / 100.0F;

        RenderUtil.enableRenderState();
        RenderUtil.beginLines3D(1.5F);
        for (int i = 0; i < count; i++) {
            Marker marker = this.markers.get(i);
            AxisAlignedBB box = marker.box;
            int color = marker.type == 0 ? chestColor : marker.type == 1 ? trappedColor : enderColor;
            RenderUtil.line3D(start, (box.minX + box.maxX) * 0.5, (box.minY + box.maxY) * 0.5, (box.minZ + box.maxZ) * 0.5,
                    (color >> 16 & 255) / 255.0F, (color >> 8 & 255) / 255.0F, (color & 255) / 255.0F, opacity);
        }
        RenderUtil.endLines3D();
        RenderUtil.disableRenderState();
    }
}
