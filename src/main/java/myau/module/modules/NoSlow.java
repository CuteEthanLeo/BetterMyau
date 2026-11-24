package myau.module.modules;

import io.netty.buffer.Unpooled;
import myau.Myau;
import myau.enums.BlinkModules;
import myau.enums.FloatModules;
import myau.event.EventTarget;
import myau.event.types.EventType;
import myau.event.types.Priority;
import myau.events.LivingUpdateEvent;
import myau.events.LoadWorldEvent;
import myau.events.PacketEvent;
import myau.events.PostMotionEvent;
import myau.events.PlayerUpdateEvent;
import myau.events.RightClickMouseEvent;
import myau.events.UpdateEvent;
import myau.management.PlayerStateManager;
import myau.mixin.IAccessorEntityPlayer;
import myau.module.Module;
import myau.util.BlockUtil;
import myau.util.ItemUtil;
import myau.util.MoveUtil;
import myau.util.PacketUtil;
import myau.util.PlayerUtil;
import myau.util.TeamUtil;
import myau.property.properties.BooleanProperty;
import myau.property.properties.IntProperty;
import myau.property.properties.PercentProperty;
import myau.property.properties.ModeProperty;
import net.minecraft.block.*;
import net.minecraft.client.Minecraft;
import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityLivingBase;
import net.minecraft.entity.passive.EntityVillager;
import net.minecraft.item.Item;
import net.minecraft.item.ItemAppleGold;
import net.minecraft.item.ItemBow;
import net.minecraft.item.ItemBucketMilk;
import net.minecraft.item.ItemFood;
import net.minecraft.item.ItemPotion;
import net.minecraft.item.ItemStack;
import net.minecraft.item.ItemSword;
import net.minecraft.network.Packet;
import net.minecraft.network.PacketBuffer;
import net.minecraft.network.play.client.C07PacketPlayerDigging;
import net.minecraft.network.play.client.C08PacketPlayerBlockPlacement;
import net.minecraft.network.play.client.C09PacketHeldItemChange;
import net.minecraft.network.play.client.C0FPacketConfirmTransaction;
import net.minecraft.network.play.client.C17PacketCustomPayload;
import net.minecraft.network.play.server.S2FPacketSetSlot;
import net.minecraft.util.BlockPos;
import net.minecraft.util.EnumFacing;
import net.minecraft.util.MovingObjectPosition;

import java.util.Locale;
import java.util.Random;

/**
 * Removes the item-use slowdown. The slowdown itself is lifted by the isUsingItem redirect in
 * MixinEntityPlayerSP (see {@link #isAnyActive()}) and scaled by the motion percentages; the
 * modes ported from xw only add the packets that make a server accept it.
 */
public class NoSlow extends Module {
    private static final Minecraft mc = Minecraft.getMinecraft();
    private int lastSlot = -1;

    // ---- state of the modes ported from xw ----
    /** UPDATED_NCP (food / bow): a use-item packet went out, swap the slot on the next tick. */
    private volatile boolean shouldSwap = false;
    /** DROP: the server answered the drop with the new stack, the slowdown may be lifted. */
    private volatile boolean shouldNoSlow = false;
    private boolean hasDropped = false;
    private boolean grimEating = true;
    private int grimLastFoodAmount = 0;
    /** GRIMAC (food): xw grimFoodSpeed, false = 0.2 (vanilla slowdown), true = 1.0. */
    private boolean grimFoodNoSlow = false;
    private boolean grim19Blocking = false;
    private boolean bmcJumped = false;
    private int consumeTickCycle = 0;

