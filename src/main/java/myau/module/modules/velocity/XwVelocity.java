/*
 * Velocity modes ported from xw / Air Client, derived from LiquidBounce (GPL-3.0).
 * Source: net/ccbluex/liquidbounce/features/module/modules/combat/Velocity.kt.
 * Uses BetterMyau's event, targeting, property and packet interfaces.
 */
package myau.module.modules.velocity;

import myau.Myau;
import myau.event.types.EventType;
import myau.events.*;
import myau.mixin.IAccessorC03PacketPlayer;
import myau.mixin.IAccessorEntity;
import myau.mixin.IAccessorEntityPlayer;
import myau.mixin.IAccessorMinecraft;
import myau.mixin.IAccessorS12PacketEntityVelocity;
import myau.mixin.IAccessorS27PacketExplosion;
import myau.module.modules.KillAura;
import myau.module.modules.Speed;
import myau.module.modules.Velocity;
import myau.property.Property;
import myau.property.PropertyManager;
import myau.property.properties.ModeProperty;
import myau.util.*;
import net.minecraft.block.BlockAir;
import net.minecraft.client.Minecraft;
import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityLivingBase;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.network.Packet;
import net.minecraft.network.play.INetHandlerPlayClient;
import net.minecraft.network.play.client.*;
import net.minecraft.network.play.server.*;
import net.minecraft.potion.Potion;
import net.minecraft.util.*;

import java.lang.reflect.Field;
import java.util.*;

public final class XwVelocity {
    public static final String[] MODES = {
            "Simple", "AAC", "AACPush", "AACZero", "AACv4", "AAC5", "AAC3.3.12", "AAC3.3.14",
            "Reverse", "SmoothReverse", "Glitch", "Legit", "LegitSmart", "GhostBlock", "Vulcan",
            "Vulcan2.0.1", "S32Packet", "MatrixReduce", "Matrix", "MatrixNoXZ", "Matrix6.6.1",
            "IntaveReduce", "Intave13", "Intave13KeepLow", "Intave13Reverse", "Intave13GommeZero",
            "Intave13Wall", "Intave13Old", "Intave14", "Intave14.3.3", "IntaveStrong", "Intave/Polar-Flag",
            "AttackReduce", "Delay", "GrimC03", "Hypixel", "HypixelMorden", "HypixelAir", "HypixelPrediction",
            "Click", "BlocksMC", "Polar", "Polar2", "Buffer", "Prediction", "JumpReset", "GrimCombat",
            "GrimReduce", "Reduce", "SmartJumpReset", "Bounce", "Karhu", "MMC", "Universocraft", "Grim2",
            "GrimTest", "Watchdog", "WatchdogPrediction", "WatchdogReduce", "WatchdogDamageBoost", "Tick", "Ground"
    };
    private static final Minecraft mc = Minecraft.getMinecraft();
    private final Velocity owner;
    private final Map<String, Property<?>> options = new HashMap<>();
    private final Random random = new Random();
    private boolean received, reverseHurt, jumped, transaction, absorbed, matrixAbsorbed;
    private boolean predictionAllowNext = true, predictionJump, predictionRotate;
    private int pausePackets, timerTicks, cooldown, intaveTick, intaveHits, polarTick, polarHits;
    private int legitJumps, polarHurt = 8, predictionCounter, predictionAttack = -1, predictionLastHurt;
    private float predictionYaw;
    private long velocityTime, lastAttack, polarAttack, enableTime;
    private final boolean[] intavePhases = new boolean[3];
    private int intaveReverseCount;
    private boolean intaveFirst, intaveReversed;
    private int remainingAttacks;
    private final Deque<HeldPacket> delayed = new ArrayDeque<>();
    private final Deque<HeldPacket> buffer = new ArrayDeque<>();
    private final List<Packet<?>> watchdog = new ArrayList<>();
    private final List<Packet<?>> watchdogPrediction = new ArrayList<>();
    private final List<Packet<?>> watchdogReduce = new ArrayList<>();
    private final List<Packet<?>> grimHeld = new ArrayList<>();
    private boolean wdJump, wdPredJump, wdPredShouldJump;
    private int wdStack;
    private float wdVelocityYaw;
    private boolean grimHurt, grimDelaying, grimSkipDelay, grimNotSprinting;
    private int grimAttacks, grimJumpCountdown;
    private long grimDelayStart;
    private double grimX, grimY, grimZ;
    private Entity grimTarget;
    private boolean ownsNoClip, ownsAirSpeed, ownsTimer;

    public XwVelocity(Velocity owner) {
        this.owner = owner;
        for (Field field : PropertyManager.getPropertyFields(Velocity.class)) {
            if (!field.getName().startsWith("xw")) continue;
            try {
                Property<?> property = (Property<?>) field.get(owner);
                options.put(key(property.getName().substring(3)), property);
            } catch (IllegalAccessException e) {
                throw new IllegalStateException("Cannot read xw option " + field.getName(), e);
            }
        }
    }

    public static String[] appendModes(String[] existing) {
        String[] result = Arrays.copyOf(existing, existing.length + MODES.length);
        for (int i = 0; i < MODES.length; i++) {
            result[existing.length + i] = "XW_" + MODES[i].replaceAll("([a-z0-9])([A-Z])", "$1_$2")
                    .toUpperCase(Locale.ROOT).replace('/', '_');
        }
        return result;
    }

    private static String key(String value) { return value.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]", ""); }
    private Property<?> option(String name) {
        Property<?> result = options.get(key(name));
        if (result == null) throw new IllegalArgumentException("Unknown xw option: " + name);
        return result;
    }
    private boolean b(String name) { return (Boolean) option(name).getValue(); }
    private int i(String name) { return ((Number) option(name).getValue()).intValue(); }
    private float f(String name) { return ((Number) option(name).getValue()).floatValue(); }
    private String c(String name) { return ((ModeProperty) option(name)).getModeString(); }
    private String mode() { return MODES[owner.mode.getValue() - 8].toLowerCase(Locale.ROOT); }
    private static boolean liquid() { return mc.thePlayer.isInWater() || mc.thePlayer.isInLava() || ((IAccessorEntity) mc.thePlayer).getIsInWeb(); }
    private static boolean speedEnabled() { return Myau.moduleManager.modules.get(Speed.class).isEnabled(); }
    private static boolean moving() { return mc.thePlayer.moveForward != 0 || mc.thePlayer.moveStrafing != 0; }
    private static KillAura aura() { return (KillAura) Myau.moduleManager.modules.get(KillAura.class); }
    private static void scaleXZ(double scale) { mc.thePlayer.motionX *= scale; mc.thePlayer.motionZ *= scale; }
    private static void send(Packet<?> packet) { PacketUtil.sendPacket(packet); }
    private static void action(C0BPacketEntityAction.Action action) { send(new C0BPacketEntityAction(mc.thePlayer, action)); }
    private int randomRange(String name) {
        int min = i(name + "-min"), max = i(name + "-max");
        return min + random.nextInt(Math.max(1, max - min + 1));
    }
    private int grimAttackCount() {
        int min = i("GrimTestMinAttacks"), max = i("GrimTestMaxAttacks");
        return max <= min ? max : min + random.nextInt(max - min + 1);
    }

