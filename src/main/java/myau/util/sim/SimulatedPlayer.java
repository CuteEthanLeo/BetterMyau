package myau.util.sim;

import com.google.common.base.Predicate;
import com.google.common.collect.Lists;
import myau.Myau;
import myau.mixin.IAccessorEntity;
import myau.mixin.IAccessorEntityLivingBase;
import myau.module.modules.NoJumpDelay;
import net.minecraft.block.Block;
import net.minecraft.block.BlockFence;
import net.minecraft.block.BlockFenceGate;
import net.minecraft.block.BlockLiquid;
import net.minecraft.block.BlockSlime;
import net.minecraft.block.BlockSoulSand;
import net.minecraft.block.BlockWall;
import net.minecraft.block.BlockWeb;
import net.minecraft.block.material.Material;
import net.minecraft.block.state.IBlockState;
import net.minecraft.client.Minecraft;
import net.minecraft.client.entity.EntityPlayerSP;
import net.minecraft.enchantment.EnchantmentHelper;
import net.minecraft.enchantment.EnchantmentProtection;
import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityLivingBase;
import net.minecraft.entity.SharedMonsterAttributes;
import net.minecraft.entity.ai.attributes.BaseAttributeMap;
import net.minecraft.entity.ai.attributes.IAttribute;
import net.minecraft.entity.ai.attributes.IAttributeInstance;
import net.minecraft.entity.ai.attributes.ServersideAttributeMap;
import net.minecraft.entity.item.EntityBoat;
import net.minecraft.entity.item.EntityMinecart;
import net.minecraft.entity.player.PlayerCapabilities;
import net.minecraft.init.Blocks;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.potion.Potion;
import net.minecraft.potion.PotionEffect;
import net.minecraft.util.AxisAlignedBB;
import net.minecraft.util.BlockPos;
import net.minecraft.util.EntitySelectors;
import net.minecraft.util.FoodStats;
import net.minecraft.util.MathHelper;
import net.minecraft.util.MovementInput;
import net.minecraft.util.Vec3;
import net.minecraft.world.World;
import net.minecraft.world.biome.BiomeGenBase;
import net.minecraft.world.border.WorldBorder;
import net.minecraft.world.chunk.Chunk;
import net.minecraft.world.chunk.IChunkProvider;
import net.minecraftforge.common.ForgeModContainer;

import java.util.List;

/**
 * A copy of the local player's movement state that can be ticked ahead without touching the
 * real player. Compatible with the client user ONLY. Useful for predicting movement ticks ahead.
 * <p>
 * Usage: {@code SimulatedPlayer sim = SimulatedPlayer.fromClientPlayer(mc.thePlayer.movementInput);}
 * then call {@link #tick()} once per predicted tick and read the public state.
 */
@SuppressWarnings({"unused", "SameParameterValue"})
public class SimulatedPlayer {
    private static final Minecraft mc = Minecraft.getMinecraft();
    private static final float SPEED_IN_AIR = 0.02F;

    private final EntityPlayerSP player;
    public AxisAlignedBB box;
    public MovementInput movementInput;
    private int jumpTicks;
    public double motionZ;
    public double motionY;
    public double motionX;
    public boolean inWater;
    public boolean onGround;
    private boolean isAirBorne;
    public float rotationYaw;
    public double posX;
    public double posY;
    public double posZ;
    private final PlayerCapabilities capabilities;
    private final Entity ridingEntity;
    private float jumpMovementFactor;
    private final World worldObj;
    public boolean isCollidedHorizontally;
    public boolean isCollidedVertically;
    private final WorldBorder worldBorder;
    private final IChunkProvider chunkProvider;
    private boolean isOutsideBorder;
    private final Entity riddenByEntity;
    private BaseAttributeMap attributeMap;
    private final boolean isSpectator;
    public float fallDistance;
    private final float stepHeight;
    public boolean isCollided;
    private int fire;
    private float distanceWalkedModified;
    private float distanceWalkedOnStepModified;
    private int nextStepDistance;
    private final float height;
    private final float width;
    private final int fireResistance;
    public boolean isInWeb;
    private boolean noClip;
    private boolean isSprinting;
    private final FoodStats foodStats;

    private float moveForward = 0.0F;
    private float moveStrafing = 0.0F;
    private boolean isJumping = false;

    /** Treat the simulated player as sneaking for edge detection (stops at block edges). */
    public boolean safeWalk = false;

    private SimulatedPlayer(EntityPlayerSP player, MovementInput movementInput, PlayerCapabilities capabilities, FoodStats foodStats) {
        this.player = player;
        this.box = player.getEntityBoundingBox();
        this.movementInput = movementInput;
        this.jumpTicks = ((IAccessorEntityLivingBase) player).getJumpTicks();
        this.motionZ = player.motionZ;
        this.motionY = player.motionY;
        this.motionX = player.motionX;
        this.inWater = player.isInWater();
        this.onGround = player.onGround;
        this.isAirBorne = player.isAirBorne;
        this.rotationYaw = player.rotationYaw;
        this.posX = player.posX;
        this.posY = player.posY;
        this.posZ = player.posZ;
        this.capabilities = capabilities;
        this.ridingEntity = player.ridingEntity;
        this.jumpMovementFactor = player.jumpMovementFactor;
        this.worldObj = player.worldObj;
        this.isCollidedHorizontally = player.isCollidedHorizontally;
        this.isCollidedVertically = player.isCollidedVertically;
        this.worldBorder = player.worldObj.getWorldBorder();
        this.chunkProvider = player.worldObj.getChunkProvider();
        this.isOutsideBorder = player.isOutsideBorder();
        this.riddenByEntity = player.riddenByEntity;
        this.attributeMap = player.getAttributeMap();
        this.isSpectator = player.isSpectator();
        this.fallDistance = player.fallDistance;
        this.stepHeight = player.stepHeight;
        this.isCollided = player.isCollided;
        // Entity.fire is private and has no getter. On a client world it is reset to 0 at the
        // start of every tick (see onEntityUpdate), so the starting value never matters.
        this.fire = 0;
        this.distanceWalkedModified = player.distanceWalkedModified;
        this.distanceWalkedOnStepModified = player.distanceWalkedOnStepModified;
        // Entity.nextStepDistance is private; it only times footstep sounds, so it is kept locally.
        this.nextStepDistance = (int) player.distanceWalkedOnStepModified + 1;
        this.height = player.height;
        this.width = player.width;
        this.fireResistance = player.fireResistance;
        this.isInWeb = ((IAccessorEntity) player).getIsInWeb();
        this.noClip = player.noClip;
        this.isSprinting = player.isSprinting();
        this.foodStats = foodStats;
    }

