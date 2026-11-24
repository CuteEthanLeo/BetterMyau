package myau.module.modules;

import myau.event.EventTarget;
import myau.event.types.EventType;
import myau.events.TickEvent;
import myau.module.Module;
import myau.util.TimerUtil;
import myau.property.properties.FloatProperty;
import myau.property.properties.IntProperty;
import myau.property.properties.TextProperty;
import net.minecraft.client.Minecraft;

public class Spammer extends Module {
    private static final Minecraft mc = Minecraft.getMinecraft();
    private final TimerUtil timer = new TimerUtil();
    private int charOffset = 19968;
    public final TextProperty text = new TextProperty("text", "meow");
    public final FloatProperty delay = new FloatProperty("delay", 3.5F, 0.0F, 3600.0F);
    public final IntProperty random = new IntProperty("random", 0, 0, 10);

    public Spammer() {
        super("Spammer", false);
    }

    /**
     * Runs on the client tick rather than the render pass: chat is game logic, and the
     * tick event is only fired while a player exists.
     */
    @EventTarget
    public void onTick(TickEvent event) {
        if (event.getType() != EventType.PRE || !this.isEnabled() || mc.thePlayer == null) {
            return;
        }
        if (this.timer.hasTimeElapsed((long) (this.delay.getValue() * 1000.0F))) {
            this.timer.reset();
            String text = this.text.getValue();
            if (this.random.getValue() > 0) {
                StringBuilder builder = new StringBuilder(text).append(' ');
                for (int i = 0; i < this.random.getValue(); i++) {
                    builder.append((char) this.charOffset);
                    this.charOffset++;
                    if (this.charOffset > 40959) {
                        this.charOffset = 19968;
                    }
                }
                text = builder.toString();
            }
            mc.thePlayer.sendChatMessage(text);
        }
    }
}
