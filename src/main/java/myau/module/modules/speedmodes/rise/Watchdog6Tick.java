package myau.module.modules.speedmodes.rise;

import myau.events.JumpEvent;
import myau.events.MoveEvent;
import myau.events.StrafeEvent;
import myau.module.modules.speedmodes.SpeedMode;
import myau.module.modules.speedmodes.SpeedUtil;
import myau.util.MoveUtil;
import net.minecraft.block.BlockStairs;
import net.minecraft.client.entity.EntityPlayerSP;
import net.minecraft.potion.Potion;
import net.minecraft.util.AxisAlignedBB;
import net.minecraft.util.BlockPos;

public class Watchdog6Tick extends SpeedMode {
    private int tickCounter = 0;
    private boolean onStairs = false;
    private double startPosX = 0.0;
    private double startPosY = 0.0;
    private double startPosZ = 0.0;

    public Watchdog6Tick() {
        super("Watchdog6Tick");
    }

    @Override
    public void onMove(MoveEvent event) {
        if (this.tickCounter < 23 && this.tickCounter > 7) {
            event.zeroXZ();
        }
    }

    @Override
    public void onStrafe(StrafeEvent event) {
        EntityPlayerSP player = mc.thePlayer;
        int airTicks = MoveUtil.airTicks;
        boolean speedPotion = player.isPotionActive(Potion.moveSpeed);
        if (player.onGround) {
            this.onStairs = false;
        }
        // Check for stairs
        AxisAlignedBB aabb = player.getEntityBoundingBox();
        for (double d0 = aabb.minX; d0 < aabb.maxX && !this.onStairs; d0 += 0.3) {
            for (double d1 = aabb.minZ; d1 < aabb.maxZ; d1 += 0.3) {
                if (mc.theWorld.getBlockState(new BlockPos(d0, aabb.minY - 0.05, d1)).getBlock() instanceof BlockStairs) {
                    this.onStairs = true;
                    break;
                }
            }
        }
        if (player.hurtTime == 9 && this.tickCounter < 23) {
            this.tickCounter = 23;
        }
        if (this.tickCounter > 24 && !this.onStairs && player.ticksExisted > 5) {
            switch (airTicks) {
                case 0:
                    if (this.tickCounter <= 48 && (speedPotion || this.tickCounter <= 66)) {
                        MoveUtil.strafe((float) (SpeedUtil.getAllowedHorizontalDistance() - 0.01));
                    } else {
                        MoveUtil.strafe((float) SpeedUtil.getAllowedHorizontalDistance());
                    }
                    if (player.onGround) {
                        player.jump();
                    }
                    break;
                case 1: {
                    int level = SpeedUtil.speedLevel();
                    if (level >= 2) {
                        MoveUtil.strafe(0.48F);
                    } else if (level == 1) {
                        MoveUtil.strafe(0.405F);
                    } else {
                        MoveUtil.strafe(0.33F);
                    }
                    player.motionY = 0.31;
                    break;
                }
                case 2:
                    if (speedPotion) {
                        player.motionX *= 1.005;
                        player.motionZ *= 1.005;
                    }
                    player.motionY -= 0.11;
                    break;
                case 3:
                    player.motionY -= 0.22;
                    break;
                case 4:
                    if (speedPotion) {
                        if (this.tickCounter > 66) {
                            player.motionX *= 1.01;
                            player.motionZ *= 1.01;
                        }
                    } else {
                        player.motionX *= 1.005;
                        player.motionZ *= 1.005;
                    }
                    break;
                case 5:
                    if (SpeedUtil.hasBlockAtOffset(player.motionY * 2.0)) {
                        player.motionY += 0.085;
                        if (speedPotion) {
                            if (this.tickCounter > 66) {
                                MoveUtil.strafe((float) (SpeedUtil.getAllowedHorizontalDistance() * 1.069));
                            }
                        } else {
                            MoveUtil.strafe();
                        }
                    }
                    break;
                default:
                    break;
            }
            if (SpeedUtil.hasBlockAtOffset(player.motionY)) {
                if (speedPotion) {
                    if (this.tickCounter > 66) {
                        MoveUtil.strafe((float) (SpeedUtil.getAllowedHorizontalDistance() * 1.059));
                    }
                } else {
                    MoveUtil.strafe();
                }
            }
            player.stepHeight = 0.2F;
            if (SpeedUtil.speed() < 0.125F) {
                MoveUtil.strafe(0.125F);
            }
            if (SpeedUtil.angleDiff(SpeedUtil.moveAngle(), SpeedUtil.motionAngle()) > 40.0 && speedPotion) {
                player.motionX *= 0.74;
                player.motionZ *= 0.74;
            }
        }
        if (this.tickCounter < 8 || this.onStairs || player.ticksExisted < 6) {
            if (player.onGround) {
                player.jump();
            }
            if (player.onGround && !speedPotion) {
                MoveUtil.strafe((float) (SpeedUtil.getAllowedHorizontalDistance() - 0.01));
            } else if (player.onGround) {
                MoveUtil.strafe(0.26F);
            }
            if (SpeedUtil.speed() < 0.125F) {
                MoveUtil.strafe(0.125F);
            }
            if (airTicks == 1) {
                MoveUtil.strafe();
                player.motionY += 0.057F;
            }
            if (airTicks == 3) {
                player.motionY -= 0.1309F;
            }
            if (airTicks == 4) {
                player.motionY -= 0.2;
            }
        }
        if (this.tickCounter == 24) {
            MoveUtil.strafe(0.125F);
        }
    }

    @Override
    public void onMotion() {
        this.tickCounter++;
        EntityPlayerSP player = mc.thePlayer;
        if (this.tickCounter < 23) {
            if (player.onGround && this.tickCounter > 3) {
                player.motionY = this.tickCounter % 2 != 0 ? 0.296875 : 0.001;
            }
        } else if (player.onGround) {
            // small offset
            player.motionY = 0.001;
        }
    }

    @Override
    public void onJump(JumpEvent event) {
        if (this.tickCounter >= 23 && !this.onStairs && mc.thePlayer.ticksExisted >= 6) {
            event.setMotion(0.4F);
        } else {
            event.setMotion(0.42F);
        }
    }

    @Override
    public void onEnable() {
        EntityPlayerSP player = mc.thePlayer;
        this.tickCounter = 0;
        this.startPosX = player.posX;
        this.startPosY = player.posY;
        this.startPosZ = player.posZ;
        if (player.onGround) {
            player.jump();
        }
    }

    @Override
    public void onDisable() {
        EntityPlayerSP player = mc.thePlayer;
        if (player.onGround) {
            MoveUtil.stopXZ();
        }
        player.stepHeight = 0.6F;
        this.tickCounter = 0;
    }
}
