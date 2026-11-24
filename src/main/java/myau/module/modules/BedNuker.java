package myau.module.modules;

import com.google.common.base.CaseFormat;
import myau.Myau;
import myau.enums.ChatColors;
import myau.enums.DelayModules;
import myau.event.EventTarget;
import myau.event.types.EventType;
import myau.event.types.Priority;
import myau.events.*;
import myau.management.RotationState;
import myau.mixin.IAccessorPlayerControllerMP;
import myau.module.Module;
import myau.property.properties.BooleanProperty;
import myau.property.properties.FloatProperty;
import myau.property.properties.ModeProperty;
import myau.property.properties.PercentProperty;
import myau.property.properties.TextProperty;
import myau.util.*;
import net.minecraft.block.Block;
import net.minecraft.block.BlockBed;
import net.minecraft.block.BlockBed.EnumPartType;
import net.minecraft.block.BlockDirectional;
import net.minecraft.block.material.Material;
import net.minecraft.block.state.IBlockState;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.ScaledResolution;
import net.minecraft.client.renderer.GlStateManager;
import net.minecraft.enchantment.Enchantment;
import net.minecraft.enchantment.EnchantmentHelper;
import net.minecraft.item.Item;
import net.minecraft.item.ItemEnderPearl;
import net.minecraft.item.ItemPickaxe;
import net.minecraft.item.ItemStack;
import net.minecraft.network.play.client.C07PacketPlayerDigging;
import net.minecraft.network.play.client.C07PacketPlayerDigging.Action;
import net.minecraft.network.play.client.C08PacketPlayerBlockPlacement;
import net.minecraft.network.play.client.C0APacketAnimation;
import net.minecraft.network.play.server.S02PacketChat;
import net.minecraft.network.play.server.S08PacketPlayerPosLook;
import net.minecraft.network.play.server.S12PacketEntityVelocity;
import net.minecraft.network.play.server.S27PacketExplosion;
import net.minecraft.potion.Potion;
import net.minecraft.util.BlockPos;
import net.minecraft.util.EnumFacing;
import net.minecraft.util.IChatComponent;
import net.minecraft.util.MathHelper;
import net.minecraft.util.MovingObjectPosition;
import net.minecraft.util.MovingObjectPosition.MovingObjectType;
import org.lwjgl.opengl.GL11;

import java.awt.*;
import java.util.*;

public class BedNuker extends Module {
    private static final Minecraft mc = Minecraft.getMinecraft();
    private static final int OWN_BED_SCAN_RADIUS = 25;
    private static final int OWN_BED_SCAN_DELAY = 20;
    private static final int OWN_BED_SCAN_RETRIES = 5;
    private static final double BIG_TELEPORT_DIST_SQ = 16.0 * 16.0;
    private static final long PEARL_IGNORE_MS = 3000L;
    private final TimerUtil timer = new TimerUtil();
    /** 自家床（两半都记录），只在主线程读写。 */
    private final Set<BlockPos> bedWhitelist = new HashSet<>();
    private volatile int scanGeneration = 0;
    private volatile long lastPearlMs = 0L;
    private volatile boolean firstTeleportInWorld = true;
    private boolean pendingStrong = false;
    private boolean unknownHintShown = false;
    private final Color colorRed = new Color(ChatColors.RED.toAwtColor());
    private final Color colorYellow = new Color(ChatColors.YELLOW.toAwtColor());
    private final Color colorGreen = new Color(ChatColors.GREEN.toAwtColor());
    private BlockPos targetBed = null;
    private int breakStage = 0;
    private int tickCounter = 0;
    private float breakProgress = 0.0F;
    private boolean isBed = false;
    private int savedSlot = -1;
    private boolean readyToBreak = false;
    private boolean breaking = false;
    private final HumanRotation rotation = new HumanRotation();
    private BlockPos aimBlock;
    private double aimX, aimY, aimZ;
    private boolean rotating;
    private boolean aimReady;
    public final FloatProperty turnSpeed = new FloatProperty("turn-speed", 24.0f, 5.0f, 100.0f);
    public final PercentProperty turnSmoothing = new PercentProperty("turn-smoothing", 65);
    public final ModeProperty mode = new ModeProperty("mode", 0, new String[]{"LEGIT", "SWAP", "FULL-LEGIT"});
    public final FloatProperty range = new FloatProperty("range", 4.5F, 3.0F, 6.0F);
    public final PercentProperty speed = new PercentProperty("speed", 0);
    public final BooleanProperty groundSpeed = new BooleanProperty("ground-spoof", false);
    public final ModeProperty ignoreVelocity = new ModeProperty("ignore-velocity", 0, new String[]{"NONE", "CANCEL", "DELAY"});
    public final BooleanProperty surroundings = new BooleanProperty("surroundings", true);
    public final BooleanProperty toolCheck = new BooleanProperty("tool-check", true);
    public final BooleanProperty whiteList = new BooleanProperty("whitelist", true);
    public final TextProperty startKeywords = new TextProperty("start-keywords",
            "Protect your bed,Destroy the enemy bed,保护你的床,摧毁敌人的床", this.whiteList::getValue);
    public final BooleanProperty protectUnknown = new BooleanProperty("protect-unknown", true, this.whiteList::getValue);
    public final BooleanProperty swing = new BooleanProperty("swing", true);
    public final ModeProperty moveFix = new ModeProperty("move-fix", 1, new String[]{"NONE", "SILENT", "STRICT"});
    public final ModeProperty showTarget = new ModeProperty("show-target", 1, new String[]{"NONE", "DEFAULT", "HUD"});
    public final ModeProperty showProgress = new ModeProperty("show-progress", 1, new String[]{"NONE", "DEFAULT", "HUD"});