    /**
     * Snapshot of the local player. {@code input} is copied, so the simulation keeps using the
     * keys as they are now even if the real input object changes later.
     */
    public static SimulatedPlayer fromClientPlayer(MovementInput input) {
        EntityPlayerSP player = mc.thePlayer;

        PlayerCapabilities capabilities = createCapabilitiesCopy(player);
        FoodStats foodStats = createFoodStatsCopy(player);

        MovementInput movementInput = new MovementInput();
        movementInput.jump = input.jump;
        movementInput.moveForward = input.moveForward;
        movementInput.moveStrafe = input.moveStrafe;
        movementInput.sneak = input.sneak;

        return new SimulatedPlayer(player, movementInput, capabilities, foodStats);
    }

    private static FoodStats createFoodStatsCopy(EntityPlayerSP player) {
        NBTTagCompound foodStatsNBT = new NBTTagCompound();
        FoodStats foodStats = new FoodStats();

        player.getFoodStats().writeNBT(foodStatsNBT);
        foodStats.readNBT(foodStatsNBT);
        return foodStats;
    }

    private static PlayerCapabilities createCapabilitiesCopy(EntityPlayerSP player) {
        NBTTagCompound capabilitiesNBT = new NBTTagCompound();
        PlayerCapabilities capabilities = new PlayerCapabilities();

        player.capabilities.writeCapabilitiesToNBT(capabilitiesNBT);
        capabilities.readCapabilitiesFromNBT(capabilitiesNBT);

        return capabilities;
    }

    public Vec3 getPos() {
        return new Vec3(this.posX, this.posY, this.posZ);
    }

    public AxisAlignedBB getBox() {
        return this.box;
    }

    /** Advances the simulation by one player tick. */
    public void tick() {
        if (!this.onEntityUpdate() || this.player.isRiding()) {
            return;
        }

        this.playerUpdate(false);
        this.clientPlayerLivingUpdate();
        this.playerUpdate(true);
    }

    private void clientPlayerLivingUpdate() {
        this.pushOutOfBlocks(this.posX - (double) this.width * 0.35, this.getEntityBoundingBox().minY + 0.5, this.posZ + (double) this.width * 0.35);
        this.pushOutOfBlocks(this.posX - (double) this.width * 0.35, this.getEntityBoundingBox().minY + 0.5, this.posZ - (double) this.width * 0.35);
        this.pushOutOfBlocks(this.posX + (double) this.width * 0.35, this.getEntityBoundingBox().minY + 0.5, this.posZ - (double) this.width * 0.35);
        this.pushOutOfBlocks(this.posX + (double) this.width * 0.35, this.getEntityBoundingBox().minY + 0.5, this.posZ + (double) this.width * 0.35);

        boolean flag3 = (float) this.foodStats.getFoodLevel() > 6.0F || this.capabilities.allowFlying;
        double f = 0.8;

        boolean shouldSprint = this.player.isSprinting();

        if (this.onGround && this.movementInput.moveForward >= f && !this.isSprinting() && flag3 && !this.player.isUsingItem()
                && !this.isPotionActive(Potion.blindness) && shouldSprint) {
            this.setSprinting(true);
        }

        if (!this.isSprinting() && this.movementInput.moveForward >= f && flag3 && !this.player.isUsingItem()
                && !this.isPotionActive(Potion.blindness) && shouldSprint) {
            this.setSprinting(true);
        }

        if (this.movementInput.sneak) {
            this.setSprinting(false);
        }

        if (this.isSprinting() && (this.movementInput.moveForward < 0.8 || this.isCollidedHorizontally || !flag3)) {
            this.setSprinting(false);
        }

        if (this.capabilities.allowFlying) {
            if (mc.playerController.isSpectatorMode()) {
                if (!this.capabilities.isFlying) {
                    this.capabilities.isFlying = true;
                }
            }
        }

        if (this.capabilities.isFlying) {
            if (this.movementInput.sneak) {
                this.motionY -= (double) (this.capabilities.getFlySpeed() * 3.0F);
            }
            if (this.movementInput.jump) {
                this.motionY += (double) (this.capabilities.getFlySpeed() * 3.0F);
            }
        }

        this.livingEntityUpdate();
    }

    private void playerUpdate(boolean post) {
        if (!post) {
            this.noClip = this.isSpectator;

            if (this.isSpectator) {
                this.onGround = false;
            }
        } else {
            this.clampPositionFromEntityPlayer();
        }
    }

    private void livingEntityUpdate() {
        if (this.jumpTicks > 0) {
            --this.jumpTicks;
        }

        if (Math.abs(this.motionX) < 0.005) {
            this.motionX = 0.0;
        }

        if (Math.abs(this.motionY) < 0.005) {
            this.motionY = 0.0;
        }

        if (Math.abs(this.motionZ) < 0.005) {
            this.motionZ = 0.0;
        }

        if (this.isMovementBlocked()) {
            this.isJumping = false;
            this.moveStrafing = 0.0F;
            this.moveForward = 0.0F;
        } else if (this.isServerWorld()) {
            this.updateLivingEntityInput();
        }

        if (this.isJumping) {
            if (this.isInWater() || this.isInLava()) {
                this.updateAITick();
            } else if (this.onGround && this.jumpTicks == 0) {
                this.jump();
                this.jumpTicks = this.getJumpDelay();
            }
        } else {
            this.jumpTicks = 0;
        }

        this.moveStrafing *= 0.98F;
        this.moveForward *= 0.98F;
        this.playerSideMoveEntityWithHeading(this.moveStrafing, this.moveForward);

        // EntityPlayer post onLivingUpdate
        this.jumpMovementFactor = SPEED_IN_AIR;
        if (this.isSprinting()) {
            this.jumpMovementFactor = (float) ((double) this.jumpMovementFactor + (double) SPEED_IN_AIR * 0.3);
        }

        // EntityPlayerSP post onLivingUpdate
        if (this.onGround && this.capabilities.isFlying && !this.isSpectator) {
            this.capabilities.isFlying = false;
        }
    }

