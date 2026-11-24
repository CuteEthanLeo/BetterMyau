package myau.module.modules;

import myau.Myau;
import myau.event.EventTarget;
import myau.event.types.EventType;
import myau.event.types.Priority;
import myau.events.LoadWorldEvent;
import myau.events.MoveInputEvent;
import myau.events.PacketEvent;
import myau.events.Render3DEvent;
import myau.events.UpdateEvent;
import myau.management.RotationState;
import myau.mixin.IAccessorEntity;
import myau.mixin.IAccessorRenderManager;
import myau.module.Module;
import myau.property.properties.BooleanProperty;
import myau.property.properties.FloatProperty;
import myau.property.properties.ModeProperty;
import myau.util.MoveUtil;
import myau.util.RenderUtil;
import myau.util.RotationUtil;
import net.minecraft.block.BlockChest;
import net.minecraft.client.Minecraft;
import net.minecraft.network.play.server.S24PacketBlockAction;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.tileentity.TileEntityChest;
import net.minecraft.util.AxisAlignedBB;
import net.minecraft.util.BlockPos;
import net.minecraft.util.EnumFacing;
import net.minecraft.util.MovingObjectPosition;
import net.minecraft.util.Vec3;

import java.util.Collections;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Turns to and opens every chest in reach that has not been opened yet, and marks chests green
 * (not opened) or red (opened). Ported from Zak0.
 *
 * <p>Turns at rotation priority 8, and only in ticks where no other module has turned: its handler runs
 * last (LOWEST, registered after the other LOWEST rotators), so it never takes a rotation away from
 * another module, and its move-fix check on priority 8 can't fire for someone else's rotation.
 */
public class ChestAura extends Module {
    private static final Minecraft mc = Minecraft.getMinecraft();
    private static final int ROTATION_PRIORITY = 8;

    public final FloatProperty range = new FloatProperty("range", 4.0F, 1.0F, 6.0F);
    public final BooleanProperty throughWalls = new BooleanProperty("through-walls", true);
    public final ModeProperty moveFix = new ModeProperty("move-fix", 1, new String[]{"NONE", "SILENT", "STRICT"});
    public final BooleanProperty noScaffold = new BooleanProperty("no-scaffold", true);
    public final BooleanProperty esp = new BooleanProperty("esp", true);

    /** Chests seen open (by anyone) or opened by us. Filled from the network thread too. */
    private final Set<BlockPos> opened = Collections.newSetFromMap(new ConcurrentHashMap<>());
    /** Chest turned to this tick, opened in POST once the rotation has gone out. */
    private BlockPos pendingChest;
    private EnumFacing pendingFace;
    private Vec3 pendingHit;

    public ChestAura() {
        super("ChestAura", false);
    }

    @Override
    public void onDisabled() {
        this.pendingChest = null;
    }

    @Override
    public String[] getSuffix() {
        return new String[]{this.moveFix.getModeString()};
    }

    @EventTarget
    public void onWorld(LoadWorldEvent event) {
        this.opened.clear();
        this.pendingChest = null;
    }

    /** Block action 1 on a chest is its viewer count changing: someone opened it. Network thread. */
    @EventTarget
    public void onPacket(PacketEvent event) {
        if (!this.isEnabled() || event.getType() != EventType.RECEIVE) return;
        if (event.getPacket() instanceof S24PacketBlockAction) {
            S24PacketBlockAction packet = (S24PacketBlockAction) event.getPacket();
            if (packet.getData1() == 1 && packet.getBlockType() instanceof BlockChest) {
                this.opened.add(packet.getBlockPosition());
            }
        }
    }

    @EventTarget(Priority.LOWEST)
    public void onUpdate(UpdateEvent event) {
        if (!this.isEnabled() || mc.thePlayer == null || mc.theWorld == null) return;
        if (event.getType() == EventType.PRE) {
            this.pendingChest = null;
            if (!this.canWork() || event.isRotated()) return;
            for (TileEntity tile : mc.theWorld.loadedTileEntityList) {
                if (tile instanceof TileEntityChest && ((TileEntityChest) tile).numPlayersUsing > 0) {
                    this.opened.add(tile.getPos());
                }
            }
            TileEntityChest chest = this.findChest();
            if (chest == null) return;
            BlockPos pos = chest.getPos();
            Vec3 eye = mc.thePlayer.getPositionEyes(1.0F);
            float[] rotation = RotationUtil.getRotations(
                    pos.getX() + 0.5 - eye.xCoord, pos.getY() + 0.5 - eye.yCoord, pos.getZ() + 0.5 - eye.zCoord,
                    event.getYaw(), event.getPitch(), 180.0F, 0.0F);
            event.setRotation(rotation[0], rotation[1], ROTATION_PRIORITY);
            if (this.moveFix.getValue() != 0) {
                event.setPervRotation(rotation[0], ROTATION_PRIORITY);
            }
            // where that rotation meets the chest, for the click
            Vec3 look = ((IAccessorEntity) mc.thePlayer).callGetVectorForRotation(rotation[1], rotation[0]);
            double reach = this.range.getValue() + 1.0;
            MovingObjectPosition hit = chestBox(pos).calculateIntercept(eye, eye.addVector(look.xCoord * reach, look.yCoord * reach, look.zCoord * reach));
            this.pendingChest = pos;
            this.pendingFace = hit != null ? hit.sideHit : EnumFacing.UP;
            this.pendingHit = hit != null ? hit.hitVec : new Vec3(pos.getX() + 0.5, pos.getY() + 0.875, pos.getZ() + 0.5);
        } else if (event.getType() == EventType.POST && this.pendingChest != null) {
            BlockPos pos = this.pendingChest;
            this.pendingChest = null;
            // the rotation went out with this tick's movement packet; now the click
            if (this.canWork() && mc.playerController.onPlayerRightClick(
                    mc.thePlayer, mc.theWorld, mc.thePlayer.getHeldItem(), pos, this.pendingFace, this.pendingHit)) {
                mc.thePlayer.swingItem();
                this.opened.add(pos);
            }
        }
    }

