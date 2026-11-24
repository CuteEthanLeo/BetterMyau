package myau.module.modules;

import myau.Myau;
import myau.event.EventTarget;
import myau.event.types.EventType;
import myau.events.LoadWorldEvent;
import myau.events.MoveEvent;
import myau.events.MoveInputEvent;
import myau.events.PacketEvent;
import myau.events.StepConfirmEvent;
import myau.events.StepEvent;
import myau.events.TickEvent;
import myau.mixin.IAccessorC03PacketPlayer;
import myau.mixin.IAccessorEntity;
import myau.module.Module;
import myau.property.Property;
import myau.property.properties.BooleanProperty;
import myau.property.properties.FloatProperty;
import myau.property.properties.IntProperty;
import myau.property.properties.ModeProperty;
import myau.util.MoveUtil;
import myau.util.PacketUtil;
import myau.util.TimerUtil;
import net.minecraft.block.Block;
import net.minecraft.client.Minecraft;
import net.minecraft.client.entity.EntityPlayerSP;
import net.minecraft.init.Blocks;
import net.minecraft.network.play.client.C03PacketPlayer;
import net.minecraft.network.play.client.C03PacketPlayer.C04PacketPlayerPosition;
import net.minecraft.stats.StatList;
import net.minecraft.util.AxisAlignedBB;
import net.minecraft.util.BlockPos;

import java.util.List;

public class Step extends Module {
    private static final Minecraft mc = Minecraft.getMinecraft();
    private static final String[] MODES = new String[]{
            "Vanilla", "Jump", "NCP", "MotionNCP", "OldNCP", "AAC", "LAAC", "AAC3.3.4", "Spartan", "Rewinside", "BlocksMCTimer", "Hypixel"
    };
    private static final int VANILLA = 0, JUMP = 1, NCP = 2, MOTION_NCP = 3, OLD_NCP = 4, AAC = 5, LAAC = 6, AAC334 = 7, SPARTAN = 8,
            REWINSIDE = 9, BLOCKSMC_TIMER = 10, HYPIXEL = 11;

    public final ModeProperty mode = new ModeProperty("mode", NCP, MODES);
    public final FloatProperty height = new FloatProperty("height", 1.0F, 0.6F, 10.0F, () -> !this.isMotionMode());
    public final FloatProperty jumpHeight = new FloatProperty("jump-height", 0.42F, 0.37F, 0.42F, () -> this.mode.getValue() == JUMP);
    public final IntProperty delay = new IntProperty("delay", 0, 0, 500);
    // Hypixel (LiquidBounce Nextgen ModuleStep.Hypixel): "does not seem to work above a certain y level"
    public final BooleanProperty hypixelAlternateBypass = new BooleanProperty("hypixel-alternate-bypass", false, () -> this.mode.getValue() == HYPIXEL);
    public final BooleanProperty hypixelSpoof = new BooleanProperty("hypixel-spoof", false, () -> this.mode.getValue() == HYPIXEL);
    /** Ticks since the Hypixel step started (0 = idle). */
    private int hypixelTick = 0;
    private double hypixelHeight = 1.0;

    private boolean isStep = false;
    private double stepX = 0.0;
    private double stepY = 0.0;
    private double stepZ = 0.0;

    private int ncpNextStep = 0;
    private boolean spartanSwitch = false;
    private boolean isAACStep = false;

    /** BlocksMCTimer sequence: 0 = idle, 1..3 = ticks after the jump. */
    private int timerStage = 0;
    private boolean timerModified = false;

    private final TimerUtil timer = new TimerUtil();

    public Step() {
        super("Step", false);
    }

    /** Modes that step with motion instead of raising the step height. */
    private boolean isMotionMode() {
        switch (this.mode.getValue()) {
            case JUMP:
            case MOTION_NCP:
            case LAAC:
            case AAC334:
            case BLOCKSMC_TIMER:
            case HYPIXEL:
                return true;
            default:
                return false;
        }
    }

    private void setTimer(float speed) {
        MoveUtil.setTimer(speed);
        this.timerModified = speed != 1.0F;
    }

    private void resetState() {
        this.isStep = false;
        this.stepX = 0.0;
        this.stepY = 0.0;
        this.stepZ = 0.0;
        this.ncpNextStep = 0;
        this.isAACStep = false;
        this.timerStage = 0;
        this.hypixelTick = 0;
        if (this.timerModified) {
            MoveUtil.setTimer(1.0F);
            this.timerModified = false;
        }
    }

    @Override
    public void onDisabled() {
        this.resetState();
        if (mc.thePlayer != null) {
            // Change step height back to default (0.6 is default)
            mc.thePlayer.stepHeight = 0.6F;
        }
    }