    private void resetBreaking() {
        if (this.targetBed != null) {
            mc.theWorld.sendBlockBreakProgress(mc.thePlayer.getEntityId(), this.targetBed, -1);
        }
        this.targetBed = null;
        this.aimReady = false;
        this.aimBlock = null;
        this.breakStage = 0;
        this.tickCounter = 0;
        this.breakProgress = 0.0F;
        this.isBed = false;
        this.readyToBreak = false;
        this.breaking = false;
    }

    private float calcProgress() {
        if (this.targetBed == null) {
            return 0.0F;
        } else {
            float progress = this.breakProgress;
            if (this.groundSpeed.getValue()) {
                int slot = ItemUtil.findInventorySlot(mc.thePlayer.inventory.currentItem, mc.theWorld.getBlockState(this.targetBed).getBlock());
                progress = (float) this.tickCounter * this.getBreakDelta(mc.theWorld.getBlockState(this.targetBed), this.targetBed, slot, true);
            }
            return Math.min(1.0F, progress / (1.0F - 0.3F * ((float) this.speed.getValue().intValue() / 100.0F)));
        }
    }

    private void restoreSlot() {
        if (this.savedSlot != -1) {
            mc.thePlayer.inventory.currentItem = this.savedSlot;
            this.syncHeldItem();
            this.savedSlot = -1;
        }
    }

    private void syncHeldItem() {
        int currentPlayerItem = ((IAccessorPlayerControllerMP) mc.playerController).getCurrentPlayerItem();
        if (mc.thePlayer.inventory.currentItem != currentPlayerItem) {
            mc.thePlayer.stopUsingItem();
        }
        ((IAccessorPlayerControllerMP) mc.playerController).callSyncCurrentPlayItem();
    }

    private boolean hasProperTool(Block block) {
        Material material = block.getMaterial();
        if (material != Material.iron && material != Material.anvil && material != Material.rock) {
            return true;
        } else {
            for (int i = 0; i < 9; i++) {
                ItemStack stack = mc.thePlayer.inventory.getStackInSlot(i);
                if (stack != null) {
                    Item item = stack.getItem();
                    if (item instanceof ItemPickaxe) {
                        return true;
                    }
                }
            }
            return false;
        }
    }

    private EnumFacing getHitFacing(BlockPos blockPos) {
        double x = (double) blockPos.getX() + 0.5 - mc.thePlayer.posX;
        double y = (double) blockPos.getY() + 0.25 - mc.thePlayer.posY - (double) mc.thePlayer.getEyeHeight();
        double z = (double) blockPos.getZ() + 0.5 - mc.thePlayer.posZ;
        float[] rotations = RotationUtil.getRotationsTo(x, y, z, mc.thePlayer.rotationYaw, mc.thePlayer.rotationPitch);
        MovingObjectPosition mop = RotationUtil.rayTrace(rotations[0], rotations[1], 8.0, 1.0F);
        return mop == null ? EnumFacing.UP : mop.sideHit;
    }

    private float getDigSpeed(IBlockState iBlockState, int slot, boolean boolean5) {
        ItemStack item = mc.thePlayer.inventory.getStackInSlot(slot);
        float digSpeed = item == null ? 1.0F : item.getItem().getDigSpeed(item, iBlockState);
        if (digSpeed > 1.0F) {
            int enchantmentLevel = EnchantmentHelper.getEnchantmentLevel(Enchantment.efficiency.effectId, item);
            if (enchantmentLevel > 0) {
                digSpeed += (float) (enchantmentLevel * enchantmentLevel + 1);
            }
        }
        if (mc.thePlayer.isPotionActive(Potion.digSpeed)) {
            digSpeed *= 1.0F + (float) (mc.thePlayer.getActivePotionEffect(Potion.digSpeed).getAmplifier() + 1) * 0.2F;
        }
        if (mc.thePlayer.isPotionActive(Potion.digSlowdown)) {
            switch (mc.thePlayer.getActivePotionEffect(Potion.digSlowdown).getAmplifier()) {
                case 0:
                    digSpeed *= 0.3F;
                    break;
                case 1:
                    digSpeed *= 0.09F;
                    break;
                case 2:
                    digSpeed *= 0.0027F;
                    break;
                default:
                    digSpeed *= 8.1E-4F;
            }
        }
        if (mc.thePlayer.isInsideOfMaterial(Material.water) && !EnchantmentHelper.getAquaAffinityModifier(mc.thePlayer)) {
            digSpeed /= 5.0F;
        }
        if (!boolean5) {
            digSpeed /= 5.0F;
        }
        return digSpeed;
    }

    boolean canHarvest(Block block, int slot) {
        if (block.getMaterial().isToolNotRequired()) {
            return true;
        } else {
            ItemStack stack = mc.thePlayer.inventory.getStackInSlot(slot);
            return stack != null && stack.canHarvestBlock(block);
        }
    }

