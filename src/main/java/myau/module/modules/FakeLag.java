package myau.module.modules;

import myau.events.Render2DEvent;
import myau.event.EventTarget;
import myau.event.types.EventType;
import myau.events.PacketEvent;
import myau.events.TickEvent;
import myau.module.Module;
import myau.property.properties.*;
import myau.util.PacketUtil;
import net.minecraft.client.Minecraft;
import net.minecraft.network.Packet;
import net.minecraft.network.handshake.client.C00Handshake;
import net.minecraft.network.login.client.C00PacketLoginStart;
import net.minecraft.network.login.client.C01PacketEncryptionResponse;
import net.minecraft.network.play.client.C00PacketKeepAlive;
import net.minecraft.network.play.client.C01PacketChatMessage;
import net.minecraft.network.status.client.C00PacketServerQuery;
import net.minecraft.network.status.client.C01PacketPing;

import java.util.Deque;
import java.util.concurrent.ConcurrentLinkedDeque;

public class FakeLag extends Module {
    private static final Minecraft mc = Minecraft.getMinecraft();

    public final IntProperty delay = new IntProperty("delay", 300, 50, 1000);
    public final ModeProperty mode = new ModeProperty("mode", 0, new String[]{"ALWAYS", "HURT"});

    private final Deque<Packet<?>> packetBuffer = new ConcurrentLinkedDeque<>();
    private long lastFlushTime = 0;
    private boolean shouldBuffer = false;

    public FakeLag() {
        super("FakeLag", false);
    }

    private boolean isCriticalPacket(Packet<?> packet) {
        return packet instanceof C00Handshake
                || packet instanceof C00PacketLoginStart
                || packet instanceof C00PacketServerQuery
                || packet instanceof C01PacketPing
                || packet instanceof C01PacketEncryptionResponse
                || packet instanceof C00PacketKeepAlive
                || packet instanceof C01PacketChatMessage;
    }

    @EventTarget
    public void onPacket(PacketEvent event) {
        if (!this.isEnabled() || mc.thePlayer == null) return;
        if (event.getType() != EventType.SEND) return; // 只拦截发送包
        if (isCriticalPacket(event.getPacket())) return;

        if (mode.getValue() == 0) {
            // ALWAYS: 持续缓冲
            packetBuffer.offer(event.getPacket());
            event.setCancelled(true);
        } else if (mode.getValue() == 1 && mc.thePlayer.hurtTime > 0) {
            // HURT: 仅在受伤期间缓冲
            shouldBuffer = true;
            packetBuffer.offer(event.getPacket());
            event.setCancelled(true);
        }
    }

    @EventTarget
    public void onTick(TickEvent event) {
        if (!this.isEnabled() || mc.thePlayer == null) return;

        if (event.getType() == EventType.POST) {
            long now = System.currentTimeMillis();

            if (mode.getValue() == 0) {
                // ALWAYS: 按延迟间隔释放
                if (now - lastFlushTime >= delay.getValue()) {
                    flush();
                    lastFlushTime = now;
                }
            } else if (mode.getValue() == 1) {
                // HURT: 受伤结束后释放
                if (shouldBuffer && mc.thePlayer.hurtTime <= 0) {
                    flush();
                    shouldBuffer = false;
                    lastFlushTime = now;
                }
            }
        }
    }

    private void flush() {
        while (!packetBuffer.isEmpty()) {
            Packet<?> packet = packetBuffer.poll();
            if (packet != null) {
                PacketUtil.sendPacketNoEvent(packet);
            }
        }
    }

    @Override
    public void onEnabled() {
        packetBuffer.clear();
        lastFlushTime = System.currentTimeMillis();
        shouldBuffer = false;
    }

    @Override
    public void onDisabled() {
        flush();
        shouldBuffer = false;
    }

    @Override
    public String[] getSuffix() {
        return new String[]{delay.getValue() + "ms"};
    }

    // ---------------------------------------------------------------- dynamic island


    /** While packets are held: how many, and (ALWAYS) how long until they are flushed. */
    @EventTarget
    public void onIsland(Render2DEvent event) {
        if (!this.isEnabled() || mc.thePlayer == null) return;
        int held = this.packetBuffer.size();
        if (held <= 0) return;
        float progress = this.mode.getValue() == 0
                ? Math.min(1.0F, (System.currentTimeMillis() - this.lastFlushTime) / (float) Math.max(1, this.delay.getValue()))
                : -1;
        myau.ui.glass.Island.live("fakelag", myau.ui.glass.Island.P_TIMER, "FakeLag §f" + held + " §7packets", myau.ui.GlassTheme.WARN, progress);
    }
}
