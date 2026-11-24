package myau.module.modules.flymodes.other;

import myau.events.UpdateEvent;
import myau.module.modules.flymodes.FlyMode;
import net.minecraft.util.EnumParticleTypes;

public class Jetpack extends FlyMode {
    public Jetpack() {
        super("Jetpack");
    }

    @Override
    public void onUpdate(UpdateEvent event) {
        if (!mc.gameSettings.keyBindJump.isKeyDown()) {
            return;
        }

        // Let's bring back the particles, this mode is useless anyway
        mc.effectRenderer.spawnEffectParticle(
                EnumParticleTypes.FLAME.getParticleID(),
                mc.thePlayer.posX,
                mc.thePlayer.posY + 0.2,
                mc.thePlayer.posZ,
                -mc.thePlayer.motionX,
                -0.5,
                -mc.thePlayer.motionZ
        );

        mc.thePlayer.motionY += 0.15;

        mc.thePlayer.motionX *= 1.1;
        mc.thePlayer.motionZ *= 1.1;
    }
}
