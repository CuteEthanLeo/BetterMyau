package myau.module.modules;

import myau.event.EventTarget;
import myau.event.types.EventType;
import myau.events.UpdateEvent;
import myau.module.Module;
import myau.module.mode.ModeList;
import myau.module.modules.nowebmodes.NoWebMode;
import myau.module.modules.nowebmodes.aac.AAC;
import myau.module.modules.nowebmodes.aac.LAAC;
import myau.module.modules.nowebmodes.intave.IntaveNew;
import myau.module.modules.nowebmodes.intave.IntaveOld;
import myau.module.modules.nowebmodes.other.None;
import myau.module.modules.nowebmodes.other.OldGrim;
import myau.module.modules.nowebmodes.other.Rewi;
import myau.property.properties.ModeProperty;
import net.minecraft.client.Minecraft;

public class NoWeb extends Module {
    private static final Minecraft mc = Minecraft.getMinecraft();
    /** Must be declared before {@link #mode}. */
    private final ModeList<NoWeb, NoWebMode> modes = new ModeList<NoWeb, NoWebMode>(
            // Vanilla
            new None(),
            // AAC
            new AAC(), new LAAC(),
            // Intave
            new IntaveOld(), new IntaveNew(),
            // Other
            new Rewi(), new OldGrim()
    );
    public final ModeProperty mode = new ModeProperty("mode", 0, this.modes.names());
    /** The mode that is currently running; null while the module is off. */
    private NoWebMode active;

    public NoWeb() {
        super("NoWeb", false);
        this.modes.bind(this);
    }

    private NoWebMode cur() {
        return this.modes.get(this.mode.getValue());
    }

    @EventTarget
    public void onUpdate(UpdateEvent event) {
        if (!this.isEnabled() || event.getType() != EventType.PRE) {
            return;
        }
        if (this.active == null || mc.thePlayer == null || mc.theWorld == null) {
            return;
        }
        this.active.onUpdate(event);
    }

    @Override
    public void onEnabled() {
        this.active = this.cur();
        this.active.onEnable();
    }

    @Override
    public void onDisabled() {
        NoWebMode previous = this.active;
        this.active = null;
        if (previous != null) {
            previous.onDisable();
        }
    }

    @Override
    public void verifyValue(String name) {
        if (this.isEnabled() && this.active != this.cur()) {
            NoWebMode previous = this.active;
            this.active = this.cur();
            if (previous != null) {
                previous.onDisable();
            }
            this.active.onEnable();
        }
    }

    @Override
    public String[] getSuffix() {
        return new String[]{this.mode.getModeString()};
    }
}