    /**
     * Ticks until the next jump is allowed. Vanilla is 10; NoJumpDelay caps the real counter at
     * {@code delay + 1} on the following game tick, which is the same as starting from there.
     */
    private int getJumpDelay() {
        if (Myau.moduleManager != null) {
            NoJumpDelay noJumpDelay = (NoJumpDelay) Myau.moduleManager.modules.get(NoJumpDelay.class);
            if (noJumpDelay != null && noJumpDelay.isEnabled()) {
                return Math.min(10, noJumpDelay.delay.getValue() + 1);
            }
        }
        return 10;
    }

    // Entity version of onEntityUpdate
    private boolean onEntityUpdate() {
        this.handleWaterMovement();
        if (this.worldObj.isRemote) {
            this.fire = 0;
        } else if (this.fire > 0) {
            --this.fire;
        }

        if (this.isInLava()) {
            this.setOnFireFromLava();
            this.fallDistance *= 0.5F;
        }

        // If player is below world then just ignore
        return !(this.posY < -64.0);
    }

    private void clampPositionFromEntityPlayer() {
        // Post EntityPlayer onUpdate
        double d3 = MathHelper.clamp_double(this.posX, -2.9999999E7, 2.9999999E7);
        double d4 = MathHelper.clamp_double(this.posZ, -2.9999999E7, 2.9999999E7);
        if (d3 != this.posX || d4 != this.posZ) {
            this.setPosition(d3, this.posY, d4);
        }
    }

    private void setPosition(double x, double y, double z) {
        this.posX = x;
        this.posY = y;
        this.posZ = z;
        float f = this.width / 2.0F;
        float f1 = this.height;
        this.setEntityBoundingBox(new AxisAlignedBB(x - (double) f, y, z - (double) f, x + (double) f, y + (double) f1, z + (double) f));
    }

    private void setSprinting(boolean state) {
        this.isSprinting = state;
    }

    private boolean pushOutOfBlocks(double x, double y, double z) {
        if (this.noClip) {
            return false;
        }
        BlockPos blockPos = new BlockPos(x, y, z);
        double d0 = x - (double) blockPos.getX();
        double d1 = z - (double) blockPos.getZ();
        int entHeight = Math.max((int) Math.ceil(this.height), 1);
        boolean inTranslucentBlock = !this.isHeadspaceFree(blockPos, entHeight);
        if (inTranslucentBlock) {
            int i = -1;
            double d2 = 9999.0;
            if (this.isHeadspaceFree(blockPos.west(), entHeight) && d0 < d2) {
                d2 = d0;
                i = 0;
            }
            if (this.isHeadspaceFree(blockPos.east(), entHeight) && 1.0 - d0 < d2) {
                d2 = 1.0 - d0;
                i = 1;
            }
            if (this.isHeadspaceFree(blockPos.north(), entHeight) && d1 < d2) {
                d2 = d1;
                i = 4;
            }
            if (this.isHeadspaceFree(blockPos.south(), entHeight) && 1.0 - d1 < d2) {
                i = 5;
            }

            float f = 0.1F;
            if (i == 0) {
                this.motionX = -f;
            }
            if (i == 1) {
                this.motionX = f;
            }
            if (i == 4) {
                this.motionZ = -f;
            }
            if (i == 5) {
                this.motionZ = f;
            }
        }
        return false;
    }

    private boolean isHeadspaceFree(BlockPos pos, int height) {
        for (int y = 0; y < height; y++) {
            if (!this.isOpenBlockSpace(pos.add(0, y, 0))) {
                return false;
            }
        }
        return true;
    }

    private boolean isOpenBlockSpace(BlockPos pos) {
        IBlockState state = this.getBlockState(pos);
        return state != null && state.getBlock() != null && !state.getBlock().isNormalCube();
    }

    private void playerSideMoveEntityWithHeading(float moveStrafing, float moveForward) {
        if (this.capabilities.isFlying && this.ridingEntity == null) {
            double d3 = this.motionY;
            float f = this.jumpMovementFactor;
            this.jumpMovementFactor = this.capabilities.getFlySpeed() * (float) (this.isSprinting() ? 2 : 1);
            this.livingEntitySideMoveEntityWithHeading(moveStrafing, moveForward);
            this.motionY = d3 * 0.6;
            this.jumpMovementFactor = f;
        } else {
            this.livingEntitySideMoveEntityWithHeading(moveStrafing, moveForward);
        }
    }

