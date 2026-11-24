package myau.module.modules;

import myau.event.EventTarget;
import myau.event.types.EventType;
import myau.events.JumpEvent;
import myau.events.MoveEvent;
import myau.events.UpdateEvent;
import myau.module.Module;
import myau.property.properties.BooleanProperty;
import myau.property.properties.FloatProperty;
import myau.property.properties.ModeProperty;
import myau.util.MoveUtil;
import net.minecraft.block.BlockPane;
import net.minecraft.client.Minecraft;
import net.minecraft.client.entity.EntityPlayerSP;
import net.minecraft.util.BlockPos;

public class HighJump extends Module {
    private static final Minecraft mc = Minecraft.getMinecraft();
    private static final int VANILLA = 0, DAMAGE = 1, AACV3 = 2, DAC = 3, MINEPLEX = 4;

    public final ModeProperty mode = new ModeProperty("mode", VANILLA, new String[]{"Vanilla", "Damage", "AACv3", "DAC", "Mineplex"});
    public final FloatProperty height = new FloatProperty(
            "height", 2.0F, 1.1F, 5.0F, () -> this.mode.getValue() == VANILLA || this.mode.getValue() == DAMAGE
    );
    public final BooleanProperty glass = new BooleanProperty("only-glass-pane", false);

    public HighJump() {
        super("HighJump", false);
    }

    /** With only-glass-pane the module works only while the player stands inside a pane block. */
    private boolean isBlockedByGlassCheck(EntityPlayerSP player) {
        return this.glass.getValue() && !(mc.theWorld.getBlockState(new BlockPos(player)).getBlock() instanceof BlockPane);
    }

    @EventTarget
    public void onUpdate(UpdateEvent event) {
        if (!this.isEnabled() || event.getType() != EventType.PRE) {
            return;
        }
        EntityPlayerSP player = mc.thePlayer;
        if (player == null || mc.theWorld == null || this.isBlockedByGlassCheck(player)) {
            return;
        }
        switch (this.mode.getValue()) {
            case DAMAGE:
                if (player.hurtTime > 0 && player.onGround) {
                    player.motionY += 0.42F * this.height.getValue();
                }
                break;
            case AACV3:
                if (!player.onGround) {
                    player.motionY += 0.059;
                }
                break;
            case DAC:
                if (!player.onGround) {
                    player.motionY += 0.049999;
                }
                break;
            case MINEPLEX:
                if (!player.onGround) {
                    MoveUtil.strafe(0.35F);
                }
                break;
            default:
                break;
        }
    }

    @EventTarget
    public void onMove(MoveEvent event) {
        if (!this.isEnabled()) {
            return;
        }
        EntityPlayerSP player = mc.thePlayer;
        if (player == null || mc.theWorld == null || this.isBlockedByGlassCheck(player)) {
            return;
        }
        if (!player.onGround && this.mode.getValue() == MINEPLEX) {
            player.motionY += player.fallDistance == 0.0F ? 0.0499 : 0.05;
        }
    }

    @EventTarget
    public void onJump(JumpEvent event) {
        if (!this.isEnabled() || event.getType() != EventType.PRE) {
            return;
        }
        EntityPlayerSP player = mc.thePlayer;
        if (player == null || mc.theWorld == null || this.isBlockedByGlassCheck(player)) {
            return;
        }
        switch (this.mode.getValue()) {
            case VANILLA:
                event.setMotion(event.getMotion() * this.height.getValue());
                break;
            case MINEPLEX:
                event.setMotion(0.47F);
                break;
            default:
                break;
        }
    }

    @Override
    public String[] getSuffix() {
        return new String[]{this.mode.getModeString()};
    }
}