    private float getBreakDelta(IBlockState iBlockState, BlockPos blockPos, int slot, boolean boolean5) {
        Block block = iBlockState.getBlock();
        float hardness = block.getBlockHardness(mc.theWorld, blockPos);
        float boost = this.canHarvest(block, slot) ? 30.0F : 100.0F;
        return hardness < 0.0F ? 0.0F : this.getDigSpeed(iBlockState, slot, boolean5) / hardness / boost;
    }

    private float calcBlockStrength(BlockPos blockPos) {
        IBlockState blockState = mc.theWorld.getBlockState(blockPos);
        int slot = ItemUtil.findInventorySlot(mc.thePlayer.inventory.currentItem, blockState.getBlock());
        return this.getBreakDelta(blockState, blockPos, slot, mc.thePlayer.onGround);
    }

    private BlockPos validateBedPlacement(BlockPos bedPosition) {
        IBlockState blockState = mc.theWorld.getBlockState(bedPosition);
        if (blockState.getBlock() instanceof BlockBed) {
            ArrayList<BlockPos> pos = new ArrayList<>();
            EnumPartType partType = blockState.getValue(BlockBed.PART);
            EnumFacing facing = blockState.getValue(BlockBed.FACING);
            for (BlockPos blockPos : Arrays.asList(bedPosition, bedPosition.offset(partType == EnumPartType.HEAD ? facing.getOpposite() : facing))) {
                for (EnumFacing enumFacing : Arrays.asList(EnumFacing.UP, EnumFacing.NORTH, EnumFacing.EAST, EnumFacing.SOUTH, EnumFacing.WEST)) {
                    Block block = mc.theWorld.getBlockState(blockPos.offset(enumFacing)).getBlock();
                    if (BlockUtil.isReplaceable(block)) {
                        return null;
                    }
                    if (!(block instanceof BlockBed)) {
                        pos.add(blockPos.offset(enumFacing));
                    }
                }
            }
            if (!pos.isEmpty()) {
                pos.sort(
                        (blockPos, blockPos2) -> {
                            int o = Float.compare(this.calcBlockStrength(blockPos2), this.calcBlockStrength(blockPos));
                            return o != 0
                                    ? o
                                    : Double.compare(
                                    blockPos.distanceSqToCenter(mc.thePlayer.posX, mc.thePlayer.posY + (double) mc.thePlayer.getEyeHeight(), mc.thePlayer.posZ),
                                    blockPos2.distanceSqToCenter(mc.thePlayer.posX, mc.thePlayer.posY + (double) mc.thePlayer.getEyeHeight(), mc.thePlayer.posZ)
                            );
                        }
                );
                return pos.get(0);
            }
        }
        return null;
    }

    // FULL-LEGIT: BFS纯最短路径,只通过空气可达,绝不穿墙
    private BlockPos findPathToBed() {
        Set<BlockPos> bedSet = new HashSet<>();
        int r = (int) Math.ceil(this.range.getValue());
        int sX = MathHelper.floor_double(mc.thePlayer.posX);
        int sY = MathHelper.floor_double(mc.thePlayer.posY + mc.thePlayer.getEyeHeight());
        int sZ = MathHelper.floor_double(mc.thePlayer.posZ);

        for (int i = sX - r; i <= sX + r; i++) {
            for (int j = sY - r; j <= sY + r; j++) {
                for (int k = sZ - r; k <= sZ + r; k++) {
                    BlockPos pos = new BlockPos(i, j, k);
                    Block block = mc.theWorld.getBlockState(pos).getBlock();
                    if (block instanceof BlockBed && !this.isProtected(pos)) {
                        bedSet.add(pos);
                    }
                }
            }
        }
        if (bedSet.isEmpty()) return null;

        // BFS只穿过空气(不穿墙)
        Queue<BlockPos> queue = new LinkedList<>();
        Set<BlockPos> visited = new HashSet<>();
        BlockPos start = new BlockPos(mc.thePlayer.posX, mc.thePlayer.posY, mc.thePlayer.posZ);
        queue.add(start);
        visited.add(start);

        BlockPos bestTarget = null;
        double bestDist = Double.MAX_VALUE;

        while (!queue.isEmpty()) {
            BlockPos cur = queue.poll();

            for (EnumFacing f : EnumFacing.values()) {
                BlockPos next = cur.offset(f);
                if (visited.contains(next)) continue;
                if (Math.abs(next.getX() - sX) > r || Math.abs(next.getY() - sY) > r || Math.abs(next.getZ() - sZ) > r) continue;

                Block block = mc.theWorld.getBlockState(next).getBlock();

                // 床直接可达
                if (block instanceof BlockBed) {
                    if (!this.isProtected(next)
                            && PlayerUtil.isBlockWithinReach(next, mc.thePlayer.posX, mc.thePlayer.posY + mc.thePlayer.getEyeHeight(), mc.thePlayer.posZ, this.range.getValue())) {
                        return next;
                    }
                    continue;
                }

                // 空气: 继续BFS
                if (block.getMaterial() == Material.air || BlockUtil.isReplaceable(block)) {
                    visited.add(next);
                    queue.add(next);
                    continue;
                }

                // 可破坏方块: 候选目标(离床最近的优先)
                if (block.getBlockHardness(mc.theWorld, next) >= 0
                        && (!this.toolCheck.getValue() || this.hasProperTool(block))
                        && PlayerUtil.isBlockWithinReach(next, mc.thePlayer.posX, mc.thePlayer.posY + mc.thePlayer.getEyeHeight(), mc.thePlayer.posZ, this.range.getValue())) {
                    for (BlockPos bedPos : bedSet) {
                        double dist = next.distanceSq(bedPos);
                        if (dist < bestDist) {
                            bestDist = dist;
                            bestTarget = next;
                        }
                    }
                }
                // 不可破坏方块(墙): 跳过,不遍历
            }
        }

        return bestTarget;
    }