    private void livingEntitySideMoveEntityWithHeading(float strafing, float forwards) {
        if (!this.isServerWorld()) {
            return;
        }
        double d0;
        float f3;
        float f5;
        float f6;
        if (!this.isInWater() || this.capabilities.isFlying) {
            if (this.isInLava() && !this.capabilities.isFlying) {
                d0 = this.posY;
                this.moveFlying(strafing, forwards, 0.02F);
                this.moveEntity(this.motionX, this.motionY, this.motionZ);
                this.motionX *= 0.5;
                this.motionY *= 0.5;
                this.motionZ *= 0.5;
                this.motionY -= 0.02;
                if (this.isCollidedHorizontally && this.isOffsetPositionInLiquid(this.motionX, this.motionY + 0.6000000238418579 - this.posY + d0, this.motionZ)) {
                    this.motionY = 0.30000001192092896;
                }
            } else {
                float f4 = 0.91F;
                if (this.onGround) {
                    f4 = this.worldObj.getBlockState(new BlockPos(
                            MathHelper.floor_double(this.posX),
                            MathHelper.floor_double(this.getEntityBoundingBox().minY) - 1,
                            MathHelper.floor_double(this.posZ)
                    )).getBlock().slipperiness * 0.91F;
                }

                float f = 0.16277136F / (f4 * f4 * f4);
                if (this.onGround) {
                    f5 = this.getAIMoveSpeed() * f;
                } else {
                    f5 = this.jumpMovementFactor;
                }

                this.moveFlying(strafing, forwards, f5);
                f4 = 0.91F;
                if (this.onGround) {
                    f4 = this.worldObj.getBlockState(new BlockPos(
                            MathHelper.floor_double(this.posX),
                            MathHelper.floor_double(this.getEntityBoundingBox().minY) - 1,
                            MathHelper.floor_double(this.posZ)
                    )).getBlock().slipperiness * 0.91F;
                }

                if (this.isOnLadder()) {
                    f6 = 0.15F;
                    this.motionX = MathHelper.clamp_double(this.motionX, -f6, f6);
                    this.motionZ = MathHelper.clamp_double(this.motionZ, -f6, f6);
                    this.fallDistance = 0.0F;
                    if (this.motionY < -0.15) {
                        this.motionY = -0.15;
                    }

                    boolean flag = this.isSneaking();
                    if (flag && this.motionY < 0.0) {
                        this.motionY = 0.0;
                    }
                }

                this.moveEntity(this.motionX, this.motionY, this.motionZ);
                if (this.isCollidedHorizontally && this.isOnLadder()) {
                    this.motionY = 0.2;
                }

                BlockPos columnPos = new BlockPos((int) this.posX, 0, (int) this.posZ);
                if (this.worldObj.isRemote && (!this.worldObj.isBlockLoaded(columnPos) || !this.worldObj.getChunkFromBlockCoords(columnPos).isLoaded())) {
                    if (this.posY > 0.0) {
                        this.motionY = -0.1;
                    } else {
                        this.motionY = 0.0;
                    }
                } else {
                    this.motionY -= 0.08;
                }

                this.motionY *= 0.9800000190734863;
                this.motionX *= f4;
                this.motionZ *= f4;
            }
        } else {
            d0 = this.posY;
            f5 = 0.8F;
            f6 = 0.02F;
            f3 = (float) EnchantmentHelper.getDepthStriderModifier(this.player);
            if (f3 > 3.0F) {
                f3 = 3.0F;
            }

            if (!this.onGround) {
                f3 *= 0.5F;
            }

            if (f3 > 0.0F) {
                f5 += (0.54600006F - f5) * f3 / 3.0F;
                f6 += (this.getAIMoveSpeed() * 1.0F - f6) * f3 / 3.0F;
            }

            this.moveFlying(strafing, forwards, f6);
            this.moveEntity(this.motionX, this.motionY, this.motionZ);
            this.motionX *= f5;
            this.motionY *= 0.800000011920929;
            this.motionZ *= f5;
            this.motionY -= 0.02;
            if (this.isCollidedHorizontally && this.isOffsetPositionInLiquid(this.motionX, this.motionY + 0.6000000238418579 - this.posY + d0, this.motionZ)) {
                this.motionY = 0.30000001192092896;
            }
        }
    }