    public final ModeProperty swordMode = new ModeProperty("sword-mode", 1, new String[]{
            "NONE", "VANILLA",
            "NCP", "UPDATED_NCP", "AAC5", "SWITCH_ITEM", "INVALID_C08", "POST_PLACE", "GRIMAC_1_9", "GRIM",
            "BLOCKS_MC", "HYTBW32", "INTAVE14", "OLD_INTAVE", "GRIM_NEW", "GRIM_TICK", "TATAKO",
            "PREDICTION_SEMI", "PREDICTION"
    });
    // ── PREDICTION / PREDICTION_SEMI: ported from LeaderClient (GPLv3), meant for KillAura HYPIXEL_* auto-block ──
    public final IntProperty predSwapDelay = new IntProperty("pred-swap-delay", 0, 0, 3, () -> this.isSwordMode("PREDICTION"));
    public final BooleanProperty predTest = new BooleanProperty("pred-test", false, () -> this.isSwordMode("PREDICTION"));
    public final BooleanProperty predC17 = new BooleanProperty("pred-c17-packet", false, () -> this.isSwordMode("PREDICTION"));
    public final BooleanProperty predNoAttack = new BooleanProperty("pred-no-attack", false, () -> this.isSwordMode("PREDICTION"));
    public final IntProperty semiCancelTick = new IntProperty("semi-cancel-tick", 1, 0, 2, () -> this.isSwordMode("PREDICTION_SEMI"));
    public final IntProperty semiCancelTick2 = new IntProperty("semi-cancel-tick-2", 1, 0, 2, () -> this.isSwordMode("PREDICTION_SEMI"));
    private int predDelay = 0;
    private boolean predPost = false;
    public final PercentProperty swordMotion = new PercentProperty("sword-motion", 100, () -> this.swordMode.getValue() != 0);
    public final BooleanProperty swordSprint = new BooleanProperty("sword-sprint", true, () -> this.swordMode.getValue() != 0);
    public final BooleanProperty bmcOldOffset = new BooleanProperty("bmc-old-offset", false, () -> this.isSwordMode("BLOCKS_MC"));
    public final BooleanProperty grim19KeepSprinting = new BooleanProperty("grim19-keep-sprinting", true, () -> this.isSwordMode("GRIMAC_1_9"));
    public final BooleanProperty grim19NoC0F = new BooleanProperty("grim19-no-c0f", true, () -> this.isSwordMode("GRIMAC_1_9"));
    public final ModeProperty foodMode = new ModeProperty("food-mode", 0, new String[]{
            "NONE", "VANILLA", "FLOAT",
            "UPDATED_NCP", "AAC5", "SWITCH_ITEM", "INVALID_C08", "INTAVE", "OLD_INTAVE", "GRIMAC", "DROP",
            "BLOCKS_MC", "GRIM_TICK", "INTAVE_NEW", "HYTBW32", "INTAVE14", "GRIM_NEW"
    });
    public final PercentProperty foodMotion = new PercentProperty("food-motion", 100, () -> this.foodMode.getValue() != 0);
    public final BooleanProperty foodSprint = new BooleanProperty("food-sprint", true, () -> this.foodMode.getValue() != 0);
    public final BooleanProperty consumeFood = new BooleanProperty("consume-food", true, () -> this.foodMode.getValue() != 0);
    public final BooleanProperty consumeDrink = new BooleanProperty("consume-drink", true, () -> this.foodMode.getValue() != 0);
    public final ModeProperty bowMode = new ModeProperty("bow-mode", 0, new String[]{
            "NONE", "VANILLA", "FLOAT",
            "UPDATED_NCP", "AAC5", "SWITCH_ITEM", "INVALID_C08", "GRIMAC", "INTAVE14", "GRIM_NEW", "GRIM_TICK", "OLD_INTAVE"
    });
    public final PercentProperty bowMotion = new PercentProperty("bow-motion", 100, () -> this.bowMode.getValue() != 0);
    public final BooleanProperty bowSprint = new BooleanProperty("bow-sprint", true, () -> this.bowMode.getValue() != 0);

    public NoSlow() {
        super("NoSlow", false);
    }

    private boolean isSwordMode(String mode) {
        return mode.equals(this.swordMode.getModeString());
    }

    private boolean isFoodMode(String mode) {
        return mode.equals(this.foodMode.getModeString());
    }

    private boolean isBowMode(String mode) {
        return mode.equals(this.bowMode.getModeString());
    }

    public boolean isSwordActive() {
        return this.swordMode.getValue() != 0 && ItemUtil.isHoldingSword();
    }

    public boolean isFoodActive() {
        return this.foodMode.getValue() != 0 && ItemUtil.isEating() && this.isHeldConsumeEnabled();
    }

    public boolean isBowActive() {
        return this.bowMode.getValue() != 0 && ItemUtil.isUsingBow();
    }

    public boolean isFloatMode() {
        return this.foodMode.getValue() == 2 && ItemUtil.isEating()
                || this.bowMode.getValue() == 2 && ItemUtil.isUsingBow();
    }

    public boolean isAnyActive() {
        if (this.isSwordActive() && this.isSwordMode("PREDICTION_SEMI")) {
            // no packets: slowdown is skipped only on the auto-block ticks where the server sees no block
            KillAura killAura = this.killAura();
            return killAura.isEnabled() && killAura.shouldAutoBlock()
                    && (killAura.blockTick == this.semiCancelTick.getValue() || killAura.blockTick == this.semiCancelTick2.getValue());
        }
        if (this.isSwordActive() && this.isSwordMode("PREDICTION")) {
            return !this.isKillAuraAttackTick() && this.predDelay == 0;
        }
        return mc.thePlayer.isUsingItem()
                && (this.isSwordActive() || this.isFoodActive() && !this.isConsumeSlowed() || this.isBowActive());
    }

    private KillAura killAura() {
        return (KillAura) Myau.moduleManager.modules.get(KillAura.class);
    }

