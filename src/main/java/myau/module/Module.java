package myau.module;

import myau.Myau;
import myau.module.modules.HUD;
import myau.util.KeyBindUtil;
import net.minecraft.client.Minecraft;

public abstract class Module {
    private static final int MAX_PENDING_ATTEMPTS = 3;

    protected static final Minecraft mc = Minecraft.getMinecraft();
    protected final String name;
    protected final boolean defaultEnabled;
    protected final int defaultKey;
    protected final boolean defaultHidden;
    protected boolean enabled;
    protected int key;
    protected boolean hidden;

    /**
     * Set when the module was enabled from the startup config before a world existed.
     * {@link #activateIfPending()} runs {@link #onEnabled()} once the player is in a world.
     */
    private boolean pendingWorldEnable = false;
    private int pendingAttempts = 0;

    public Module(String name, boolean enabled) {
        this(name, enabled, false);
    }

    public Module(String name, boolean enabled, boolean hidden) {
        this.name = name;
        this.enabled = this.defaultEnabled = enabled;
        this.key = this.defaultKey = 0;
        this.hidden = this.defaultHidden = hidden;
    }

    public String getName() {
        return this.name;
    }

    public String formatModule() {
        return String.format(
                "%s%s &r(%s&r)",
                this.key == 0 ? "" : String.format("&l[%s] &r", KeyBindUtil.getKeyName(this.key)),
                this.name,
                this.enabled ? "&a&lON" : "&c&lOFF"
        );
    }

    public String[] getSuffix() {
        return new String[0];
    }

    public boolean isEnabled() {
        return this.enabled;
    }

    public void setEnabled(boolean enabled) {
        if (this.enabled == enabled) {
            return;
        }
        this.enabled = enabled;
        if (!enabled) {
            this.pendingWorldEnable = false;
            this.pendingAttempts = 0;
        }
        try {
            if (enabled) {
                this.onEnabled();
            } else {
                this.onDisabled();
            }
        } catch (Exception e) {
            Myau.LOGGER.error("Module {} threw while {}", this.name, enabled ? "enabling" : "disabling", e);
        }
        // The click GUI module only "toggles" to open the screen; that is not a setting change.
        if (!(this instanceof myau.module.modules.GuiModule)) myau.config.ConfigManager.markDirty();
        this.notifyToggle(enabled);
    }

    private void notifyToggle(boolean enabled) {
        if (Myau.moduleManager == null || this.name.equalsIgnoreCase("KeepSprint")) {
            return;
        }
        try {
            HUD hud = (HUD) Myau.moduleManager.modules.get(HUD.class);
            if (hud != null && hud.isEnabled() && hud.toggleAlerts.getValue()) {
                HUD.showNotification(this.name, enabled);
            }
        } catch (Exception e) {
            Myau.LOGGER.warn("Failed to show toggle notification for {}", this.name, e);
        }
    }

    public boolean toggle() {
        boolean enabled = !this.enabled;
        this.setEnabled(enabled);
        if (this.enabled != enabled) {
            return false;
        }
        if (!this.name.equalsIgnoreCase("KeepSprint") && Myau.moduleManager != null) {
            // Played directly (not on the next world tick) so it is also heard in menus.
            myau.ui.glass.UiSound.toggle(enabled);
        }
        return true;
    }

    public int getKey() {
        return this.key;
    }

    public void setKey(int integer) {
        if (this.key != integer) myau.config.ConfigManager.markDirty();
        this.key = integer;
    }

    public boolean isHidden() {
        return this.hidden;
    }

    public void setHidden(boolean boolean1) {
        if (this.hidden != boolean1) myau.config.ConfigManager.markDirty();
        this.hidden = boolean1;
    }

    public void onEnabled() {
    }

    public void onDisabled() {
    }

    public void verifyValue(String string) {
    }

    /**
     * Applies the enabled flag from the startup config. {@link #onEnabled()} runs
     * immediately when a world is already loaded; otherwise it is deferred until the
     * first client tick inside a world (see {@link #activateIfPending()}).
     */
    public void setEnabledDeferred(boolean enabled) {
        this.enabled = enabled;
        this.pendingWorldEnable = false;
        this.pendingAttempts = 0;
        if (!enabled) {
            return;
        }
        if (mc.thePlayer != null && mc.theWorld != null) {
            try {
                this.onEnabled();
            } catch (Exception e) {
                Myau.LOGGER.error("Module {} threw while enabling from config", this.name, e);
            }
        } else {
            this.pendingWorldEnable = true;
            this.onStartupConfigApplied();
        }
    }

    /**
     * Subclasses may override this to initialise state that does not need a world
     * (key bindings, colours, timers) when the startup config is applied.
     */
    protected void onStartupConfigApplied() {
    }

    public boolean hasPendingWorldEnable() {
        return this.pendingWorldEnable;
    }

    /**
     * Called every client tick while a world and player exist. The pending flag is only
     * cleared after {@link #onEnabled()} completed; a failing activation is retried a few
     * times and then given up with an error in the log.
     */
    public void activateIfPending() {
        if (!this.pendingWorldEnable) {
            return;
        }
        if (!this.enabled) {
            this.pendingWorldEnable = false;
            this.pendingAttempts = 0;
            return;
        }
        if (mc.thePlayer == null || mc.theWorld == null) {
            return;
        }
        try {
            this.onEnabled();
            this.pendingWorldEnable = false;
            this.pendingAttempts = 0;
        } catch (Exception e) {
            this.pendingAttempts++;
            if (this.pendingAttempts >= MAX_PENDING_ATTEMPTS) {
                this.pendingWorldEnable = false;
                this.enabled = false;
                Myau.LOGGER.error("Module {} failed to activate {} times; it has been disabled", this.name, this.pendingAttempts, e);
            } else {
                Myau.LOGGER.warn("Module {} failed to activate (attempt {}), retrying next tick", this.name, this.pendingAttempts, e);
            }
        }
    }
}
