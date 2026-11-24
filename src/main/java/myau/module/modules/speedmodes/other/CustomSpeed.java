package myau.module.modules.speedmodes.other;

import myau.module.modules.speedmodes.SpeedMode;
import myau.util.FallingPlayer;
import myau.util.MoveUtil;
import net.minecraft.client.entity.EntityPlayerSP;
import net.minecraft.item.Item;
import net.minecraft.item.ItemBucketMilk;
import net.minecraft.item.ItemFood;
import net.minecraft.item.ItemPotion;
import net.minecraft.item.ItemStack;

public class CustomSpeed extends SpeedMode {
    public CustomSpeed() {
        super("Custom");
    }

    @Override
    public void onMotion() {
        EntityPlayerSP player = mc.thePlayer;
        if (parent.customNotOnVoid.getValue() && new FallingPlayer(player).findCollision(500) == null
                || parent.customNotOnFalling.getValue() && player.fallDistance > 2.5F
                || parent.customNotOnConsuming.getValue() && player.isUsingItem() && isConsumable(player.getHeldItem())) {
            if (player.onGround) {
                MoveUtil.tryJump();
            }
            MoveUtil.setTimer(1.0F);
            return;
        }
        if (MoveUtil.isMoving()) {
            if (player.onGround) {
                if (parent.customGroundStrafe.getValue() > 0.0F) {
                    MoveUtil.strafe(parent.customGroundStrafe.getValue());
                }
                MoveUtil.setTimer(parent.customGroundTimer.getValue());
                player.motionY = (double) parent.customY.getValue();
            } else {
                if (parent.customAirStrafe.getValue() > 0.0F) {
                    MoveUtil.strafe(parent.customAirStrafe.getValue());
                }
                if (player.ticksExisted % parent.customAirTimerTick.getValue() == 0) {
                    MoveUtil.setTimer(parent.customAirTimer.getValue());
                } else {
                    MoveUtil.setTimer(1.0F);
                }
            }
        }
    }

    private static boolean isConsumable(ItemStack stack) {
        if (stack == null) {
            return false;
        }
        Item item = stack.getItem();
        return item instanceof ItemFood || item instanceof ItemPotion || item instanceof ItemBucketMilk;
    }

    @Override
    public void onEnable() {
        if (parent.customResetXZ.getValue()) {
            MoveUtil.stopXZ();
        }
        if (parent.customResetY.getValue()) {
            mc.thePlayer.motionY = 0.0;
        }
    }
}