    /**
     * pred-no-attack: true on the tick KillAura hits. LeaderClient auto-block modes answer by phase
     * (KillAura.isBlockAttackPhase); any other mode only counts while KillAura is actually on a target.
     */
    private boolean isKillAuraAttackTick() {
        if (!this.predNoAttack.getValue()) {
            return false;
        }
        KillAura killAura = this.killAura();
        if (!killAura.isEnabled() || killAura.getTarget() == null) {
            return false;
        }
        if (killAura.isHypixelLeaderBlock()) {
            return killAura.isBlockAttackPhase();
        }
        return true;
    }

    private void sendPredictionSwap() {
        int current = mc.thePlayer.inventory.currentItem;
        int randomSlot = new Random().nextInt(9);
        while (randomSlot == current) {
            randomSlot = new Random().nextInt(9);
        }
        PacketUtil.sendPacket(new C09PacketHeldItemChange(randomSlot));
        if (this.predC17.getValue()) {
            PacketUtil.sendPacket(new C17PacketCustomPayload("woshijiejue", new PacketBuffer(Unpooled.buffer())));
        }
        PacketUtil.sendPacket(new C09PacketHeldItemChange(current));
    }

    @EventTarget
    public void onUpdatePrediction(UpdateEvent event) {
        if (!this.isEnabled() || !this.isSwordMode("PREDICTION")) {
            return;
        }
        if (ItemUtil.isHoldingSword() && mc.thePlayer.isUsingItem()) {
            if (event.getType() == EventType.PRE) {
                this.predDelay--;
                if (this.predDelay < 0) {
                    if (!this.isKillAuraAttackTick()) {
                        if (this.predTest.getValue()) {
                            Myau.blinkManager.setBlinkState(true, BlinkModules.NO_SLOW);
                        }
                        this.sendPredictionSwap();
                    }
                    this.predPost = true;
                    this.predDelay = this.predSwapDelay.getValue();
                }
            }
        } else if (this.predPost) {
            if (this.predTest.getValue()) {
                Myau.blinkManager.setBlinkState(false, BlinkModules.NO_SLOW);
                this.sendPredictionSwap();
            }
            this.predPost = false;
        }
    }

    @EventTarget
    public void onPostMotionPrediction(PostMotionEvent event) {
        if (!this.isEnabled() || !this.isSwordMode("PREDICTION")) {
            return;
        }
        if (!ItemUtil.isHoldingSword() || !mc.thePlayer.isUsingItem()) {
            return;
        }
        if (this.predPost) {
            this.predPost = false;
            if (this.predTest.getValue()) {
                PacketUtil.sendPacket(new C08PacketPlayerBlockPlacement(mc.thePlayer.getHeldItem()));
                Myau.blinkManager.setBlinkState(false, BlinkModules.NO_SLOW);
            }
        }
    }

    public boolean canSprint() {
        return this.isSwordActive() && (this.swordSprint.getValue() || this.isGrim19KeepSprint())
                || this.isFoodActive() && this.foodSprint.getValue()
                || this.isBowActive() && this.bowSprint.getValue();
    }

    public int getMotionMultiplier() {
        if (ItemUtil.isHoldingSword()) {
            // GRIMAC_1_9 always moves at full speed once it took over the block
            return this.isGrim19Active() ? 100 : this.swordMotion.getValue();
        } else if (ItemUtil.isEating()) {
            // GRIMAC (food) is all or nothing: vanilla slowdown, or none after the apple drop
            return this.isFoodMode("GRIMAC") ? 100 : this.foodMotion.getValue();
        } else {
            return ItemUtil.isUsingBow() ? this.bowMotion.getValue() : 100;
        }
    }

    /** consume-food / consume-drink for the held item; anything else that is eaten or drunk stays allowed. */
    private boolean isHeldConsumeEnabled() {
        ItemStack stack = mc.thePlayer.getHeldItem();
        if (stack == null) {
            return false;
        }
        Item item = stack.getItem();
        if (item instanceof ItemFood) {
            return this.consumeFood.getValue();
        }
        if (item instanceof ItemPotion || item instanceof ItemBucketMilk) {
            return this.consumeDrink.getValue();
        }
        return true;
    }

    /** Food modes that keep the vanilla slowdown until their bypass has gone through. */
    private boolean isConsumeSlowed() {
        if (this.isFoodMode("DROP")) {
            return !this.shouldNoSlow;
        }
        if (this.isFoodMode("GRIMAC")) {
            return !this.grimFoodNoSlow;
        }
        return false;
    }

    private boolean isGrim19Active() {
        return this.grim19Blocking && this.isSwordMode("GRIMAC_1_9") && ItemUtil.isHoldingSword();
    }

    private boolean isGrim19KeepSprint() {
        return this.isGrim19Active() && this.grim19KeepSprinting.getValue();
    }

    private void resetState() {
        this.shouldSwap = false;
        this.shouldNoSlow = false;
        this.hasDropped = false;
        this.grimEating = true;
        this.grimLastFoodAmount = 0;
        this.grimFoodNoSlow = false;
        this.grim19Blocking = false;
        this.bmcJumped = false;
        this.consumeTickCycle = 0;
        if (this.predPost && Myau.blinkManager != null) {
            Myau.blinkManager.setBlinkState(false, BlinkModules.NO_SLOW);
        }
        this.predDelay = 0;
        this.predPost = false;
    }