    public synchronized void onUpdate(UpdateEvent event) {
        if (event.getType() != EventType.PRE || mc.thePlayer == null || mc.theWorld == null) return;
        processDelayed();
        if (liquid() || mc.thePlayer.isDead) return;
        int hurt = mc.thePlayer.hurtTime;
        switch (mode()) {
            case "glitch":
                mc.thePlayer.noClip = received; ownsNoClip = true;
                if (hurt == 7) mc.thePlayer.motionY = 0.4;
                received = false;
                break;
            case "reverse":
                if (received) {
                    Entity entity = nearest(f("Range"));
                    if (entity != null && (!b("onLook") || lookingAt(entity))) {
                        if (!mc.thePlayer.onGround) MoveUtil.setSpeed(MoveUtil.getSpeed() * f("ReverseStrength"), MoveUtil.getMoveYaw());
                        else if (elapsed(80)) received = false;
                    }
                }
                break;
            case "smoothreverse":
                if (received) {
                    Entity entity = nearest(f("Range"));
                    ownsAirSpeed = true;
                    if (entity == null || b("onLook") && !lookingAt(entity)) {
                        ((IAccessorEntityPlayer) mc.thePlayer).setSpeedInAir(0.02f); reverseHurt = false;
                        if (entity != null) received = false;
                    } else {
                        if (hurt > 0) reverseHurt = true;
                        if (!mc.thePlayer.onGround) ((IAccessorEntityPlayer) mc.thePlayer).setSpeedInAir(reverseHurt ? f("SmoothReverseStrength") : 0.02f);
                        else if (elapsed(80)) { received = reverseHurt = false; ((IAccessorEntityPlayer) mc.thePlayer).setSpeedInAir(0.02f); }
                    }
                }
                break;
            case "aac":
                if (received && elapsed(80)) { scaleXZ(f("Horizontal")); received = false; }
                break;
            case "aacv4": if (hurt > 0 && !mc.thePlayer.onGround) scaleXZ(f("AACv4MotionReducer")); break;
            case "aacpush":
                if (jumped) { if (mc.thePlayer.onGround) jumped = false; }
                else {
                    if (hurt > 0 && mc.thePlayer.motionX != 0 && mc.thePlayer.motionZ != 0) mc.thePlayer.onGround = true;
                    if (mc.thePlayer.hurtResistantTime > 0 && b("AACPushYReducer") && !speedEnabled()) mc.thePlayer.motionY -= 0.014999993;
                }
                if (mc.thePlayer.hurtResistantTime >= 19) scaleXZ(1.0 / f("AACPushXZReducer"));
                break;
            case "aaczero":
                if (hurt > 0) {
                    if (received && !mc.thePlayer.onGround && mc.thePlayer.fallDistance <= 2) {
                        mc.thePlayer.motionY -= 1; mc.thePlayer.isAirBorne = true; mc.thePlayer.onGround = true;
                    }
                } else received = false;
                break;
            case "legit":
                if ((!b("DisableInAir") || onGround(0.5)) && mc.thePlayer.maxHurtResistantTime != 0
                        && mc.thePlayer.maxHurtResistantTime == mc.thePlayer.hurtResistantTime && random.nextInt(100) < i("Chance")) {
                    scaleXZ(f("Horizontal") / 100f); mc.thePlayer.motionY *= f("Vertical") / 100f;
                }
                break;
            case "intavereduce":
                if (received) {
                    intaveTick++;
                    if (hurt == 2) {
                        intaveHits++;
                        if (mc.thePlayer.onGround && intaveTick % 2 == 0 && intaveHits <= 10) { MoveUtil.tryJump(); intaveTick = 0; }
                        received = false;
                    }
                }
                break;
            case "polar2":
                if (received) {
                    polarTick++;
                    if (hurt == 2) {
                        polarHits++;
                        if (mc.thePlayer.onGround && polarTick % 2 == 0 && polarHits <= 10) { MoveUtil.tryJump(); polarTick = 0; }
                        received = false;
                    }
                }
                break;
            case "hypixel": case "hypixelmorden": if (received && mc.thePlayer.onGround) absorbed = false; break;
            case "hypixelair":
                if (received) { if (mc.thePlayer.onGround) MoveUtil.tryJump(); received = false; }
                break;
            case "prediction":
                if (received) {
                    if (!mc.thePlayer.movementInput.jump && mc.thePlayer.isSprinting() && mc.thePlayer.onGround && hurt == 9) { MoveUtil.tryJump(); cooldown = 0; }
                    received = false;
                }
                break;
            case "polar": if (hurt == polarHurt) { MoveUtil.tryJump(); polarHurt = 8 + random.nextInt(2); } break;
            case "intave14": if (hurt == 0) received = false; break;
            case "intave14.3.3":
                if (hurt == 10) scaleXZ(-1); else if (hurt == 9 && mc.thePlayer.onGround) scaleXZ(0.9);
                break;
            case "legitsmart":
                if (received) {
                    if (mc.thePlayer.onGround && hurt == 9 && mc.thePlayer.isSprinting() && mc.currentScreen == null) {
                        if (legitJumps > i("LegitSmartJumpLimit")) legitJumps = 0;
                        else { legitJumps++; if (mc.thePlayer.ticksExisted % 5 != 0) MoveUtil.tryJump(); }
                    } else if (hurt == 8) { received = false; legitJumps = 0; }
                }
                break;
            case "matrix": received = false; break;
            case "matrixnoxz": if (received && mc.thePlayer.onGround) matrixAbsorbed = false; break;
            case "intave13":
                if (received) {
                    if (!(b("Intave13-NotWhileSpeed") && speedEnabled())
                            && !(b("Intave13-NotWhileJumpBoost") && mc.thePlayer.isPotionActive(Potion.jump))
                            && b("Intave13-Jump") && random.nextInt(100) < i("Intave13-JumpChance")
                            && mc.thePlayer.onGround && (b("Intave13-JumpInInv") || mc.currentScreen == null)) MoveUtil.tryJump();
                    received = false;
                }
                break;
            case "grimreduce":
                if (received && hurt >= i("GrimReduce-MinHT") && hurt <= i("GrimReduce-MaxHT")) {
                    scaleXZ(f("GrimReduce-Factor")); mc.thePlayer.motionY *= f("GrimReduce-Factor");
                    if (b("GrimReduce-OnlyGround") && !mc.thePlayer.onGround) received = false;
                }
                break;
            case "jumpreset":
                if (received) {
                    boolean ready = !b("JRUseCooldown") || cooldown >= i(c("JRCoolDownMode").equals("Ticks") ? "JRTicksUntilJump" : "JRHitsUntilJump");
                    if (!mc.thePlayer.movementInput.jump && mc.thePlayer.isSprinting() && mc.thePlayer.onGround
                            && hurt == 9 && ready && random.nextInt(100) < i("JRChance")) { MoveUtil.tryJump(); cooldown = 0; }
                    received = false;
                }
                if (b("JRUseCooldown") && (c("JRCoolDownMode").equals("Ticks") || hurt == 9)) cooldown++;
                break;
            case "bounce":
                if (hurt == 9 - i("BounceTick")) {
                    if (b("BounceHorizontal")) scaleXZ(-1);
                    if (b("BounceVertical")) mc.thePlayer.motionY *= -1;
                }
                break;
            case "tick": if (hurt == 10 - i("TickVelocity")) scaleXZ(0); break;
            case "ground": if (hurt == i("GroundDelay")) mc.thePlayer.onGround = true; break;
            case "mmc": if (hurt > 0 && mc.thePlayer.onGround) scaleXZ(0); break;
            case "grimtest": updateGrimTest(); break;
            case "watchdog": updateWatchdog(); break;
            case "watchdogprediction":
                if (wdPredJump || hurt == 1) {
                    float diff = Math.abs(mc.thePlayer.rotationYaw % 360 - wdVelocityYaw);
                    if (mc.thePlayer.onGround || Math.random() > 0.98 && b("WDPredPreventGhosting")
                            || diff <= 15 || diff >= 345 || hurt > 13) {
                        wdPredJump = false; release(watchdogPrediction);
                    }
                }
                break;
            default: break; // Remaining modes act on packets, attacks, jumps or collision boxes.
        }
    }