    private void moveEntity(double xMotion, double yMotion, double zMotion) {
        double velocityX = xMotion;
        double velocityY = yMotion;
        double velocityZ = zMotion;
        if (this.noClip) {
            this.setEntityBoundingBox(this.getEntityBoundingBox().offset(velocityX, velocityY, velocityZ));
            this.resetPositionToBB();
            return;
        }
        double d0 = this.posX;
        double d1 = this.posY;
        double d2 = this.posZ;
        if (this.isInWeb) {
            this.isInWeb = false;
            velocityX *= 0.25;
            velocityY *= 0.05000000074505806;
            velocityZ *= 0.25;
            this.motionX = 0.0;
            this.motionY = 0.0;
            this.motionZ = 0.0;
        }
        double d3 = velocityX;
        double d4 = velocityY;
        double d5 = velocityZ;

        boolean flag = this.onGround && (this.isSneaking() || this.safeWalk);

        if (flag) {
            double[] edge = this.checkForCollision(velocityX, velocityZ);
            d3 = edge[0];
            d5 = edge[1];
        }

        List<AxisAlignedBB> list1 = this.worldObj.getCollidingBoundingBoxes(this.player, this.getEntityBoundingBox().addCoord(velocityX, velocityY, velocityZ));
        AxisAlignedBB axisalignedbb = this.getEntityBoundingBox();

        for (AxisAlignedBB axisalignedbb1 : list1) {
            velocityY = axisalignedbb1.calculateYOffset(this.getEntityBoundingBox(), velocityY);
        }

        this.setEntityBoundingBox(this.getEntityBoundingBox().offset(0.0, velocityY, 0.0));
        boolean flag1 = this.onGround || d4 != velocityY && d4 < 0.0;

        for (AxisAlignedBB axisalignedbb2 : list1) {
            velocityX = axisalignedbb2.calculateXOffset(this.getEntityBoundingBox(), velocityX);
        }

        this.setEntityBoundingBox(this.getEntityBoundingBox().offset(velocityX, 0.0, 0.0));

        for (AxisAlignedBB axisalignedbb13 : list1) {
            velocityZ = axisalignedbb13.calculateZOffset(this.getEntityBoundingBox(), velocityZ);
        }

        this.setEntityBoundingBox(this.getEntityBoundingBox().offset(0.0, 0.0, velocityZ));
        if (this.stepHeight > 0.0F && flag1 && (d3 != velocityX || d5 != velocityZ)) {
            double d11 = velocityX;
            double d7 = velocityY;
            double d8 = velocityZ;
            AxisAlignedBB axisalignedbb3 = this.getEntityBoundingBox();
            this.setEntityBoundingBox(axisalignedbb);
            velocityY = this.stepHeight;
            List<AxisAlignedBB> list = this.worldObj.getCollidingBoundingBoxes(this.player, this.getEntityBoundingBox().addCoord(d3, velocityY, d5));
            AxisAlignedBB axisalignedbb4 = this.getEntityBoundingBox();
            AxisAlignedBB axisalignedbb5 = axisalignedbb4.addCoord(d3, 0.0, d5);
            double d9 = velocityY;

            for (AxisAlignedBB axisalignedbb6 : list) {
                d9 = axisalignedbb6.calculateYOffset(axisalignedbb5, d9);
            }

            axisalignedbb4 = axisalignedbb4.offset(0.0, d9, 0.0);
            double d15 = d3;

            for (AxisAlignedBB axisalignedbb7 : list) {
                d15 = axisalignedbb7.calculateXOffset(axisalignedbb4, d15);
            }

            axisalignedbb4 = axisalignedbb4.offset(d15, 0.0, 0.0);
            double d16 = d5;

            for (AxisAlignedBB axisalignedbb8 : list) {
                d16 = axisalignedbb8.calculateZOffset(axisalignedbb4, d16);
            }

            axisalignedbb4 = axisalignedbb4.offset(0.0, 0.0, d16);
            AxisAlignedBB axisalignedbb14 = this.getEntityBoundingBox();
            double d17 = velocityY;

            for (AxisAlignedBB axisalignedbb9 : list) {
                d17 = axisalignedbb9.calculateYOffset(axisalignedbb14, d17);
            }

            axisalignedbb14 = axisalignedbb14.offset(0.0, d17, 0.0);
            double d18 = d3;

            for (AxisAlignedBB axisalignedbb10 : list) {
                d18 = axisalignedbb10.calculateXOffset(axisalignedbb14, d18);
            }

            axisalignedbb14 = axisalignedbb14.offset(d18, 0.0, 0.0);
            double d19 = d5;

            for (AxisAlignedBB axisalignedbb11 : list) {
                d19 = axisalignedbb11.calculateZOffset(axisalignedbb14, d19);
            }

            axisalignedbb14 = axisalignedbb14.offset(0.0, 0.0, d19);
            double d20 = d15 * d15 + d16 * d16;
            double d10 = d18 * d18 + d19 * d19;

            if (d20 > d10) {
                velocityX = d15;
                velocityZ = d16;
                velocityY = -d9;
                this.setEntityBoundingBox(axisalignedbb4);
            } else {
                velocityX = d18;
                velocityZ = d19;
                velocityY = -d17;
                this.setEntityBoundingBox(axisalignedbb14);
            }

            for (AxisAlignedBB axisalignedbb12 : list) {
                velocityY = axisalignedbb12.calculateYOffset(this.getEntityBoundingBox(), velocityY);
            }

            this.setEntityBoundingBox(this.getEntityBoundingBox().offset(0.0, velocityY, 0.0));

            if (d11 * d11 + d8 * d8 >= velocityX * velocityX + velocityZ * velocityZ) {
                velocityX = d11;
                velocityY = d7;
                velocityZ = d8;
                this.setEntityBoundingBox(axisalignedbb3);
            }
        }
        this.resetPositionToBB();
        this.isCollidedHorizontally = d3 != velocityX || d5 != velocityZ;
        this.isCollidedVertically = d4 != velocityY;
        this.onGround = this.isCollidedVertically && d4 < 0.0;
        this.isCollided = this.isCollidedHorizontally || this.isCollidedVertically;
        int i = MathHelper.floor_double(this.posX);
        int j = MathHelper.floor_double(this.posY - 0.20000000298023224);
        int k = MathHelper.floor_double(this.posZ);
        BlockPos blockPos = new BlockPos(i, j, k);
        Block block1 = this.worldObj.getBlockState(blockPos).getBlock();
        if (block1.getMaterial() == Material.air) {
            Block block = this.worldObj.getBlockState(blockPos.down()).getBlock();
            if (block instanceof BlockFence || block instanceof BlockWall || block instanceof BlockFenceGate) {
                block1 = block;
            }
        }
        this.updateFallState(velocityY, this.onGround);
        if (d3 != velocityX) {
            this.motionX = 0.0;
        }
        if (d5 != velocityZ) {
            this.motionZ = 0.0;
        }
        if (d4 != velocityY) {
            this.onLanded(block1);
        }
        if (this.canTriggerWalking() && !flag && this.ridingEntity == null) {
            double d12 = this.posX - d0;
            double d13 = this.posY - d1;
            double d14 = this.posZ - d2;
            if (block1 != Blocks.ladder) {
                d13 = 0.0;
            }
            if (block1 != null && this.onGround) {
                this.onEntityCollidedWithBlock(block1);
            }
            this.distanceWalkedModified = (float) ((double) this.distanceWalkedModified + (double) MathHelper.sqrt_double(d12 * d12 + d14 * d14) * 0.6);
            this.distanceWalkedOnStepModified = (float) ((double) this.distanceWalkedOnStepModified + (double) MathHelper.sqrt_double(d12 * d12 + d13 * d13 + d14 * d14) * 0.6);
            if (this.distanceWalkedOnStepModified > (float) this.nextStepDistance && block1.getMaterial() != Material.air) {
                this.nextStepDistance = (int) this.distanceWalkedOnStepModified + 1;
            }
        }

        try {
            this.doBlockCollisions();
        } catch (Throwable throwable) {
            throwable.printStackTrace();
        }

        boolean flag2 = this.isWet();

        if (this.worldObj.isFlammableWithin(this.getEntityBoundingBox().contract(0.001, 0.001, 0.001))) {
            if (!flag2) {
                ++this.fire;
                if (this.fire == 0) {
                    this.setFire(8);
                }
            }
        } else if (this.fire <= 0) {
            this.fire = -this.fireResistance;
        }

        if (flag2 && this.fire > 0) {
            this.fire = -this.fireResistance;
        }
    }

    /**
     * Sneak / safe-walk edge detection: shrinks the horizontal move until the player would still
     * stand on something. Returns {x, z}.
     */
    private double[] checkForCollision(double velocityX, double velocityZ) {
        double d6;

        double d3 = velocityX;
        double d5 = velocityZ;

        for (d6 = 0.05; velocityX != 0.0 && this.worldObj.getCollidingBoundingBoxes(this.player, this.box.offset(velocityX, -1.0, 0.0)).isEmpty(); d3 = velocityX) {
            if (velocityX < d6 && velocityX >= -d6) {
                velocityX = 0.0;
            } else if (velocityX > 0.0) {
                velocityX -= d6;
            } else {
                velocityX += d6;
            }
        }

        for (; velocityZ != 0.0 && this.worldObj.getCollidingBoundingBoxes(this.player, this.box.offset(0.0, -1.0, velocityZ)).isEmpty(); d5 = velocityZ) {
            if (velocityZ < d6 && velocityZ >= -d6) {
                velocityZ = 0.0;
            } else if (velocityZ > 0.0) {
                velocityZ -= d6;
            } else {
                velocityZ += d6;
            }
        }

        for (; velocityX != 0.0 && velocityZ != 0.0 && this.worldObj.getCollidingBoundingBoxes(this.player, this.box.offset(velocityX, -1.0, velocityZ)).isEmpty(); d5 = velocityZ) {
            if (velocityX < d6 && velocityX >= -d6) {
                velocityX = 0.0;
            } else if (velocityX > 0.0) {
                velocityX -= d6;
            } else {
                velocityX += d6;
            }

            d3 = velocityX;

            if (velocityZ < d6 && velocityZ >= -d6) {
                velocityZ = 0.0;
            } else if (velocityZ > 0.0) {
                velocityZ -= d6;
            } else {
                velocityZ += d6;
            }
        }

        return new double[]{d3, d5};
    }