    @Override
    public void onEnabled() {
        this.resetState();
    }

    @Override
    public void onDisabled() {
        this.resetState();
    }

    @Override
    public void verifyValue(String name) {
        if (name.endsWith("-mode")) {
            this.resetState();
        }
    }

    @EventTarget
    public void onLoadWorld(LoadWorldEvent event) {
        this.resetState();
    }

    @EventTarget
    public void onLivingUpdate(LivingUpdateEvent event) {
        if (this.isEnabled() && this.isAnyActive()) {
            float multiplier = (float) this.getMotionMultiplier() / 100.0F;
            mc.thePlayer.movementInput.moveForward *= multiplier;
            mc.thePlayer.movementInput.moveStrafe *= multiplier;
            if (!this.canSprint()) {
                mc.thePlayer.setSprinting(false);
            } else if (this.isGrim19KeepSprint()
                    && mc.thePlayer.movementInput.moveForward >= 0.8F
                    && !mc.thePlayer.isCollidedHorizontally
                    && ((float) mc.thePlayer.getFoodStats().getFoodLevel() > 6.0F || mc.thePlayer.capabilities.allowFlying)) {
                mc.thePlayer.setSprinting(true);
            }
        }
    }

    @EventTarget(Priority.LOW)
    public void onPlayerUpdate(PlayerUpdateEvent event) {
        if (this.isEnabled() && this.isFloatMode()) {
            int item = mc.thePlayer.inventory.currentItem;
            if (this.lastSlot != item && PlayerUtil.isUsingItem()) {
                this.lastSlot = item;
                Myau.floatManager.setFloatState(true, FloatModules.NO_SLOW);
            }
        } else {
            this.lastSlot = -1;
            Myau.floatManager.setFloatState(false, FloatModules.NO_SLOW);
        }
        if (this.isEnabled()) {
            // xw MotionEvent PRE
            this.handleMotion(true);
        }
    }

    @EventTarget
    public void onUpdate(UpdateEvent event) {
        if (!this.isEnabled() || event.getType() != EventType.POST) {
            return;
        }
        // xw MotionEvent POST. These packets leave after this tick's C03, so they must not count as
        // "already dug / placed this tick" for the next one, or KillAura would never act while blocking.
        PlayerStateManager state = Myau.playerStateManager;
        if (state == null) {
            this.handleMotion(false);
            return;
        }
        boolean digging = state.digging;
        boolean placing = state.placing;
        boolean swapping = state.swapping;
        this.handleMotion(false);
        state.digging = digging;
        state.placing = placing;
        state.swapping = swapping;
    }

    @EventTarget
    public void onPacket(PacketEvent event) {
        if (!this.isEnabled() || mc.thePlayer == null) {
            return;
        }
        if (event.isCancelled() || this.shouldSwap) {
            return;
        }
        Packet<?> packet = event.getPacket();
        if (this.isFoodMode("GRIMAC")) {
            this.handleGrimConsumePacket(event, packet);
            if (event.isCancelled()) {
                return;
            }
        }
        // GRIMAC_1_9: once the block is taken over, no further use-item packet reaches the server
        // (this also swallows the one handleSword() sends, exactly as in xw)
        if (this.grim19Blocking && this.grim19NoC0F.getValue() && this.isSwordMode("GRIMAC_1_9")
                && packet instanceof C08PacketPlayerBlockPlacement
                && ((C08PacketPlayerBlockPlacement) packet).getPlacedBlockDirection() == 255
                && ItemUtil.isHoldingSword()) {
            event.setCancelled(true);
            return;
        }
        if (this.isFoodMode("DROP")) {
            this.handleDropPacket(event, packet);
            if (event.isCancelled()) {
                return;
            }
        }
        if (packet instanceof C08PacketPlayerBlockPlacement) {
            ItemStack sent = ((C08PacketPlayerBlockPlacement) packet).getStack();
            ItemStack held = mc.thePlayer.getHeldItem();
            if (sent != null && held != null && sent.getItem() != null && sent.getItem() == held.getItem()) {
                Item item = sent.getItem();
                if (this.isFoodMode("UPDATED_NCP") && (item instanceof ItemFood || item instanceof ItemPotion || item instanceof ItemBucketMilk)
                        || this.isBowMode("UPDATED_NCP") && item instanceof ItemBow) {
                    this.shouldSwap = true;
                }
            }
        }
    }

