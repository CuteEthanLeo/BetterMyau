package myau.module.modules;

import myau.enums.BlinkModules;
import com.google.common.base.CaseFormat;
import myau.Myau;
import myau.event.EventManager;
import myau.event.EventTarget;
import myau.event.types.EventType;
import myau.event.types.Priority;
import myau.events.*;
import myau.management.RotationState;
import myau.mixin.IAccessorMinecraft;
import myau.mixin.IAccessorPlayerControllerMP;
import myau.mixin.IAccessorRenderManager;
import myau.ml.MLAim;
import myau.module.Module;
import myau.property.properties.*;
import myau.util.*;
import net.minecraft.client.Minecraft;
import net.minecraft.client.entity.EntityOtherPlayerMP;
import net.minecraft.client.gui.inventory.GuiContainer;
import net.minecraft.entity.DataWatcher.WatchableObject;
import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityLivingBase;
import net.minecraft.entity.boss.EntityDragon;
import net.minecraft.entity.boss.EntityWither;
import net.minecraft.entity.monster.EntityIronGolem;
import net.minecraft.entity.monster.EntityMob;
import net.minecraft.entity.monster.EntitySilverfish;
import net.minecraft.entity.monster.EntitySlime;
import net.minecraft.entity.passive.EntityAnimal;
import net.minecraft.entity.passive.EntityBat;
import net.minecraft.entity.passive.EntitySquid;
import net.minecraft.entity.passive.EntityVillager;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.item.ItemStack;
import net.minecraft.item.ItemSword;
import net.minecraft.network.play.client.C02PacketUseEntity;
import net.minecraft.network.play.client.C02PacketUseEntity.Action;
import net.minecraft.network.play.client.C07PacketPlayerDigging;
import net.minecraft.network.play.client.C08PacketPlayerBlockPlacement;
import net.minecraft.network.play.client.C09PacketHeldItemChange;
import net.minecraft.network.play.server.S06PacketUpdateHealth;
import net.minecraft.network.play.server.S1CPacketEntityMetadata;
import net.minecraft.util.*;
import net.minecraft.util.MovingObjectPosition.MovingObjectType;
import net.minecraft.world.WorldSettings.GameType;
import org.lwjgl.opengl.GL11;

import java.awt.*;
import java.text.DecimalFormat;
import java.text.DecimalFormatSymbols;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Random;

public class KillAura extends Module {
    private static final Minecraft mc = Minecraft.getMinecraft();
    private static final DecimalFormat df = new DecimalFormat("+0.0;-0.0", new DecimalFormatSymbols(Locale.US));
    private final TimerUtil timer = new TimerUtil();
    /** {@code rotations} index of the ML mode. */
    private static final int ROT_ML = 6;
    private final MLAim mlAim = new MLAim();
    private AttackData target = null;
    private int switchTick = 0;
    private boolean hitRegistered = false;
    private boolean blockingState = false;
    private boolean isBlocking = false;
    private boolean fakeBlockState = false;
    private boolean blinkReset = false;
    private long attackDelayMS = 0L;
    /** Auto-block phase counter; read by Velocity PREDICTION and NoSlow PREDICTION_SEMI. */
    public int blockTick = 0;
    // phase result of this player tick's HYPIXEL_LEADER / TEST / CUSTOM step (see isBlockAttackPhase)
    private boolean phaseResolved = false;
    private boolean phaseAttack = false;
    private boolean swapped = false;
    private boolean postBlock = false;
    private int lastTickProcessed;
    private EntityLivingBase noise4Target;
    private float noise4YawVelocity;
    private float noise4PitchVelocity;
    private float noise4YawDrift;
    private float noise4PitchDrift;
    private float noise4AimX = 0.5F;
    private float noise4AimY = 0.5F;
    private float noise4AimZ = 0.5F;
    private float noise4DesiredX = 0.5F;
    private float noise4DesiredY = 0.5F;
    private float noise4DesiredZ = 0.5F;
    private int noise4AimTicks;

    public final ModeProperty mode;
    public final ModeProperty sort;
    public final ModeProperty autoBlock;
    public final BooleanProperty autoBlockRequirePress;
    public final FloatProperty autoBlockMinCPS;
    public final FloatProperty autoBlockMaxCPS;
    public final FloatProperty autoBlockRange;
    public final FloatProperty swingRange;
    public final FloatProperty attackRange;
    public final IntProperty fov;
    public final IntProperty minCPS;
    public final IntProperty maxCPS;
    public final IntProperty switchDelay;
    public final ModeProperty rotations;
    public final ModeProperty moveFix;
    public final PercentProperty smoothing;
    public final IntProperty angleStep;
    // ML rotation mode (ported from Zak0): see myau.ml.MLAim
    public final FloatProperty mlStrength;
    public final FloatProperty mlMaxMove;
    public final FloatProperty mlSmooth;
    public final TextProperty mlModel;
    public final BooleanProperty throughWalls;
    public final BooleanProperty requirePress;
    public final BooleanProperty allowMining;
    public final BooleanProperty weaponsOnly;
    public final BooleanProperty allowTools;
    public final BooleanProperty inventoryCheck;
    public final BooleanProperty botCheck;
    public final BooleanProperty players;
    public final BooleanProperty bosses;
    public final BooleanProperty mobs;
    public final BooleanProperty animals;
    public final BooleanProperty golems;
    public final BooleanProperty silverfish;
    public final BooleanProperty teams;
    public final ModeProperty showTarget;
    public final BooleanProperty showAimPoint;
    public final ModeProperty debugLog;
    // ── ported from xw (AirClient) KillAura; defaults reproduce the previous behaviour ──
    public final ModeProperty clickMode;
    public final PercentProperty clickDropChance;
    public final IntProperty multiLimit;
    public final BooleanProperty raycast;
    public final BooleanProperty raycastIgnored;
    public final BooleanProperty livingRaycast;
    public final BooleanProperty failSwing;
    public final BooleanProperty swingOnlyInAir;
    public final IntProperty maxRotationDiffToSwing;
    public final ModeProperty unblockMode;
    public final PercentProperty blockRate;
    public final IntProperty blockOnNoHitDelay;
    public final BooleanProperty blockOnNoHitSameTick;

    private static final int AB_QUICK_MACRO = 9;
    private static final int AB_BLOCK_ON_NO_HIT = 10;
    // ── ported from LeaderClient (GPLv3): Hypixel auto-block timings ──
    private static final int AB_HYPIXEL_LEADER = 11;
    private static final int AB_HYPIXEL_TEST = 12;
    private static final int AB_HYPIXEL_CUSTOM = 13;
    public final BooleanProperty noSwap = new BooleanProperty("no-swap", true, () -> this.isAutoBlockMode(AB_HYPIXEL_LEADER));
    // Phase numbers are 1-based; one cycle lasts (max-tick - 1) ticks.
    public final IntProperty maxTick = new IntProperty("custom-max-tick", 3, 2, 6, () -> this.isAutoBlockMode(AB_HYPIXEL_CUSTOM));
    public final IntProperty startBlinkTick = new IntProperty("custom-start-blink-tick", 1, 0, 5, () -> this.isAutoBlockMode(AB_HYPIXEL_CUSTOM));
    public final IntProperty stopBlinkTick = new IntProperty("custom-stop-blink-tick", 2, 0, 5, () -> this.isAutoBlockMode(AB_HYPIXEL_CUSTOM));
    public final IntProperty swapTick = new IntProperty("custom-swap-tick", 2, 0, 5, () -> this.isAutoBlockMode(AB_HYPIXEL_CUSTOM));
    public final IntProperty switchBackTick = new IntProperty("custom-switch-back-tick", 2, 0, 5, () -> this.isAutoBlockMode(AB_HYPIXEL_CUSTOM));
    public final IntProperty stopBlockTick = new IntProperty("custom-stop-block-tick", 2, 0, 5, () -> this.isAutoBlockMode(AB_HYPIXEL_CUSTOM));
    public final IntProperty attackTick = new IntProperty("custom-attack-tick", 1, 0, 5, () -> this.isAutoBlockMode(AB_HYPIXEL_CUSTOM));
    public final IntProperty startBlockTick = new IntProperty("custom-start-block-tick", 1, 0, 5, () -> this.isAutoBlockMode(AB_HYPIXEL_CUSTOM));
    public final BooleanProperty postStartBlock = new BooleanProperty("custom-post-block", false, () -> this.isAutoBlockMode(AB_HYPIXEL_CUSTOM));
    public final BooleanProperty lowTimerCheck = new BooleanProperty("low-timer-check", false);
    private final ClickScheduler clickScheduler = new ClickScheduler();
    private int ticksSinceAttack = 0;

    private void setBlinkModeState(boolean state) {
        if (Myau.blinkManager != null) {
            Myau.blinkManager.setBlinkState(state, BlinkModules.AUTO_BLOCK);
        }
    }

    private long getAttackDelay() {
        if (this.clickMode.getValue() != 0) {
            // STABLE / GAUSSIAN / BUTTERFLY / DRAG: interval drawn from the chosen distribution
            return this.isBlocking
                    ? this.clickScheduler.nextDelay(this.clickMode.getValue() - 1, this.autoBlockMinCPS.getValue(), this.autoBlockMaxCPS.getValue())
                    : this.clickScheduler.nextDelay(this.clickMode.getValue() - 1, this.minCPS.getValue(), this.maxCPS.getValue());
        }
        return this.isBlocking ? (long) (1000.0F / RandomUtil.nextLong(this.autoBlockMinCPS.getValue().longValue(), this.autoBlockMaxCPS.getValue().longValue())) : 1000L / RandomUtil.nextLong(this.minCPS.getValue(), this.maxCPS.getValue());
    }

    /** Whether to swing at thin air when the crosshair is not (yet) on the target. */
    private boolean shouldFailSwing(float yaw, float pitch) {
        if (!this.failSwing.getValue()) {
            return false;
        }
        if (this.maxRotationDiffToSwing.getValue() < 180) {
            float[] ideal = RotationUtil.getRotationsToEntity(this.target.getEntity());
            if (RotationUtil.getRotationDifference(yaw, pitch, ideal[0], ideal[1]) > this.maxRotationDiffToSwing.getValue()) {
                return false;
            }
        }
        if (this.swingOnlyInAir.getValue()) {
            MovingObjectPosition mop = RotationUtil.rayTrace(yaw, pitch, this.attackRange.getValue(), 1.0F);
            return mop == null || mop.typeOfHit == MovingObjectType.MISS;
        }
        return true;
    }

    /** AutoGapple is holding packets to eat and wants the aura to keep hitting (always-attack), and there is a sword to hit with. */
    private boolean isCombatEating() {
        return AutoGapple.isCombatEating()
                && (ItemUtil.isHoldingSword() || this.findSwordSlot(mc.thePlayer.inventory.currentItem) != -1);
    }