    @EventTarget
    public void onLoadWorld(LoadWorldEvent event) {
        this.resetState();
    }

    @EventTarget
    public void onTick(TickEvent event) {
        if (!this.isEnabled() || event.getType() != EventType.PRE) {
            return;
        }
        EntityPlayerSP player = mc.thePlayer;
        if (player == null) {
            return;
        }

        // Remaining ticks of the BlocksMCTimer sequence
        if (this.timerStage > 0) {
            switch (this.timerStage) {
                case 1:
                    this.setTimer(0.2F);
                    this.timerStage = 2;
                    break;
                case 2:
                    this.setTimer(4.0F);
                    this.timerStage = 3;
                    break;
                default:
                    MoveUtil.strafe(0.27F);
                    this.setTimer(1.0F);
                    this.timerStage = 0;
                    break;
            }
            return;
        }

        if (player.isOnLadder() || MoveUtil.isInLiquid() || ((IAccessorEntity) player).getIsInWeb()) {
            return;
        }
        if (!MoveUtil.isMoving()) {
            return;
        }

        // Motion steps
        switch (this.mode.getValue()) {
            case JUMP:
                if (player.isCollidedHorizontally && player.onGround && !mc.gameSettings.keyBindJump.isKeyDown()) {
                    this.fakeJump();
                    player.motionY = this.jumpHeight.getValue().doubleValue();
                }
                break;
            case BLOCKSMC_TIMER:
                if (player.onGround && player.isCollidedHorizontally) {
                    if (!this.couldStep() || this.isChestNearby()) {
                        this.setTimer(1.0F);
                        return;
                    }
                    this.fakeJump();
                    MoveUtil.tryJump();
                    this.setTimer(5.0F);
                    this.timerStage = 1;
                }
                break;
            case LAAC:
                if (player.isCollidedHorizontally) {
                    if (player.onGround && this.timer.hasTimeElapsed(this.delay.getValue())) {
                        this.isStep = true;

                        this.fakeJump();
                        player.motionY += 0.620000001490116;

                        double yaw = MoveUtil.getDirection();
                        player.motionX -= Math.sin(yaw) * 0.2;
                        player.motionZ += Math.cos(yaw) * 0.2;
                        this.timer.reset();
                    }
                    player.onGround = true;
                } else {
                    this.isStep = false;
                }
                break;
            case AAC334:
                if (player.isCollidedHorizontally && MoveUtil.isMoving()) {
                    if (player.onGround && this.couldStep()) {
                        player.motionX *= 1.26;
                        player.motionZ *= 1.26;
                        MoveUtil.tryJump();
                        this.isAACStep = true;
                    }
                    if (this.isAACStep) {
                        player.motionY -= 0.015;
                        if (!player.isUsingItem() && player.movementInput.moveStrafe == 0.0F) {
                            player.jumpMovementFactor = 0.3F;
                        }
                    }
                } else {
                    this.isAACStep = false;
                }
                break;
            default:
                break;
        }
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
        if (this.mode.getValue() != MOTION_NCP || !player.isCollidedHorizontally || mc.gameSettings.keyBindJump.isKeyDown()) {
            return;
        }

        // Motion steps
        if (player.onGround && this.couldStep()) {
            this.fakeJump();
            player.motionY = 0.0;
            event.setY(0.41999998688698);
            this.ncpNextStep = 1;
        } else if (this.ncpNextStep == 1) {
            event.setY(0.7531999805212 - 0.41999998688698);
            this.ncpNextStep = 2;
        } else if (this.ncpNextStep == 2) {
            double yaw = MoveUtil.getDirection();

            event.setY(1.001335979112147 - 0.7531999805212);
            event.setX(-Math.sin(yaw) * 0.7);
            event.setZ(Math.cos(yaw) * 0.7);

            this.ncpNextStep = 0;
        }
    }

    @EventTarget
    public void onStep(StepEvent event) {
        if (!this.isEnabled()) {
            return;
        }
        EntityPlayerSP player = mc.thePlayer;
        if (player == null) {
            return;
        }

        // Some fly modes should disable step
        if (this.flyBlocksStep(player)) {
            event.setHeight(0.0F);
            return;
        }

        // Set step to default in some cases
        if (!player.onGround || !this.timer.hasTimeElapsed(this.delay.getValue()) || this.isMotionMode()) {
            if (!this.isMotionMode()) {
                // This event fires before every move here, so a pending step can never be left over from an older move.
                this.isStep = false;
            }
            event.setHeight(0.6F);
            return;
        }

        // Set step height (only for this move, the mixin restores the player's own value afterwards)
        float stepHeight = this.height.getValue();
        event.setHeight(stepHeight);

        // Detect possible step
        if (stepHeight > 0.6F) {
            this.isStep = true;
            this.stepX = player.posX;
            this.stepY = player.posY;
            this.stepZ = player.posZ;
        } else {
            this.isStep = false;
        }
    }