    private BlockPos findNearestBed() {
        return this.findTargetBed(mc.thePlayer.posX, mc.thePlayer.posY + (double) mc.thePlayer.getEyeHeight(), mc.thePlayer.posZ);
    }

    private BlockPos findTargetBed(double x, double y, double z) {
        ArrayList<BlockPos> targets = new ArrayList<>();
        int sX = MathHelper.floor_double(x);
        int sY = MathHelper.floor_double(y);
        int sZ = MathHelper.floor_double(z);
        for (int i = sX - 6; i <= sX + 6; i++) {
            for (int j = sY - 6; j <= sY + 6; j++) {
                for (int k = sZ - 6; k <= sZ + 6; k++) {
                    BlockPos newPos = new BlockPos(i, j, k);
                    Block block = mc.theWorld.getBlockState(newPos).getBlock();
                    if (block instanceof BlockBed
                            && PlayerUtil.isBlockWithinReach(newPos, x, y, z, this.range.getValue().doubleValue())
                            && !this.isProtected(newPos)) {
                        targets.add(newPos);
                    }
                }
            }
        }
        if (targets.isEmpty()) {
            return null;
        } else {
            targets.sort(
                    Comparator.comparingDouble(
                            blockPos -> blockPos.distanceSqToCenter(mc.thePlayer.posX, mc.thePlayer.posY + (double) mc.thePlayer.getEyeHeight(), mc.thePlayer.posZ)
                    )
            );
            for (BlockPos blockPos : targets) {
                if (this.surroundings.getValue()) {
                    BlockPos pos = this.validateBedPlacement(blockPos);
                    if (pos != null) {
                        Block block = mc.theWorld.getBlockState(pos).getBlock();
                        if (this.toolCheck.getValue() && !this.hasProperTool(block)) {
                            continue;
                        }
                        return pos;
                    }
                }
                return blockPos;
            }
            return null;
        }
    }

    private void doSwing() {
        if (this.swing.getValue()) {
            mc.thePlayer.swingItem();
        } else {
            PacketUtil.sendPacket(new C0APacketAnimation());
        }
    }

    private Color getProgressColor(int mode) {
        switch (mode) {
            case 1:
                float progress = this.calcProgress();
                if (progress <= 0.5F) {
                    return ColorUtil.interpolate(progress / 0.5F, this.colorRed, this.colorYellow);
                }
                return ColorUtil.interpolate((progress - 0.5F) / 0.5F, this.colorYellow, this.colorGreen);
            case 2:
                return ((HUD) Myau.moduleManager.modules.get(HUD.class)).getColor(System.currentTimeMillis());
            default:
                return new Color(-1);
        }
    }

    public BedNuker() {
        super("BedNuker", false);
    }

    public boolean isReady() {
        return this.targetBed != null && this.readyToBreak;
    }

    public boolean isBreaking() {
        return this.targetBed != null && this.breaking;
    }

