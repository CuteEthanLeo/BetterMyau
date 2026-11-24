package myau.module.modules.speedmodes.rise;

import myau.events.JumpEvent;
import myau.events.PacketEvent;
import myau.events.StrafeEvent;
import myau.module.modules.speedmodes.SpeedMode;
import myau.module.modules.speedmodes.SpeedUtil;
import myau.util.MoveUtil;
import net.minecraft.block.Block;
import net.minecraft.client.entity.EntityPlayerSP;
import net.minecraft.init.Blocks;
import net.minecraft.network.play.server.S12PacketEntityVelocity;

public class WatchdogRise extends SpeedMode {
    private boolean onIce = false;
    private boolean onUnevenGround = false;
    private boolean appliedGlideBoost = false;
    private boolean liftBoosted = false;
    private double motionDelta = 0.0;
    private int collisionExpireTick = 0;
    private boolean suppressBoost = false;
    private boolean verticallyBlocked = false;
    private boolean recentlyCollided = false;
    private boolean pendingToggle = false;

    public WatchdogRise() {
        super("WatchdogRise");
    }

    private String type() {
        return parent.watchdogType.getModeString();
    }

    private boolean lowStrafe() {
        return "LowStrafe".equals(this.type());
    }

    private static boolean inAllFluidsAndWeb(EntityPlayerSP player) {
        // Same (always-false in practice) condition as xw: water AND web AND lava.
        return player.isInWater() && SpeedUtil.isInWeb() && player.isInLava();
    }

    private static float allowed() {
        return (float) SpeedUtil.getAllowedHorizontalDistance();
    }

    @Override
    public void onPacket(PacketEvent event) {
        if (mc.thePlayer == null || !(event.getPacket() instanceof S12PacketEntityVelocity)) {
            return;
        }
        if (((S12PacketEntityVelocity) event.getPacket()).getEntityID() == mc.thePlayer.getEntityId()) {
            int airTicks = MoveUtil.airTicks;
            if (this.lowStrafe() && (airTicks == 1 || airTicks == 2 || airTicks == 3)) {
                event.setCancelled(true);
            }
        }
    }

    @Override
    public void onMotion() {
        EntityPlayerSP player = mc.thePlayer;
        boolean flyDisabler = parent.watchdogDamageStrafeHypixelFlyDisabler.getValue();
        if (MoveUtil.isMoving()) {
            if (player.hurtTime < 10 && flyDisabler) {
                MoveUtil.strafe();
            } else if (player.hurtTime == 1) {
                MoveUtil.strafe();
            }
        } else if (player.hurtTime == 1) {
            player.motionX *= -1.0;
            player.motionZ *= -1.0;
        }
        if (inAllFluidsAndWeb(player)) {
            this.suppressBoost = true;
            return;
        }
        if (player.isCollidedHorizontally || player.ticksExisted < 2) {
            this.recentlyCollided = true;
            this.collisionExpireTick = player.ticksExisted + 9;
        }
        if (!player.isCollidedHorizontally && player.ticksExisted > this.collisionExpireTick) {
            this.recentlyCollided = false;
            this.verticallyBlocked = false;
        }
        Block below = SpeedUtil.blockAt(0.0, -1.0, 0.0);
        if (below == Blocks.packed_ice || below == Blocks.ice) {
            this.onIce = true;
        } else if (MoveUtil.airTicks > 1) {
            this.onIce = false;
        }
        if (player.onGround) {
            this.appliedGlideBoost = false;
        }
        if (SpeedUtil.blockAt(0.0, player.motionY, 0.0) != Blocks.air) {
            this.suppressBoost = false;
        }
        if (player.isCollidedVertically && !player.onGround && this.lowStrafe() && SpeedUtil.isOnGround(2.0)) {
            this.suppressBoost = true;
        }
        if (Math.abs(player.posY - Math.rint(player.posY)) > 0.0325 && player.onGround) {
            this.onUnevenGround = true;
        } else if (player.onGround) {
            this.onUnevenGround = false;
        }
        boolean inWeb = SpeedUtil.isInWeb();
        int level = SpeedUtil.speedLevel();
        if (inWeb) {
            player.motionY = -0.0784000015258789;
            MoveUtil.strafe((float) (SpeedUtil.getAllowedHorizontalDistance() * 3.1));
        }
        if (inWeb && level >= 1 && !player.onGround) {
            MoveUtil.strafe((float) (SpeedUtil.getAllowedHorizontalDistance() * 3.8));
        }
        if (inWeb && level >= 2 && !player.onGround) {
            MoveUtil.strafe((float) (SpeedUtil.getAllowedHorizontalDistance() * 4.0));
        }
    }

