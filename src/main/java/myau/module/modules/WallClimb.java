package myau.module.modules;

import myau.event.EventTarget;
import myau.event.types.EventType;
import myau.events.BlockBBEvent;
import myau.events.LoadWorldEvent;
import myau.events.MoveEvent;
import myau.events.PacketEvent;
import myau.events.UpdateEvent;
import myau.mixin.IAccessorC03PacketPlayer;
import myau.module.Module;
import myau.property.properties.FloatProperty;
import myau.property.properties.ModeProperty;
import myau.util.MoveUtil;
import net.minecraft.block.Block;
import net.minecraft.block.state.IBlockState;
import net.minecraft.client.Minecraft;
import net.minecraft.client.entity.EntityPlayerSP;
import net.minecraft.init.Blocks;
import net.minecraft.network.play.client.C03PacketPlayer;
import net.minecraft.util.AxisAlignedBB;
import net.minecraft.util.BlockPos;
import net.minecraft.util.MathHelper;

public class WallClimb extends Module {
    private static final Minecraft mc = Minecraft.getMinecraft();
    private static final int SIMPLE = 0, CHECKER_CLIMB = 1, CLIP = 2, AAC3312 = 3, AAC_GLIDE = 4;
    private static final int CLIP_JUMP = 0, CLIP_FAST = 1;

    public final ModeProperty mode = new ModeProperty("mode", SIMPLE, new String[]{"Simple", "CheckerClimb", "Clip", "AAC3.3.12", "AACGlide"});
    public final ModeProperty clipMode = new ModeProperty("clip-mode", CLIP_FAST, new String[]{"Jump", "Fast"}, () -> this.mode.getValue() == CLIP);
    public final FloatProperty checkerClimbMotion = new FloatProperty(
            "checker-climb-motion", 0.0F, 0.0F, 1.0F, () -> this.mode.getValue() == CHECKER_CLIMB
    );

    private boolean glitch = false;
    private int waited = 0;

    public WallClimb() {
        super("WallClimb", false);
    }

    @Override
    public void onDisabled() {
        this.glitch = false;
        this.waited = 0;
    }

    @EventTarget
    public void onLoadWorld(LoadWorldEvent event) {
        this.glitch = false;
        this.waited = 0;
    }

    @EventTarget
    public void onMove(MoveEvent event) {
        if (!this.isEnabled()) {
            return;
        }
        EntityPlayerSP player = mc.thePlayer;
        if (player == null) {
            return;
        }
        if (!player.isCollidedHorizontally || player.isOnLadder() || MoveUtil.isInLiquid()) {
            return;
        }
        if (this.mode.getValue() == SIMPLE) {
            event.setY(0.2);
            player.motionY = 0.0;
        }
    }

    @EventTarget
    public void onUpdate(UpdateEvent event) {
        if (!this.isEnabled() || event.getType() != EventType.POST) {
            return;
        }
        EntityPlayerSP player = mc.thePlayer;
        if (player == null || mc.theWorld == null) {
            return;
        }
        switch (this.mode.getValue()) {
            case CLIP:
                if (player.motionY < 0.0) {
                    this.glitch = true;
                }
                if (player.isCollidedHorizontally) {
                    if (this.clipMode.getValue() == CLIP_JUMP) {
                        if (player.onGround) {
                            MoveUtil.tryJump();
                        }
                    } else {
                        if (player.onGround) {
                            player.motionY = 0.42;
                        } else if (player.motionY < 0.0) {
                            player.motionY = -0.3;
                        }
                    }
                }
                break;
            case CHECKER_CLIMB:
                float motion = this.checkerClimbMotion.getValue();
                if (motion != 0.0F && this.isInsideBlock(player)) {
                    player.motionY = motion;
                }
                break;
            case AAC3312:
                if (player.isCollidedHorizontally && !player.isOnLadder()) {
                    this.waited++;
                    if (this.waited == 1) {
                        player.motionY = 0.43;
                    }
                    if (this.waited == 12) {
                        player.motionY = 0.43;
                    }
                    if (this.waited == 23) {
                        player.motionY = 0.43;
                    }
                    if (this.waited == 29) {
                        player.setPosition(player.posX, player.posY + 0.5, player.posZ);
                    }
                    if (this.waited >= 30) {
                        this.waited = 0;
                    }
                } else if (player.onGround) {
                    this.waited = 0;
                }
                break;
            case AAC_GLIDE:
                if (!player.isCollidedHorizontally || player.isOnLadder()) {
                    return;
                }
                player.motionY = -0.19;
                break;
            default:
                break;
        }
    }

    @EventTarget
    public void onPacket(PacketEvent event) {
        if (!this.isEnabled() || event.getType() != EventType.SEND || !this.glitch) {
            return;
        }
        if (mc.thePlayer == null || !(event.getPacket() instanceof C03PacketPlayer)) {
            return;
        }
        C03PacketPlayer packet = (C03PacketPlayer) event.getPacket();
        double yaw = MoveUtil.getDirection();
        ((IAccessorC03PacketPlayer) packet).setX(packet.getPositionX() - Math.sin(yaw) * 0.00000001);
        ((IAccessorC03PacketPlayer) packet).setZ(packet.getPositionZ() + Math.cos(yaw) * 0.00000001);
        this.glitch = false;
    }

    @EventTarget
    public void onBlockBB(BlockBBEvent event) {
        if (!this.isEnabled()) {
            return;
        }
        EntityPlayerSP player = mc.thePlayer;
        if (player == null) {
            return;
        }
        switch (this.mode.getValue()) {
            case CHECKER_CLIMB:
                if (event.getY() > player.posY) {
                    event.setBoundingBox(null);
                }
                break;
            case CLIP:
                if (event.getBlock() == Blocks.air && event.getY() < player.posY && player.isCollidedHorizontally && !player.isOnLadder()
                        && !MoveUtil.isInLiquid()) {
                    event.setBoundingBox(
                            AxisAlignedBB.fromBounds(0.0, 0.0, 0.0, 1.0, 1.0, 1.0).offset(player.posX, (int) player.posY - 1.0, player.posZ)
                    );
                }
                break;
            default:
                break;
        }
    }

    /** True when the player's box overlaps the collision box of a block at foot height. */
    private boolean isInsideBlock(EntityPlayerSP player) {
        AxisAlignedBB playerBox = player.getEntityBoundingBox();
        int y = MathHelper.floor_double(playerBox.minY);
        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos(0, 0, 0);
        for (int x = MathHelper.floor_double(playerBox.minX); x < MathHelper.floor_double(playerBox.maxX) + 1; x++) {
            for (int z = MathHelper.floor_double(playerBox.minZ); z < MathHelper.floor_double(playerBox.maxZ) + 1; z++) {
                pos.set(x, y, z);
                IBlockState state = mc.theWorld.getBlockState(pos);
                Block block = state.getBlock();
                if (block == Blocks.air) {
                    continue;
                }
                AxisAlignedBB box = block.getCollisionBoundingBox(mc.theWorld, pos, state);
                if (box != null && playerBox.intersectsWith(box)) {
                    return true;
                }
            }
        }
        return false;
    }

    @Override
    public String[] getSuffix() {
        return new String[]{this.mode.getModeString()};
    }
}