    private AxisAlignedBB getEntityBoundingBox() {
        return this.box;
    }

    private void setEntityBoundingBox(AxisAlignedBB box) {
        this.box = box;
    }

    private void setOnFireFromLava() {
        this.setFire(15);
    }

    private void setFire(int seconds) {
        int i = seconds * 20;
        i = EnchantmentProtection.getFireTimeForEntity(this.player, i);
        if (this.fire < i) {
            this.fire = i;
        }
    }

    private boolean isWet() {
        return this.inWater || this.isRainingAt(new BlockPos(this.posX, this.posY, this.posZ))
                || this.isRainingAt(new BlockPos(this.posX, this.posY + (double) this.height, this.posZ));
    }

    private void doBlockCollisions() {
        BlockPos blockpos = new BlockPos(this.getEntityBoundingBox().minX + 0.001, this.getEntityBoundingBox().minY + 0.001, this.getEntityBoundingBox().minZ + 0.001);
        BlockPos blockpos1 = new BlockPos(this.getEntityBoundingBox().maxX - 0.001, this.getEntityBoundingBox().maxY - 0.001, this.getEntityBoundingBox().maxZ - 0.001);
        if (this.isAreaLoaded(blockpos.getX(), blockpos.getY(), blockpos.getZ(), blockpos1.getX(), blockpos.getY(), blockpos.getZ(), true)) {
            for (int i = blockpos.getX(); i <= blockpos1.getX(); i++) {
                for (int j = blockpos.getY(); j <= blockpos1.getY(); j++) {
                    for (int k = blockpos.getZ(); k <= blockpos1.getZ(); k++) {
                        BlockPos pos = new BlockPos(i, j, k);
                        IBlockState state = this.worldObj.getBlockState(pos);
                        try {
                            Block block = state.getBlock();
                            // We don't want things to negatively interact back to us (cactus, tripwire, tnt or whatever)
                            if (block instanceof BlockWeb) {
                                this.isInWeb = true;
                            } else if (block instanceof BlockSoulSand) {
                                this.motionX *= 0.4;
                                this.motionZ *= 0.4;
                            }
                        } catch (Throwable throwable) {
                            throwable.printStackTrace();
                        }
                    }
                }
            }
        }
    }

    private void updateFallState(double motionY, boolean onGround) {
        if (!this.isInWater()) {
            this.handleWaterMovement();
        }

        if (onGround) {
            if (this.fallDistance > 0.0F) {
                this.fallDistance = 0.0F;
            }
        } else if (motionY < 0.0) {
            this.fallDistance = (float) ((double) this.fallDistance - motionY);
        }
    }

    private boolean handleWaterMovement() {
        if (this.handleMaterialAcceleration(this.getEntityBoundingBox().expand(0.0, -0.4000000059604645, 0.0).contract(0.001, 0.001, 0.001), Material.water)) {
            this.fallDistance = 0.0F;
            this.inWater = true;
            this.fire = 0;
        } else {
            this.inWater = false;
        }

        return this.inWater;
    }

    private boolean handleMaterialAcceleration(AxisAlignedBB boundingBox, Material material) {
        int i = MathHelper.floor_double(boundingBox.minX);
        int j = MathHelper.floor_double(boundingBox.maxX + 1.0);
        int k = MathHelper.floor_double(boundingBox.minY);
        int l = MathHelper.floor_double(boundingBox.maxY + 1.0);
        int i1 = MathHelper.floor_double(boundingBox.minZ);
        int j1 = MathHelper.floor_double(boundingBox.maxZ + 1.0);
        if (!this.isAreaLoaded(i, k, i1, j, l, j1, true)) {
            return false;
        }
        boolean flag = false;
        Vec3 vec3 = new Vec3(0.0, 0.0, 0.0);
        BlockPos.MutableBlockPos blockPos = new BlockPos.MutableBlockPos();
        for (int k1 = i; k1 < j; k1++) {
            for (int l1 = k; l1 < l; l1++) {
                for (int i2 = i1; i2 < j1; i2++) {
                    blockPos.set(k1, l1, i2);
                    IBlockState state = this.getBlockState(blockPos);
                    if (state == null) {
                        continue;
                    }
                    Block block = state.getBlock();
                    if (block == null) {
                        continue;
                    }
                    if (block.getMaterial() == material) {
                        double d0 = (double) ((float) (l1 + 1) - BlockLiquid.getLiquidHeightPercent(state.getValue(BlockLiquid.LEVEL)));
                        if ((double) l >= d0) {
                            flag = true;
                            vec3 = block.modifyAcceleration(this.worldObj, blockPos, this.player, vec3);
                        }
                    }
                }
            }
        }
        if (vec3.lengthVector() > 0.0 && this.isPushedByWater()) {
            vec3 = vec3.normalize();
            double d1 = 0.014;
            this.motionX += vec3.xCoord * d1;
            this.motionY += vec3.yCoord * d1;
            this.motionZ += vec3.zCoord * d1;
        }
        return flag;
    }

    private boolean isAreaLoaded(int minX, int minY, int minZ, int maxX, int maxY, int maxZ, boolean allowEmpty) {
        if (maxY >= 0 && minY < 256) {
            int minX1 = minX >> 4;
            int minZ1 = minZ >> 4;
            int maxX1 = maxX >> 4;
            int maxZ1 = maxZ >> 4;
            for (int i = minX1; i <= maxX1; i++) {
                for (int j = minZ1; j <= maxZ1; j++) {
                    if (!this.isChunkLoaded(i, j, allowEmpty)) {
                        return false;
                    }
                }
            }
            return true;
        }
        return false;
    }