    private void sendAttack(EntityLivingBase entity) {
        AttackEvent event = new AttackEvent(entity);
        EventManager.call(event);
        ((IAccessorPlayerControllerMP) mc.playerController).callSyncCurrentPlayItem();
        // eating with always-attack and not holding the sword: hit with it for this packet only
        int swordSlot = this.isCombatEating() && !ItemUtil.isHoldingSword()
                ? this.findSwordSlot(mc.thePlayer.inventory.currentItem) : -1;
        if (swordSlot != -1) {
            PacketUtil.sendPacket(new C09PacketHeldItemChange(swordSlot));
        }
        PacketUtil.sendPacket(new C02PacketUseEntity(entity, Action.ATTACK));
        if (swordSlot != -1) {
            PacketUtil.sendPacket(new C09PacketHeldItemChange(mc.thePlayer.inventory.currentItem));
        }
        if (mc.playerController.getCurrentGameType() != GameType.SPECTATOR) {
            PlayerUtil.attackEntity(entity);
        }
    }

    private boolean performAttack(float yaw, float pitch) {
        if (!Myau.playerStateManager.digging && !Myau.playerStateManager.placing) {
            if (this.isPlayerBlocking() && this.autoBlock.getValue() != 1 && this.isCombatEating()) {
                this.stopBlock(); // AutoGapple always-attack: drop the block and hit
            }
            if (this.isPlayerBlocking() && this.autoBlock.getValue() != 1) {
                return false;
            } else if (this.attackDelayMS > 0L) {
                return false;
            } else if (Velocity.extraAttacked && this.autoBlock.getValue() == AB_HYPIXEL_LEADER) {
                // Velocity PREDICTION already sent this tick's hit (LeaderClient)
                Velocity.extraAttacked = false;
                Velocity velocity = (Velocity) Myau.moduleManager.modules.get(Velocity.class);
                if (velocity.reduceMode.getValue() == 2) {
                    this.blockTick = 0;
                }
                if (velocity.reduceMode.getValue() == 1) {
                    setBlinkModeState(false);
                    this.blockTick = 2;
                }
                return false;
            } else {
                this.attackDelayMS = this.attackDelayMS + this.getAttackDelay();
                if (this.clickMode.getValue() != 0 && this.clickScheduler.rollDrop(this.clickDropChance.getValue() / 100.0F)) {
                    return false; // this click slipped: it took its slot on the timeline but nothing was pressed
                }
                boolean onTarget = !((this.rotations.getValue() != 0 || !this.isBoxInAttackRange(this.target.getBox()))
                        && RotationUtil.rayTrace(this.target.getBox(), yaw, pitch, this.attackRange.getValue()) == null);
                EntityLivingBase attackEntity = this.target.getEntity();
                if (onTarget && this.raycast.getValue() && this.rotations.getValue() != 0) {
                    Entity first = RotationUtil.raycastEntity(
                            this.attackRange.getValue(), yaw, pitch,
                            entity -> !this.livingRaycast.getValue() || entity instanceof EntityLivingBase && !(entity instanceof net.minecraft.entity.item.EntityArmorStand)
                    );
                    if (first != null && first != attackEntity) {
                        // something else is in the way: hit it instead (if allowed), otherwise only swing
                        if (this.raycastIgnored.getValue() && first instanceof EntityLivingBase && this.isValidTarget((EntityLivingBase) first)) {
                            attackEntity = (EntityLivingBase) first;
                        } else {
                            onTarget = false;
                        }
                    }
                }
                if (!onTarget) {
                    if (this.shouldFailSwing(yaw, pitch)) {
                        mc.thePlayer.swingItem();
                    }
                    return false;
                } else {
                    mc.thePlayer.swingItem();
                    this.sendAttack(attackEntity);
                    if (this.mode.getValue() == 2) {
                        // MULTI: every other valid target in reach gets hit by the same swing
                        int limit = this.multiLimit.getValue();
                        int hits = 1;
                        for (Entity entity : new ArrayList<>(mc.theWorld.loadedEntityList)) {
                            if (limit != 0 && hits >= limit) {
                                break;
                            }
                            if (entity != attackEntity && entity instanceof EntityLivingBase
                                    && this.isValidTarget((EntityLivingBase) entity)
                                    && this.isInAttackRange((EntityLivingBase) entity)) {
                                this.sendAttack((EntityLivingBase) entity);
                                hits++;
                            }
                        }
                    }
                    this.hitRegistered = true;
                    this.ticksSinceAttack = 0;
                    return true;
                }
            }
        } else {
            return false;
        }
    }

    private void sendUseItem() {
        if (this.autoBlock.getValue() >= AB_QUICK_MACRO && this.blockRate.getValue() < 100
                && RandomUtil.nextFloat(0.0F, 100.0F) > this.blockRate.getValue()) {
            return; // block-rate: this block is skipped
        }
        ((IAccessorPlayerControllerMP) mc.playerController).callSyncCurrentPlayItem();
        this.startBlock(mc.thePlayer.getHeldItem());
    }

    private C08PacketPlayerBlockPlacement createBlockPacket(ItemStack itemStack) {
        if (this.autoBlock.getValue() == AB_QUICK_MACRO) {
            return new C08PacketPlayerBlockPlacement(new BlockPos(-1, -2, -1), 255, null, 0.0F, 0.0F, 0.0F);
        }
        return new C08PacketPlayerBlockPlacement(itemStack);
    }

    private void startBlock(ItemStack itemStack) {
        PacketUtil.sendPacket(this.createBlockPacket(itemStack));
        mc.thePlayer.setItemInUse(itemStack, itemStack.getMaxItemUseDuration());
        this.blockingState = true;
    }

    private void stopBlock() {
        PacketUtil.sendPacket(new C07PacketPlayerDigging(C07PacketPlayerDigging.Action.RELEASE_USE_ITEM, BlockPos.ORIGIN, EnumFacing.DOWN));
        mc.thePlayer.stopUsingItem();
        this.blockingState = false;
    }

    /** Unblock the way unblock-mode asks for: release packet, or a slot switch (which also ends the use). */
    private void stopBlockByMode() {
        int item = ((IAccessorPlayerControllerMP) mc.playerController).getCurrentPlayerItem();
        int slot = -1;
        switch (this.unblockMode.getValue()) {
            case 1: // SWITCH
                slot = (item + 1) % 9;
                break;
            case 2: // EMPTY
                for (int i = 0; i < 9; i++) {
                    if (i != item && mc.thePlayer.inventory.getStackInSlot(i) == null) {
                        slot = i;
                        break;
                    }
                }
                break;
        }
        if (slot == -1) {
            this.stopBlock();
            return;
        }
        PacketUtil.sendPacket(new C09PacketHeldItemChange(slot));
        PacketUtil.sendPacket(new C09PacketHeldItemChange(item));
        mc.thePlayer.stopUsingItem();
        this.blockingState = false;
    }

    private void interactAttack(float yaw, float pitch) {
        if (this.target != null) {
            MovingObjectPosition mop = RotationUtil.rayTrace(this.target.getBox(), yaw, pitch, 8.0);
            if (mop != null) {
                ((IAccessorPlayerControllerMP) mc.playerController).callSyncCurrentPlayItem();
                PacketUtil.sendPacket(
                        new C02PacketUseEntity(
                                this.target.getEntity(),
                                new Vec3(mop.hitVec.xCoord - this.target.getX(), mop.hitVec.yCoord - this.target.getY(), mop.hitVec.zCoord - this.target.getZ())
                        )
                );
                PacketUtil.sendPacket(new C02PacketUseEntity(this.target.getEntity(), Action.INTERACT));
                PacketPacketBypass();
            }
        }
    }

    private void PacketPacketBypass() {
        PacketUtil.sendPacket(this.createBlockPacket(mc.thePlayer.getHeldItem()));
        mc.thePlayer.setItemInUse(mc.thePlayer.getHeldItem(), mc.thePlayer.getHeldItem().getMaxItemUseDuration());
        this.blockingState = true;
    }

    private boolean canAttack() {
        boolean combatEating = this.isCombatEating();
        if (this.inventoryCheck.getValue() && mc.currentScreen instanceof GuiContainer) {
            return false;
        } else if (this.lowTimerCheck.getValue() && ((IAccessorMinecraft) mc).getTimer().timerSpeed < 1.0F) {
            return false;
        } else if (combatEating
                || !(Boolean) this.weaponsOnly.getValue()
                || ItemUtil.hasRawUnbreakingEnchant()
                || this.allowTools.getValue() && ItemUtil.isHoldingTool()) {
            if (((IAccessorPlayerControllerMP) mc.playerController).getIsHittingBlock()) {
                return false;
            } else if (!combatEating && (ItemUtil.isEating() || ItemUtil.isUsingBow()) && PlayerUtil.isUsingItem()) {
                return false;
            } else {
                AutoHeal autoHeal = (AutoHeal) Myau.moduleManager.modules.get(AutoHeal.class);
                AutoRod autoRod = (AutoRod) Myau.moduleManager.modules.get(AutoRod.class);
                Clutch clutch = (Clutch) Myau.moduleManager.modules.get(Clutch.class);
                if (autoHeal.isEnabled() && autoHeal.isSwitching()) {
                    return false;
                } else if (clutch != null && clutch.isBusy()) {
                    // Falling into the void: Clutch holds the block slot until we land.
                    return false;
                } else if (autoRod != null && autoRod.isBusy()) {
                    // AutoRod's hook is in flight; it hands the sword back as soon as the hook lands.
                    return false;
                } else {
                    BedNuker bedNuker = (BedNuker) Myau.moduleManager.modules.get(BedNuker.class);
                    AutoBlockIn autoBlockIn = (AutoBlockIn) Myau.moduleManager.modules.get(AutoBlockIn.class);
                    if (bedNuker.isEnabled() && bedNuker.isReady()) {
                        return false;
                    } else if (LeaderScaffold.isAnyScaffoldEnabled()) {
                        return false;
                    } else if (autoBlockIn.isEnabled()) {
                        return false;
                    } else if (this.requirePress.getValue()) {
                        return PlayerUtil.isAttacking();
                    } else {
                        return !this.allowMining.getValue() || !mc.objectMouseOver.typeOfHit.equals(MovingObjectType.BLOCK) || !PlayerUtil.isAttacking();
                    }
                }
            }
        } else {
            return false;
        }
    }

    private boolean canAutoBlock() {
        if (this.isCombatEating()) {
            return false; // AutoGapple is eating: no blocking until the apple is down
        } else if (!ItemUtil.isHoldingSword()) {
            return false;
        } else {
            return !this.autoBlockRequirePress.getValue() || PlayerUtil.isUsingItem();
        }
    }