    @EventTarget
    public void onRightClick(RightClickMouseEvent event) {
        if (this.isEnabled()) {
            if (mc.objectMouseOver != null) {
                switch (mc.objectMouseOver.typeOfHit) {
                    case BLOCK:
                        BlockPos blockPos = mc.objectMouseOver.getBlockPos();
                        if (BlockUtil.isInteractable(blockPos) && !PlayerUtil.isSneaking()) {
                            return;
                        }
                        break;
                    case ENTITY:
                        Entity entityHit = mc.objectMouseOver.entityHit;
                        if (entityHit instanceof EntityVillager) {
                            return;
                        }
                        if (entityHit instanceof EntityLivingBase && TeamUtil.isShop((EntityLivingBase) entityHit)) {
                            return;
                        }
                }
            }
            if (this.isFloatMode() && !Myau.floatManager.isPredicted() && mc.thePlayer.onGround) {
                event.setCancelled(true);
                mc.thePlayer.motionY = 0.42F;
            }
        }
    }

    // ------------------------------------------------------------------------------------------
    // xw modes: packets around the movement packet (pre = before this tick's C03, !pre = after it)
    // ------------------------------------------------------------------------------------------

    private void handleMotion(boolean pre) {
        ItemStack heldItem = mc.thePlayer.getHeldItem();
        if (heldItem == null) {
            this.grim19Blocking = false;
            this.consumeTickCycle = 0;
            this.shouldSwap = false;
            return;
        }
        boolean using = this.isUsingOrBlocking();
        if (!using) {
            this.grim19Blocking = false;
            this.consumeTickCycle = 0;
        }
        if (mc.thePlayer.motionX == 0.0 && mc.thePlayer.motionY == 0.0 && mc.thePlayer.motionZ == 0.0 && !this.shouldSwap) {
            return;
        }
        Item item = heldItem.getItem();
        if (item instanceof ItemSword) {
            if (using) {
                this.handleSword(pre, heldItem);
            }
        } else if (item instanceof ItemBow) {
            if (using || this.shouldSwap) {
                this.handleBow(pre, heldItem);
            }
        } else if (this.isConsumable(item) && (using || this.shouldSwap)) {
            this.handleConsume(pre, heldItem);
        }
        if (pre) {
            // a pending swap is done by now; never carry it over to another item or mode
            this.shouldSwap = false;
        }
    }

    /**
     * xw usingItemFunc(): really using the item, KillAura holding the block for the player, or
     * the UPDATED_NCP sword mode with the use key down.
     */
    private boolean isUsingOrBlocking() {
        if (mc.thePlayer.isUsingItem()) {
            return true;
        }
        if (!ItemUtil.isHoldingSword()) {
            return false;
        }
        Module killAura = Myau.moduleManager.modules.get(KillAura.class);
        if (killAura instanceof KillAura && killAura.isEnabled() && ((KillAura) killAura).isPlayerBlocking()) {
            return true;
        }
        return this.isSwordMode("UPDATED_NCP") && mc.gameSettings.keyBindUseItem.isKeyDown();
    }

    private boolean isConsumable(Item item) {
        return this.consumeFood.getValue() && item instanceof ItemFood
                || this.consumeDrink.getValue() && (item instanceof ItemPotion || item instanceof ItemBucketMilk);
    }