    private boolean elapsed(long milliseconds) { return System.currentTimeMillis() - velocityTime >= milliseconds; }
    private static boolean onGround(double offset) {
        return !mc.theWorld.getCollidingBoundingBoxes(mc.thePlayer, mc.thePlayer.getEntityBoundingBox().offset(0, -offset, 0)).isEmpty();
    }

    private void updateWatchdog() {
        int hurt = mc.thePlayer.hurtTime;
        if (b("WDAlwaysCancelVertical")) {
            if (hurt == 1 && (Math.abs(mc.thePlayer.posY - Math.floor(mc.thePlayer.posY)) < 0.03 || mc.thePlayer.motionY < 0)) scaleXZ(-1);
            if (hurt >= 1 && hurt <= 4 && mc.thePlayer.onGround) mc.thePlayer.movementInput.jump = true;
        }
        if (mc.thePlayer.onGround && wdJump && !speedEnabled()) {
            wdJump = false;
            double x = mc.thePlayer.motionX, y = mc.thePlayer.motionY, z = mc.thePlayer.motionZ;
            release(watchdog);
            mc.thePlayer.motionX = x; mc.thePlayer.motionY = y; mc.thePlayer.motionZ = z;
            MoveUtil.tryJump();
        }
    }

    private void updateGrimTest() {
        if (grimJumpCountdown > 0) grimJumpCountdown--;
        if (grimDelaying) {
            Entity target = crosshair(6);
            if (target != null) {
                double reduce = 1;
                for (int n = grimAttackCount(); n > 0; n--) {
                    if (mc.thePlayer.isSprinting()) mc.thePlayer.setSprinting(false);
                    mc.playerController.attackEntity(mc.thePlayer, target); mc.thePlayer.swingItem(); reduce *= 0.6;
                }
                double x = grimX * reduce, y = grimY, z = grimZ * reduce;
                endGrimDelay(false);
                mc.thePlayer.motionX = x; mc.thePlayer.motionY = y; mc.thePlayer.motionZ = z;
            } else if (System.currentTimeMillis() - grimDelayStart >= i("GrimTestDelayTimeoutMs")) endGrimDelay(true);
        }
        if (grimSkipDelay && mc.thePlayer.hurtTime == 0) grimSkipDelay = false;
        if (c("GrimTestType").equals("Reduce") && grimTarget != null && grimAttacks > 0) {
            if (grimNotSprinting) action(C0BPacketEntityAction.Action.START_SPRINTING);
            while (grimAttacks-- > 0) {
                send(new C02PacketUseEntity(grimTarget, C02PacketUseEntity.Action.ATTACK)); mc.thePlayer.swingItem();
                scaleXZ(0.6); mc.thePlayer.onGround = false;
            }
            if (grimNotSprinting) action(C0BPacketEntityAction.Action.STOP_SPRINTING);
            grimNotSprinting = false;
        }
    }

    public synchronized void onTick(TickEvent event) {
        if (event.getType() != EventType.PRE || mc.thePlayer == null || mc.theWorld == null) return;
        processDelayed();
        for (Iterator<HeldPacket> it = buffer.iterator(); it.hasNext();) {
            HeldPacket held = it.next();
            if (--held.ticks <= 0) { applyMotion(held.packet); it.remove(); }
        }
        switch (mode()) {
            case "reduce":
                if (remainingAttacks > 0) {
                    Entity target = aura().getTarget();
                    if (target != null && (!b("Reduce-RequireKillAura") || aura().isEnabled())) {
                        mc.playerController.attackEntity(mc.thePlayer, target);
                        scaleXZ(f("Reduce-Horizontal")); mc.thePlayer.motionY *= f("Reduce-Vertical"); remainingAttacks--;
                    } else remainingAttacks = 0;
                }
                break;
            case "hypixelprediction": tickHypixelPrediction(); break;
            case "prediction":
                if (mc.thePlayer.hurtTime == 10 && !mc.thePlayer.isBlocking() && !aura().shouldAutoBlock()) {
                    Entity target = crosshair(3);
                    if (target != null) for (int n = randomRange("PredictionClicks"); n > 0; n--) sprintAttack(target, "Normal");
                }
                break;
            case "click":
                if (mc.thePlayer.hurtTime == i("HurtTimeToClick")
                        && !(b("IgnoreBlocking") && (mc.thePlayer.isBlocking() || aura().shouldAutoBlock()))) {
                    Entity target = crosshair(f("ClickRange"));
                    if (target == null && !b("WhenFacingEnemyOnly")) target = nearest(f("ClickRange"));
                    if (target != null) for (int n = randomRange("Clicks"); n > 0; n--) sprintAttack(target, c("SwingMode"));
                }
                break;
            case "watchdogreduce": if (b("WDReduceCancelOnGround") && mc.thePlayer.onGround) release(watchdogReduce); break;
            case "grimc03":
                net.minecraft.util.Timer timer = ((IAccessorMinecraft) mc).getTimer();
                if (timer.timerSpeed <= 1) {
                    timer.timerSpeed = timerTicks > 0 ? Math.min(1, 0.8f + 0.2f * (20 - timerTicks--) / 20) : 1;
                    ownsTimer = true;
                }
                if (received) {
                    BlockPos pos = new BlockPos(mc.thePlayer.posX, mc.thePlayer.posY, mc.thePlayer.posZ);
                    if (mc.theWorld.isAirBlock(pos)) {
                        timerTicks = 20;
                        send(new C03PacketPlayer(true));
                        send(new C07PacketPlayerDigging(C07PacketPlayerDigging.Action.STOP_DESTROY_BLOCK, pos, EnumFacing.DOWN));
                        mc.theWorld.setBlockToAir(pos); received = false;
                    }
                }
                break;
            default: break;
        }
    }

