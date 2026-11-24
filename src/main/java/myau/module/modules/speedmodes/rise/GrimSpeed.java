package myau.module.modules.speedmodes.rise;

import myau.events.StrafeEvent;
import myau.events.UpdateEvent;
import myau.module.modules.speedmodes.SpeedMode;
import myau.module.modules.speedmodes.SpeedUtil;
import net.minecraft.client.entity.EntityPlayerSP;
import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityLivingBase;
import net.minecraft.util.AxisAlignedBB;

public class GrimSpeed extends SpeedMode {
    private int airTicks = 0;
    private int groundSpoof = 0;

    public GrimSpeed() {
        super("GrimSpeed");
    }

    @Override
    public void onUpdate(UpdateEvent event) {
        if (!mc.thePlayer.onGround) {
            this.airTicks++;
        } else {
            this.airTicks = 0;
        }
    }

    @Override
    public void onMotion() {
        if (parent.grimFastFall.getValue()) {
            if (this.groundSpoof == 0) {
                this.groundSpoof = mc.thePlayer.onGround ? 1 : 0;
            } else {
                this.groundSpoof = 0;
            }
            if (mc.thePlayer.onGround) {
                mc.thePlayer.jump();
            }
        }
    }

    @Override
    public void onStrafe(StrafeEvent event) {
        EntityPlayerSP player = mc.thePlayer;
        double size = (double) parent.grimBoundingBoxSize.getValue();
        AxisAlignedBB expandedBox = player.getEntityBoundingBox().expand(size, size, size);
        for (Entity entity : mc.theWorld.loadedEntityList) {
            if (entity instanceof EntityLivingBase && entity != player
                    && expandedBox.intersectsWith(entity.getEntityBoundingBox())) {
                SpeedUtil.moveFlying((double) parent.grimInPlayerSpeed.getValue());
            }
        }
        SpeedUtil.moveFlying((double) parent.grimMoveFlyingIncrease.getValue());
        if (player.hurtTime == 0 && player.onGround && mc.gameSettings.keyBindJump.isKeyDown()) {
            player.jump();
        }
    }

    @Override
    public void onDisable() {
        this.airTicks = 0;
        this.groundSpoof = 0;
    }

    @Override
    public void onEnable() {
        this.airTicks = 0;
        this.groundSpoof = 0;
    }
}
