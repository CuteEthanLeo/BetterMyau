package myau.module.modules;

import myau.Myau;
import myau.event.EventTarget;
import myau.event.types.EventType;
import myau.events.PacketEvent;
import myau.events.UpdateEvent;
import myau.mixin.IAccessorEntityPlayer;
import myau.module.Module;
import myau.property.properties.BooleanProperty;
import myau.property.properties.FloatProperty;
import myau.property.properties.ModeProperty;
import myau.util.MoveUtil;
import net.minecraft.block.Block;
import net.minecraft.block.BlockSlab;
import net.minecraft.block.BlockSlime;
import net.minecraft.block.BlockStairs;
import net.minecraft.block.state.IBlockState;
import net.minecraft.client.Minecraft;
import net.minecraft.client.entity.EntityPlayerSP;
import net.minecraft.init.Blocks;
import net.minecraft.network.play.server.S08PacketPlayerPosLook;
import net.minecraft.util.AxisAlignedBB;
import net.minecraft.util.BlockPos;


public class BufferSpeed extends Module {
    private static final Minecraft mc = Minecraft.getMinecraft();
    private static final int OLD = 0, NEW = 1;
    private static final String[] OLD_NEW = new String[]{"Old", "New"};

    public final BooleanProperty speedLimit = new BooleanProperty("speed-limit", true);
    public final FloatProperty maxSpeed = new FloatProperty("max-speed", 2.0F, 1.0F, 5.0F, () -> this.speedLimit.getValue());

    public final BooleanProperty buffer = new BooleanProperty("buffer", true);

    public final BooleanProperty stairs = new BooleanProperty("stairs", true);
    public final ModeProperty stairsMode = new ModeProperty("stairs-mode", NEW, OLD_NEW, () -> this.stairs.getValue());
    public final FloatProperty stairsBoost = new FloatProperty(
            "stairs-boost", 1.87F, 1.0F, 2.0F, () -> this.stairs.getValue() && this.stairsMode.getValue() == OLD
    );

    public final BooleanProperty slabs = new BooleanProperty("slabs", true);
    public final ModeProperty slabsMode = new ModeProperty("slabs-mode", NEW, OLD_NEW, () -> this.slabs.getValue());
    public final FloatProperty slabsBoost = new FloatProperty(
            "slabs-boost", 1.87F, 1.0F, 2.0F, () -> this.slabs.getValue() && this.slabsMode.getValue() == OLD
    );

    public final BooleanProperty ice = new BooleanProperty("ice", false);
    public final FloatProperty iceBoost = new FloatProperty("ice-boost", 1.342F, 1.0F, 2.0F, () -> this.ice.getValue());

    public final BooleanProperty snow = new BooleanProperty("snow", true);
    public final FloatProperty snowBoost = new FloatProperty("snow-boost", 1.87F, 1.0F, 2.0F, () -> this.snow.getValue());
    public final BooleanProperty snowPort = new BooleanProperty("snow-port", true, () -> this.snow.getValue());

    public final BooleanProperty wall = new BooleanProperty("wall", true);
    public final ModeProperty wallMode = new ModeProperty("wall-mode", NEW, OLD_NEW, () -> this.wall.getValue());
    public final FloatProperty wallBoost = new FloatProperty(
            "wall-boost", 1.87F, 1.0F, 2.0F, () -> this.wall.getValue() && this.wallMode.getValue() == OLD
    );

    public final BooleanProperty headBlock = new BooleanProperty("head-block", true);
    public final FloatProperty headBlockBoost = new FloatProperty("head-block-boost", 1.87F, 1.0F, 2.0F, () -> this.headBlock.getValue());

    public final BooleanProperty slime = new BooleanProperty("slime", true);
    public final BooleanProperty airStrafe = new BooleanProperty("air-strafe", false);
    public final BooleanProperty noHurt = new BooleanProperty("no-hurt", true);

    private double speed = 0.0;
    private boolean down = false;
    private boolean forceDown = false;
    private boolean fastHop = false;
    private boolean hadFastHop = false;
    private boolean legitHop = false;

    public BufferSpeed() {
        super("BufferSpeed", false);
    }

    private static void setSpeedInAir(EntityPlayerSP player, float value) {
        ((IAccessorEntityPlayer) player).setSpeedInAir(value);
    }

    private Block blockAt(BlockPos pos) {
        return mc.theWorld.getBlockState(pos).getBlock();
    }

