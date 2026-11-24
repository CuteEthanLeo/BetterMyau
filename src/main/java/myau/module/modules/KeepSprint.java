package myau.module.modules;

import myau.event.EventTarget;
import myau.event.types.EventType;
import myau.events.UpdateEvent;
import myau.module.Module;
import myau.property.properties.BooleanProperty;
import myau.property.properties.PercentProperty;
import myau.util.RotationUtil;
import net.minecraft.entity.Entity;
import net.minecraft.client.Minecraft;

public class KeepSprint extends Module {
    private static final Minecraft mc = Minecraft.getMinecraft();
    /** LeaderClient: only keep sprint for hits made between POST and the next PRE (mouse clicks), not KillAura's PRE hits. */
    public final BooleanProperty prediction = new BooleanProperty("prediction", false);
    public final PercentProperty slowdown = new PercentProperty("slowdown", 0);
    private boolean outsidePlayerTick = false;
    public final BooleanProperty groundOnly = new BooleanProperty("ground-only", false);
    public final BooleanProperty reachOnly = new BooleanProperty("reach-only", false);

    public KeepSprint() {
        super("KeepSprint", false);
    }

    @EventTarget
    public void onUpdate(UpdateEvent event) {
        if (this.isEnabled()) {
            this.outsidePlayerTick = event.getType() == EventType.POST;
        }
    }

    /** Vanilla mouse attack (MixinEntityPlayer): reach measured to the crosshair hit. */
    public boolean shouldKeepSprint() {
        return this.canKeepSprint(this.reachOnly.getValue()
                ? mc.objectMouseOver.hitVec.distanceTo(mc.getRenderViewEntity().getPositionEyes(1.0F)) : 0.0);
    }

    /** Client-side attacks (PlayerUtil.attackEntity, used by KillAura): reach measured to the target. */
    public boolean shouldKeepSprint(Entity target) {
        return this.canKeepSprint(this.reachOnly.getValue() ? RotationUtil.distanceToEntity(target) : 0.0);
    }

    /** One rule for every attack path; prediction keeps sprint only for hits between POST and the next PRE. */
    private boolean canKeepSprint(double reach) {
        if (this.prediction.getValue() && !this.outsidePlayerTick) {
            return false;
        } else if (this.groundOnly.getValue() && !mc.thePlayer.onGround) {
            return false;
        } else {
            return !this.reachOnly.getValue() || reach > 3.0;
        }
    }
}