    private void handleSword(boolean pre, ItemStack heldItem) {
        int slot = mc.thePlayer.inventory.currentItem;
        switch (this.swordMode.getModeString()) {
            case "NCP":
                if (pre) {
                    PacketUtil.sendPacket(releasePacket(EnumFacing.DOWN));
                } else {
                    PacketUtil.sendPacket(usePacket(heldItem));
                }
                break;
            case "UPDATED_NCP":
                if (!pre) {
                    PacketUtil.sendPacket(new C08PacketPlayerBlockPlacement(BlockPos.ORIGIN, 255, heldItem, 0.0F, 0.0F, 0.0F));
                }
                break;
            case "AAC5":
                if (!pre) {
                    PacketUtil.sendPacket(usePacket(heldItem));
                }
                break;
            case "SWITCH_ITEM":
                if (pre) {
                    this.switchSlot();
                }
                break;
            case "INVALID_C08":
                if (pre) {
                    this.sendInvalidC08();
                }
                break;
            case "POST_PLACE":
                if (pre) {
                    PacketUtil.sendPacket(releasePacket(EnumFacing.DOWN));
                } else {
                    PacketUtil.sendPacket(usePacket(heldItem));
                    PacketUtil.sendPacket(new C08PacketPlayerBlockPlacement(heldItem));
                }
                break;
            case "GRIMAC_1_9":
                if (pre) {
                    this.grim19Blocking = true;
                    if (this.grim19NoC0F.getValue()) {
                        PacketUtil.sendPacket(usePacket(heldItem));
                    }
                }
                break;
            case "GRIM":
                if (pre) {
                    PacketUtil.sendPacket(new C09PacketHeldItemChange(slot % 8 + 1));
                    PacketUtil.sendPacket(new C17PacketCustomPayload("许锦良", new PacketBuffer(Unpooled.buffer())));
                    PacketUtil.sendPacket(new C09PacketHeldItemChange(slot));
                } else {
                    PacketUtil.sendPacket(usePacket(heldItem));
                    PacketUtil.sendPacket(new C08PacketPlayerBlockPlacement(heldItem));
                }
                break;
            case "BLOCKS_MC":
                if (!this.bmcJumped) {
                    this.bmcJumped = true;
                    if (mc.thePlayer.onGround) {
                        mc.thePlayer.jump();
                    }
                }
                ItemStack stack = mc.thePlayer.inventory.getStackInSlot(slot);
                if (stack != null) {
                    String name = stack.getUnlocalizedName().toLowerCase(Locale.ROOT);
                    if (name.contains("apple") || name.contains("bow") || name.contains("potion")) {
                        float randomFactor = this.bmcOldOffset.getValue()
                                ? 0.5F + (float) (Math.random() * 0.44)
                                : (float) (Math.random() * 0.96);
                        BlockPos position = mc.thePlayer.getPosition();
                        int adjustedY = position.getY() > 0 ? position.getY() - 255 : position.getY() + 255;
                        PacketUtil.sendPacket(new C08PacketPlayerBlockPlacement(
                                new BlockPos(position.getX(), adjustedY, position.getZ()), 0, stack, 0.0F, randomFactor, 0.0F
                        ));
                    }
                }
                break;
            case "HYTBW32":
                if (pre) {
                    // xw sends currentItem + 1 unwrapped (slot 9 from the last slot); wrapped here
                    PacketUtil.sendPacket(new C09PacketHeldItemChange((slot + 1) % 9));
                    PacketUtil.sendPacket(new C17PacketCustomPayload("sbhyt", new PacketBuffer(Unpooled.buffer())));
                    PacketUtil.sendPacket(new C09PacketHeldItemChange(slot));
                } else {
                    PacketUtil.sendPacket(new C08PacketPlayerBlockPlacement(heldItem));
                }
                break;
            case "INTAVE14":
                this.sendIntave14(heldItem);
                break;
            case "OLD_INTAVE":
                if (pre) {
                    PacketUtil.sendPacket(releasePacket(EnumFacing.UP));
                }
                break;
            case "GRIM_NEW":
                this.sendGrimNew();
                break;
            case "GRIM_TICK":
                if (pre) {
                    this.sendGrimTick();
                }
                break;
            case "TATAKO":
                if (pre) {
                    PacketUtil.sendPacket(new C08PacketPlayerBlockPlacement(heldItem));
                }
                break;
            default:
                break;
        }
    }

    private void handleConsume(boolean pre, ItemStack heldItem) {
        int slot = mc.thePlayer.inventory.currentItem;
        switch (this.foodMode.getModeString()) {
            case "UPDATED_NCP":
                if (pre) {
                    this.sendUpdatedNcpSwap(heldItem);
                }
                break;
            case "AAC5":
                PacketUtil.sendPacket(usePacket(heldItem));
                break;
            case "SWITCH_ITEM":
                if (pre) {
                    this.switchSlot();
                }
                break;
            case "INVALID_C08":
                if (pre) {
                    this.sendInvalidC08();
                }
                break;
            case "INTAVE":
                if (pre) {
                    PacketUtil.sendPacketNoEvent(releasePacket(EnumFacing.UP));
                }
                break;
            case "OLD_INTAVE":
                if (pre) {
                    PacketUtil.sendPacketNoEvent(new C09PacketHeldItemChange(slot % 8 + 1));
                    PacketUtil.sendPacketNoEvent(new C09PacketHeldItemChange(slot));
                }
                break;
            case "GRIMAC":
                this.handleGrimConsumeMotion(heldItem);
                break;
            case "BLOCKS_MC":
                if (pre) {
                    // first tick of every use (xw never resets this counter, so it only fires once per session there)
                    this.consumeTickCycle++;
                    if (this.consumeTickCycle == 1) {
                        PacketUtil.sendPacket(new C09PacketHeldItemChange((slot + 1) % 9));
                        PacketUtil.sendPacket(new C09PacketHeldItemChange(slot));
                        PacketUtil.sendPacket(new C08PacketPlayerBlockPlacement(new BlockPos(-1, -1, -1), 0, heldItem, 0.0F, 0.0F, 0.0F));
                    }
                }
                break;
            case "GRIM_TICK":
                if (pre) {
                    this.sendGrimTick();
                }
                break;
            case "INTAVE_NEW":
                if (pre) {
                    PacketUtil.sendPacket(new C07PacketPlayerDigging(
                            C07PacketPlayerDigging.Action.RELEASE_USE_ITEM,
                            new BlockPos(mc.thePlayer.posX, mc.thePlayer.getPositionEyes(1.0F).yCoord, mc.thePlayer.posZ),
                            EnumFacing.DOWN
                    ));
                }
                break;
            case "HYTBW32":
                if (pre && heldItem.getItem() instanceof ItemFood) {
                    PacketUtil.sendPacket(new C07PacketPlayerDigging(
                            C07PacketPlayerDigging.Action.STOP_DESTROY_BLOCK, mc.thePlayer.getPosition().up(), EnumFacing.UP
                    ));
                }
                break;
            case "INTAVE14":
                this.sendIntave14(heldItem);
                break;
            case "GRIM_NEW":
                this.sendGrimNew();
                break;
            default:
                // DROP works entirely from the packet handler
                break;
        }
    }

