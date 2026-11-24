package myau.module.modules;

import myau.event.EventTarget;
import myau.events.BlockBBEvent;
import myau.events.MoveEvent;
import myau.mixin.IAccessorEntity;
import myau.module.Module;
import myau.property.properties.FloatProperty;
import myau.property.properties.IntProperty;
import myau.property.properties.ModeProperty;
import myau.util.PacketUtil;
import net.minecraft.block.Block;
import net.minecraft.block.BlockLadder;
import net.minecraft.block.BlockVine;
import net.minecraft.block.state.IBlockState;
import net.minecraft.client.Minecraft;
import net.minecraft.client.entity.EntityPlayerSP;
import net.minecraft.network.play.client.C03PacketPlayer.C04PacketPlayerPosition;
import net.minecraft.util.AxisAlignedBB;
import net.minecraft.util.BlockPos;
import net.minecraft.util.EnumFacing;
import net.minecraft.util.MathHelper;

public class FastClimb extends Module {
    private static final Minecraft mc = Minecraft.getMinecraft();
    private static final int VANILLA = 0, DELAY = 1, CLIP = 2, AAC300 = 3, AAC305 = 4, SAAC312 = 5, AAC312 = 6;
    /** Ladder thickness used by AAC3.0.0 (vanilla: 0.125). */
    private static final double AAC300_LADDER_THICKNESS = 0.99F;

    public final ModeProperty mode = new ModeProperty(
            "mode", VANILLA, new String[]{"Vanilla", "Delay", "Clip", "AAC3.0.0", "AAC3.0.5", "SAAC3.1.2", "AAC3.1.2"}
    );
    public final FloatProperty speed = new FloatProperty("speed", 1.0F, 0.01F, 5.0F, () -> this.mode.getValue() == VANILLA);
    // Delay mode | Separated Vanilla & Delay speed value
    public final FloatProperty climbSpeed = new FloatProperty("climb-speed", 1.0F, 0.01F, 5.0F, () -> this.mode.getValue() == DELAY);
    public final IntProperty tickDelay = new IntProperty("tick-delay", 10, 1, 20, () -> this.mode.getValue() == DELAY);

    private int climbCount = 0;

    public FastClimb() {
        super("FastClimb", false);
    }

    @Override
    public void onDisabled() {
        this.climbCount = 0;
    }

    private void playerClimb(EntityPlayerSP player) {
        player.motionY = 0.0;
        player.onGround = true;
        ((IAccessorEntity) player).setIsInWeb(false);
    }

    @EventTarget
    public void onMove(MoveEvent event) {
        if (!this.isEnabled()) {
            return;
        }
        EntityPlayerSP player = mc.thePlayer;
        if (player == null || mc.theWorld == null) {
            return;
        }
        switch (this.mode.getValue()) {
            case VANILLA:
                if (player.isCollidedHorizontally && player.isOnLadder()) {
                    event.setY(this.speed.getValue().doubleValue());
                    player.motionY = 0.0;
                }
                break;
            case DELAY:
                if (player.isCollidedHorizontally && player.isOnLadder()) {
                    if (this.climbCount >= this.tickDelay.getValue()) {
                        event.setY(this.climbSpeed.getValue().doubleValue());
                        this.playerClimb(player);

                        PacketUtil.sendPacket(new C04PacketPlayerPosition(player.posX, player.posY, player.posZ, true));

                        this.climbCount = 0;
                    } else {
                        player.posY = player.prevPosY;

                        this.playerClimb(player);
                        this.climbCount += 1;
                    }
                }
                break;
            case AAC300:
                if (player.isCollidedHorizontally) {
                    double x = 0.0;
                    double z = 0.0;

                    switch (player.getHorizontalFacing()) {
                        case NORTH:
                            z = -0.99;
                            break;
                        case EAST:
                            x = 0.99;
                            break;
                        case SOUTH:
                            z = 0.99;
                            break;
                        case WEST:
                            x = -0.99;
                            break;
                        default:
                            break;
                    }

                    Block block = mc.theWorld.getBlockState(new BlockPos(player.posX + x, player.posY, player.posZ + z)).getBlock();

                    if (block instanceof BlockLadder || block instanceof BlockVine) {
                        event.setY(0.5);
                        player.motionY = 0.0;
                    }
                }
                break;
            case AAC305:
                if (mc.gameSettings.keyBindForward.isKeyDown() && this.isTouchingClimbable(player)) {
                    event.setX(0.0);
                    event.setY(0.5);
                    event.setZ(0.0);

                    player.motionX = 0.0;
                    player.motionY = 0.0;
                    player.motionZ = 0.0;
                }
                break;
            case SAAC312:
                if (player.isCollidedHorizontally && player.isOnLadder()) {
                    event.setY(0.1649);
                    player.motionY = 0.0;
                }
                break;
            case AAC312:
                if (player.isCollidedHorizontally && player.isOnLadder()) {
                    event.setY(0.1699);
                    player.motionY = 0.0;
                }
                break;
            case CLIP:
                if (player.isOnLadder() && mc.gameSettings.keyBindForward.isKeyDown()) {
                    int startY = (int) player.posY;
                    for (int i = startY; i <= startY + 8; i++) {
                        Block block = mc.theWorld.getBlockState(new BlockPos(player.posX, (double) i, player.posZ)).getBlock();

                        if (!(block instanceof BlockLadder)) {
                            double x = 0.0;
                            double z = 0.0;

                            switch (player.getHorizontalFacing()) {
                                case NORTH:
                                    z = -1.0;
                                    break;
                                case EAST:
                                    x = 1.0;
                                    break;
                                case SOUTH:
                                    z = 1.0;
                                    break;
                                case WEST:
                                    x = -1.0;
                                    break;
                                default:
                                    break;
                            }

                            player.setPosition(player.posX + x, i, player.posZ + z);
                            break;
                        } else {
                            player.setPosition(player.posX, i, player.posZ);
                        }
                    }
                }
                break;
            default:
                break;
        }
    }