    private boolean hasValidTarget() {
        return mc.theWorld
                .loadedEntityList
                .stream()
                .anyMatch(
                        entity -> entity instanceof EntityLivingBase
                                && this.isValidTarget((EntityLivingBase) entity)
                                && this.isInBlockRange((EntityLivingBase) entity)
                );
    }

    private boolean isValidTarget(EntityLivingBase entityLivingBase) {
        if (!mc.theWorld.loadedEntityList.contains(entityLivingBase)) {
            return false;
        } else if (entityLivingBase != mc.thePlayer && entityLivingBase != mc.thePlayer.ridingEntity) {
            if (entityLivingBase == mc.getRenderViewEntity() || entityLivingBase == mc.getRenderViewEntity().ridingEntity) {
                return false;
            } else if (entityLivingBase.deathTime > 0) {
                return false;
            } else if (RotationUtil.angleToEntity(entityLivingBase) > this.fov.getValue().floatValue()) {
                return false;
            } else if (!this.throughWalls.getValue() && RotationUtil.rayTrace(entityLivingBase) != null) {
                return false;
            } else if (entityLivingBase instanceof EntityOtherPlayerMP) {
                if (!this.players.getValue()) {
                    return false;
                } else if (TeamUtil.isFriend((EntityPlayer) entityLivingBase)) {
                    return false;
                } else {
                    return (!this.teams.getValue() || !TeamUtil.isSameTeam((EntityPlayer) entityLivingBase)) && (!this.botCheck.getValue() || !TeamUtil.isBot((EntityPlayer) entityLivingBase));
                }
            } else if (entityLivingBase instanceof EntityDragon || entityLivingBase instanceof EntityWither) {
                return this.bosses.getValue();
            } else if (!(entityLivingBase instanceof EntityMob) && !(entityLivingBase instanceof EntitySlime)) {
                if (entityLivingBase instanceof EntityAnimal
                        || entityLivingBase instanceof EntityBat
                        || entityLivingBase instanceof EntitySquid
                        || entityLivingBase instanceof EntityVillager) {
                    return this.animals.getValue();
                } else if (!(entityLivingBase instanceof EntityIronGolem)) {
                    return false;
                } else {
                    return this.golems.getValue() && (!this.teams.getValue() || !TeamUtil.hasTeamColor(entityLivingBase));
                }
            } else if (!(entityLivingBase instanceof EntitySilverfish)) {
                return this.mobs.getValue();
            } else {
                return this.silverfish.getValue() && (!this.teams.getValue() || !TeamUtil.hasTeamColor(entityLivingBase));
            }
        } else {
            return false;
        }
    }

    private boolean isInRange(EntityLivingBase entityLivingBase) {
        return this.isInBlockRange(entityLivingBase) || this.isInSwingRange(entityLivingBase) || this.isInAttackRange(entityLivingBase);
    }

    private boolean isInBlockRange(EntityLivingBase entityLivingBase) {
        return RotationUtil.distanceToEntity(entityLivingBase) <= (double) this.autoBlockRange.getValue();
    }

    private boolean isInSwingRange(EntityLivingBase entityLivingBase) {
        return RotationUtil.distanceToEntity(entityLivingBase) <= (double) this.swingRange.getValue();
    }

    private boolean isBoxInSwingRange(AxisAlignedBB axisAlignedBB) {
        return RotationUtil.distanceToBox(axisAlignedBB) <= (double) this.swingRange.getValue();
    }

    private boolean isInAttackRange(EntityLivingBase entityLivingBase) {
        return RotationUtil.distanceToEntity(entityLivingBase) <= (double) this.attackRange.getValue();
    }

    private boolean isBoxInAttackRange(AxisAlignedBB axisAlignedBB) {
        return RotationUtil.distanceToBox(axisAlignedBB) <= (double) this.attackRange.getValue();
    }

    private static int getRegenAmplifier(EntityLivingBase entity) {
        return entity.isPotionActive(net.minecraft.potion.Potion.regeneration)
                ? entity.getActivePotionEffect(net.minecraft.potion.Potion.regeneration).getAmplifier()
                : -1;
    }

    private static boolean isInWeb(EntityLivingBase entity) {
        return mc.theWorld.getBlockState(new BlockPos(entity.posX, entity.posY, entity.posZ)).getBlock() == net.minecraft.init.Blocks.web;
    }

    private boolean isPlayerTarget(EntityLivingBase entityLivingBase) {
        return entityLivingBase instanceof EntityPlayer && TeamUtil.isTarget((EntityPlayer) entityLivingBase);
    }

    private int findEmptySlot(int currentSlot) {
        for (int i = 0; i < 9; i++) {
            if (i != currentSlot && mc.thePlayer.inventory.getStackInSlot(i) == null) {
                return i;
            }
        }
        for (int i = 0; i < 9; i++) {
            if (i != currentSlot) {
                ItemStack stack = mc.thePlayer.inventory.getStackInSlot(i);
                if (stack != null && !stack.hasDisplayName()) {
                    return i;
                }
            }
        }
        return Math.floorMod(currentSlot - 1, 9);
    }

    private int findSwordSlot(int currentSlot) {
        for (int i = 0; i < 9; i++) {
            if (i != currentSlot) {
                ItemStack item = mc.thePlayer.inventory.getStackInSlot(i);
                if (item != null && item.getItem() instanceof ItemSword) {
                    return i;
                }
            }
        }
        return -1;
    }

    private float[] getNoiseOffset(AttackData target) {
        long t = System.currentTimeMillis();
        int id = target.getEntity().getEntityId();
        double dist = RotationUtil.distanceToBox(target.getBox());

        float maxAmp = (float) Math.min(2.5, Math.toDegrees(Math.atan2(0.6, dist)) * 0.7);
        float yawAmp = maxAmp;
        float pitchAmp = maxAmp * 0.65f;

        float noiseYaw = (float) Math.sin(t * 0.0023 + id * 2.7) * yawAmp;
        float noisePitch = (float) Math.cos(t * 0.0019 + id * 4.1) * pitchAmp;

        return new float[]{noiseYaw, noisePitch};
    }

    private void resetNoise4() {
        this.noise4Target = null;
        this.noise4YawVelocity = 0.0F;
        this.noise4PitchVelocity = 0.0F;
        this.noise4YawDrift = 0.0F;
        this.noise4PitchDrift = 0.0F;
        this.noise4AimX = this.noise4DesiredX = 0.5F;
        this.noise4AimY = this.noise4DesiredY = 0.5F;
        this.noise4AimZ = this.noise4DesiredZ = 0.5F;
        this.noise4AimTicks = 0;
    }

    private float[] getNoise4Rotation(AttackData target, float currentYaw, float currentPitch) {
        EntityLivingBase entity = target.getEntity();
        if (this.noise4Target != entity) {
            this.resetNoise4();
            this.noise4Target = entity;
        }

        if (--this.noise4AimTicks <= 0) {
            this.noise4DesiredX = RandomUtil.nextFloat(0.43F, 0.57F);
            this.noise4DesiredY = RandomUtil.nextFloat(0.43F, 0.60F);
            this.noise4DesiredZ = RandomUtil.nextFloat(0.43F, 0.57F);
            this.noise4AimTicks = (int) RandomUtil.nextFloat(6.0F, 11.0F);
        }
        this.noise4AimX += (this.noise4DesiredX - this.noise4AimX) * 0.18F;
        this.noise4AimY += (this.noise4DesiredY - this.noise4AimY) * 0.18F;
        this.noise4AimZ += (this.noise4DesiredZ - this.noise4AimZ) * 0.18F;

        AxisAlignedBB box = target.getBox();
        double x = MathHelper.clamp_double(box.minX + (box.maxX - box.minX) * this.noise4AimX
                + (entity.posX - entity.prevPosX) * 0.5, box.minX + (box.maxX - box.minX) * 0.25,
                box.minX + (box.maxX - box.minX) * 0.75);
        double y = MathHelper.clamp_double(box.minY + (box.maxY - box.minY) * this.noise4AimY
                + (entity.posY - entity.prevPosY) * 0.5, box.minY + (box.maxY - box.minY) * 0.30,
                box.minY + (box.maxY - box.minY) * 0.70);
        double z = MathHelper.clamp_double(box.minZ + (box.maxZ - box.minZ) * this.noise4AimZ
                + (entity.posZ - entity.prevPosZ) * 0.5, box.minZ + (box.maxZ - box.minZ) * 0.25,
                box.minZ + (box.maxZ - box.minZ) * 0.75);
        Vec3 eyes = mc.thePlayer.getPositionEyes(1.0F);
        double dx = x - eyes.xCoord;
        double dy = y - eyes.yCoord;
        double dz = z - eyes.zCoord;
        double horizontal = Math.sqrt(dx * dx + dz * dz);
        float targetYaw = (float) (Math.atan2(dz, dx) * 180.0 / Math.PI) - 90.0F;
        float targetPitch = (float) (-Math.atan2(dy, horizontal) * 180.0 / Math.PI);

        float driftLimit = (float) Math.min(0.35, Math.toDegrees(Math.atan2(0.025, Math.max(0.1, horizontal))));
        this.noise4YawDrift = this.noise4YawDrift * 0.82F + RandomUtil.nextFloat(-driftLimit, driftLimit) * 0.18F;
        this.noise4PitchDrift = this.noise4PitchDrift * 0.82F + RandomUtil.nextFloat(-driftLimit, driftLimit) * 0.18F;
        float smoothingFactor = this.smoothing.getValue() / 100.0F;
        float speedLimit = this.angleStep.getValue() * (1.0F - 0.55F * smoothingFactor);
        float acceleration = Math.max(0.75F, speedLimit * 0.24F);
        float yawError = MathHelper.wrapAngleTo180_float(targetYaw + this.noise4YawDrift - currentYaw);
        float pitchError = targetPitch + this.noise4PitchDrift - currentPitch;
        float desiredYawVelocity = MathHelper.clamp_float(yawError * 0.48F, -speedLimit, speedLimit);
        float desiredPitchVelocity = MathHelper.clamp_float(pitchError * 0.42F, -speedLimit, speedLimit);
        this.noise4YawVelocity += MathHelper.clamp_float(desiredYawVelocity - this.noise4YawVelocity, -acceleration, acceleration);
        this.noise4PitchVelocity += MathHelper.clamp_float(desiredPitchVelocity - this.noise4PitchVelocity, -acceleration, acceleration);

        float yaw = RotationUtil.quantizeAngle(currentYaw + this.noise4YawVelocity);
        float pitch = RotationUtil.quantizeAngle(MathHelper.clamp_float(currentPitch + this.noise4PitchVelocity, -90.0F, 90.0F));
        if (RotationUtil.rayTrace(box, currentYaw, currentPitch, this.attackRange.getValue()) != null
                && RotationUtil.rayTrace(box, yaw, pitch, this.attackRange.getValue()) == null) {
            this.noise4YawVelocity = 0.0F;
            this.noise4PitchVelocity = 0.0F;
            yaw = RotationUtil.quantizeAngle(currentYaw);
            pitch = RotationUtil.quantizeAngle(MathHelper.clamp_float(currentPitch, -90.0F, 90.0F));
        }
        return new float[]{yaw, pitch};
    }