    @EventTarget
    public void onStepConfirm(StepConfirmEvent event) {
        EntityPlayerSP player = mc.thePlayer;
        if (player == null || !this.isStep) { // Check if step
            return;
        }

        if (player.getEntityBoundingBox().minY - this.stepY > 0.6) { // Check if full block step
            switch (this.mode.getValue()) {
                case NCP:
                case AAC:
                    this.fakeJump();

                    // Half legit step (1 packet missing) [COULD TRIGGER TOO MANY PACKETS]
                    PacketUtil.sendPacket(new C04PacketPlayerPosition(this.stepX, this.stepY + 0.41999998688698, this.stepZ, false));
                    PacketUtil.sendPacket(new C04PacketPlayerPosition(this.stepX, this.stepY + 0.7531999805212, this.stepZ, false));
                    this.timer.reset();
                    break;
                case SPARTAN:
                    this.fakeJump();

                    if (this.spartanSwitch) {
                        // Vanilla step (3 packets) [COULD TRIGGER TOO MANY PACKETS]
                        PacketUtil.sendPacket(new C04PacketPlayerPosition(this.stepX, this.stepY + 0.41999998688698, this.stepZ, false));
                        PacketUtil.sendPacket(new C04PacketPlayerPosition(this.stepX, this.stepY + 0.7531999805212, this.stepZ, false));
                        PacketUtil.sendPacket(new C04PacketPlayerPosition(this.stepX, this.stepY + 1.001335979112147, this.stepZ, false));
                    } else { // Force step
                        PacketUtil.sendPacket(new C04PacketPlayerPosition(this.stepX, this.stepY + 0.6, this.stepZ, false));
                    }

                    // Spartan allows one unlegit step so just swap between legit and unlegit
                    this.spartanSwitch = !this.spartanSwitch;

                    // Reset timer
                    this.timer.reset();
                    break;
                case REWINSIDE:
                    this.fakeJump();

                    // Vanilla step (3 packets) [COULD TRIGGER TOO MANY PACKETS]
                    PacketUtil.sendPacket(new C04PacketPlayerPosition(this.stepX, this.stepY + 0.41999998688698, this.stepZ, false));
                    PacketUtil.sendPacket(new C04PacketPlayerPosition(this.stepX, this.stepY + 0.7531999805212, this.stepZ, false));
                    PacketUtil.sendPacket(new C04PacketPlayerPosition(this.stepX, this.stepY + 1.001335979112147, this.stepZ, false));

                    // Reset timer
                    this.timer.reset();
                    break;
                default:
                    break;
            }
        }

        this.isStep = false;
        this.stepX = 0.0;
        this.stepY = 0.0;
        this.stepZ = 0.0;
    }

    @EventTarget
    public void onPacket(PacketEvent event) {
        if (this.isEnabled() && this.mode.getValue() == HYPIXEL && this.hypixelSpoof.getValue()
                && event.getType() == EventType.SEND && event.getPacket() instanceof C03PacketPlayer
                && MoveUtil.airTicks == 8) {
            ((IAccessorC03PacketPlayer) event.getPacket()).setOnGround(true);
        }
        if (event.getType() != EventType.SEND || !this.isStep || this.mode.getValue() != OLD_NCP) {
            return;
        }
        if (mc.thePlayer == null || !(event.getPacket() instanceof C03PacketPlayer)) {
            return;
        }
        C03PacketPlayer packet = (C03PacketPlayer) event.getPacket();
        // xw only arms this when the move actually ran into a block (its StepEvent fires only then).
        if (mc.thePlayer.isCollidedHorizontally) {
            ((IAccessorC03PacketPlayer) packet).setY(packet.getPositionY() + 0.07);
        }
        this.isStep = false;
    }

    /** Nextgen canStep(height): walking into a wall that is lower than {@code height}. */
    private boolean canStep(double height) {
        EntityPlayerSP player = mc.thePlayer;
        if (!player.isCollidedHorizontally || player.isSneaking() || !player.onGround) {
            return false;
        }
        double angle = Math.toRadians(player.rotationYaw);
        AxisAlignedBB offsetBox = player.getEntityBoundingBox().offset(-Math.sin(angle) * 0.1, 0.0, Math.cos(angle) * 0.1);
        AxisAlignedBB stepBox = offsetBox.offset(0.0, height, 0.0);
        return mc.theWorld.getCollidingBoundingBoxes(player, stepBox).isEmpty()
                && !mc.theWorld.getCollidingBoundingBoxes(player, offsetBox).isEmpty();
    }