    @Override
    public void onStrafe(StrafeEvent event) {
        EntityPlayerSP player = mc.thePlayer;
        if (player.ticksExisted > 1 && player.isCollidedHorizontally && player.ticksExisted % 3 == 1) {
            MoveUtil.stopXZ();
            return;
        }
        String type = this.type();
        boolean flyDisabler = parent.watchdogDamageStrafeHypixelFlyDisabler.getValue();
        if (player.hurtTime < 2 && flyDisabler && !"Strafe".equals(type)) {
            this.suppressBoost = true;
        }
        if (player.hurtTime < 20 && flyDisabler) {
            SpeedUtil.partialStrafePercent(100.0);
        }
        if (inAllFluidsAndWeb(player)) {
            this.suppressBoost = true;
            return;
        }
        if ("Strafe".equals(type)) {
            this.strafeMode(player);
        } else if ("LowStrafe".equals(type)) {
            this.lowStrafeMode(player);
        } else if ("NCPStrafe".equals(type)) {
            this.ncpStrafeMode(player);
        }
        int airTicks = MoveUtil.airTicks;
        int level = SpeedUtil.speedLevel();
        // Ice speed
        if (SpeedUtil.speed() < 0.45F || level == 1 && SpeedUtil.speed() < 0.55F || level >= 2 && SpeedUtil.speed() < 0.55F) {
            if (this.onIce && player.onGround && !this.suppressBoost) {
                player.motionX *= 1.45;
                player.motionZ *= 1.45;
            }
            Block ahead = SpeedUtil.blockAt(0.0, player.motionY, 0.0);
            if (this.onIce && (ahead == Blocks.ice || ahead == Blocks.packed_ice) && !this.suppressBoost) {
                player.motionX *= 1.01;
                player.motionZ *= 1.01;
            }
            if (this.onIce && airTicks == 1 && !this.suppressBoost) {
                player.motionX *= 1.15;
                player.motionZ *= 1.15;
            }
            if (this.onIce && airTicks > 1 && !this.suppressBoost && "LowStrafe".equals(type)) {
                player.motionX *= 1.015;
                player.motionZ *= 1.015;
            }
        }
        // Friction full disabler
        if (SpeedUtil.angleDiff(SpeedUtil.moveAngle(), SpeedUtil.motionAngle()) < 5.0 && "LowStrafe".equals(type)
                && parent.watchdogFrictionFullDisabler.getValue()
                && (!this.verticallyBlocked || airTicks > 2 && airTicks < 6)
                && player.ticksExisted > 10 && !this.suppressBoost && player.hurtTime > 1
                && SpeedUtil.speed() < SpeedUtil.getAllowedHorizontalDistance() - 0.0323
                && airTicks < 18) {
            if (level >= 1) {
                MoveUtil.strafe((float) (SpeedUtil.getAllowedHorizontalDistance() - 0.003));
            } else {
                MoveUtil.strafe((float) (SpeedUtil.getAllowedHorizontalDistance() - 0.001));
            }
        }
    }

    private void strafeMode(EntityPlayerSP player) {
        int airTicks = MoveUtil.airTicks;
        int level = SpeedUtil.speedLevel();
        if (player.onGround && this.suppressBoost) {
            player.motionY = 0.42;
        }
        player.setSprinting(true);
        // xw has three branches here that all do the same thing.
        if (player.onGround && MoveUtil.isMoving()) {
            MoveUtil.strafe(allowed());
            player.jump();
        }
        if (airTicks == 1 && !this.suppressBoost && !this.recentlyCollided) {
            if (level >= 2) {
                MoveUtil.strafe(0.48F);
            } else if (level == 1) {
                MoveUtil.strafe(0.4F);
            } else {
                MoveUtil.strafe(0.33F);
            }
            MoveUtil.strafe();
        }
        // (airTicks == 2 with air-strafe: xw averages the motion with itself, a no-op.)
        if (airTicks == 8 && SpeedUtil.blockAt(0.0, player.motionY * 4.7, 0.0) != Blocks.air) {
            player.motionZ *= 1.001;
            player.motionX *= 1.001;
        }
        this.motionDelta = Math.hypot(
                player.motionX - (player.lastTickPosX - player.posX),
                player.motionZ - (player.lastTickPosZ - player.posZ)
        );
        if (this.motionDelta < 0.0125 && parent.watchdogFrictionOverride.getValue()) {
            MoveUtil.strafe();
        }
        SpeedUtil.useDiagonalSpeed();
        if (SpeedUtil.speed() < 0.125F) {
            MoveUtil.strafe(0.125F);
        }
    }

    /** The lift / pull-down part shared by LowStrafe and NCPStrafe. */
    private void lowHopPart(EntityPlayerSP player) {
        int airTicks = MoveUtil.airTicks;
        int level = SpeedUtil.speedLevel();
        if (airTicks == 1 && !this.suppressBoost) {
            this.liftBoosted = true;
            player.motionY += 0.057F;
            if (level >= 2) {
                MoveUtil.strafe(0.4875F);
            } else if (level == 1) {
                MoveUtil.strafe(0.4F);
            } else {
                MoveUtil.strafe(0.3325F);
            }
        }
        // (airTicks == 2 with air-strafe: xw averages the motion with itself, a no-op.)
        if (airTicks == 3 && !this.suppressBoost) {
            player.motionY -= 0.1309F;
        }
        if (airTicks == 4 && !this.suppressBoost) {
            player.motionY -= 0.2;
        }
    }