    @EventTarget
    public void onMove(MoveInputEvent event) {
        if (!this.isEnabled()) return;
        if (this.moveFix.getValue() == 1
                && RotationState.isActived()
                && RotationState.getPriority() == (float) ROTATION_PRIORITY
                && MoveUtil.isForwardPressed()) {
            MoveUtil.fixStrafe(RotationState.getSmoothedYaw());
        }
    }

    @EventTarget
    public void onRender(Render3DEvent event) {
        if (!this.isEnabled() || !this.esp.getValue() || mc.theWorld == null) return;
        IAccessorRenderManager rm = (IAccessorRenderManager) mc.getRenderManager();
        RenderUtil.enableRenderState();
        for (TileEntity tile : mc.theWorld.loadedTileEntityList) {
            if (!(tile instanceof TileEntityChest)) continue;
            TileEntityChest chest = (TileEntityChest) tile;
            chest.checkForAdjacentChests();
            // a double chest is drawn once, from its west / north half, like the vanilla renderer does
            if (chest.adjacentChestXNeg != null || chest.adjacentChestZNeg != null) continue;
            BlockPos pos = chest.getPos();
            double maxX = pos.getX() + (chest.adjacentChestXPos != null ? 1.9375 : 0.9375);
            double maxZ = pos.getZ() + (chest.adjacentChestZPos != null ? 1.9375 : 0.9375);
            AxisAlignedBB box = new AxisAlignedBB(pos.getX() + 0.0625, pos.getY(), pos.getZ() + 0.0625, maxX, pos.getY() + 0.875, maxZ)
                    .offset(-rm.getRenderPosX(), -rm.getRenderPosY(), -rm.getRenderPosZ());
            if (this.isOpened(chest)) {
                RenderUtil.drawFilledBox(box, 255, 0, 0, 100);
            } else {
                RenderUtil.drawFilledBox(box, 0, 255, 0, 100);
            }
        }
        RenderUtil.disableRenderState();
    }

    // ---------------------------------------------------------------- helpers

    private boolean canWork() {
        if (mc.currentScreen != null || mc.thePlayer.openContainer != mc.thePlayer.inventoryContainer) {
            return false; // a chest (or anything) is already open, ChestStealer's silent mode included
        }
        if (mc.thePlayer.isSneaking() || mc.playerController.isSpectator()) {
            return false; // sneaking would place the held block instead of opening
        }
        if (this.noScaffold.getValue() && LeaderScaffold.isAnyScaffoldEnabled()) {
            return false;
        }
        KillAura killAura = (KillAura) Myau.moduleManager.modules.get(KillAura.class);
        return killAura == null || !killAura.isEnabled() || killAura.getTarget() == null;
    }

    private TileEntityChest findChest() {
        Vec3 eye = mc.thePlayer.getPositionEyes(1.0F);
        double rangeSq = this.range.getValue() * this.range.getValue();
        TileEntityChest best = null;
        double bestSq = Double.MAX_VALUE;
        for (TileEntity tile : mc.theWorld.loadedTileEntityList) {
            if (!(tile instanceof TileEntityChest)) continue;
            TileEntityChest chest = (TileEntityChest) tile;
            if (this.isOpened(chest)) continue;
            BlockPos pos = chest.getPos();
            Vec3 center = new Vec3(pos.getX() + 0.5, pos.getY() + 0.5, pos.getZ() + 0.5);
            double distSq = eye.squareDistanceTo(center);
            if (distSq > rangeSq || distSq >= bestSq) continue;
            if (!this.throughWalls.getValue() && !this.canSee(eye, center, chest)) continue;
            best = chest;
            bestSq = distSq;
        }
        return best;
    }

    /** Nothing but the chest itself (or its other half) between the eyes and its middle. */
    private boolean canSee(Vec3 eye, Vec3 center, TileEntityChest chest) {
        MovingObjectPosition hit = mc.theWorld.rayTraceBlocks(eye, center, false, true, false);
        if (hit == null || hit.getBlockPos() == null) return true;
        BlockPos at = hit.getBlockPos();
        if (at.equals(chest.getPos())) return true;
        return mc.theWorld.getTileEntity(at) instanceof TileEntityChest && at.distanceSq(chest.getPos()) <= 1.0;
    }

    /** Either half of a double chest counts. */
    private boolean isOpened(TileEntityChest chest) {
        if (this.opened.contains(chest.getPos())) return true;
        chest.checkForAdjacentChests();
        return chest.adjacentChestXNeg != null && this.opened.contains(chest.adjacentChestXNeg.getPos())
                || chest.adjacentChestXPos != null && this.opened.contains(chest.adjacentChestXPos.getPos())
                || chest.adjacentChestZNeg != null && this.opened.contains(chest.adjacentChestZNeg.getPos())
                || chest.adjacentChestZPos != null && this.opened.contains(chest.adjacentChestZPos.getPos());
    }

    private static AxisAlignedBB chestBox(BlockPos pos) {
        return new AxisAlignedBB(pos.getX() + 0.0625, pos.getY(), pos.getZ() + 0.0625,
                pos.getX() + 0.9375, pos.getY() + 0.875, pos.getZ() + 0.9375);
    }
}