    @EventTarget(Priority.HIGH)
    public void onTick(TickEvent event) {
        if (this.isEnabled() && event.getType() == EventType.PRE) {
            AutoBlockIn autoBlockIn = (AutoBlockIn) Myau.moduleManager.modules.get(AutoBlockIn.class);
            if(autoBlockIn.isEnabled()) return;
            if (this.targetBed != null) {
                if (mc.theWorld.isAirBlock(this.targetBed) || !PlayerUtil.canReach(this.targetBed, this.range.getValue().doubleValue())) {
                    this.restoreSlot();
                    this.resetBreaking();
                } else if (!this.isBed) {
                    BlockPos nearestBed = this.mode.getValue() == 2 ? this.findPathToBed() : this.findNearestBed();
                    if (nearestBed != null && mc.theWorld.getBlockState(nearestBed).getBlock() instanceof BlockBed) {
                        this.resetBreaking();
                    }
                }
            }
            if (this.targetBed != null) {
                // The previous movement packet must have reached the click point before digging.
                if (this.breakStage < 2 && !this.aimReady) return;
                int slot = ItemUtil.findInventorySlot(mc.thePlayer.inventory.currentItem, mc.theWorld.getBlockState(this.targetBed).getBlock());
                if (this.mode.getValue() == 0 && this.savedSlot == -1) {
                    this.savedSlot = mc.thePlayer.inventory.currentItem;
                    mc.thePlayer.inventory.currentItem = slot;
                    this.syncHeldItem();
                }
                switch (this.breakStage) {
                    case 0:
                        if (!mc.thePlayer.isUsingItem()) {
                            this.doSwing();
                            PacketUtil.sendPacket(
                                    new C07PacketPlayerDigging(Action.START_DESTROY_BLOCK, this.targetBed, this.getHitFacing(this.targetBed))
                            );
                            this.doSwing();
                            mc.effectRenderer.addBlockHitEffects(this.targetBed, this.getHitFacing(this.targetBed));
                            this.breakStage = 1;
                        }
                        break;
                    case 1:
                        if (this.mode.getValue() == 1) {
                            this.readyToBreak = false;
                        }
                        this.breaking = true;
                        this.tickCounter++;
                        this.breakProgress = this.breakProgress
                                + this.getBreakDelta(mc.theWorld.getBlockState(this.targetBed), this.targetBed, slot, mc.thePlayer.onGround);
                        float tick = (float) this.tickCounter;
                        IBlockState blockState = mc.theWorld.getBlockState(this.targetBed);
                        boolean canBreak = mc.thePlayer.onGround && this.groundSpeed.getValue();
                        BlockPos target = this.targetBed;
                        float delta = tick * this.getBreakDelta(blockState, target, slot, canBreak);
                        mc.effectRenderer.addBlockHitEffects(this.targetBed, this.getHitFacing(this.targetBed));
                        if (this.breakProgress >= 1.0F - 0.3F * ((float) this.speed.getValue().intValue() / 100.0F)
                                || delta >= 1.0F - 0.3F * ((float) this.speed.getValue().intValue() / 100.0F)) {
                            if (this.mode.getValue() == 1) {
                                this.readyToBreak = true;
                                this.savedSlot = mc.thePlayer.inventory.currentItem;
                                mc.thePlayer.inventory.currentItem = slot;
                                this.syncHeldItem();
                                if (mc.thePlayer.isUsingItem()) {
                                    this.savedSlot = mc.thePlayer.inventory.currentItem;
                                    mc.thePlayer.inventory.currentItem = (mc.thePlayer.inventory.currentItem + 1) % 9;
                                    this.syncHeldItem();
                                }
                            }
                            this.breaking = false;
                            PacketUtil.sendPacket(
                                    new C07PacketPlayerDigging(Action.STOP_DESTROY_BLOCK, this.targetBed, this.getHitFacing(this.targetBed))
                            );
                            this.doSwing();
                            IBlockState blockState_ = mc.theWorld.getBlockState(this.targetBed);
                            Block block = blockState_.getBlock();
                            if (block.getMaterial() != Material.air) {
                                mc.theWorld.playAuxSFX(2001, this.targetBed, Block.getStateId(blockState_));
                                mc.theWorld.setBlockToAir(this.targetBed);
                            }
                            if (block instanceof BlockBed) {
                                this.timer.reset();
                            }
                            this.breakStage = 2;
                        }
                        break;
                    case 2:
                        this.restoreSlot();
                        this.resetBreaking();
                }
                if (this.targetBed != null) {
                    return;
                }
            }
            if (mc.thePlayer.capabilities.allowEdit && this.timer.hasTimeElapsed(500)) {
                this.targetBed = this.mode.getValue() == 2 ? this.findPathToBed() : this.findNearestBed();
                this.breakStage = 0;
                this.tickCounter = 0;
                this.breakProgress = 0.0F;
                this.isBed = this.targetBed != null && mc.theWorld.getBlockState(this.targetBed).getBlock() instanceof BlockBed;
                this.restoreSlot();
                if (this.targetBed != null) {
                    this.readyToBreak = true;
                }
            }
            if (this.targetBed == null) {
                Myau.delayManager.setDelayState(false, DelayModules.BED_NUKER);
            }
        }
    }

    @EventTarget(Priority.LOWEST)
    public void onUpdate(UpdateEvent event) {
        if (event.getType() != EventType.PRE) return;
        if (mc.thePlayer == null || mc.theWorld == null) {
            rotation.reset();
            rotating = aimReady = false;
            aimBlock = null;
            return;
        }
        AutoBlockIn autoBlockIn = (AutoBlockIn) Myau.moduleManager.modules.get(AutoBlockIn.class);
        if (autoBlockIn.isEnabled() || event.isRotating() > 5) {
            rotation.reset();
            rotating = aimReady = false;
            return;
        }
        boolean aiming = isEnabled() && targetBed != null && breakStage < 2 && mc.currentScreen == null;
        float targetYaw = mc.thePlayer.rotationYaw;
        float targetPitch = mc.thePlayer.rotationPitch;
        if (aiming) {
            if (!targetBed.equals(aimBlock)) {
                aimBlock = targetBed;
                aimX = 0.5 + RandomUtil.nextFloat(-0.09f, 0.09f);
                aimY = (isBed ? 0.28 : 0.5) + RandomUtil.nextFloat(-0.06f, 0.06f);
                aimZ = 0.5 + RandomUtil.nextFloat(-0.09f, 0.09f);
            }
            float[] wanted = RotationUtil.getRotations(targetBed.getX() + aimX,
                    targetBed.getY() + aimY, targetBed.getZ() + aimZ);
            targetYaw = wanted[0];
            targetPitch = wanted[1];
            // Check the last rotation actually sent, not the one being prepared this tick.
            aimReady = Math.abs(MathHelper.wrapAngleTo180_float(targetYaw - event.getYaw())) < 2.0f
                    && Math.abs(targetPitch - event.getPitch()) < 2.0f;
        } else {
            aimReady = false;
            if (!rotating) return;
            if (Math.abs(MathHelper.wrapAngleTo180_float(targetYaw - event.getYaw())) < 0.35f
                    && Math.abs(targetPitch - event.getPitch()) < 0.35f) {
                rotation.reset();
                rotating = false;
                return;
            }
        }
        float[] next = rotation.step(aiming ? aimBlock : "camera", event.getYaw(), event.getPitch(),
                targetYaw, targetPitch, turnSpeed.getValue(), turnSmoothing.getValue() / 100.0f, RotationUtil.gcd());
        event.setRotation(next[0], next[1], 5);
        event.setPervRotation(moveFix.getValue() != 0 ? next[0] : mc.thePlayer.rotationYaw, 5);
        rotating = true;
    }