    public KillAura() {
        super("KillAura", false);
        this.lastTickProcessed = 0;
        this.mode = new ModeProperty("mode", 0, new String[]{"SINGLE", "SWITCH", "MULTI"});
        this.sort = new ModeProperty("sort", 0, new String[]{
                "DISTANCE", "HEALTH", "HURT_TIME", "FOV",
                "LIVING_TIME", "ARMOR", "HURT_TICKS", "HEALTH_ABSORPTION", "REGEN_AMPLIFIER", "ON_LADDER", "IN_LIQUID", "IN_WEB"
        });
        this.autoBlock = new ModeProperty(
                "auto-block", 2, new String[]{"NONE", "VANILLA", "SPOOF", "HYPIXEL", "BLINK", "INTERACT", "SWAP", "LEGIT", "FAKE", "QUICK_MACRO", "BLOCK_ON_NO_HIT", "HYPIXEL_LEADER", "HYPIXEL_TEST", "HYPIXEL_CUSTOM"}
        );
        this.autoBlockRequirePress = new BooleanProperty("auto-block-require-press", false);
        this.autoBlockMinCPS = new FloatProperty("auto-block-min-aps", 8.0F, 1.0F, 20.0F);
        this.autoBlockMaxCPS = new FloatProperty("auto-block-max-aps", 10.0F, 1.0F, 20.0F);
        this.autoBlockRange = new FloatProperty("auto-block-range", 6.0F, 3.0F, 8.0F);
        this.swingRange = new FloatProperty("swing-range", 3.5F, 3.0F, 6.0F);
        this.attackRange = new FloatProperty("attack-range", 3.0F, 3.0F, 6.0F);
        this.fov = new IntProperty("fov", 360, 30, 360);
        this.minCPS = new IntProperty("min-aps", 14, 1, 20);
        this.maxCPS = new IntProperty("max-aps", 14, 1, 20);
        this.switchDelay = new IntProperty("switch-delay", 150, 0, 1000);
        this.rotations = new ModeProperty("rotations", 2, new String[]{"NONE", "LEGIT", "SILENT", "LOCK_VIEW", "NOISE3", "NOISE4", "ML"});
        this.moveFix = new ModeProperty("move-fix", 1, new String[]{"NONE", "SILENT", "STRICT"});
        this.smoothing = new PercentProperty("smoothing", 0);
        this.angleStep = new IntProperty("angle-step", 90, 30, 180);
        this.mlStrength = new FloatProperty("ml-strength", 1.0F, 0.0F, 3.0F, () -> this.rotations.getValue() == ROT_ML);
        this.mlMaxMove = new FloatProperty("ml-max-move", 15.0F, 1.0F, 45.0F, () -> this.rotations.getValue() == ROT_ML);
        this.mlSmooth = new FloatProperty("ml-smooth", 0.3F, 0.0F, 0.95F, () -> this.rotations.getValue() == ROT_ML);
        this.mlModel = new TextProperty("ml-model", MLAim.LATEST, () -> this.rotations.getValue() == ROT_ML);
        this.throughWalls = new BooleanProperty("through-walls", true);
        this.requirePress = new BooleanProperty("require-press", false);
        this.allowMining = new BooleanProperty("allow-mining", true);
        this.weaponsOnly = new BooleanProperty("weapons-only", true);
        this.allowTools = new BooleanProperty("allow-tools", false, this.weaponsOnly::getValue);
        this.inventoryCheck = new BooleanProperty("inventory-check", true);
        this.botCheck = new BooleanProperty("bot-check", true);
        this.players = new BooleanProperty("players", true);
        this.bosses = new BooleanProperty("bosses", false);
        this.mobs = new BooleanProperty("mobs", false);
        this.animals = new BooleanProperty("animals", false);
        this.golems = new BooleanProperty("golems", false);
        this.silverfish = new BooleanProperty("silverfish", false);
        this.teams = new BooleanProperty("teams", true);
        this.showTarget = new ModeProperty("show-target", 1, new String[]{"NONE", "HALO"});
        this.showAimPoint = new BooleanProperty("show-aim-point", true);
        this.debugLog = new ModeProperty("debug-log", 0, new String[]{"NONE", "HEALTH"});
        this.clickMode = new ModeProperty("click-mode", 0, new String[]{"LEGACY", "STABLE", "GAUSSIAN", "BUTTERFLY", "DRAG"});
        this.clickDropChance = new PercentProperty("click-drop-chance", 0, 0, 30, () -> this.clickMode.getValue() != 0);
        this.multiLimit = new IntProperty("multi-limit", 0, 0, 50, () -> this.mode.getValue() == 2);
        this.raycast = new BooleanProperty("raycast", false, () -> this.rotations.getValue() != 0);
        this.raycastIgnored = new BooleanProperty("raycast-ignored", false, () -> this.rotations.getValue() != 0 && this.raycast.getValue());
        this.livingRaycast = new BooleanProperty("living-raycast", true, () -> this.rotations.getValue() != 0 && this.raycast.getValue());
        this.failSwing = new BooleanProperty("fail-swing", true);
        this.swingOnlyInAir = new BooleanProperty("swing-only-in-air", false, this.failSwing::getValue);
        this.maxRotationDiffToSwing = new IntProperty("max-rotation-diff-to-swing", 180, 0, 180, this.failSwing::getValue);
        this.unblockMode = new ModeProperty("unblock-mode", 0, new String[]{"STOP", "SWITCH", "EMPTY"}, () -> this.autoBlock.getValue() >= AB_QUICK_MACRO);
        this.blockRate = new PercentProperty("block-rate", 100, 1, 100, () -> this.autoBlock.getValue() >= AB_QUICK_MACRO);
        this.blockOnNoHitDelay = new IntProperty("block-on-no-hit-delay", 1, 0, 20, () -> this.autoBlock.getValue() == AB_BLOCK_ON_NO_HIT);
        this.blockOnNoHitSameTick = new BooleanProperty("block-on-no-hit-same-tick", false, () -> this.autoBlock.getValue() == AB_BLOCK_ON_NO_HIT);
    }

    // ==========================================
    // 🌟 补回刚才漏贴的两个极其重要的外部调用方法
    // ==========================================
    public EntityLivingBase getTarget() {
        return this.target != null ? this.target.getEntity() : null;
    }

    public boolean isAttackAllowed() {
        if (LeaderScaffold.isAnyScaffoldEnabled()) {
            return false;
        } else if (!this.weaponsOnly.getValue()
                || ItemUtil.hasRawUnbreakingEnchant()
                || this.allowTools.getValue() && ItemUtil.isHoldingTool()) {
            return !this.requirePress.getValue() || KeyBindUtil.isKeyDown(mc.gameSettings.keyBindAttack.getKeyCode());
        } else {
            return false;
        }
    }

    public boolean shouldAutoBlock() {
        if (this.isCombatEating()) {
            return false;
        } else if (this.isPlayerBlocking() && this.isBlocking) {
            return !mc.thePlayer.isInWater() && !mc.thePlayer.isInLava() && (this.autoBlock.getValue() == 3
                    || this.autoBlock.getValue() == 4
                    || this.autoBlock.getValue() == 5
                    || this.autoBlock.getValue() == 6
                    || this.autoBlock.getValue() == 7
                    || this.autoBlock.getValue() == AB_QUICK_MACRO
                    || this.isHypixelLeaderBlock());
        } else {
            return false;
        }
    }

    private boolean isAutoBlockMode(int mode) {
        return this.autoBlock.getValue() == mode;
    }

    /** One of the three auto-block modes ported from LeaderClient. */
    public boolean isHypixelLeaderBlock() {
        int mode = this.autoBlock.getValue();
        return mode == AB_HYPIXEL_LEADER || mode == AB_HYPIXEL_TEST || mode == AB_HYPIXEL_CUSTOM;
    }

    public String getAutoBlockModeName() {
        return this.autoBlock.getModeString();
    }

    private void sendRandomSlotSwap() {
        int current = mc.thePlayer.inventory.currentItem;
        int randomSlot = new Random().nextInt(9);
        while (randomSlot == current) {
            randomSlot = new Random().nextInt(9);
        }
        PacketUtil.sendPacket(new C09PacketHeldItemChange(randomSlot));
        PacketUtil.sendPacket(new C09PacketHeldItemChange(current));
    }

    public boolean isBlocking() {
        return this.fakeBlockState && ItemUtil.isHoldingSword();
    }

    public boolean isPlayerBlocking() {
        return (mc.thePlayer.isUsingItem() || this.blockingState) && ItemUtil.isHoldingSword();
    }

    @EventTarget(Priority.HIGHEST)
    public void onUpdateEarly(UpdateEvent event) {
        if (event.getType() == EventType.PRE) {
            this.phaseResolved = false;
        }
    }

    /**
     * Whether the LeaderClient auto-block modes let KillAura hit on the current player tick. Single definition
     * for Velocity / NoSlow: before KillAura's PRE step (Velocity, MEDIUM priority) it is predicted from the
     * not-yet-advanced counter; after it (NoSlow's slowdown check in onLivingUpdate, POST handlers) it is the
     * value KillAura actually used, so the counter wrap/advance cannot shift it by one.
     */
    public boolean isBlockAttackPhase() {
        if (!this.isEnabled() || !this.isHypixelLeaderBlock() || !this.isBlocking) {
            return false;
        }
        if (this.phaseResolved) {
            return this.phaseAttack;
        }
        if (this.autoBlock.getValue() == AB_HYPIXEL_CUSTOM) {
            return this.blockTick + 1 == this.attackTick.getValue();
        }
        return this.blockTick == 0; // LEADER / TEST hit on step 0
    }