    @EventTarget
    public void onMoveInputHypixel(MoveInputEvent event) {
        if (!this.isEnabled() || this.mode.getValue() != HYPIXEL || mc.thePlayer == null) {
            return;
        }
        EntityPlayerSP player = mc.thePlayer;
        if (this.hypixelTick == 0) {
            if (this.canStep(1.5)) {
                this.hypixelHeight = this.canStep(1.0) ? 1.0 : this.canStep(1.25) ? 1.25 : 1.5;
                player.movementInput.jump = true;
                player.motionY = 0.42;
                this.hypixelTick = 1;
            }
            return;
        }
        this.hypixelTick++;
        boolean done = false;
        if (this.hypixelTick == 2) {
            if (this.hypixelHeight > 1.0) {
                player.motionY += 0.061;
            }
        } else if (this.hypixelTick == 4) {
            if (this.hypixelHeight == 1.0) {
                player.motionY -= 0.14;
                done = true;
            } else {
                player.motionY -= 0.095;
                done = this.hypixelHeight <= 1.25;
            }
        } else if (this.hypixelTick == 9) {
            if (this.hypixelAlternateBypass.getValue()) {
                player.onGround = true;
            } else {
                player.motionY = 0.42;
            }
            done = true;
        }
        if (done) {
            this.hypixelTick = 0;
            MoveUtil.strafe(0.1838601407459074);
        }
    }

    // There could be some anti cheats which tries to detect step by checking for achievements and stuff
    private void fakeJump() {
        EntityPlayerSP player = mc.thePlayer;
        if (player == null) {
            return;
        }
        player.isAirBorne = true;
        player.triggerAchievement(StatList.jumpStat);
    }

    private boolean couldStep() {
        EntityPlayerSP player = mc.thePlayer;
        if (player == null || mc.theWorld == null) {
            return false;
        }
        if (player.isSneaking() || mc.gameSettings.keyBindJump.isKeyDown()) {
            return false;
        }

        double yaw = MoveUtil.getDirection();
        double heightOffset = 1.001335979112147;

        for (int i = -10; i <= 10; i++) {
            double adjustedYaw = yaw + (i * Math.toRadians(8.0));
            double x = -Math.sin(adjustedYaw) * 0.2;
            double z = Math.cos(adjustedYaw) * 0.2;

            if (!mc.theWorld.getCollisionBoxes(player.getEntityBoundingBox().offset(x, heightOffset, z)).isEmpty()) {
                return false;
            }
        }
        return true;
    }

    /** Any chest within 2 blocks (same scan as xw's searchBlocks(2, ...)). */
    private boolean isChestNearby() {
        EntityPlayerSP player = mc.thePlayer;
        int radius = 2;
        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos(0, 0, 0);
        for (int x = radius; x >= -radius + 1; x--) {
            for (int y = radius; y >= -radius + 1; y--) {
                for (int z = radius; z >= -radius + 1; z--) {
                    pos.set((int) player.posX + x, (int) player.posY + y, (int) player.posZ + z);
                    Block block = mc.theWorld.getBlockState(pos).getBlock();
                    if (block == Blocks.chest || block == Blocks.ender_chest || block == Blocks.trapped_chest) {
                        return true;
                    }
                }
            }
        }
        return false;
    }

    /** xw: these fly modes turn step off while the hand is empty. */
    private boolean flyBlocksStep(EntityPlayerSP player) {
        if (Myau.moduleManager == null || Myau.propertyManager == null) {
            return false;
        }
        Module fly = Myau.moduleManager.modules.get(Fly.class);
        if (fly == null || !fly.isEnabled() || player.inventory.getCurrentItem() != null) {
            return false;
        }
        List<Property<?>> properties = Myau.propertyManager.properties.get(Fly.class);
        if (properties == null) {
            return false;
        }
        for (Property<?> property : properties) {
            if (property instanceof ModeProperty && property.getName().equalsIgnoreCase("mode")) {
                String flyMode = ((ModeProperty) property).getModeString();
                return flyMode.equals("Hypixel") || flyMode.equals("OtherHypixel") || flyMode.equals("LatestHypixel")
                        || flyMode.equals("Rewinside") || flyMode.equals("Mineplex");
            }
        }
        return false;
    }

    @Override
    public String[] getSuffix() {
        return new String[]{this.mode.getModeString()};
    }
}
