package myau.module.modules.flymodes.vanilla;

import myau.events.UpdateEvent;
import myau.module.modules.flymodes.FlyMode;

public class SmoothVanilla extends FlyMode {
    public SmoothVanilla() {
        super("SmoothVanilla");
    }

    @Override
    public void onUpdate(UpdateEvent event) {
        mc.thePlayer.capabilities.isFlying = true;
        this.parent.handleVanillaKickBypass();
    }
}