    private void onEntityCollidedWithBlock(Block block) {
        if (block instanceof BlockSlime) {
            if (Math.abs(this.motionY) < 0.1 && !this.isSneaking()) {
                double motion = 0.4 + Math.abs(this.motionY) * 0.2;

                this.motionX *= motion;
                this.motionZ *= motion;
            }
        }
    }

    private boolean canTriggerWalking() {
        return !this.capabilities.isFlying;
    }

    public boolean isOnLadder() {
        int i = MathHelper.floor_double(this.posX);
        int j = MathHelper.floor_double(this.box.minY);
        int k = MathHelper.floor_double(this.posZ);
        BlockPos pos = new BlockPos(i, j, k);
        Block block = this.worldObj.getBlockState(pos).getBlock();
        return this.isLivingOnLadder(block, this.worldObj, pos, this.player);
    }

    private void moveFlying(float strafe, float forward, float friction) {
        float newStrafe = strafe;
        float newForward = forward;
        float f = newStrafe * newStrafe + newForward * newForward;
        if (f >= 1.0E-4F) {
            f = MathHelper.sqrt_float(f);
            if (f < 1.0F) {
                f = 1.0F;
            }
            f = friction / f;
            newStrafe *= f;
            newForward *= f;
            float f1 = MathHelper.sin(this.rotationYaw * 3.1415927F / 180.0F);
            float f2 = MathHelper.cos(this.rotationYaw * 3.1415927F / 180.0F);
            this.motionX += (double) (newStrafe * f2 - newForward * f1);
            this.motionZ += (double) (newForward * f2 + newStrafe * f1);
        }
    }

    private void jump() {
        this.motionY = this.getJumpUpwardsMotion();
        if (this.isPotionActive(Potion.jump)) {
            this.motionY += (double) ((float) (this.getActivePotionEffect(Potion.jump).getAmplifier() + 1) * 0.1F);
        }

        if (this.isSprinting()) {
            float f = this.rotationYaw * 0.017453292F;
            this.motionX -= (double) (MathHelper.sin(f) * 0.2F);
            this.motionZ += (double) (MathHelper.cos(f) * 0.2F);
        }

        this.isAirBorne = true;
    }

    private boolean isSprinting() {
        return this.isSprinting;
    }

    public boolean isPotionActive(Potion potion) {
        return this.player.getActivePotionEffect(potion) != null;
    }

    public PotionEffect getActivePotionEffect(Potion potion) {
        return this.player.getActivePotionEffect(potion);
    }

    private float getJumpUpwardsMotion() {
        return 0.42F;
    }

    private boolean isInWater() {
        return this.inWater;
    }

    private void updateLivingEntityInput() {
        this.moveForward = this.movementInput.moveForward;
        this.moveStrafing = this.movementInput.moveStrafe;
        this.isJumping = this.movementInput.jump;
    }

    private boolean isServerWorld() {
        return true;
    }

    private boolean isMovementBlocked() {
        return this.player.getHealth() <= 0.0F || this.player.isPlayerSleeping();
    }

    public boolean isInLava() {
        return this.worldObj.isMaterialInBB(this.getEntityBoundingBox().expand(-0.10000000149011612, -0.4000000059604645, -0.10000000149011612), Material.lava);
    }

    private void updateAITick() {
        this.motionY += 0.03999999910593033;
    }

    private boolean isOffsetPositionInLiquid(double x, double y, double z) {
        AxisAlignedBB offsetBox = this.getEntityBoundingBox().offset(x, y, z);

        return this.isLiquidPresentInAABB(offsetBox);
    }

    private boolean isLiquidPresentInAABB(AxisAlignedBB box) {
        return this.worldObj.getCollidingBoundingBoxes(this.player, box).isEmpty() && !this.worldObj.isAnyLiquid(box);
    }

    /** Block and entity collision boxes intersecting {@code box}, evaluated at the simulated position. */
    public List<AxisAlignedBB> getCollidingBoundingBoxes(AxisAlignedBB box) {
        List<AxisAlignedBB> list = Lists.newArrayList();
        int i = MathHelper.floor_double(box.minX);
        int j = MathHelper.floor_double(box.maxX + 1.0);
        int k = MathHelper.floor_double(box.minY);
        int l = MathHelper.floor_double(box.maxY + 1.0);
        int i1 = MathHelper.floor_double(box.minZ);
        int j1 = MathHelper.floor_double(box.maxZ + 1.0);
        WorldBorder worldborder = this.getWorldBorder();
        boolean flag = this.isOutsideBorder;
        boolean flag1 = this.isInsideBorder(worldborder, flag);
        IBlockState iblockstate = Blocks.stone.getDefaultState();
        BlockPos.MutableBlockPos blockPos = new BlockPos.MutableBlockPos();
        for (int k1 = i; k1 < j; k1++) {
            for (int l1 = i1; l1 < j1; l1++) {
                if (this.isBlockLoaded(blockPos.set(k1, 64, l1))) {
                    for (int i2 = k - 1; i2 < l; i2++) {
                        blockPos.set(k1, i2, l1);
                        if (flag && flag1) {
                            this.isOutsideBorder = false;
                        } else if (!flag && !flag1) {
                            this.isOutsideBorder = true;
                        }
                        IBlockState state = iblockstate;
                        if (worldborder.contains(blockPos) || !flag1) {
                            state = this.getBlockState(blockPos);
                        }
                        state.getBlock().addCollisionBoxesToList(this.worldObj, blockPos, state, box, list, this.player);
                    }
                }
            }
        }
        double d0 = 0.25;
        List<Entity> entities = this.getEntitiesWithinAABBExcludingEntity(this.player, box.expand(d0, d0, d0));
        for (Entity entity : entities) {
            AxisAlignedBB boundingBox = entity.getCollisionBoundingBox();

            if (boundingBox != null && boundingBox.intersectsWith(box)) {
                list.add(boundingBox);
            }
            boundingBox = this.getCollisionBox(this.player, entity);
            if (boundingBox != null && boundingBox.intersectsWith(box)) {
                list.add(boundingBox);
            }
        }
        return list;
    }

    public IBlockState getBlockState(BlockPos blockPos) {
        return this.worldObj.getBlockState(blockPos);
    }