    @EventTarget(Priority.LOW)
    public void onUpdate(UpdateEvent event) {
        if (event.getType() == EventType.POST && this.postBlock) {
            this.postBlock = false;
            if (this.isEnabled() && ItemUtil.isHoldingSword()) {
                this.sendUseItem();
            }
        }
        if (event.getType() == EventType.POST && this.blinkReset) {
            this.blinkReset = false;
            setBlinkModeState(false);
            setBlinkModeState(true);
        }
        if (this.isEnabled() && event.getType() == EventType.PRE) {
            boolean noise4 = this.rotations.getValue() == 5;
            if (!noise4) {
                this.resetNoise4();
            }
            if (this.attackDelayMS > 0L) {
                this.attackDelayMS -= 50L;
            }
            if (this.ticksSinceAttack < 1000) {
                this.ticksSinceAttack++;
            }
            boolean attack = this.target != null && this.canAttack();
            if (noise4 && (!attack || !this.isBoxInSwingRange(this.target.getBox()))) {
                this.resetNoise4();
            }
            boolean block = attack && this.canAutoBlock();
            boolean noise4Ready = false;
            if (noise4 && attack && this.isBoxInSwingRange(this.target.getBox())) {
                float[] rotation = this.getNoise4Rotation(this.target, event.getYaw(), event.getPitch());
                event.setRotation(rotation[0], rotation[1], 1);
                if (this.moveFix.getValue() != 0) {
                    event.setPervRotation(rotation[0], 1);
                }
                noise4Ready = RotationUtil.rayTrace(this.target.getBox(), event.getNewYaw(), event.getNewPitch(),
                        this.attackRange.getValue()) != null;
            }
            if (!block) {
                setBlinkModeState(false);
                this.isBlocking = false;
                this.fakeBlockState = false;
                this.blockTick = 0;
            }
            if (attack) {
                boolean swap = false;
                boolean blocked = false;
                int autoBlockMode = this.autoBlock.getValue();
                if (block && !(noise4 && !noise4Ready && (autoBlockMode >= 2 && autoBlockMode <= 7 || autoBlockMode >= AB_QUICK_MACRO))) {
                    switch (autoBlockMode) {
                        case 0:
                            if (PlayerUtil.isUsingItem()) {
                                this.isBlocking = true;
                                if (!this.isPlayerBlocking() && !Myau.playerStateManager.digging && !Myau.playerStateManager.placing) {
                                    swap = true;
                                }
                            } else {
                                this.isBlocking = false;
                                if (this.isPlayerBlocking() && !Myau.playerStateManager.digging && !Myau.playerStateManager.placing) {
                                    this.stopBlock();
                                }
                            }
                            setBlinkModeState(false);
                            this.fakeBlockState = false;
                            break;
                        case 1:
                            if (this.hasValidTarget()) {
                                if (!this.isPlayerBlocking() && !Myau.playerStateManager.digging && !Myau.playerStateManager.placing) {
                                    swap = true;
                                }
                                setBlinkModeState(false);
                                this.isBlocking = true;
                                this.fakeBlockState = false;
                            } else {
                                setBlinkModeState(false);
                                this.isBlocking = false;
                                this.fakeBlockState = false;
                            }
                            break;
                        case 2:
                            if (this.hasValidTarget()) {
                                int item = ((IAccessorPlayerControllerMP) mc.playerController).getCurrentPlayerItem();
                                if (Myau.playerStateManager.digging
                                        || Myau.playerStateManager.placing
                                        || mc.thePlayer.inventory.currentItem != item
                                        || this.isPlayerBlocking() && this.blockTick != 0
                                        || this.attackDelayMS > 0L && this.attackDelayMS <= 50L) {
                                    this.blockTick = 0;
                                } else {
                                    int slot = this.findEmptySlot(item);
                                    PacketUtil.sendPacket(new C09PacketHeldItemChange(slot));
                                    PacketUtil.sendPacket(new C09PacketHeldItemChange(item));
                                    swap = true;
                                    this.blockTick = 1;
                                }
                                setBlinkModeState(false);
                                this.isBlocking = true;
                                this.fakeBlockState = false;
                            } else {
                                setBlinkModeState(false);
                                this.isBlocking = false;
                                this.fakeBlockState = false;
                            }
                            break;
                        case 3:
                            if (this.hasValidTarget()) {
                                if (!Myau.playerStateManager.digging && !Myau.playerStateManager.placing) {
                                    switch (this.blockTick) {
                                        case 0:
                                            if (!this.isPlayerBlocking()) {
                                                swap = true;
                                            }
                                            blocked = true;
                                            this.blockTick = 1;
                                            break;
                                        case 1:
                                            if (this.isPlayerBlocking()) {
                                                if(Myau.moduleManager.modules.get(NoSlow.class).isEnabled()){
                                                    int randomSlot = new Random().nextInt(9);
                                                    while (randomSlot == mc.thePlayer.inventory.currentItem) {
                                                        randomSlot = new Random().nextInt(9);
                                                    }
                                                    PacketUtil.sendPacket(new C09PacketHeldItemChange(randomSlot));
                                                    PacketUtil.sendPacket(new C09PacketHeldItemChange(mc.thePlayer.inventory.currentItem));
                                                }
                                                this.stopBlock();
                                                attack = false;
                                            }
                                            if (this.attackDelayMS <= 50L) {
                                                this.blockTick = 0;
                                            }
                                            break;
                                        default:
                                            this.blockTick = 0;
                                    }
                                }
                                this.isBlocking = true;
                                this.fakeBlockState = true;
                            } else {
                                setBlinkModeState(false);
                                this.isBlocking = false;
                                this.fakeBlockState = false;
                            }
                            break;
                        case 4:
                            if (this.hasValidTarget()) {
                                if (!Myau.playerStateManager.digging && !Myau.playerStateManager.placing) {
                                    switch (this.blockTick) {
                                        case 0:
                                            if (!this.isPlayerBlocking()) {
                                                swap = true;
                                            }
                                            this.blinkReset = true;
                                            this.blockTick = 1;
                                            break;
                                        case 1:
                                            if (this.isPlayerBlocking()) {
                                                this.stopBlock();
                                                attack = false;
                                            }
                                            if (this.attackDelayMS <= 50L) {
                                                this.blockTick = 0;
                                            }
                                            break;
                                        default:
                                            this.blockTick = 0;
                                    }
                                }
                                this.isBlocking = true;
                                this.fakeBlockState = true;
                            } else {
                                setBlinkModeState(false);
                                this.isBlocking = false;
                                this.fakeBlockState = false;
                            }
                            break;
                        case 5:
                            if (this.hasValidTarget()) {
                                int item = ((IAccessorPlayerControllerMP) mc.playerController).getCurrentPlayerItem();
                                if (mc.thePlayer.inventory.currentItem == item && !Myau.playerStateManager.digging && !Myau.playerStateManager.placing) {
                                    switch (this.blockTick) {
                                        case 0:
                                            if (!this.isPlayerBlocking()) {
                                                swap = true;
                                            }
                                            this.blinkReset = true;
                                            this.blockTick = 1;
                                            break;
                                        case 1:
                                            if (this.isPlayerBlocking()) {
                                                int slot = this.findEmptySlot(item);
                                                PacketUtil.sendPacket(new C09PacketHeldItemChange(slot));
                                                ((IAccessorPlayerControllerMP) mc.playerController).setCurrentPlayerItem(slot);
                                                attack = false;
                                            }
                                            if (this.attackDelayMS <= 50L) {
                                                this.blockTick = 0;
                                            }
                                            break;
                                        default:
                                            this.blockTick = 0;
                                    }
                                }
                                this.isBlocking = true;
                                this.fakeBlockState = true;
                            } else {
                                setBlinkModeState(false);
                                isBlocking = false;
                                this.fakeBlockState = false;
                            }
                            break;
                        case 6:
                            if (this.hasValidTarget()) {
                                int item = ((IAccessorPlayerControllerMP) mc.playerController).getCurrentPlayerItem();
                                if (mc.thePlayer.inventory.currentItem == item && !Myau.playerStateManager.digging && !Myau.playerStateManager.placing) {
                                    switch (this.blockTick) {
                                        case 0:
                                            int slot = this.findSwordSlot(item);
                                            if (slot != -1) {
                                                if (!this.isPlayerBlocking()) {
                                                    swap = true;
                                                }
                                                this.blockTick = 1;
                                            }
                                            break;
                                        case 1:
                                            int swordsSlot = this.findSwordSlot(item);
                                            if (swordsSlot == -1) {
                                                this.blockTick = 0;
                                            } else if (!this.isPlayerBlocking()) {
                                                swap = true;
                                            } else if (this.attackDelayMS <= 50L) {
                                                PacketUtil.sendPacket(new C09PacketHeldItemChange(swordsSlot));
                                                ((IAccessorPlayerControllerMP) mc.playerController).setCurrentPlayerItem(swordsSlot);
                                                this.startBlock(mc.thePlayer.inventory.getStackInSlot(swordsSlot));
                                                attack = false;
                                                this.blockTick = 0;
                                            }
                                            break;
                                        default:
                                            this.blockTick = 0;
                                    }
                                    setBlinkModeState(false);
                                    this.isBlocking = true;
                                    this.fakeBlockState = true;
                                    break;
                                }
                            }
                            setBlinkModeState(false);
                            this.isBlocking = false;
                            this.fakeBlockState = false;
                            break;
                        case 7:
                            if (this.hasValidTarget()) {
                                if (!Myau.playerStateManager.digging && !Myau.playerStateManager.placing) {
                                    switch (this.blockTick) {
                                        case 0:
                                            if (!this.isPlayerBlocking()) {
                                                swap = true;
                                            }
                                            this.blockTick = 1;
                                            break;
                                        case 1:
                                            if (this.isPlayerBlocking()) {
                                                this.stopBlock();
                                                attack = false;
                                            }
                                            if (this.attackDelayMS <= 50L) {
                                                this.blockTick = 0;
                                            }
                                            break;
                                        default:
                                            this.blockTick = 0;
                                    }
                                }
                                setBlinkModeState(false);
                                this.isBlocking = true;
                                this.fakeBlockState = false;
                            } else {
                                setBlinkModeState(false);
                                this.isBlocking = false;
                                this.fakeBlockState = false;
                            }
                            break;
                        case 8:
                            setBlinkModeState(false);
                            this.isBlocking = false;
                            this.fakeBlockState = this.hasValidTarget();
                            if (PlayerUtil.isUsingItem()
                                    && !this.isPlayerBlocking()
                                    && !Myau.playerStateManager.digging
                                    && !Myau.playerStateManager.placing) {
                                swap = true;
                            }
                            break;
                        case AB_QUICK_MACRO:
                            // LEGIT timing (unblock one tick, attack + re-block the next) with the
                            // QuickMacro block packet and the selected unblock-mode
                            if (this.hasValidTarget()) {
                                if (!Myau.playerStateManager.digging && !Myau.playerStateManager.placing) {
                                    switch (this.blockTick) {
                                        case 0:
                                            if (!this.isPlayerBlocking()) {
                                                swap = true;
                                            }
                                            this.blockTick = 1;
                                            break;
                                        case 1:
                                            if (this.isPlayerBlocking()) {
                                                this.stopBlockByMode();
                                                attack = false;
                                            }
                                            if (this.attackDelayMS <= 50L) {
                                                this.blockTick = 0;
                                            }
                                            break;
                                        default:
                                            this.blockTick = 0;
                                    }
                                }
                                setBlinkModeState(false);
                                this.isBlocking = true;
                                this.fakeBlockState = false;
                            } else {
                                setBlinkModeState(false);
                                this.isBlocking = false;
                                this.fakeBlockState = false;
                            }
                            break;
                        case AB_BLOCK_ON_NO_HIT:
                            // Only block while no hit is due: unblock ahead of the next attack,
                            // block again once block-on-no-hit-delay ticks passed without attacking
                            setBlinkModeState(false);
                            this.fakeBlockState = false;
                            if (this.hasValidTarget()) {
                                this.isBlocking = true;
                                if (!Myau.playerStateManager.digging && !Myau.playerStateManager.placing) {
                                    boolean sameTick = this.blockOnNoHitSameTick.getValue();
                                    if (this.isPlayerBlocking()) {
                                        if (this.attackDelayMS <= (sameTick ? 0L : 50L)) {
                                            this.stopBlockByMode();
                                            if (!sameTick) {
                                                attack = false;
                                            }
                                        }
                                    } else if (this.ticksSinceAttack >= this.blockOnNoHitDelay.getValue() && this.attackDelayMS > 50L) {
                                        swap = true;
                                    }
                                }
                            } else {
                                this.isBlocking = false;
                            }
                            break;
                        case AB_HYPIXEL_LEADER:
                            // LeaderClient "Hypixel": block -> idle -> (swap) unblock, blink refreshed on the block tick
                            if (this.hasValidTarget()) {
                                if (!Myau.playerStateManager.digging && !Myau.playerStateManager.placing) {
                                    switch (this.blockTick) {
                                        case 0:
                                            if (!this.isPlayerBlocking()) {
                                                swap = true;
                                            }
                                            blocked = true;
                                            this.blockTick = 1;
                                            break;
                                        case 1:
                                            attack = false;
                                            this.blockTick = 2;
                                            break;
                                        case 2:
                                            if (this.isPlayerBlocking()) {
                                                if (!this.noSwap.getValue()) {
                                                    this.sendRandomSlotSwap();
                                                }
                                                this.stopBlock();
                                            }
                                            attack = false;
                                            if (this.attackDelayMS <= 50L) {
                                                this.blockTick = 0;
                                            }
                                            break;
                                        default:
                                            this.blockTick = 0;
                                    }
                                }
                                this.isBlocking = true;
                                this.fakeBlockState = true;
                            } else {
                                setBlinkModeState(false);
                                this.sendRandomSlotSwap();
                                this.stopBlock();
                                Velocity.extraAttacked = false;
                                this.isBlocking = false;
                                this.fakeBlockState = false;
                            }
                            break;
                        case AB_HYPIXEL_TEST:
                            // LeaderClient "Hypixel Test": block -> slot flick -> release
                            if (this.hasValidTarget()) {
                                if (!Myau.playerStateManager.digging && !Myau.playerStateManager.placing) {
                                    switch (this.blockTick) {
                                        case 0:
                                            blocked = true;
                                            if (!this.isPlayerBlocking()) {
                                                swap = true;
                                            }
                                            this.blockTick = 1;
                                            break;
                                        case 1:
                                            if (this.isPlayerBlocking()) {
                                                this.sendRandomSlotSwap();
                                            }
                                            attack = false;
                                            this.blockTick = 2;
                                            break;
                                        case 2:
                                            attack = false;
                                            this.stopBlock();
                                            if (this.attackDelayMS <= 50L) {
                                                this.blockTick = 0;
                                            }
                                            break;
                                        default:
                                            this.blockTick = 0;
                                    }
                                }
                                this.isBlocking = true;
                                this.fakeBlockState = true;
                            } else {
                                setBlinkModeState(false);
                                this.sendRandomSlotSwap();
                                this.isBlocking = false;
                                this.fakeBlockState = false;
                            }
                            break;
                        case AB_HYPIXEL_CUSTOM:
                            // LeaderClient "Hypixel Custom": every action happens on a configurable phase
                            if (this.hasValidTarget()) {
                                if (!Myau.playerStateManager.digging && !Myau.playerStateManager.placing) {
                                    int phase = this.blockTick + 1;
                                    if (phase == this.startBlinkTick.getValue()) {
                                        blocked = true;
                                    }
                                    if (phase != this.attackTick.getValue()) {
                                        attack = false;
                                    }
                                    if (phase == this.startBlockTick.getValue() && !this.isPlayerBlocking()) {
                                        swap = true;
                                        if (this.postStartBlock.getValue()) {
                                            this.postBlock = true;
                                        }
                                    }
                                    if (phase == this.stopBlinkTick.getValue()) {
                                        setBlinkModeState(false);
                                    }
                                    if (phase == this.swapTick.getValue()) {
                                        int randomSlot = new Random().nextInt(9);
                                        while (randomSlot == mc.thePlayer.inventory.currentItem) {
                                            randomSlot = new Random().nextInt(9);
                                        }
                                        PacketUtil.sendPacket(new C09PacketHeldItemChange(randomSlot));
                                        this.swapped = true;
                                    }
                                    if (phase == this.switchBackTick.getValue() && this.swapped) {
                                        PacketUtil.sendPacket(new C09PacketHeldItemChange(mc.thePlayer.inventory.currentItem));
                                        this.swapped = false;
                                    }
                                    if (phase == this.stopBlockTick.getValue() && this.isPlayerBlocking()) {
                                        this.stopBlock();
                                    }
                                    this.blockTick++;
                                    if (this.blockTick >= this.maxTick.getValue() - 1) {
                                        this.blockTick = 0;
                                    }
                                }
                                this.isBlocking = true;
                                this.fakeBlockState = true;
                            } else {
                                if (this.swapped) {
                                    PacketUtil.sendPacket(new C09PacketHeldItemChange(mc.thePlayer.inventory.currentItem));
                                    this.swapped = false;
                                }
                                setBlinkModeState(false);
                                this.isBlocking = false;
                                this.fakeBlockState = false;
                            }
                            break;
                    }
                    if (this.isHypixelLeaderBlock()) {
                        this.phaseResolved = true;
                        this.phaseAttack = attack;
                    }
                }
                boolean attacked = false;
                if (this.isBoxInSwingRange(this.target.getBox())) {
                    if (this.rotations.getValue() == ROT_ML) {
                        // server-side like SILENT; the GRU picks how far to turn this tick
                        float[] rotations = this.mlAim.getRotation(
                                this.target.getBox(), this.target.getEntity(), event.getYaw(), event.getPitch(),
                                this.attackRange.getValue(), this.mlStrength.getValue(), this.mlMaxMove.getValue(),
                                this.mlSmooth.getValue(), this.mlModel.getValue());
                        event.setRotation(rotations[0], rotations[1], 1);
                        if (this.moveFix.getValue() != 0) {
                            event.setPervRotation(rotations[0], 1);
                        }
                    } else if (this.rotations.getValue() == 2 || this.rotations.getValue() == 3 || this.rotations.getValue() == 4) {
                        float[] rotations = RotationUtil.getRotationsToBox(
                                this.target.getBox(),
                                event.getYaw(),
                                event.getPitch(),
                                (float) this.angleStep.getValue() + RandomUtil.nextFloat(-5.0F, 5.0F),
                                (float) this.smoothing.getValue() / 100.0F
                        );

                        if (this.rotations.getValue() == 4) {
                            float[] noise = getNoiseOffset(this.target);
                            rotations[0] += noise[0];
                            rotations[1] += noise[1];
                        }

                        event.setRotation(rotations[0], rotations[1], 1);
                        if (this.rotations.getValue() == 3) {
                            Myau.rotationManager.setRotation(rotations[0], rotations[1], 1, true);
                        }
                        if (this.moveFix.getValue() != 0 || this.rotations.getValue() == 3) {
                            event.setPervRotation(rotations[0], 1);
                        }
                    }
                    if (attack && (!noise4 || noise4Ready)) {
                        attacked = this.performAttack(event.getNewYaw(), event.getNewPitch());
                        // click modes can put several clicks into one tick (butterfly double click, drag burst)
                        for (int extra = 0; this.clickMode.getValue() != 0 && this.attackDelayMS <= 0L && extra < 3; extra++) {
                            attacked |= this.performAttack(event.getNewYaw(), event.getNewPitch());
                        }
                    }
                }
                if (swap && !this.postBlock) {
                    if (attacked) {
                        this.interactAttack(event.getNewYaw(), event.getNewPitch());
                    } else {
                        this.sendUseItem();
                    }
                }
                if (blocked) {
                    setBlinkModeState(false);
                    setBlinkModeState(true);
                }
            }
        }
    }

