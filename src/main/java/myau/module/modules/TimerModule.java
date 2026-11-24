package myau.module.modules;

import myau.event.EventTarget;
import myau.event.types.EventType;
import myau.events.LoadWorldEvent;
import myau.events.UpdateEvent;
import myau.module.Module;
import myau.property.properties.FloatProperty;
import myau.property.properties.ModeProperty;
import myau.util.MoveUtil;
import net.minecraft.client.Minecraft;

/** The "Timer" module (named TimerModule to stay clear of net.minecraft.util.Timer). */
public class TimerModule extends Module {
    private static final Minecraft mc = Minecraft.getMinecraft();
    private static final int ON_MOVE = 0, NO_MOVE = 1, ALWAYS = 2;

    public final ModeProperty mode = new ModeProperty("mode", ON_MOVE, new String[]{"OnMove", "NoMove", "Always"});
    public final FloatProperty speed = new FloatProperty("speed", 2.0F, 0.1F, 10.0F);

    public TimerModule() {
        super("Timer", false);
    }

    @Override
    public void onDisabled() {
        MoveUtil.setTimer(1.0F);
    }

    @EventTarget
    public void onUpdate(UpdateEvent event) {
        if (!this.isEnabled() || event.getType() != EventType.PRE) {
            return;
        }
        if (mc.thePlayer == null) {
            return;
        }
        int mode = this.mode.getValue();
        boolean moving = MoveUtil.isMoving();
        if (mode == ALWAYS || mode == ON_MOVE && moving || mode == NO_MOVE && !moving) {
            MoveUtil.setTimer(this.speed.getValue());
            return;
        }
        MoveUtil.setTimer(1.0F);
    }

    @EventTarget
    public void onLoadWorld(LoadWorldEvent event) {
        if (!this.isEnabled()) {
            return;
        }
        // Normal speed while a world is loading or after leaving it; applied again on the next player update.
        MoveUtil.setTimer(1.0F);
    }

    @Override
    public String[] getSuffix() {
        return new String[]{this.speed.getValue().toString()};
    }
}