    private void tickHypixelPrediction() {
        if (predictionJump) {
            predictionJump = false;
            if (mc.thePlayer.onGround && mc.thePlayer.isSprinting() && !mc.thePlayer.isPotionActive(Potion.jump) && !liquid()) mc.thePlayer.movementInput.jump = true;
        }
        int hurt = mc.thePlayer.hurtTime;
        Entity target = aura().getTarget();
        if (hurt > predictionLastHurt) {
            if (target == null && predictionRotate) {
                mc.thePlayer.rotationYaw += MathHelper.wrapAngleTo180_float(predictionYaw - mc.thePlayer.rotationYaw) * 0.5f;
                mc.thePlayer.rotationYawHead = mc.thePlayer.rotationYaw;
                if (mc.thePlayer.onGround) MoveUtil.tryJump();
                predictionRotate = false;
            } else if (target != null) {
                if (mc.thePlayer.onGround) MoveUtil.tryJump();
                if (mc.thePlayer.getDistanceToEntity(target) <= 3) predictionAttack = 1;
            }
        }
        if (predictionAttack == 0) {
            if (target != null && mc.thePlayer.getDistanceToEntity(target) <= 3) { mc.thePlayer.swingItem(); mc.playerController.attackEntity(mc.thePlayer, target); }
            predictionAttack = -1;
        }
        if (predictionAttack > 0) predictionAttack--;
        predictionLastHurt = hurt;
    }

    public synchronized void onAttack(AttackEvent event) {
        if (mc.thePlayer == null) return;
        int hurt = mc.thePlayer.hurtTime;
        long now = System.currentTimeMillis();
        switch (mode()) {
            case "intavereduce":
                if (received) { if (hurt == i("HurtTime") && now - lastAttack <= 8000) scaleXZ(f("Factor")); lastAttack = now; }
                break;
            case "polar2":
                if (received) { if (hurt == i("Polar2-HurtTime") && now - polarAttack <= 8000) scaleXZ(f("Polar2-Factor")); polarAttack = now; }
                break;
            case "attackreduce":
                if (hurt >= Math.min(i("AttackReduce-HurtTime-min"), i("AttackReduce-HurtTime-max"))
                        && hurt <= Math.max(i("AttackReduce-HurtTime-min"), i("AttackReduce-HurtTime-max")))
                    scaleXZ(f(mc.thePlayer.isSprinting() ? "AttackReduce-SprintFactor" : "AttackReduce-Factor"));
                break;
            case "intavestrong": if (hurt > 0) scaleXZ(f("IntaveStrong-Factor")); break;
            case "intave14":
                if (!received || b("Intave14-OnlyWhenBackward") && !movingBackwards()) return;
                String[] phases = {"Intave14-FirstReduce", "Intave14-SecondReduce", "Intave14-ThirdReduce"};
                for (int phase = 0; phase < 3; phase++) {
                    if (hurt == i(phases[phase])) {
                        if (phase < i("Intave14-TriggerTimes") && !intavePhases[phase]) {
                            scaleXZ(phase == 0 || intaveFirst ? 0.6 : 0.35);
                            intaveFirst = false; intavePhases[phase] = true; intaveReverseCount++;
                        }
                        break;
                    }
                }
                if (b("Intave14-FinalReverse") && hurt == 1 && !intaveReversed && intaveReverseCount >= i("Intave14-TriggerTimes")) {
                    scaleXZ(-f("Intave14-FinalReverseFactor")); intaveReversed = true;
                }
                break;
            default: break;
        }
    }

    public synchronized void onJump(JumpEvent event) {
        if (event.getType() != EventType.PRE || mc.thePlayer == null || liquid()) return;
        switch (mode()) {
            case "aacpush": jumped = true; if (!mc.thePlayer.isCollidedVertically) event.setCancelled(true); break;
            case "aaczero": if (mc.thePlayer.hurtTime > 0) event.setCancelled(true); break;
            case "ground": if (mc.thePlayer.hurtTime == i("GroundDelay") + 1) mc.thePlayer.movementInput.jump = false; break;
            case "watchdogprediction": wdPredShouldJump = false; break; // The original recursive tryJump is already represented by this jump.
            default: break;
        }
    }

    public synchronized void onBlockBB(BlockBBEvent event) {
        if (mc.thePlayer == null) return;
        int hurt = mc.thePlayer.hurtTime;
        boolean ghost = mode().equals("ghostblock") && received;
        boolean karhu = mode().equals("karhu") && hurt > 0;
        if (ghost && hurt == 0) received = false;
        if (ghost && hurt >= Math.min(i("HurtTime-min"), i("HurtTime-max")) && hurt <= Math.max(i("HurtTime-min"), i("HurtTime-max")) || karhu) {
            if (event.getBlock() instanceof BlockAir && event.getY() == (int) mc.thePlayer.posY + 1)
                event.setBoundingBox(new AxisAlignedBB(event.getX(), event.getY(), event.getZ(), event.getX() + 1, event.getY() + 1, event.getZ() + 1));
        }
    }

