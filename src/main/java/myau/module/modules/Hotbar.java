package myau.module.modules;

import myau.Myau;
import myau.event.EventTarget;
import myau.events.Render2DEvent;
import myau.events.SwapItemEvent;
import myau.module.Module;
import myau.property.properties.BooleanProperty;
import myau.property.properties.IntProperty;
import myau.ui.glass.Dock;
import myau.ui.glass.Draw;
import myau.ui.glass.GlState;
import net.minecraft.entity.player.EntityPlayer;

/**
 * The hotbar as a floating glass dock with a status capsule above it (see {@link Dock}).
 * {@code MixinGuiIngameForge} skips the vanilla hotbar, hearts, armour, hunger, air and
 * experience bar while this is on; spectators keep the vanilla spectator menu.
 */
public class Hotbar extends Module {
    // Health, armour, hunger, air and experience in one capsule above the dock
    public final BooleanProperty statusBars = new BooleanProperty("status-bars", true);
    public final BooleanProperty healthNumbers = new BooleanProperty("health-numbers", true, () -> this.statusBars.getValue());
    // The held item's name, shown briefly when it changes
    public final BooleanProperty itemName = new BooleanProperty("item-name", true);
    // The selected item lifts and grows a little
    public final BooleanProperty magnify = new BooleanProperty("magnify", true);
    public final IntProperty scale = new IntProperty("scale", 100, 80, 130);
    public final IntProperty offsetY = new IntProperty("offset-y", 4, 0, 20);

    public Hotbar() {
        super("Hotbar", true, true);
    }

    private static Hotbar instance() {
        return Myau.moduleManager == null ? null : (Hotbar) Myau.moduleManager.modules.get(Hotbar.class);
    }

    private boolean active() {
        return this.isEnabled() && mc.thePlayer != null && mc.playerController != null
                && !mc.playerController.isSpectator() && mc.getRenderViewEntity() instanceof EntityPlayer;
    }

    /** True while the dock is drawn instead of the vanilla hotbar. */
    public static boolean replacesHotbar() {
        Hotbar hotbar = instance();
        return hotbar != null && hotbar.active();
    }

    /** True while the status capsule is drawn instead of the vanilla hearts, hunger and so on. */
    public static boolean replacesStatus() {
        Hotbar hotbar = instance();
        return hotbar != null && hotbar.active() && hotbar.statusBars.getValue();
    }

    /** Height other HUD elements should keep clear at the bottom of the screen. */
    public static int reservedHeight() {
        Hotbar hotbar = instance();
        return hotbar == null ? 39 : Dock.reservedHeight(hotbar);
    }

    @EventTarget
    public void onSwap(SwapItemEvent event) {
        if (this.isEnabled()) Dock.userSwap();
    }

    @EventTarget
    public void onRender2D(Render2DEvent event) {
        if (!this.active()) return;
        GlState.hudDefaults();
        Draw.useGuiScale();
        Dock.render(this, (EntityPlayer) mc.getRenderViewEntity(), event.getPartialTicks());
        GlState.hudDefaults();
    }
}