    @EventTarget
    public void onBlockBB(BlockBBEvent event) {
        if (!this.isEnabled()) {
            return;
        }
        int mode = this.mode.getValue();
        if (mode != AAC300 && mode != AAC305) {
            return;
        }
        EntityPlayerSP player = mc.thePlayer;
        if (player == null || mc.theWorld == null) {
            return;
        }
        Block block = event.getBlock();
        if (mode == AAC305) {
            if ((block instanceof BlockLadder || block instanceof BlockVine) && player.isOnLadder()) {
                event.setBoundingBox(null);
            }
            return;
        }
        // AAC3.0.0: xw thickens the ladder (bounds constant 0.125 -> 0.99) so that walking into it counts as a
        // horizontal collision. Only the collision box the local player is tested against is replaced here.
        if (block instanceof BlockLadder) {
            IBlockState state = mc.theWorld.getBlockState(event.getPos());
            if (!(state.getBlock() instanceof BlockLadder)) {
                return;
            }
            double f = AAC300_LADDER_THICKNESS;
            double x = event.getX();
            double y = event.getY();
            double z = event.getZ();
            switch (state.getValue(BlockLadder.FACING)) {
                case NORTH:
                    event.setBoundingBox(new AxisAlignedBB(x, y, z + 1.0 - f, x + 1.0, y + 1.0, z + 1.0));
                    break;
                case SOUTH:
                    event.setBoundingBox(new AxisAlignedBB(x, y, z, x + 1.0, y + 1.0, z + f));
                    break;
                case WEST:
                    event.setBoundingBox(new AxisAlignedBB(x + 1.0 - f, y, z, x + 1.0, y + 1.0, z + 1.0));
                    break;
                case EAST:
                default:
                    event.setBoundingBox(new AxisAlignedBB(x, y, z, x + f, y + 1.0, z + 1.0));
                    break;
            }
        }
    }

    /** True when the player's box overlaps the collision box of a ladder or vine at foot height. */
    private boolean isTouchingClimbable(EntityPlayerSP player) {
        AxisAlignedBB playerBox = player.getEntityBoundingBox();
        int y = MathHelper.floor_double(playerBox.minY);
        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos(0, 0, 0);
        for (int x = MathHelper.floor_double(playerBox.minX); x < MathHelper.floor_double(playerBox.maxX) + 1; x++) {
            for (int z = MathHelper.floor_double(playerBox.minZ); z < MathHelper.floor_double(playerBox.maxZ) + 1; z++) {
                pos.set(x, y, z);
                IBlockState state = mc.theWorld.getBlockState(pos);
                Block block = state.getBlock();
                if (block instanceof BlockLadder || block instanceof BlockVine) {
                    AxisAlignedBB box = block.getCollisionBoundingBox(mc.theWorld, pos, state);
                    if (box != null && playerBox.intersectsWith(box)) {
                        return true;
                    }
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