    public synchronized void onPacket(PacketEvent event) {
        if (mc.thePlayer == null || mc.theWorld == null || event.isCancelled()) return;
        Packet<?> packet = event.getPacket();
        String mode = mode();
        if (event.getType() == EventType.SEND) {
            if (mode.equals("blocksmc") && received && packet instanceof C0BPacketEntityAction) {
                received = false; event.setCancelled(true);
            }
            if (mode.equals("watchdogreduce") && b("WDReduceCancelOnAttack") && packet instanceof C02PacketUseEntity
                    && ((C02PacketUseEntity) packet).getAction() == C02PacketUseEntity.Action.ATTACK) release(watchdogReduce);
            if (mode.equals("grim2") && b("Grim2FullRotationFix") && packet instanceof C03PacketPlayer) {
                C03PacketPlayer movement = (C03PacketPlayer) packet;
                if (movement.getRotating()) {
                    IAccessorC03PacketPlayer access = (IAccessorC03PacketPlayer) movement;
                    access.setYaw(movement.getYaw() + (random.nextFloat() - 0.5f) * f("Grim2RotationNoise") * 2);
                    access.setPitch(movement.getPitch() + (random.nextFloat() - 0.5f) * f("Grim2RotationNoise") * 2);
                }
            }
            return;
        }
        if (event.getType() != EventType.RECEIVE) return;
        // World transitions must discard queued packets from the old world before the new one is entered.
        if (packet instanceof S07PacketRespawn || packet instanceof S01PacketJoinGame) { reset(false); return; }
        if (pausePackets > 0) { pausePackets--; return; }

        S12PacketEntityVelocity velocity = packet instanceof S12PacketEntityVelocity ? (S12PacketEntityVelocity) packet : null;
        S27PacketExplosion explosion = packet instanceof S27PacketExplosion ? (S27PacketExplosion) packet : null;
        boolean ownVelocity = velocity != null && velocity.getEntityID() == mc.thePlayer.getEntityId();
        boolean qualifying = ownVelocity && velocity.getMotionY() > 0 && (velocity.getMotionX() != 0 || velocity.getMotionZ() != 0)
                || explosion != null && mc.thePlayer.motionY + explosion.func_149144_d() > 0
                && (mc.thePlayer.motionX + explosion.func_149149_c() != 0 || mc.thePlayer.motionZ + explosion.func_149147_e() != 0);

        // These packet families have non-velocity events as well; route them outside the velocity-only gate.
        if (mode.equals("delay") && (packet instanceof S32PacketConfirmTransaction || velocity != null)) {
            delayed.add(new HeldPacket(packet, System.currentTimeMillis())); event.setCancelled(true); return;
        }
        if (mode.equals("grim2") && packet instanceof S08PacketPlayerPosLook && b("Grim2FakeS08")) {
            acknowledgePosition((S08PacketPlayerPosLook) packet); event.setCancelled(true); return;
        }
        if (mode.equals("grimtest")) {
            if (handleGrimPacket(event, qualifying, ownVelocity)) return;
        }
        if (mode.equals("watchdog") && packet instanceof S32PacketConfirmTransaction && wdJump && b("WDPingSpoof")) {
            watchdog.add(packet); event.setCancelled(true); return;
        }
        if (mode.equals("watchdogprediction") && wdPredJump && !ownVelocity && !(packet instanceof S3BPacketScoreboardObjective)
                && !(packet instanceof S3CPacketUpdateScore) && !(packet instanceof S3EPacketTeams)) {
            watchdogPrediction.add(packet); event.setCancelled(true); return;
        }
        if (mode.equals("watchdogreduce") && packet instanceof S32PacketConfirmTransaction && !watchdogReduce.isEmpty()) {
            watchdogReduce.add(packet); event.setCancelled(true); return;
        }
        if (mode.equals("vulcan") && packet instanceof S32PacketConfirmTransaction) {
            event.setCancelled(true);
            PacketUtil.sendPacketNoEvent(new C0FPacketConfirmTransaction(transaction ? 1 : -1, (short) (transaction ? -1 : 1), transaction));
            transaction = !transaction; return;
        }
        if (mode.equals("s32packet") && packet instanceof S32PacketConfirmTransaction && received) {
            received = false; event.setCancelled(true); return;
        }
        if (!qualifying) return;
        velocityTime = System.currentTimeMillis();
        if (explosion != null && b("PauseOnExplosion")) pausePackets = i("TicksToPause");
        if (!directionAllowed(velocity, explosion)) return;

        switch (mode) {
            case "simple": handleSimple(event, velocity, explosion); break;
            case "aac": case "reverse": case "smoothreverse": case "aaczero": case "ghostblock": case "intavereduce": case "polar2":
            case "prediction": case "polar": case "intave14.3.3": case "legitsmart": case "grimreduce":
                received = true; break;
            case "glitch":
                if (mc.thePlayer.onGround) { received = true; event.setCancelled(true); }
                break;
            case "matrixreduce":
                if (ownVelocity) scalePacket(velocity, f("MatrixReduceFactor") * (mc.thePlayer.onGround ? 0.86 : 1), 1);
                break;
            case "blocksmc": case "vulcan2.0.1":
                if (ownVelocity) {
                    received = true; event.setCancelled(true);
                    action(C0BPacketEntityAction.Action.START_SNEAKING); action(C0BPacketEntityAction.Action.STOP_SNEAKING);
                }
                break;
            case "grimc03": if (moving()) { received = true; event.setCancelled(true); } break;
            case "hypixel": case "hypixelmorden":
                received = true;
                if (!mc.thePlayer.onGround && !absorbed) { event.setCancelled(true); absorbed = true; }
                else if (ownVelocity) {
                    IAccessorS12PacketEntityVelocity access = (IAccessorS12PacketEntityVelocity) velocity;
                    access.setMotionX((int) (mc.thePlayer.motionX * 8000)); access.setMotionZ((int) (mc.thePlayer.motionZ * 8000));
                }
                break;
            case "hypixelair": case "s32packet": received = true; event.setCancelled(true); break;
            case "vulcan": event.setCancelled(true); break;
            case "intave14":
                received = true; intaveFirst = true; intaveReversed = false; intaveReverseCount = 0; Arrays.fill(intavePhases, false);
                break;
            case "buffer": buffer.add(new HeldPacket(packet, i("BufferDelay"))); event.setCancelled(true); break;
            case "matrix": case "matrixnoxz":
                received = true;
                if (!mc.thePlayer.onGround && !matrixAbsorbed) { event.setCancelled(true); matrixAbsorbed = true; }
                else if (ownVelocity) scalePacket(velocity, 0, 1);
                break;
            case "intave13":
                if (random.nextInt(100) < i("Intave13-Chance")) {
                    received = true;
                    if (ownVelocity) scalePacket(velocity, f(mc.thePlayer.isSprinting() ? "Intave13-XZonSprintHit" : "Intave13-XZonHit"), 1);
                } else event.setCancelled(true);
                break;
            case "grimcombat":
                if (ownVelocity && !mc.thePlayer.isDead && !mc.thePlayer.isCollidedHorizontally && !liquid()
                        && Math.hypot(velocity.getMotionX(), velocity.getMotionZ()) > 1000) {
                    Entity target = combatTarget(f("GrimCombatRange"));
                    if (target != null) {
                        boolean sprinting = mc.thePlayer.isSprinting();
                        if (!sprinting) action(C0BPacketEntityAction.Action.START_SPRINTING);
                        for (int n = i("GrimCombatAttacks"); n > 0; n--) {
                            send(new C02PacketUseEntity(target, C02PacketUseEntity.Action.ATTACK)); send(new C0APacketAnimation());
                        }
                        if (!sprinting) action(C0BPacketEntityAction.Action.STOP_SPRINTING);
                        event.setCancelled(true);
                    } else received = true;
                }
                break;
            case "hypixelprediction":
                if (ownVelocity) {
                    double x = velocity.getMotionX() / 8000.0, z = velocity.getMotionZ() / 8000.0;
                    if (x != 0 || z != 0) { predictionYaw = (float) Math.toDegrees(Math.atan2(-z, -x)) - 90; predictionRotate = true; }
                    if (b("PredFakeCheck") && !predictionAllowNext) { predictionAllowNext = true; break; }
                    predictionAllowNext = true;
                    predictionCounter = predictionCounter % 100 + i("PredChance");
                    if (predictionCounter >= 100) {
                        predictionJump = true;
                        // Modify the packet so vanilla applies the configured motion once, after this event.
                        scalePacket(velocity, f("PredHorizontal"), f("PredVertical"));
                    } else event.setCancelled(true);
                }
                break;
            case "jumpreset":
                if (!b("JRCheckDirection")) received = true;
                else if (ownVelocity && Math.abs(Math.atan2(velocity.getMotionX() / 8000.0, velocity.getMotionZ() / 8000.0)
                        - Math.toRadians(mc.thePlayer.rotationYaw)) < 2) received = true;
                break;
            case "reduce": remainingAttacks = i("Reduce-AttackCount"); break;
            case "aac5": if (ownVelocity) { received = true; event.setCancelled(true); scaleXZ(0.9); } break;
            case "aac3.3.12": case "aac3.3.14":
                if (ownVelocity) { received = true; event.setCancelled(true); scalePacket(velocity, mode.equals("aac3.3.12") ? 0.85 : 0.75, 1); }
                break;
            case "matrix6.6.1":
                received = true;
                if (ownVelocity) {
                    scalePacket(velocity, 0.4, 1);
                    if (mc.thePlayer.onGround) scalePacket(velocity, 0.85, 1);
                }
                break;
            case "intave13keeplow":
                received = true;
                if (ownVelocity) { scalePacket(velocity, 1, 0.3); mc.thePlayer.motionY *= 0.3; }
                break;
            case "intave13reverse": received = true; if (ownVelocity) scaleXZ(-0.5); break;
            case "intave13gommezero":
                received = true;
                if (ownVelocity) { event.setCancelled(true); scaleXZ(0); mc.thePlayer.motionY = 0; }
                break;
            case "intave13wall": received = true; if (ownVelocity && mc.thePlayer.isCollidedHorizontally) event.setCancelled(true); break;
            case "intave13old": received = true; if (ownVelocity) scalePacket(velocity, 0.65, 1); break;
            case "intave/polar-flag":
                received = true;
                if (ownVelocity) { event.setCancelled(true); if (mc.thePlayer.onGround) MoveUtil.tryJump(); }
                break;
            case "smartjumpreset":
                received = true;
                if (ownVelocity) {
                    event.setCancelled(true);
                    if (mc.thePlayer.onGround && mc.thePlayer.isSprinting() && !mc.thePlayer.isPotionActive(Potion.jump)) MoveUtil.tryJump();
                }
                break;
            case "universocraft": event.setCancelled(true); mc.thePlayer.motionY += 0.1 - Math.random() / 100; break;
            case "mmc": event.setCancelled(true); break;
            case "grim2": if (ownVelocity && b("Grim2CancelVelocity")) event.setCancelled(true); break;
            case "watchdog":
                if (ownVelocity) {
                    if (b("WDStack") && wdStack < 1 && !mc.thePlayer.onGround && Math.random() < 0.73 && mc.thePlayer.hurtTime <= 13) {
                        wdStack = 0; wdJump = true; watchdog.add(packet); event.setCancelled(true);
                    } else if (!speedEnabled()) event.setCancelled(true);
                    else mc.thePlayer.motionY = velocity.getMotionY() / 8000.0;
                }
                break;
            case "watchdogprediction":
                if (ownVelocity && mc.thePlayer.ticksExisted >= 50) {
                    if (velocity.getMotionY() > 0 || mc.thePlayer.hurtTime <= 14) wdPredShouldJump = true;
                    if (!mc.thePlayer.onGround && mc.thePlayer.hurtTime > 4) {
                        wdPredJump = true;
                        wdVelocityYaw = (float) Math.toDegrees(Math.atan2(velocity.getMotionZ(), velocity.getMotionX()));
                        watchdogPrediction.add(packet); event.setCancelled(true);
                    }
                }
                break;
            case "watchdogreduce":
                if (ownVelocity) { watchdogReduce.add(packet); event.setCancelled(true); }
                else if (explosion != null && b("WDReduceCancelExplosions")) event.setCancelled(true);
                break;
            case "watchdogdamageboost":
                if (ownVelocity) {
                    if (mc.thePlayer.onGround && !speedEnabled()) mc.thePlayer.motionY = velocity.getMotionY() / 8000.0;
                    event.setCancelled(true);
                } else if (explosion != null && i("WDDmgHorizontal") == 0 && i("WDDmgVertical") == 0) event.setCancelled(true);
                break;
            // These modes are implemented in their non-packet event handlers.
            case "aacpush": case "aacv4": case "legit": case "intavestrong": case "attackreduce":
            case "click": case "bounce": case "karhu": case "grimtest": case "tick": case "ground": case "delay":
                break;
            default: throw new IllegalStateException("Unmapped xw Velocity mode: " + mode);
        }
    }