    private Chunk getChunkFromBlockCoords(BlockPos blockPos) {
        return this.getChunkFromChunkCoords(blockPos.getX() >> 4, blockPos.getZ() >> 4);
    }

    private Chunk getChunkFromChunkCoords(int x, int z) {
        return this.chunkProvider.provideChunk(x, z);
    }

    private boolean isValid(BlockPos pos) {
        return pos.getX() >= -30000000 && pos.getZ() >= -30000000 && pos.getX() < 30000000 && pos.getZ() < 30000000 && pos.getY() >= 0 && pos.getY() < 256;
    }

    private WorldBorder getWorldBorder() {
        return this.worldBorder;
    }

    private boolean isInsideBorder(WorldBorder border, boolean insideBorder) {
        double d0 = border.minX();
        double d1 = border.minZ();
        double d2 = border.maxX();
        double d3 = border.maxZ();
        if (insideBorder) {
            ++d0;
            ++d1;
            --d2;
            --d3;
        } else {
            --d0;
            --d1;
            ++d2;
            ++d3;
        }
        return this.posX > d0 && this.posX < d2 && this.posZ > d1 && this.posZ < d3;
    }

    private boolean isBlockLoaded(BlockPos pos) {
        return this.isBlockLoaded(pos, true);
    }

    private boolean isBlockLoaded(BlockPos pos, boolean allowEmpty) {
        return this.isValid(pos) && this.isChunkLoaded(pos.getX() >> 4, pos.getZ() >> 4, allowEmpty);
    }

    private boolean isChunkLoaded(int x, int z, boolean allowEmpty) {
        return this.chunkProvider.chunkExists(x, z) && (allowEmpty || !this.chunkProvider.provideChunk(x, z).isEmpty());
    }

    private List<Entity> getEntitiesWithinAABBExcludingEntity(Entity entity, AxisAlignedBB box) {
        return this.getEntitiesInAABBexcluding(entity, box, EntitySelectors.NOT_SPECTATING);
    }

    private List<Entity> getEntitiesInAABBexcluding(Entity entity, AxisAlignedBB bb, Predicate<? super Entity> predicate) {
        List<Entity> list = Lists.newArrayList();
        int i = MathHelper.floor_double((bb.minX - World.MAX_ENTITY_RADIUS) / 16.0);
        int j = MathHelper.floor_double((bb.maxX + World.MAX_ENTITY_RADIUS) / 16.0);
        int k = MathHelper.floor_double((bb.minZ - World.MAX_ENTITY_RADIUS) / 16.0);
        int l = MathHelper.floor_double((bb.maxZ + World.MAX_ENTITY_RADIUS) / 16.0);
        for (int i1 = i; i1 <= j; i1++) {
            for (int j1 = k; j1 <= l; j1++) {
                if (this.isChunkLoaded(i1, j1, true)) {
                    this.getChunkFromChunkCoords(i1, j1).getEntitiesWithinAABBForEntity(entity, bb, list, predicate);
                }
            }
        }
        return list;
    }

    private AxisAlignedBB getCollisionBox(Entity player, Entity entity) {
        if (entity instanceof EntityBoat) {
            return entity.getEntityBoundingBox();
        }
        if (entity instanceof EntityMinecart) {
            return player.getCollisionBox(entity);
        }
        return null;
    }

    private float getAIMoveSpeed() {
        return (float) this.getEntityAttribute(SharedMonsterAttributes.movementSpeed).getAttributeValue();
    }

    private IAttributeInstance getEntityAttribute(IAttribute attribute) {
        return this.getAttributeMap().getAttributeInstance(attribute);
    }

    private BaseAttributeMap getAttributeMap() {
        if (this.attributeMap == null) {
            this.attributeMap = new ServersideAttributeMap();
        }

        return this.attributeMap;
    }

    private boolean isLivingOnLadder(Block block, World world, BlockPos pos, EntityLivingBase entity) {
        if (this.isSpectator) {
            return false;
        }
        if (!ForgeModContainer.fullBoundingBoxLadders) {
            return block != null && block.isLadder(world, pos, entity);
        }
        AxisAlignedBB bb = this.box;
        int mX = MathHelper.floor_double(bb.minX);
        int mY = MathHelper.floor_double(bb.minY);
        int mZ = MathHelper.floor_double(bb.minZ);
        for (int y2 = mY; (double) y2 < bb.maxY; ++y2) {
            for (int x2 = mX; (double) x2 < bb.maxX; ++x2) {
                for (int z2 = mZ; (double) z2 < bb.maxZ; ++z2) {
                    BlockPos tmp = new BlockPos(x2, y2, z2);
                    if (world.getBlockState(tmp).getBlock().isLadder(world, tmp, entity)) {
                        return true;
                    }
                }
            }
        }
        return false;
    }

    private void resetPositionToBB() {
        this.posX = (this.getEntityBoundingBox().minX + this.getEntityBoundingBox().maxX) / 2.0;
        this.posY = this.getEntityBoundingBox().minY;
        this.posZ = (this.getEntityBoundingBox().minZ + this.getEntityBoundingBox().maxZ) / 2.0;
    }

    private void onLanded(Block block) {
        if (block instanceof BlockSlime) {
            if (this.isSneaking()) {
                this.motionY = 0.0;
            } else if (this.motionY < 0.0) {
                this.motionY = -this.motionY;
            }
        } else {
            this.motionY = 0.0;
        }
    }

    public boolean isSneaking() {
        return this.movementInput.sneak && !this.player.isPlayerSleeping();
    }

    private boolean isRainingAt(BlockPos pos) {
        if (this.worldObj.getRainStrength(1.0F) <= 0.2) {
            return false;
        }
        if (!this.canSeeSky(pos)) {
            return false;
        }
        if (this.worldObj.getPrecipitationHeight(pos).getY() > pos.getY()) {
            return false;
        }
        BiomeGenBase base = this.worldObj.getBiomeGenForCoords(pos);

        return !base.getEnableSnow() && !this.worldObj.canSnowAt(pos, false) && base.canRain();
    }

    private boolean canSeeSky(BlockPos pos) {
        return this.getChunkFromBlockCoords(pos).canSeeSky(pos);
    }

    private boolean isPushedByWater() {
        return !this.capabilities.isFlying;
    }
}
