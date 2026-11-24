package myau.module.modules;

import myau.event.EventTarget;
import myau.event.types.EventType;
import myau.events.UpdateEvent;
import myau.module.Module;
import myau.property.properties.FloatProperty;
import myau.property.properties.ModeProperty;
import myau.util.PacketUtil;
import net.minecraft.client.Minecraft;
import net.minecraft.client.entity.EntityPlayerSP;
import net.minecraft.network.play.client.C02PacketUseEntity;
import net.minecraft.network.play.client.C03PacketPlayer.C04PacketPlayerPosition;

import java.util.concurrent.ThreadLocalRandom;

/** Leaves the server (one way or another) when health drops too low, then turns itself off. */
public class AutoLeave extends Module {
    private static final Minecraft mc = Minecraft.getMinecraft();
    public final FloatProperty health = new FloatProperty("health", 8.0F, 0.0F, 20.0F);
    public final ModeProperty mode = new ModeProperty("mode", 0, new String[]{"Quit", "InvalidPacket", "SelfHurt", "IllegalChat"});

    public AutoLeave() {
        super("AutoLeave", false);
    }

    @EventTarget
    public void onUpdate(UpdateEvent event) {
        if (!this.isEnabled() || event.getType() != EventType.PRE) {
            return;
        }
        EntityPlayerSP player = mc.thePlayer;
        if (player.getHealth() > this.health.getValue() || player.capabilities.isCreativeMode || mc.isIntegratedServerRunning()) {
            return;
        }
        switch (this.mode.getValue()) {
            case 0:
                mc.theWorld.sendQuittingDisconnectingPacket();
                break;
            case 1:
                PacketUtil.sendPacket(new C04PacketPlayerPosition(Double.NaN, Double.NEGATIVE_INFINITY, Double.POSITIVE_INFINITY, !player.onGround));
                break;
            case 2:
                PacketUtil.sendPacket(new C02PacketUseEntity(player, C02PacketUseEntity.Action.ATTACK));
                break;
            case 3:
                ThreadLocalRandom random = ThreadLocalRandom.current();
                player.sendChatMessage(random.nextInt(Integer.MAX_VALUE) + "§§§" + random.nextInt(Integer.MAX_VALUE));
                break;
            default:
                break;
        }
        this.setEnabled(false);
    }

    @Override
    public String[] getSuffix() {
        return new String[]{this.mode.getModeString()};
    }
}