    private boolean handleGrimPacket(PacketEvent event, boolean qualifying, boolean ownVelocity) {
        Packet<?> packet = event.getPacket();
        String type = c("GrimTestType");
        if (type.equals("Grim Full")) {
            if (ownVelocity) { event.setCancelled(true); return true; }
            if (packet instanceof S08PacketPlayerPosLook) {
                acknowledgePosition((S08PacketPlayerPosLook) packet); event.setCancelled(true); return true;
            }
        }
        if (type.equals("Reduce") && b("GrimTestDelayRelease") && !grimDelaying && packet instanceof S08PacketPlayerPosLook) grimSkipDelay = true;
        if (packet instanceof S0BPacketAnimation) {
            S0BPacketAnimation animation = (S0BPacketAnimation) packet;
            if (animation.getEntityID() == mc.thePlayer.getEntityId() && animation.getAnimationType() == 1) grimHurt = true;
        }
        if (qualifying && ownVelocity) {
            S12PacketEntityVelocity velocity = (S12PacketEntityVelocity) packet;
            grimTarget = aura().getTarget();
            double x = velocity.getMotionX() / 8000.0, y = velocity.getMotionY() / 8000.0, z = velocity.getMotionZ() / 8000.0;
            double strength = Math.hypot(x, z);
            if (type.equals("Reduce") && grimHurt) {
                grimHurt = false;
                if (!liquid() && !mc.thePlayer.isOnLadder() && System.currentTimeMillis() - enableTime >= 1000 && strength >= 0.1 && mc.thePlayer.isSprinting()) {
                    if (b("GrimTestDelayRelease") && !grimSkipDelay && strength >= f("GrimTestDelaySpeedThreshold")) {
                        grimDelaying = true; grimX = x; grimY = y; grimZ = z; grimDelayStart = System.currentTimeMillis();
                        event.setCancelled(true); return true;
                    }
                    grimSkipDelay = false; grimAttacks = grimAttackCount(); grimNotSprinting = !mc.thePlayer.isSprinting();
                }
            } else if (type.equals("Jump Reset")) grimJumpCountdown = i("GrimTestJumpResetTick");
        }
        if (grimDelaying) {
            if (packet instanceof S23PacketBlockChange || packet instanceof S29PacketSoundEffect || packet instanceof S3EPacketTeams) return false;
            if (packet instanceof S06PacketUpdateHealth) {
                if (((S06PacketUpdateHealth) packet).getHealth() <= 0) endGrimDelay(true);
                return false;
            }
            if (packet instanceof S08PacketPlayerPosLook) { endGrimDelay(true); return false; }
            grimHeld.add(packet); event.setCancelled(true); return true;
        }
        return false;
    }