    @EventTarget
    public void onTick(TickEvent event) {
        if (this.isEnabled()) {
            switch (event.getType()) {
                case PRE:
                    if (this.target == null
                            || !this.isValidTarget(this.target.getEntity())
                            || !this.isBoxInAttackRange(this.target.getBox())
                            || !this.isBoxInSwingRange(this.target.getBox())
                            || this.timer.hasTimeElapsed(this.switchDelay.getValue().longValue())) {
                        this.timer.reset();
                        ArrayList<EntityLivingBase> targets = new ArrayList<>();
                        for (Entity entity : mc.theWorld.loadedEntityList) {
                            if (entity instanceof EntityLivingBase
                                    && this.isValidTarget((EntityLivingBase) entity)
                                    && this.isInRange((EntityLivingBase) entity)) {
                                targets.add((EntityLivingBase) entity);
                            }
                        }
                        if (targets.isEmpty()) {
                            this.target = null;
                        } else {
                            if (targets.stream().anyMatch(this::isInSwingRange)) {
                                targets.removeIf(entityLivingBase -> !this.isInSwingRange(entityLivingBase));
                            }
                            if (targets.stream().anyMatch(this::isInAttackRange)) {
                                targets.removeIf(entityLivingBase -> !this.isInAttackRange(entityLivingBase));
                            }
                            if (targets.stream().anyMatch(this::isPlayerTarget)) {
                                targets.removeIf(entityLivingBase -> !this.isPlayerTarget(entityLivingBase));
                            }
                            targets.sort(
                                    (entityLivingBase1, entityLivingBase2) -> {
                                        int sortBase = 0;
                                        switch (this.sort.getValue()) {
                                            case 1:
                                                sortBase = Float.compare(TeamUtil.getHealthScore(entityLivingBase1), TeamUtil.getHealthScore(entityLivingBase2));
                                                break;
                                            case 2:
                                                sortBase = Integer.compare(entityLivingBase1.hurtResistantTime, entityLivingBase2.hurtResistantTime);
                                                break;
                                            case 3:
                                                sortBase = Float.compare(
                                                        RotationUtil.angleToEntity(entityLivingBase1),
                                                        RotationUtil.angleToEntity(entityLivingBase2)
                                                );
                                                break;
                                            case 4: // LIVING_TIME: longest alive first
                                                sortBase = Integer.compare(entityLivingBase2.ticksExisted, entityLivingBase1.ticksExisted);
                                                break;
                                            case 5: // ARMOR: least armour first
                                                sortBase = Integer.compare(entityLivingBase1.getTotalArmorValue(), entityLivingBase2.getTotalArmorValue());
                                                break;
                                            case 6: // HURT_TICKS: not currently hurt first
                                                sortBase = Integer.compare(entityLivingBase1.hurtTime, entityLivingBase2.hurtTime);
                                                break;
                                            case 7: // HEALTH_ABSORPTION
                                                sortBase = Float.compare(
                                                        entityLivingBase1.getHealth() + entityLivingBase1.getAbsorptionAmount(),
                                                        entityLivingBase2.getHealth() + entityLivingBase2.getAbsorptionAmount()
                                                );
                                                break;
                                            case 8: // REGEN_AMPLIFIER: weakest regeneration first
                                                sortBase = Integer.compare(getRegenAmplifier(entityLivingBase1), getRegenAmplifier(entityLivingBase2));
                                                break;
                                            case 9: // ON_LADDER first
                                                sortBase = Boolean.compare(entityLivingBase2.isOnLadder(), entityLivingBase1.isOnLadder());
                                                break;
                                            case 10: // IN_LIQUID first
                                                sortBase = Boolean.compare(
                                                        entityLivingBase2.isInWater() || entityLivingBase2.isInLava(),
                                                        entityLivingBase1.isInWater() || entityLivingBase1.isInLava()
                                                );
                                                break;
                                            case 11: // IN_WEB first
                                                sortBase = Boolean.compare(isInWeb(entityLivingBase2), isInWeb(entityLivingBase1));
                                        }
                                        return sortBase != 0
                                                ? sortBase
                                                : Double.compare(RotationUtil.distanceToEntity(entityLivingBase1), RotationUtil.distanceToEntity(entityLivingBase2));
                                    }
                            );
                            if (this.mode.getValue() == 1 && this.hitRegistered) {
                                this.hitRegistered = false;
                                this.switchTick++;
                            }
                            if (this.mode.getValue() == 0 || this.switchTick >= targets.size()) {
                                this.switchTick = 0;
                            }
                            this.target = new AttackData(targets.get(this.switchTick));
                        }
                    }
                    if (this.target != null) {
                        this.target = new AttackData(this.target.getEntity());
                    }
                    break;
                case POST:
                    if (this.isPlayerBlocking() && !mc.thePlayer.isBlocking()) {
                        mc.thePlayer.setItemInUse(mc.thePlayer.getHeldItem(), mc.thePlayer.getHeldItem().getMaxItemUseDuration());
                    }
            }
        }
    }