    @EventTarget
    public void onPlayerUpdate(PlayerUpdateEvent event) {
        if (this.isEnabled()) {
            if (this.isBreaking()
                    && !Myau.playerStateManager.attacking
                    && !Myau.playerStateManager.digging
                    && !Myau.playerStateManager.placing
                    && !Myau.playerStateManager.swinging) {
                this.doSwing();
            }
        }
    }

    @EventTarget
    public void onMoveInput(MoveInputEvent event) {
        if (this.isEnabled() || this.rotating) {
            if (this.moveFix.getValue() == 1
                    && RotationState.isActived()
                    && RotationState.getPriority() == 5.0F
                    && MoveUtil.isForwardPressed()) {
                MoveUtil.fixStrafe(RotationState.getSmoothedYaw());
            }
        }
    }

    @EventTarget(Priority.HIGH)
    public void onKnockback(KnockbackEvent event) {
        if (this.isEnabled() && !event.isCancelled() && !(event.getY() <= 0.0)) {
            if (this.ignoreVelocity.getValue() == 1 && this.targetBed != null) {
                event.setCancelled(true);
                event.setX(mc.thePlayer.motionX);
                event.setY(mc.thePlayer.motionY);
                event.setZ(mc.thePlayer.motionZ);
            }
        }
    }

    @EventTarget
    public void onRender2D(Render2DEvent event) {
        if (this.isEnabled()) {
            if (this.targetBed != null && (!this.isBed || !this.surroundings.getValue())) {
                if (this.showProgress.getValue() != 0) {
                    float breakProgress = this.calcProgress();
                    myau.ui.glass.Island.live("bednuker", myau.ui.glass.Island.P_PROGRESS,
                            String.format("Breaking §f%d%%", (int) (breakProgress * 100.0F)),
                            0xff000000 | this.getProgressColor(this.showProgress.getValue()).getRGB(), breakProgress);
                }
            }
        }
    }

    @EventTarget(Priority.LOW)
    public void onRender3D(Render3DEvent event) {
        if (this.isEnabled() && this.targetBed != null && !mc.theWorld.isAirBlock(this.targetBed)) {
            mc.theWorld.sendBlockBreakProgress(mc.thePlayer.getEntityId(), this.targetBed, (int) (this.calcProgress() * 10.0F) - 1);
            if (this.showTarget.getValue() != 0) {
                BedESP bedESP = (BedESP) Myau.moduleManager.modules.get(BedESP.class);
                Color color = this.getProgressColor(this.showTarget.getValue());
                RenderUtil.enableRenderState();
                BlockPos target = this.targetBed;
                double newHeight = this.isBed ? bedESP.getHeight() : 1.0;
                int r = color.getRed();
                int g = color.getBlue();
                int b = color.getGreen();
                RenderUtil.drawBlockBox(target, newHeight, r, b, g);
                RenderUtil.disableRenderState();
            }
        }
    }

    // ===================== 自家床识别 =====================

    /** 床是否受保护（自家床，或尚未识别自家床时的兜底暂停）。 */
    private boolean isProtected(BlockPos pos) {
        if (!this.whiteList.getValue()) {
            return false;
        }
        if (this.bedWhitelist.contains(pos)) {
            return true;
        }
        if (this.bedWhitelist.isEmpty() && this.protectUnknown.getValue()) {
            if (!this.unknownHintShown && this.isEnabled()) {
                this.unknownHintShown = true;
                ChatUtil.sendFormatted(Myau.clientName
                        + "&e未识别到自家床，拆床已暂停；站在自家床旁输入 &f.ownbed&e 手动标记，或关闭 &fprotect-unknown&r");
            }
            return true;
        }
        return false;
    }

    private boolean matchesStartKeyword(String text) {
        String lower = text.toLowerCase(Locale.ROOT);
        for (String keyword : this.startKeywords.getValue().split("[,，]")) {
            String k = keyword.trim().toLowerCase(Locale.ROOT);
            if (!k.isEmpty() && lower.contains(k)) {
                return true;
            }
        }
        return false;
    }

    /** 可从任意线程调用；真正的扫描在主线程执行，只有最新一次请求生效。 */
    private synchronized void requestScan(boolean strong) {
        // 开局消息（强）后紧跟的传送（弱）不能把"替换名单"冲掉
        this.pendingStrong |= strong;
        int generation = ++this.scanGeneration;
        TickScheduler.schedule(OWN_BED_SCAN_DELAY, () -> {
            boolean s;
            synchronized (this) {
                if (generation != this.scanGeneration) {
                    return;
                }
                s = this.pendingStrong;
                this.pendingStrong = false;
            }
            this.runScan(generation, s, 0);
        });
    }