    private static void acknowledgePosition(S08PacketPlayerPosLook packet) {
        double x = packet.getX(), y = packet.getY(), z = packet.getZ();
        float yaw = packet.getYaw(), pitch = packet.getPitch();
        Set<S08PacketPlayerPosLook.EnumFlags> flags = packet.func_179834_f();
        if (flags.contains(S08PacketPlayerPosLook.EnumFlags.X)) x += mc.thePlayer.posX;
        if (flags.contains(S08PacketPlayerPosLook.EnumFlags.Y)) y += mc.thePlayer.posY;
        if (flags.contains(S08PacketPlayerPosLook.EnumFlags.Z)) z += mc.thePlayer.posZ;
        if (flags.contains(S08PacketPlayerPosLook.EnumFlags.Y_ROT)) yaw += mc.thePlayer.rotationYaw;
        if (flags.contains(S08PacketPlayerPosLook.EnumFlags.X_ROT)) pitch += mc.thePlayer.rotationPitch;
        mc.thePlayer.setPosition(x, y, z);
        mc.thePlayer.motionX = mc.thePlayer.motionY = mc.thePlayer.motionZ = 0;
        send(new C03PacketPlayer.C06PacketPlayerPosLook(x, mc.thePlayer.getEntityBoundingBox().minY, z, yaw, pitch, false));
    }

    private boolean directionAllowed(S12PacketEntityVelocity velocity, S27PacketExplosion explosion) {
        if (!b("CheckDirection")) return true;
        double x = velocity != null ? velocity.getMotionX() : mc.thePlayer.motionX + explosion.func_149149_c();
        double z = velocity != null ? velocity.getMotionZ() : mc.thePlayer.motionZ + explosion.func_149147_e();
        if (x == 0 && z == 0) return true;
        int packetAngle = Math.floorMod((int) Math.toDegrees(Math.atan2(x, z)), 360);
        int direction = (int) MoveUtil.getMoveYaw();
        int relative = Math.floorMod(packetAngle - direction, 360);
        return relative >= 135 && relative <= 225 && b("ApplyOnFront")
                || (relative >= 315 || relative <= 45) && b("ApplyOnBack")
                || (relative >= 45 && relative <= 135 || relative >= 225 && relative <= 315) && b("ApplyOnSide");
    }

    private void handleSimple(PacketEvent event, S12PacketEntityVelocity velocity, S27PacketExplosion explosion) {
        float horizontal = f("Horizontal"), vertical = f("Vertical");
        if (velocity != null) {
            event.setCancelled(true);
            double x = velocity.getMotionX() / 8000.0, y = velocity.getMotionY() / 8000.0, z = velocity.getMotionZ() / 8000.0;
            if (b("LimitMaxMotion")) {
                double length = Math.hypot(x, z);
                if (length > f("MaxXZMotion") && length > 0) { x *= f("MaxXZMotion") / length; z *= f("MaxXZMotion") / length; }
                y = Math.min(y, f("MaxYMotion") + 0.00075);
            }
            if (horizontal != 0) { mc.thePlayer.motionX = x * horizontal; mc.thePlayer.motionZ = z * horizontal; }
            if (vertical != 0) mc.thePlayer.motionY = y * vertical;
        } else if (explosion != null) {
            float x, y, z;
            // Preserve the source mode's explosion semantics, including its both-nonzero special case.
            if (horizontal != 0 && vertical != 0) { x = y = z = 0; }
            else { x = explosion.func_149149_c() * horizontal; y = explosion.func_149144_d() * vertical; z = explosion.func_149147_e() * horizontal; }
            if (b("LimitMaxMotion")) {
                double length = Math.hypot(x, z);
                if (length > f("MaxXZMotion") && length > 0) { x *= f("MaxXZMotion") / length; z *= f("MaxXZMotion") / length; }
                y = Math.min(y, f("MaxYMotion") + 0.00075f);
            }
            IAccessorS27PacketExplosion access = (IAccessorS27PacketExplosion) explosion;
            access.setMotionX(x); access.setMotionY(y); access.setMotionZ(z);
        }
    }

    private static void scalePacket(S12PacketEntityVelocity packet, double horizontal, double vertical) {
        IAccessorS12PacketEntityVelocity access = (IAccessorS12PacketEntityVelocity) packet;
        access.setMotionX((int) (packet.getMotionX() * horizontal));
        access.setMotionY((int) (packet.getMotionY() * vertical));
        access.setMotionZ((int) (packet.getMotionZ() * horizontal));
    }

    private static boolean selected(Entity entity) {
        return entity instanceof EntityLivingBase && entity != mc.thePlayer && entity.isEntityAlive()
                && (!(entity instanceof EntityPlayer) || !TeamUtil.isFriend((EntityPlayer) entity)
                && !TeamUtil.isSameTeam((EntityPlayer) entity) && !TeamUtil.isBot((EntityPlayer) entity));
    }

