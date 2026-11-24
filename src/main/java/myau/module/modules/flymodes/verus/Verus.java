package myau.module.modules.flymodes.verus;

import myau.events.JumpEvent;
import myau.events.PacketEvent;
import myau.events.UpdateEvent;
import myau.mixin.IAccessorC03PacketPlayer;
import myau.module.modules.flymodes.FlyMode;
import myau.module.modules.flymodes.FlyUtil;
import myau.util.MoveUtil;
import myau.util.PacketUtil;
import net.minecraft.network.play.client.C03PacketPlayer;

/**
 * Modified code ported from the VerusDamage script by Arcane.
 *
 * - Getting below a block (like the NCPLatest fly method) should help to temporarily bypass Speed(A) checks
 * - Turning off Damage should bypass Fly(G) checks
 */
public class Verus extends FlyMode {
    private int boostTicks = 0;

    public Verus() {
        super("Verus");
    }

    @Override
    public void onEnable() {
        this.boostTicks = 0;
        if (mc.theWorld.getCollidingBoundingBoxes(
                mc.thePlayer,
                mc.thePlayer.getEntityBoundingBox().offset(0.0, 3.0001, 0.0).expand(0.0, 0.0, 0.0)
        ).isEmpty()) {
            if (this.parent.verusDamage.getValue()) {
                FlyUtil.sendPosition(mc.thePlayer.posX, mc.thePlayer.posY + 3.0001, mc.thePlayer.posZ, false);
            }

            PacketUtil.sendPacket(new C03PacketPlayer.C06PacketPlayerPosLook(
                    mc.thePlayer.posX,
                    mc.thePlayer.posY,
                    mc.thePlayer.posZ,
                    mc.thePlayer.rotationYaw,
                    mc.thePlayer.rotationPitch,
                    false
            ));
            PacketUtil.sendPacket(new C03PacketPlayer.C06PacketPlayerPosLook(
                    mc.thePlayer.posX,
                    mc.thePlayer.posY,
                    mc.thePlayer.posZ,
                    mc.thePlayer.rotationYaw,
                    mc.thePlayer.rotationPitch,
                    true
            ));
        }
        mc.thePlayer.setPosition(mc.thePlayer.posX, mc.thePlayer.posY + this.parent.verusYBoost.getValue().doubleValue(), mc.thePlayer.posZ);
    }

    @Override
    public void onDisable() {
        if (this.boostTicks > 0) {
            if (mc.thePlayer != null) {
                MoveUtil.stopXZ();
            }
            MoveUtil.setTimer(1.0F);
        }
    }

    @Override
    public void onUpdate(UpdateEvent event) {
        MoveUtil.stopXZ();
        MoveUtil.stop();

        if (this.boostTicks == 0 && mc.thePlayer.hurtTime > 0) {
            this.boostTicks = this.parent.verusBoostTicks.getValue();
        }

        this.boostTicks--;

        if (this.parent.verusTimerSlow.getValue()) {
            if (mc.thePlayer.ticksExisted % 3 == 0) {
                MoveUtil.setTimer(0.15F);
            } else {
                MoveUtil.setTimer(0.08F);
            }
        }

        FlyUtil.strafe(this.parent.verusBoostMotion.getValue(), true);
    }

    @Override
    public void onPacket(PacketEvent event) {
        if (event.getPacket() instanceof C03PacketPlayer) {
            ((IAccessorC03PacketPlayer) event.getPacket()).setOnGround(true);
        }
    }

    @Override
    public void onJump(JumpEvent event) {
        event.setCancelled(true);
    }
}
