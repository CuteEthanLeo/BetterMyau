package myau.module.modules;

import myau.Myau;
import myau.event.EventTarget;
import myau.event.types.EventType;
import myau.events.LoadWorldEvent;
import myau.events.PacketEvent;
import myau.events.UpdateEvent;
import myau.module.Module;
import myau.property.properties.BooleanProperty;
import myau.property.properties.ModeProperty;
import myau.util.ChatUtil;
import net.minecraft.network.play.server.S08PacketPlayerPosLook;

/**
 * Turns the chosen modules off when the server teleports the player back (flag),
 * when the player dies or when the world changes.
 */
public class AutoDisable extends Module {
    public final BooleanProperty onFlag = new BooleanProperty("on-flag", true);
    public final BooleanProperty onWorldChange = new BooleanProperty("on-world-change", false);
    public final BooleanProperty onDeath = new BooleanProperty("on-death", false);
    public final ModeProperty warn = new ModeProperty("warn", 0, new String[]{"CHAT", "NOTIFICATION"});
    public final BooleanProperty killAura = new BooleanProperty("kill-aura", true);
    public final BooleanProperty scaffold = new BooleanProperty("scaffold", true);
    public final BooleanProperty fly = new BooleanProperty("fly", true);
    public final BooleanProperty speed = new BooleanProperty("speed", true);
    public final BooleanProperty longJump = new BooleanProperty("long-jump", false);

    public AutoDisable() {
        super("AutoDisable", false);
    }

    @EventTarget
    public void onPacket(PacketEvent event) {
        if (!this.isEnabled() || event.getType() != EventType.RECEIVE) return;
        if (mc.thePlayer == null) return;
        // The teleports right after joining / changing world are not flags.
        if (event.getPacket() instanceof S08PacketPlayerPosLook && this.onFlag.getValue() && mc.thePlayer.ticksExisted > 20) {
            // Network thread: modules are switched on the client thread.
            mc.addScheduledTask(() -> this.disableModules("flagged"));
        }
    }

    @EventTarget
    public void onUpdate(UpdateEvent event) {
        if (!this.isEnabled() || event.getType() != EventType.PRE) return;
        if (mc.thePlayer == null) return;
        if (this.onDeath.getValue() && (mc.thePlayer.isDead || mc.thePlayer.getHealth() <= 0.0F)) {
            this.disableModules("deaths");
        }
    }

    @EventTarget
    public void onLoadWorld(LoadWorldEvent event) {
        if (!this.isEnabled()) return;
        if (this.onWorldChange.getValue()) {
            this.disableModules("world changed");
        }
    }

    private boolean disable(Class<? extends Module> type, BooleanProperty selected) {
        if (!selected.getValue() || Myau.moduleManager == null) return false;
        Module module = Myau.moduleManager.modules.get(type);
        if (module == null || !module.isEnabled()) return false;
        module.setEnabled(false);
        return true;
    }

    private void disableModules(String reason) {
        if (!this.isEnabled()) return;
        boolean any = this.disable(KillAura.class, this.killAura);
        any |= this.disable(Scaffold.class, this.scaffold);
        any |= this.disable(Fly.class, this.fly);
        any |= this.disable(Speed.class, this.speed);
        any |= this.disable(LongJump.class, this.longJump);
        if (!any) return;
        if (this.warn.getValue() == 0) {
            ChatUtil.sendFormatted(Myau.clientName + "&eModules have been disabled due to &c" + reason);
        } else {
            HUD.notify("AutoDisable", "Modules have been disabled due to " + reason, false);
        }
    }
}