    private static Entity nearest(float range) {
        Entity best = null;
        double distance = range;
        for (Entity entity : mc.theWorld.loadedEntityList) {
            if (!selected(entity)) continue;
            double next = mc.thePlayer.getDistanceToEntity(entity);
            if (next <= distance) { best = entity; distance = next; }
        }
        return best;
    }

    private static Entity crosshair(float range) {
        RayCastUtil.RayCastResult result = RayCastUtil.rayCast(new RotationUtil.RotationVec(
                myau.management.RotationState.isActived() ? myau.management.RotationState.getRotationYawHead() : mc.thePlayer.rotationYaw,
                myau.management.RotationState.isActived() ? myau.management.RotationState.getRotationPitch() : mc.thePlayer.rotationPitch), range);
        return result != null && selected(result.entityHit) ? result.entityHit : null;
    }

    private static Entity combatTarget(float range) {
        Entity target = crosshair(range);
        if (target == null) target = aura().getTarget();
        return target != null && selected(target) && mc.thePlayer.getDistanceToEntity(target) <= range ? target : null;
    }

    private boolean lookingAt(Entity target) {
        float[] wanted = RotationUtil.getRotationsToEntity(target);
        return RotationUtil.getRotationDifference(mc.thePlayer.rotationYaw, mc.thePlayer.rotationPitch, wanted[0], wanted[1]) <= f("MaxAngleDifference");
    }

    private static boolean movingBackwards() {
        if (MoveUtil.getSpeed() < 0.1) return true;
        float angle = (float) Math.toDegrees(Math.atan2(mc.thePlayer.motionX, mc.thePlayer.motionZ));
        return Math.abs(MathHelper.wrapAngleTo180_float(angle - mc.thePlayer.rotationYaw)) >= 60;
    }

    private static void sprintAttack(Entity entity, String swing) {
        boolean sprinting = mc.thePlayer.isSprinting();
        mc.thePlayer.setSprinting(true);
        if (swing.equals("Normal")) mc.thePlayer.swingItem();
        else if (swing.equals("Packet")) send(new C0APacketAnimation());
        mc.playerController.attackEntity(mc.thePlayer, entity);
        mc.thePlayer.setSprinting(sprinting);
    }

    @SuppressWarnings("unchecked")
    private static void process(Packet<?> packet) {
        net.minecraft.client.network.NetHandlerPlayClient handler = mc.getNetHandler();
        net.minecraft.client.multiplayer.WorldClient world = mc.theWorld;
        if (handler == null || world == null) return;
        Runnable apply = () -> {
            if (mc.getNetHandler() == handler && mc.theWorld == world)
                ((Packet<INetHandlerPlayClient>) packet).processPacket(handler);
        };
        if (mc.isCallingFromMinecraftThread()) apply.run(); else mc.addScheduledTask(apply);
    }

    private static void release(List<Packet<?>> packets) {
        List<Packet<?>> copy = new ArrayList<>(packets);
        packets.clear();
        for (Packet<?> packet : copy) process(packet);
    }

    private void processDelayed() {
        long cutoff = System.currentTimeMillis() - i("SpoofDelay");
        while (!delayed.isEmpty() && delayed.peek().timestamp <= cutoff) process(delayed.remove().packet);
    }

    private static void applyMotion(Packet<?> packet) {
        if (packet instanceof S12PacketEntityVelocity) {
            S12PacketEntityVelocity velocity = (S12PacketEntityVelocity) packet;
            mc.thePlayer.motionX = velocity.getMotionX() / 8000.0;
            mc.thePlayer.motionY = velocity.getMotionY() / 8000.0;
            mc.thePlayer.motionZ = velocity.getMotionZ() / 8000.0;
        } else if (packet instanceof S27PacketExplosion) {
            S27PacketExplosion explosion = (S27PacketExplosion) packet;
            mc.thePlayer.motionX += explosion.func_149149_c(); mc.thePlayer.motionY += explosion.func_149144_d(); mc.thePlayer.motionZ += explosion.func_149147_e();
        }
    }

    private void endGrimDelay(boolean applyVelocity) {
        if (!grimDelaying) return;
        grimDelaying = false;
        release(grimHeld);
        if (applyVelocity && mc.thePlayer != null) { mc.thePlayer.motionX = grimX; mc.thePlayer.motionY = grimY; mc.thePlayer.motionZ = grimZ; }
    }

    public synchronized void reset(boolean flush) {
        if (flush && mc.thePlayer != null && mc.theWorld != null) {
            while (!delayed.isEmpty()) process(delayed.remove().packet);
            release(watchdog); release(watchdogPrediction); release(watchdogReduce); endGrimDelay(true);
            for (HeldPacket held : buffer) applyMotion(held.packet);
        }
        delayed.clear(); buffer.clear(); watchdog.clear(); watchdogPrediction.clear(); watchdogReduce.clear(); grimHeld.clear();
        if (mc.thePlayer != null) {
            if (ownsNoClip) mc.thePlayer.noClip = false;
            if (ownsAirSpeed) ((IAccessorEntityPlayer) mc.thePlayer).setSpeedInAir(0.02f);
        }
        if (ownsTimer && ((IAccessorMinecraft) mc).getTimer().timerSpeed <= 1) ((IAccessorMinecraft) mc).getTimer().timerSpeed = 1;
        ownsNoClip = ownsAirSpeed = ownsTimer = false;
        received = reverseHurt = jumped = transaction = absorbed = matrixAbsorbed = false;
        predictionAllowNext = true; predictionJump = predictionRotate = false;
        pausePackets = timerTicks = cooldown = intaveTick = intaveHits = polarTick = polarHits = legitJumps = 0;
        predictionCounter = predictionLastHurt = 0; predictionAttack = -1; predictionYaw = 0; polarHurt = 8 + random.nextInt(2);
        velocityTime = lastAttack = polarAttack = 0; enableTime = System.currentTimeMillis();
        Arrays.fill(intavePhases, false); intaveFirst = true; intaveReverseCount = 0; intaveReversed = false; remainingAttacks = 0;
        wdJump = wdPredJump = wdPredShouldJump = false; wdStack = 0; wdVelocityYaw = 0;
        grimHurt = grimDelaying = grimSkipDelay = grimNotSprinting = false;
        grimAttacks = grimJumpCountdown = 0; grimTarget = null; grimDelayStart = 0; grimX = grimY = grimZ = 0;
    }

    private static final class HeldPacket {
        final Packet<?> packet;
        final long timestamp;
        int ticks;
        HeldPacket(Packet<?> packet, long timestamp) { this.packet = packet; this.timestamp = timestamp; }
        HeldPacket(Packet<?> packet, int ticks) { this(packet, 0L); this.ticks = ticks; }
    }
}