    private void runScan(int generation, boolean strong, int attempt) {
        if (generation != this.scanGeneration || mc.thePlayer == null || mc.theWorld == null) {
            return;
        }
        if (mc.thePlayer.isDead || mc.thePlayer.getHealth() <= 0.0F
                || (mc.thePlayer.capabilities.allowFlying && !mc.playerController.isInCreativeMode())) {
            return; // 死亡/观战中，不在自家出生点
        }
        Set<BlockPos> found = this.scanBedsAround(OWN_BED_SCAN_RADIUS);
        if (found.isEmpty()) {
            if (attempt < OWN_BED_SCAN_RETRIES) {
                TickScheduler.schedule(OWN_BED_SCAN_DELAY, () -> this.runScan(generation, strong, attempt + 1));
            }
            return;
        }
        if (strong) {
            this.bedWhitelist.clear();
        }
        int before = this.bedWhitelist.size();
        this.bedWhitelist.addAll(found);
        if (this.bedWhitelist.size() != before || strong) {
            this.unknownHintShown = false;
            ChatUtil.sendFormatted(Myau.clientName + "&a已识别自家床 &f" + this.describeBeds(found) + "&a，拆床不会碰它&r");
        }
    }

    private Set<BlockPos> scanBedsAround(int radius) {
        Set<BlockPos> beds = new HashSet<>();
        int sX = MathHelper.floor_double(mc.thePlayer.posX);
        int sY = MathHelper.floor_double(mc.thePlayer.posY + (double) mc.thePlayer.getEyeHeight());
        int sZ = MathHelper.floor_double(mc.thePlayer.posZ);
        for (int i = sX - radius; i <= sX + radius; i++) {
            for (int j = Math.max(0, sY - radius); j <= Math.min(255, sY + radius); j++) {
                for (int k = sZ - radius; k <= sZ + radius; k++) {
                    BlockPos pos = new BlockPos(i, j, k);
                    if (mc.theWorld.getBlockState(pos).getBlock() instanceof BlockBed) {
                        this.addWholeBed(beds, pos);
                    }
                }
            }
        }
        return beds;
    }

    /** 加入床的这一半以及另一半。 */
    private void addWholeBed(Set<BlockPos> set, BlockPos pos) {
        set.add(pos);
        IBlockState state = mc.theWorld.getBlockState(pos);
        if (state.getBlock() instanceof BlockBed) {
            EnumFacing facing = state.getValue(BlockDirectional.FACING);
            BlockPos other = state.getValue(BlockBed.PART) == EnumPartType.FOOT ? pos.offset(facing) : pos.offset(facing.getOpposite());
            set.add(other);
        }
    }

    private String describeBeds(Set<BlockPos> beds) {
        StringBuilder sb = new StringBuilder();
        Set<BlockPos> seen = new HashSet<>();
        for (BlockPos pos : beds) {
            if (seen.contains(pos)) {
                continue;
            }
            IBlockState state = mc.theWorld.getBlockState(pos);
            if (state.getBlock() instanceof BlockBed && state.getValue(BlockBed.PART) == EnumPartType.FOOT) {
                continue; // 每张床只显示床头
            }
            seen.add(pos);
            if (sb.length() > 0) {
                sb.append(", ");
            }
            sb.append('(').append(pos.getX()).append(", ").append(pos.getY()).append(", ").append(pos.getZ()).append(')');
        }
        return sb.length() == 0 ? beds.size() + " 格" : sb.toString();
    }

    /** .ownbed：把 6 格内最近的床标记为自家床。返回标记的床头/床脚之一，没有则 null。 */
    public BlockPos markNearestOwnBed() {
        if (mc.thePlayer == null || mc.theWorld == null) {
            return null;
        }
        BlockPos best = null;
        double bestDist = Double.MAX_VALUE;
        int sX = MathHelper.floor_double(mc.thePlayer.posX);
        int sY = MathHelper.floor_double(mc.thePlayer.posY);
        int sZ = MathHelper.floor_double(mc.thePlayer.posZ);
        for (int i = sX - 6; i <= sX + 6; i++) {
            for (int j = sY - 6; j <= sY + 6; j++) {
                for (int k = sZ - 6; k <= sZ + 6; k++) {
                    BlockPos pos = new BlockPos(i, j, k);
                    if (mc.theWorld.getBlockState(pos).getBlock() instanceof BlockBed) {
                        double dist = pos.distanceSqToCenter(mc.thePlayer.posX, mc.thePlayer.posY, mc.thePlayer.posZ);
                        if (dist < bestDist) {
                            bestDist = dist;
                            best = pos;
                        }
                    }
                }
            }
        }
        if (best != null) {
            this.addWholeBed(this.bedWhitelist, best);
            this.unknownHintShown = false;
            if (this.targetBed != null && this.isProtected(this.targetBed)) {
                this.resetBreaking();
            }
        }
        return best;
    }

    public void clearOwnBed() {
        this.bedWhitelist.clear();
        this.unknownHintShown = false;
    }

    public Set<BlockPos> getOwnBeds() {
        return Collections.unmodifiableSet(this.bedWhitelist);
    }

