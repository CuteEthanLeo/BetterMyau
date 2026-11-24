package myau.module.modules.nowebmodes.other;

import myau.events.UpdateEvent;
import myau.module.modules.nowebmodes.NoWebMode;

public class None extends NoWebMode {
    public None() {
        super("None");
    }

    @Override
    public void onUpdate(UpdateEvent event) {
        clearWeb();
    }
}