    @EventTarget(Priority.LOWEST)
    public void onPacket(PacketEvent event) {
        if (this.isEnabled() && !event.isCancelled() && mc.thePlayer != null && mc.theWorld != null) {
            if (event.getPacket() instanceof C07PacketPlayerDigging) {
                C07PacketPlayerDigging packet = (C07PacketPlayerDigging) event.getPacket();
                if (packet.getStatus() == C07PacketPlayerDigging.Action.RELEASE_USE_ITEM) {
                    this.blockingState = false;
                }
            }
            if (event.getPacket() instanceof C09PacketHeldItemChange) {
                this.blockingState = false;
                if (this.isBlocking) {
                    mc.thePlayer.stopUsingItem();
                }
            }
            if (this.debugLog.getValue() == 1 && this.isAttackAllowed()) {
                if (event.getPacket() instanceof S06PacketUpdateHealth) {
                    float packet = ((S06PacketUpdateHealth) event.getPacket()).getHealth() - mc.thePlayer.getHealth();
                    if (packet != 0.0F && this.lastTickProcessed != mc.thePlayer.ticksExisted) {
                        this.lastTickProcessed = mc.thePlayer.ticksExisted;
                        ChatUtil.sendFormatted(
                                String.format(
                                        "%sHealth: %s&l%s&r (&otick: %d&r)&r",
                                        Myau.clientName,
                                        packet > 0.0F ? "&a" : "&c",
                                        df.format(packet),
                                        mc.thePlayer.ticksExisted
                                )
                        );
                    }
                }
                if (event.getPacket() instanceof S1CPacketEntityMetadata) {
                    S1CPacketEntityMetadata packet = (S1CPacketEntityMetadata) event.getPacket();
                    // The metadata list can be null (empty metadata packets), so check before iterating.
                    List<WatchableObject> watchedObjects = packet.func_149376_c();
                    if (watchedObjects != null && packet.getEntityId() == mc.thePlayer.getEntityId()) {
                        for (WatchableObject watchableObject : watchedObjects) {
                            if (watchableObject.getDataValueId() == 6) {
                                float diff = (Float) watchableObject.getObject() - mc.thePlayer.getHealth();
                                if (diff != 0.0F && this.lastTickProcessed != mc.thePlayer.ticksExisted) {
                                    this.lastTickProcessed = mc.thePlayer.ticksExisted;
                                    ChatUtil.sendFormatted(
                                            String.format(
                                                    "%sHealth: %s&l%s&r (&otick: %d&r)&r",
                                                    Myau.clientName,
                                                    diff > 0.0F ? "&a" : "&c",
                                                    df.format(diff),
                                                    mc.thePlayer.ticksExisted
                                            )
                                    );
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    @EventTarget
    public void onMove(MoveInputEvent event) {
        if (this.isEnabled()) {
            if (this.moveFix.getValue() == 1
                    && this.rotations.getValue() != 3
                    && RotationState.isActived()
                    && RotationState.getPriority() == 1.0F
                    && MoveUtil.isForwardPressed()) {
                MoveUtil.fixStrafe(RotationState.getSmoothedYaw());
            }
            if (this.shouldAutoBlock()) {
                mc.thePlayer.movementInput.jump = false;
            }
        }
    }

    @EventTarget(Priority.LOWEST)
    public void onRender(Render3DEvent event) {
        if (this.isEnabled() && target != null) {
            Entity entity = this.target.getEntity();
            if (this.showTarget.getValue() != 0
                    && TeamUtil.isEntityLoaded(entity)
                    && this.isAttackAllowed()) {

                double x = RenderUtil.lerpDouble(entity.posX, entity.lastTickPosX, event.getPartialTicks())
                        - ((IAccessorRenderManager) mc.getRenderManager()).getRenderPosX();
                double y = RenderUtil.lerpDouble(entity.posY, entity.lastTickPosY, event.getPartialTicks())
                        - ((IAccessorRenderManager) mc.getRenderManager()).getRenderPosY();
                double z = RenderUtil.lerpDouble(entity.posZ, entity.lastTickPosZ, event.getPartialTicks())
                        - ((IAccessorRenderManager) mc.getRenderManager()).getRenderPosZ();

                RenderUtil.enableRenderState();

                GL11.glPushMatrix();
                GL11.glDisable(GL11.GL_TEXTURE_2D);
                GL11.glEnable(GL11.GL_BLEND);
                GL11.glBlendFunc(GL11.GL_SRC_ALPHA, GL11.GL_ONE_MINUS_SRC_ALPHA);
                GL11.glEnable(GL11.GL_LINE_SMOOTH);
                GL11.glHint(GL11.GL_LINE_SMOOTH_HINT, GL11.GL_NICEST);

                GL11.glEnable(GL11.GL_DEPTH_TEST);
                GL11.glDepthMask(false);

                long now = System.currentTimeMillis();
                double baseRadius = entity.width * 0.85;
                double breathingRadius = baseRadius + Math.sin(now * 0.003) * 0.06;

                double cycle = (Math.sin(now * 0.0015) + 1.0) / 2.0;
                double currentY = y - 0.2 + (entity.height + 0.4) * cycle;

                // 白色渐变光环：中间粗亮 → 越往外越细淡
                // 最外层光晕 — 极宽极淡
                GL11.glLineWidth(10.0F);
                GL11.glBegin(GL11.GL_LINE_LOOP);
                GL11.glColor4f(1.0F, 1.0F, 1.0F, 0.06F);
                for (int i = 0; i <= 360; i += 6) {
                    double angle = Math.toRadians(i);
                    GL11.glVertex3d(x + Math.cos(angle) * (breathingRadius + 0.10), currentY, z + Math.sin(angle) * (breathingRadius + 0.10));
                }
                GL11.glEnd();

                // 外层 — 细淡
                GL11.glLineWidth(6.0F);
                GL11.glBegin(GL11.GL_LINE_LOOP);
                GL11.glColor4f(1.0F, 1.0F, 1.0F, 0.15F);
                for (int i = 0; i <= 360; i += 6) {
                    double angle = Math.toRadians(i);
                    GL11.glVertex3d(x + Math.cos(angle) * (breathingRadius + 0.06), currentY, z + Math.sin(angle) * (breathingRadius + 0.06));
                }
                GL11.glEnd();

                // 中层 — 中等粗细，半透
                GL11.glLineWidth(4.0F);
                GL11.glBegin(GL11.GL_LINE_LOOP);
                GL11.glColor4f(1.0F, 1.0F, 1.0F, 0.40F);
                for (int i = 0; i <= 360; i += 6) {
                    double angle = Math.toRadians(i);
                    GL11.glVertex3d(x + Math.cos(angle) * (breathingRadius + 0.02), currentY, z + Math.sin(angle) * (breathingRadius + 0.02));
                }
                GL11.glEnd();

                // 核心层 — 最粗最亮最白
                GL11.glLineWidth(2.8F);
                GL11.glBegin(GL11.GL_LINE_LOOP);
                GL11.glColor4f(1.0F, 1.0F, 1.0F, 0.92F);
                for (int i = 0; i <= 360; i += 6) {
                    double angle = Math.toRadians(i);
                    GL11.glVertex3d(x + Math.cos(angle) * breathingRadius, currentY, z + Math.sin(angle) * breathingRadius);
                }
                GL11.glEnd();

                GL11.glDepthMask(true);
                GL11.glDisable(GL11.GL_LINE_SMOOTH);
                GL11.glDisable(GL11.GL_BLEND);
                GL11.glEnable(GL11.GL_TEXTURE_2D);
                GL11.glPopMatrix();

                RenderUtil.disableRenderState();
            }
        }
        this.renderAimPoint(event.getPartialTicks());
    }

    // ── show-aim-point: where the rotation actually sent to the server meets the target ──
    private Entity aimEntity = null;
    private double aimOffX, aimOffY, aimOffZ;        // latest sample, relative to the entity position
    private double aimShowX, aimShowY, aimShowZ;     // smoothed offset that is drawn
    private boolean aimHit = false;
    private long aimSampleTime = 0L;
    private float aimAlpha = 0.0F;
    private float aimHitBlend = 0.0F;
    private long aimLastFrame = 0L;

    @EventTarget(Priority.LOWEST)
    public void onUpdateAimPoint(UpdateEvent event) {
        if (event.getType() != EventType.POST) {
            return;
        }
        if (!this.isEnabled() || this.target == null || !this.showAimPoint.getValue() || mc.thePlayer == null) {
            return;
        }
        Entity entity = this.target.getEntity();
        if (entity == null) {
            return;
        }
        // POST carries lastReportedYaw / Pitch: the rotation of the C03 this tick
        float border = entity.getCollisionBorderSize();
        AxisAlignedBB box = entity.getEntityBoundingBox().expand(border, border, border);
        Vec3 eyes = mc.thePlayer.getPositionEyes(1.0F);
        Vec3 look = ((myau.mixin.IAccessorEntity) mc.thePlayer).callGetVectorForRotation(event.getPitch(), event.getYaw());
        Vec3 end = eyes.addVector(look.xCoord * 8.0, look.yCoord * 8.0, look.zCoord * 8.0);
        Vec3 point;
        boolean hit;
        MovingObjectPosition intercept = box.calculateIntercept(eyes, end);
        if (box.isVecInside(eyes)) {
            point = eyes.addVector(look.xCoord * 0.3, look.yCoord * 0.3, look.zCoord * 0.3);
            point = RotationUtil.clampVecToBox(point, box);
            hit = true;
        } else if (intercept != null) {
            point = intercept.hitVec;
            hit = true;
        } else {
            // not on the box yet: the point of the view ray nearest to the box centre, pulled onto the box
            double cx = (box.minX + box.maxX) * 0.5 - eyes.xCoord;
            double cy = (box.minY + box.maxY) * 0.5 - eyes.yCoord;
            double cz = (box.minZ + box.maxZ) * 0.5 - eyes.zCoord;
            double t = Math.max(0.0, cx * look.xCoord + cy * look.yCoord + cz * look.zCoord);
            point = RotationUtil.clampVecToBox(eyes.addVector(look.xCoord * t, look.yCoord * t, look.zCoord * t), box);
            hit = false;
        }
        double offX = point.xCoord - entity.posX;
        double offY = point.yCoord - entity.posY;
        double offZ = point.zCoord - entity.posZ;
        if (entity != this.aimEntity || this.aimAlpha <= 0.01F) {
            this.aimShowX = offX;
            this.aimShowY = offY;
            this.aimShowZ = offZ;
            this.aimHitBlend = hit ? 1.0F : 0.0F;
        }
        this.aimEntity = entity;
        this.aimOffX = offX;
        this.aimOffY = offY;
        this.aimOffZ = offZ;
        this.aimHit = hit;
        this.aimSampleTime = System.currentTimeMillis();
    }

    private void renderAimPoint(float partialTicks) {
        long now = System.currentTimeMillis();
        float dt = this.aimLastFrame == 0L ? 0.0F : Math.min(0.1F, (now - this.aimLastFrame) / 1000.0F);
        this.aimLastFrame = now;

        Entity entity = this.aimEntity;
        boolean live = entity != null
                && this.isEnabled()
                && this.showAimPoint.getValue()
                && this.target != null
                && this.target.getEntity() == entity
                && !entity.isDead
                && now - this.aimSampleTime < 250L;
        // 150 ms fade in / out
        this.aimAlpha = MathHelper.clamp_float(this.aimAlpha + (live ? dt : -dt) / 0.15F, 0.0F, 1.0F);
        if (entity == null || this.aimAlpha <= 0.0F) {
            if (this.aimAlpha <= 0.0F) {
                this.aimEntity = null;
            }
            return;
        }

        // ~60 ms exponential follow so per-tick jumps of the sample do not show
        float follow = 1.0F - (float) Math.exp(-dt / 0.06F);
        this.aimShowX += (this.aimOffX - this.aimShowX) * follow;
        this.aimShowY += (this.aimOffY - this.aimShowY) * follow;
        this.aimShowZ += (this.aimOffZ - this.aimShowZ) * follow;
        float blendFollow = 1.0F - (float) Math.exp(-dt / 0.10F);
        this.aimHitBlend += ((this.aimHit ? 1.0F : 0.0F) - this.aimHitBlend) * blendFollow;

        IAccessorRenderManager rm = (IAccessorRenderManager) mc.getRenderManager();
        double x = RenderUtil.lerpDouble(entity.posX, entity.lastTickPosX, partialTicks) + this.aimShowX - rm.getRenderPosX();
        double y = RenderUtil.lerpDouble(entity.posY, entity.lastTickPosY, partialTicks) + this.aimShowY - rm.getRenderPosY();
        double z = RenderUtil.lerpDouble(entity.posZ, entity.lastTickPosZ, partialTicks) + this.aimShowZ - rm.getRenderPosZ();

        double half = 0.045 * (1.0 + 0.08 * Math.sin(now * 0.006));
        AxisAlignedBB cube = new AxisAlignedBB(x - half, y - half, z - half, x + half, y + half, z + half);

        // solid blue on the target; a paler, see-through blue while the rotation is not there yet
        float h = this.aimHitBlend;
        int r = (int) (150 - 110 * h);
        int g = (int) (185 - 45 * h);
        int b = 255;
        int fillAlpha = (int) (255 * this.aimAlpha * (0.45F + 0.55F * h));
        int lineAlpha = (int) (255 * this.aimAlpha * (0.55F + 0.45F * h));

        RenderUtil.enableRenderState();
        GL11.glDepthMask(false);
        RenderUtil.drawFilledBox(cube, r, g, b, fillAlpha);
        RenderUtil.drawBoundingBox(cube, Math.min(255, r + 60), Math.min(255, g + 60), b, lineAlpha, 1.5F);
        GL11.glDepthMask(true);
        RenderUtil.disableRenderState();
    }

    @EventTarget
    public void onLeftClick(LeftClickMouseEvent event) {
        if (this.isBlocking) {
            event.setCancelled(true);
        } else {
            if (this.isEnabled() && this.target != null && this.canAttack()) {
                event.setCancelled(true);
            }
        }
    }

    @EventTarget
    public void onRightClick(RightClickMouseEvent event) {
        if (this.isBlocking) {
            event.setCancelled(true);
        } else {
            if (this.isEnabled() && this.target != null && this.canAttack()) {
                event.setCancelled(true);
            }
        }
    }

    @EventTarget
    public void onHitBlock(HitBlockEvent event) {
        if (this.isBlocking) {
            event.setCancelled(true);
        } else {
            if (this.isEnabled() && this.target != null && this.canAttack()) {
                event.setCancelled(true);
            }
        }
    }

    @EventTarget
    public void onCancelUse(CancelUseEvent event) {
        if (this.isBlocking) {
            event.setCancelled(true);
        }
    }

    /** The ML rotation mode's model and state, for {@code .ml}. */
    public MLAim getMlAim() {
        return this.mlAim;
    }

    @Override
    public void onEnabled() {
        this.resetNoise4();
        this.mlAim.reset();
        this.target = null;
        this.switchTick = 0;
        this.hitRegistered = false;
        this.attackDelayMS = 0L;
        this.blockTick = 0;
        this.swapped = false;
        this.postBlock = false;
        this.ticksSinceAttack = 0;
        this.clickScheduler.reset();
    }

    @Override
    public void onDisabled() {
        this.resetNoise4();
        this.mlAim.reset();
        setBlinkModeState(false);
        if (this.isHypixelLeaderBlock() && mc.thePlayer != null
                && Myau.moduleManager.modules.get(NoSlow.class).isEnabled()) {
            this.sendRandomSlotSwap();
            this.stopBlock();
        }
        Velocity.extraAttacked = false;
        this.swapped = false;
        this.postBlock = false;
        this.blockingState = false;
        this.isBlocking = false;
        this.fakeBlockState = false;
    }

    @Override
    public void verifyValue(String value) {
        if (this.mlModel.getName().equals(value)) {
            this.mlAim.markDirty();
        }
        if (!this.autoBlock.getName().equals(value)) {
            if (this.swingRange.getName().equals(value)) {
                if (this.swingRange.getValue() < this.attackRange.getValue()) {
                    this.attackRange.setValue(this.swingRange.getValue());
                }
            } else if (this.attackRange.getName().equals(value)) {
                if (this.swingRange.getValue() < this.attackRange.getValue()) {
                    this.swingRange.setValue(this.attackRange.getValue());
                }
            } else if (this.minCPS.getName().equals(value)) {
                if (this.minCPS.getValue() > this.maxCPS.getValue()) {
                    this.maxCPS.setValue(this.minCPS.getValue());
                }
            } else if (this.autoBlockMinCPS.getName().equals(value)) {
                if (this.autoBlockMinCPS.getValue() > this.autoBlockMaxCPS.getValue()) {
                    this.autoBlockMaxCPS.setValue(this.autoBlockMinCPS.getValue());
                }
            } else if (this.autoBlockMaxCPS.getName().equals(value)) {
                if (this.autoBlockMinCPS.getValue() > this.autoBlockMaxCPS.getValue()) {
                    this.autoBlockMinCPS.setValue(this.autoBlockMaxCPS.getValue());
                }
            } else {
                if (this.maxCPS.getName().equals(value) && this.minCPS.getValue() > this.maxCPS.getValue()) {
                    this.minCPS.setValue(this.maxCPS.getValue());
                }
            }
        }
    }

    @Override
    public String[] getSuffix() {
        return new String[]{CaseFormat.UPPER_UNDERSCORE.to(CaseFormat.UPPER_CAMEL, this.mode.getModeString())};
    }

    public static class AttackData {
        private final EntityLivingBase entity;
        private final AxisAlignedBB box;
        private final double x;
        private final double y;
        private final double z;

        public AttackData(EntityLivingBase entityLivingBase) {
            this.entity = entityLivingBase;
            double collisionBorderSize = entityLivingBase.getCollisionBorderSize();
            this.box = entityLivingBase.getEntityBoundingBox().expand(collisionBorderSize, collisionBorderSize, collisionBorderSize);
            this.x = entityLivingBase.posX;
            this.y = entityLivingBase.posY;
            this.z = entityLivingBase.posZ;
        }

        public EntityLivingBase getEntity() {
            return this.entity;
        }

        public AxisAlignedBB getBox() {
            return this.box;
        }

        public double getX() {
            return this.x;
        }

        public double getY() {
            return this.y;
        }

        public double getZ() {
            return this.z;
        }
    }
}