    public String describeOwnBeds() {
        return this.bedWhitelist.isEmpty() || mc.theWorld == null ? "" : this.describeBeds(this.bedWhitelist);
    }

    @EventTarget
    public void onLoadWorld(LoadWorldEvent event) {
        rotation.reset();
        rotating = aimReady = false;
        aimBlock = null;
        this.bedWhitelist.clear();
        synchronized (this) {
            this.scanGeneration++;
            this.pendingStrong = false;
        }
        this.unknownHintShown = false;
        this.firstTeleportInWorld = true;
        this.lastPearlMs = 0L;
    }

    @EventTarget
    public void onPacket(PacketEvent event) {
        if (!event.isCancelled()) {
            if (event.getType() == EventType.SEND && event.getPacket() instanceof C08PacketPlayerBlockPlacement) {
                ItemStack stack = ((C08PacketPlayerBlockPlacement) event.getPacket()).getStack();
                if (stack != null && stack.getItem() instanceof ItemEnderPearl) {
                    this.lastPearlMs = System.currentTimeMillis();
                }
            }
            if (event.getType() == EventType.RECEIVE && event.getPacket() instanceof S02PacketChat) {
                IChatComponent component = ((S02PacketChat) event.getPacket()).getChatComponent();
                if (component != null && this.matchesStartKeyword(component.getUnformattedText())) {
                    this.requestScan(true);
                }
            }
            if (event.getType() == EventType.RECEIVE && event.getPacket() instanceof S08PacketPlayerPosLook && mc.thePlayer != null) {
                S08PacketPlayerPosLook packet = (S08PacketPlayerPosLook) event.getPacket();
                Set<S08PacketPlayerPosLook.EnumFlags> flags = packet.func_179834_f();
                boolean first = this.firstTeleportInWorld;
                this.firstTeleportInWorld = false;
                double dx = flags != null && flags.contains(S08PacketPlayerPosLook.EnumFlags.X) ? packet.getX() : packet.getX() - mc.thePlayer.posX;
                double dy = flags != null && flags.contains(S08PacketPlayerPosLook.EnumFlags.Y) ? packet.getY() : packet.getY() - mc.thePlayer.posY;
                double dz = flags != null && flags.contains(S08PacketPlayerPosLook.EnumFlags.Z) ? packet.getZ() : packet.getZ() - mc.thePlayer.posZ;
                boolean big = first || dx * dx + dy * dy + dz * dz > BIG_TELEPORT_DIST_SQ;
                boolean fromPearl = System.currentTimeMillis() - this.lastPearlMs < PEARL_IGNORE_MS;
                if (big && !fromPearl) {
                    this.requestScan(false);
                }
            }
            if (this.isEnabled() && this.targetBed != null && this.ignoreVelocity.getValue() == 2 && Myau.delayManager.getDelayModule() != DelayModules.BED_NUKER) {
                if (event.getPacket() instanceof S12PacketEntityVelocity) {
                    S12PacketEntityVelocity packet = (S12PacketEntityVelocity) event.getPacket();
                    if (packet.getEntityID() == mc.thePlayer.getEntityId() && packet.getMotionY() > 0) {
                        Myau.delayManager.delay(DelayModules.BED_NUKER);
                        Myau.delayManager.delayedPacket.offer(packet);
                        event.setCancelled(true);
                    }
                }
                if (event.getPacket() instanceof S27PacketExplosion) {
                    S27PacketExplosion explosion = (S27PacketExplosion) event.getPacket();
                    if (explosion.func_149149_c() != 0.0F || explosion.func_149144_d() != 0.0F || explosion.func_149147_e() != 0.0F) {
                        Myau.delayManager.delay(DelayModules.BED_NUKER);
                        Myau.delayManager.delayedPacket.offer(explosion);
                        event.setCancelled(true);
                    }
                }
            }
        }
    }

    @EventTarget
    public void onLeftClick(LeftClickMouseEvent event) {
        if (this.isEnabled()) {
            if (this.isReady() || this.targetBed != null && mc.objectMouseOver != null && mc.objectMouseOver.typeOfHit == MovingObjectType.BLOCK) {
                event.setCancelled(true);
            }
        }
    }

    @EventTarget
    public void onRightClick(RightClickMouseEvent event) {
        if (this.isEnabled()) {
            if (this.isReady()) {
                event.setCancelled(true);
            }
        }
    }

    @EventTarget
    public void onHitBlock(HitBlockEvent event) {
        if (this.isEnabled()) {
            if (this.isReady() || this.targetBed != null && mc.objectMouseOver != null && mc.objectMouseOver.typeOfHit == MovingObjectType.BLOCK) {
                event.setCancelled(true);
            }
        }
    }

    @EventTarget
    public void onSwap(SwapItemEvent event) {
        if (this.isEnabled()) {
            if (this.savedSlot != -1) {
                event.setCancelled(true);
            }
        }
    }

    @Override
    public void onDisabled() {
        this.resetBreaking();
        this.savedSlot = -1;
        Myau.delayManager.setDelayState(false, DelayModules.BED_NUKER);
    }

    @Override
    public String[] getSuffix() {
        return new String[]{CaseFormat.UPPER_UNDERSCORE.to(CaseFormat.UPPER_CAMEL, this.mode.getModeString())};
    }
}