    private void handleBow(boolean pre, ItemStack heldItem) {
        switch (this.bowMode.getModeString()) {
            case "UPDATED_NCP":
                if (pre) {
                    this.sendUpdatedNcpSwap(heldItem);
                }
                break;
            case "AAC5":
                PacketUtil.sendPacket(usePacket(heldItem));
                break;
            case "SWITCH_ITEM":
                if (pre) {
                    this.switchSlot();
                }
                break;
            case "INVALID_C08":
                if (pre) {
                    this.sendInvalidC08();
                }
                break;
            case "GRIMAC":
                if (!pre) {
                    PacketUtil.sendPacketNoEvent(usePacket(heldItem));
                }
                break;
            case "INTAVE14":
                this.sendIntave14(heldItem);
                break;
            case "GRIM_NEW":
                this.sendGrimNew();
                break;
            case "GRIM_TICK":
                if (pre) {
                    this.sendGrimTick();
                }
                break;
            case "OLD_INTAVE":
                if (pre) {
                    PacketUtil.sendPacket(releasePacket(EnumFacing.UP));
                }
                break;
            default:
                break;
        }
    }

    /** The use-item packet (no block, direction 255). */
    private static C08PacketPlayerBlockPlacement usePacket(ItemStack stack) {
        return new C08PacketPlayerBlockPlacement(new BlockPos(-1, -1, -1), 255, stack, 0.0F, 0.0F, 0.0F);
    }

    private static C07PacketPlayerDigging releasePacket(EnumFacing facing) {
        return new C07PacketPlayerDigging(C07PacketPlayerDigging.Action.RELEASE_USE_ITEM, BlockPos.ORIGIN, facing);
    }

    /** xw updateSlot(): silently to the next hotbar slot and straight back. */
    private void switchSlot() {
        int slot = mc.thePlayer.inventory.currentItem;
        PacketUtil.sendPacket(new C09PacketHeldItemChange((slot + 1) % 9));
        PacketUtil.sendPacket(new C09PacketHeldItemChange(slot));
    }

    private void sendUpdatedNcpSwap(ItemStack heldItem) {
        if (this.shouldSwap) {
            // shouldSwap stays set while these go out, so onPacket() does not re-arm on our own C08
            this.switchSlot();
            PacketUtil.sendPacket(new C08PacketPlayerBlockPlacement(BlockPos.ORIGIN, 255, heldItem, 0.0F, 0.0F, 0.0F));
            this.shouldSwap = false;
        }
    }

    private void sendInvalidC08() {
        if (mc.thePlayer.inventory.getFirstEmptyStack() != -1 && mc.thePlayer.ticksExisted % 3 == 0) {
            PacketUtil.sendPacket(new C08PacketPlayerBlockPlacement(new BlockPos(-1, -1, -1), 1, null, 0.0F, 0.0F, 0.0F));
        }
    }

    private void sendIntave14(ItemStack heldItem) {
        PacketUtil.sendPacket(releasePacket(EnumFacing.DOWN));
        PacketUtil.sendPacket(usePacket(heldItem));
    }

    private void sendGrimNew() {
        int slot = mc.thePlayer.inventory.currentItem;
        PacketUtil.sendPacket(new C09PacketHeldItemChange(slot % 8 + 1));
        PacketUtil.sendPacket(new C09PacketHeldItemChange(slot % 7 + 2));
        PacketUtil.sendPacket(new C09PacketHeldItemChange(slot));
    }

    private void sendGrimTick() {
        if (mc.thePlayer.ticksExisted % 32 % 4 < 2) {
            PacketUtil.sendPacket(releasePacket(EnumFacing.DOWN));
        }
    }

    // ---- GRIMAC (food): drop one golden apple of the stack while eating ----