    private void frictionPart(EntityPlayerSP player) {
        double d16 = Math.hypot(
                player.motionX - (player.lastTickPosX - player.posX),
                player.motionZ - (player.lastTickPosZ - player.posZ)
        );
        if (d16 < 0.0125) {
            MoveUtil.strafe();
        }
        if (SpeedUtil.speed() < 0.1245F && parent.watchdogFrictionOverride.getValue()) {
            MoveUtil.strafe(0.1245F);
        }
    }

    private void lowStrafeMode(EntityPlayerSP player) {
        int airTicks = MoveUtil.airTicks;
        int level = SpeedUtil.speedLevel();
        boolean damageBoost = parent.watchdogDamageBoost.getValue();
        boolean alwaysGlide = parent.watchdogAlwaysGlideStrafe.getValue();
        boolean flyDisabler = parent.watchdogDamageStrafeHypixelFlyDisabler.getValue();
        double allowedD = SpeedUtil.getAllowedHorizontalDistance();
        if (player.onGround && MoveUtil.isMoving()) {
            MoveUtil.strafe(allowed());
            player.jump();
        }
        this.lowHopPart(player);
        if (airTicks == 6 && !this.suppressBoost
                && SpeedUtil.hasBlockAtOffset(player.motionY * 3.0)
                && (SpeedUtil.speed() < allowedD * 0.994 || !damageBoost || player.hurtTime > 5)
                && (!this.suppressBoost || !flyDisabler)
                && player.hurtTime > 1) {
            player.motionY += 0.075;
            MoveUtil.strafe(SpeedUtil.speed());
            double d15 = Math.sqrt(player.motionX * player.motionX + player.motionZ * player.motionZ);
            if ((d15 < allowedD || player.motionX == 0.0 || player.motionZ == 0.0)
                    && !this.suppressBoost && !this.recentlyCollided && level >= 1) {
                MoveUtil.strafe((float) (allowedD * 0.994));
            }
        }
        if (airTicks == 7 && !this.suppressBoost
                && SpeedUtil.hasBlockAtOffset(player.motionY * 2.0)
                && (SpeedUtil.speed() < allowedD * 1.1 || !damageBoost || player.hurtTime > 5)
                && player.hurtTime > 1) {
            MoveUtil.strafe(alwaysGlide ? SpeedUtil.speed() : (float) (allowedD * 1.1));
        }
        // Glide boost logic
        if (player.hurtTime <= 1 || this.suppressBoost || parent.watchdogUHCMode.getValue() || this.appliedGlideBoost
                || (airTicks <= 6 || !alwaysGlide) && (airTicks <= 6 || alwaysGlide)
                || SpeedUtil.speed() >= allowedD && damageBoost && player.hurtTime <= 5) {
            if (player.hurtTime > 1 && airTicks > 5 && !this.appliedGlideBoost && !this.suppressBoost && damageBoost) {
                MoveUtil.strafe();
            }
        } else {
            // xw: both the always-glide and the normal branch use the same factors.
            if (level >= 2) {
                MoveUtil.strafe((float) (allowedD * 1.093));
            } else {
                MoveUtil.strafe((float) (allowedD * 1.095));
            }
            this.appliedGlideBoost = true;
        }
        if ((this.suppressBoost || airTicks > 3 || player.onGround) && this.pendingToggle) {
            this.pendingToggle = false;
        }
        this.frictionPart(player);
        if (airTicks > 1 && !player.isCollidedHorizontally) {
            SpeedUtil.moveFlying(0.0009);
        }
    }

    private void ncpStrafeMode(EntityPlayerSP player) {
        SpeedUtil.useDiagonalSpeed();
        if (player.onGround) {
            MoveUtil.strafe(allowed());
            player.jump();
        }
        this.lowHopPart(player);
        this.frictionPart(player);
    }

    /** Called for both the PRE and the POST jump event, like in xw (only PRE has an effect). */
    @Override
    public void onJump(JumpEvent event) {
        if (mc.thePlayer.isInLava() || SpeedUtil.isInWeb()) {
            event.setCancelled(true);
        }
        if (this.lowStrafe() && parent.watchdogAlternateMotion.getValue()) {
            event.setMotion(0.42001F);
        }
    }

    @Override
    public void onEnable() {
        this.pendingToggle = false;
        this.onUnevenGround = false;
        this.liftBoosted = false;
        this.appliedGlideBoost = false;
        this.recentlyCollided = false;
        if (MoveUtil.airTicks > 2) {
            this.suppressBoost = true;
        }
    }

    @Override
    public void onDisable() {
        this.onIce = false;
        this.liftBoosted = false;
        this.suppressBoost = false;
        mc.thePlayer.setSprinting(false);
    }
}