    @EventTarget
    public void onUpdate(UpdateEvent event) {
        if (!this.isEnabled() || event.getType() != EventType.PRE) {
            return;
        }
        EntityPlayerSP player = mc.thePlayer;
        if (player == null || mc.theWorld == null) {
            return;
        }

        Module speedModule = Myau.moduleManager.modules.get(Speed.class);
        if (speedModule != null && speedModule.isEnabled() || this.noHurt.getValue() && player.hurtTime > 0) {
            this.reset();
            return;
        }

        BlockPos blockPos = new BlockPos(player);

        if (this.forceDown || this.down && player.motionY == 0.0) {
            player.motionY = -1.0;
            this.down = false;
            this.forceDown = false;
        }

        if (this.fastHop) {
            setSpeedInAir(player, 0.0211F);
            this.hadFastHop = true;
        } else if (this.hadFastHop) {
            setSpeedInAir(player, 0.02F);
            this.hadFastHop = false;
        }

        if (!MoveUtil.isMoving() || player.isSneaking() || player.isInWater() || mc.gameSettings.keyBindJump.isKeyDown()) {
            this.reset();
            return;
        }

        if (player.onGround) {
            this.fastHop = false;

            if (this.slime.getValue() && (this.blockAt(blockPos.down()) instanceof BlockSlime || this.blockAt(blockPos) instanceof BlockSlime)) {
                MoveUtil.tryJump();

                // Kept exactly as in xw (it derives the horizontal motion from motionY)
                player.motionX = player.motionY * 1.132;
                player.motionY = 0.08;
                player.motionZ = player.motionY * 1.132;

                this.down = true;
                return;
            }
            if (this.slabs.getValue() && this.blockAt(blockPos) instanceof BlockSlab) {
                if (this.slabsMode.getValue() == OLD) {
                    this.boost(this.slabsBoost.getValue());
                } else {
                    this.newHop(player);
                }
                return;
            }
            if (this.stairs.getValue() && (this.blockAt(blockPos.down()) instanceof BlockStairs || this.blockAt(blockPos) instanceof BlockStairs)) {
                if (this.stairsMode.getValue() == OLD) {
                    this.boost(this.stairsBoost.getValue());
                } else {
                    this.newHop(player);
                }
                return;
            }
            this.legitHop = true;

            if (this.headBlock.getValue() && this.blockAt(blockPos.up(2)) != Blocks.air) {
                this.boost(this.headBlockBoost.getValue());
                return;
            }

            Block below = this.blockAt(blockPos.down());
            if (this.ice.getValue() && (below == Blocks.ice || below == Blocks.packed_ice)) {
                this.boost(this.iceBoost.getValue());
                return;
            }

            if (this.snow.getValue() && this.blockAt(blockPos) == Blocks.snow_layer
                    && (this.snowPort.getValue() || player.posY - (int) player.posY >= 0.12500)) {
                if (player.posY - (int) player.posY >= 0.12500) {
                    this.boost(this.snowBoost.getValue());
                } else {
                    MoveUtil.tryJump();
                    this.forceDown = true;
                }
                return;
            }

            if (this.wall.getValue()) {
                if (this.wallMode.getValue() == OLD) {
                    if (player.isCollidedVertically && this.isNearBlock() || this.blockAt(new BlockPos(player).up(2)) != Blocks.air) {
                        this.boost(this.wallBoost.getValue());
                        return;
                    }
                } else if (this.isNearBlock() && !player.movementInput.jump) {
                    MoveUtil.tryJump();
                    player.motionY = 0.08;
                    player.motionX *= 0.99;
                    player.motionZ *= 0.99;
                    this.down = true;
                    return;
                }
            }

            if (this.buffer.getValue() && this.speed > 0.2) {
                this.speed /= 1.0199999809265137;
                MoveUtil.strafe();
            }
        } else {
            this.speed = 0.0;

            if (this.airStrafe.getValue()) {
                MoveUtil.strafe();
            }
        }
    }

    /** "New" stairs / slabs mode: a low hop with slightly raised air speed. */
    private void newHop(EntityPlayerSP player) {
        this.fastHop = true;
        if (this.legitHop) {
            MoveUtil.tryJump();
            player.onGround = false;
            this.legitHop = false;
            return;
        }
        player.onGround = false;

        MoveUtil.strafe(0.375F);

        MoveUtil.tryJump();
        player.motionY = 0.41;
    }

    @EventTarget
    public void onPacket(PacketEvent event) {
        if (!this.isEnabled() || event.getType() != EventType.RECEIVE) {
            return;
        }
        if (event.getPacket() instanceof S08PacketPlayerPosLook) {
            this.speed = 0.0;
        }
    }

    @Override
    public void onEnabled() {
        this.reset();
    }

    @Override
    public void onDisabled() {
        this.reset();
        this.down = false;
        this.forceDown = false;
        this.fastHop = false;
    }

    private void reset() {
        EntityPlayerSP player = mc.thePlayer;
        if (player == null) {
            return;
        }
        this.legitHop = true;
        this.speed = 0.0;

        if (this.hadFastHop) {
            setSpeedInAir(player, 0.02F);
            this.hadFastHop = false;
        }
    }

    private void boost(float boost) {
        EntityPlayerSP player = mc.thePlayer;

        player.motionX *= boost;
        player.motionZ *= boost;

        this.speed = (float) MoveUtil.getSpeed();

        if (this.speedLimit.getValue() && this.speed > this.maxSpeed.getValue()) {
            this.speed = this.maxSpeed.getValue().doubleValue();
        }
    }

    private boolean isNearBlock() {
        EntityPlayerSP player = mc.thePlayer;
        BlockPos[] blocks = new BlockPos[]{
                new BlockPos(player.posX, player.posY + 1, player.posZ - 0.7),
                new BlockPos(player.posX + 0.7, player.posY + 1, player.posZ),
                new BlockPos(player.posX, player.posY + 1, player.posZ + 0.7),
                new BlockPos(player.posX - 0.7, player.posY + 1, player.posZ)
        };

        for (BlockPos blockPos : blocks) {
            IBlockState blockState = mc.theWorld.getBlockState(blockPos);
            Block block = blockState.getBlock();

            AxisAlignedBB collisionBoundingBox = block.getCollisionBoundingBox(mc.theWorld, blockPos, blockState);

            // Condition kept exactly as in xw
            if ((collisionBoundingBox == null || collisionBoundingBox.maxX == collisionBoundingBox.minY + 1)
                    && !block.isTranslucent() && block == Blocks.water && !(block instanceof BlockSlab)
                    || block == Blocks.barrier) {
                return true;
            }
        }
        return false;
    }
}