    private void handleGrimConsumeMotion(ItemStack heldItem) {
        if (!(heldItem.getItem() instanceof ItemAppleGold)) {
            return;
        }
        if (!mc.gameSettings.keyBindUseItem.isKeyDown()) {
            if (!this.grimEating) {
                this.grimEating = true;
                this.grimLastFoodAmount = 0;
            }
            return;
        }
        if (this.grimEating) {
            if (heldItem.stackSize <= 1) {
                return;
            }
            this.grimLastFoodAmount = heldItem.stackSize;
            boolean anti = this.canUseGrimAppleDrop(heldItem);
            this.grimFoodNoSlow = anti;
            if (anti) {
                PacketUtil.sendPacketNoEvent(new C07PacketPlayerDigging(C07PacketPlayerDigging.Action.DROP_ITEM, BlockPos.ORIGIN, EnumFacing.DOWN));
                PacketUtil.sendPacket(new C0FPacketConfirmTransaction());
            }
            this.grimEating = false;
        }
        ItemStack current = mc.thePlayer.getHeldItem();
        if (!this.grimEating && (current == null || current.stackSize != this.grimLastFoodAmount)) {
            this.grimFoodNoSlow = false;
            this.grimEating = true;
        }
    }

    private void handleGrimConsumePacket(PacketEvent event, Packet<?> packet) {
        ItemStack heldItem = mc.thePlayer.getHeldItem();
        if (heldItem == null) {
            return;
        }
        if (mc.thePlayer.isUsingItem() && heldItem.getItem() instanceof ItemAppleGold && mc.gameSettings.keyBindUseItem.isKeyDown()
                && packet instanceof C07PacketPlayerDigging
                && ((C07PacketPlayerDigging) packet).getStatus() == C07PacketPlayerDigging.Action.DROP_ITEM) {
            event.setCancelled(true);
            return;
        }
        if (event.getType() != EventType.RECEIVE || !(packet instanceof S2FPacketSetSlot) || !(heldItem.getItem() instanceof ItemAppleGold)) {
            return;
        }
        if (this.canUseGrimAppleDrop(heldItem)) {
            event.setCancelled(true);
        }
    }

    private boolean canUseGrimAppleDrop(ItemStack heldItem) {
        if (heldItem.stackSize <= 1) {
            return false;
        }
        MovingObjectPosition mouseOver = mc.objectMouseOver;
        if (mouseOver == null || mouseOver.typeOfHit != MovingObjectPosition.MovingObjectType.BLOCK || mc.theWorld == null) {
            return true;
        }
        Block block = mc.theWorld.getBlockState(mouseOver.getBlockPos()).getBlock();
        return !(heldItem.getItem() instanceof ItemFood) || !isInteractBlock(block);
    }

    private static boolean isInteractBlock(Block block) {
        return block instanceof BlockFence
                || block instanceof BlockFenceGate
                || block instanceof BlockDoor
                || block instanceof BlockChest
                || block instanceof BlockEnderChest
                || block instanceof BlockEnchantmentTable
                || block instanceof BlockFurnace
                || block instanceof BlockAnvil
                || block instanceof BlockBed
                || block instanceof BlockWorkbench
                || block instanceof BlockNote
                || block instanceof BlockTrapDoor
                || block instanceof BlockHopper
                || block instanceof BlockDispenser
                || block instanceof BlockDaylightDetector
                || block instanceof BlockRedstoneRepeater
                || block instanceof BlockRedstoneComparator
                || block instanceof BlockButton
                || block instanceof BlockBeacon
                || block instanceof BlockBrewingStand
                || block instanceof BlockSign;
    }

    // ---- DROP (food): drop one item when the use starts; the server's slot update lifts the slowdown ----

    private void handleDropPacket(PacketEvent event, Packet<?> packet) {
        ItemStack heldItem = mc.thePlayer.getHeldItem();
        if (heldItem == null || !(heldItem.getItem() instanceof ItemFood) || !this.consumeFood.getValue() || !MoveUtil.isMoving()) {
            this.shouldNoSlow = false;
            return;
        }
        if (!mc.thePlayer.isUsingItem()) {
            this.shouldNoSlow = false;
            this.hasDropped = false;
        }
        boolean useItem = packet instanceof C08PacketPlayerBlockPlacement
                && ((C08PacketPlayerBlockPlacement) packet).getPlacedBlockDirection() == 255;
        if (useItem && !this.hasDropped) {
            PacketUtil.sendPacket(new C07PacketPlayerDigging(C07PacketPlayerDigging.Action.DROP_ITEM, BlockPos.ORIGIN, EnumFacing.DOWN));
            this.shouldNoSlow = false;
            this.hasDropped = true;
        } else if (packet instanceof S2FPacketSetSlot && mc.thePlayer.isUsingItem()) {
            S2FPacketSetSlot setSlot = (S2FPacketSetSlot) packet;
            int slot = mc.thePlayer.inventory.currentItem;
            if (setSlot.func_149175_c() != 0 || setSlot.func_149173_d() != slot + 36) {
                return;
            }
            event.setCancelled(true);
            this.shouldNoSlow = true;
            ItemStack stack = setSlot.func_149174_e();
            IAccessorEntityPlayer accessor = (IAccessorEntityPlayer) mc.thePlayer;
            accessor.setItemInUse(stack);
            if (stack == null) {
                accessor.setItemInUseCount(0);
            }
            mc.thePlayer.inventory.mainInventory[slot] = stack;
        }
    }
}
