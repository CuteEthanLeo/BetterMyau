package myau.module.modules;

import myau.Myau;
import myau.event.EventTarget;
import myau.event.types.EventType;
import myau.event.types.Priority;
import myau.events.LoadWorldEvent;
import myau.events.PacketEvent;
import myau.events.Render2DEvent;
import myau.events.TickEvent;
import myau.module.Module;
import myau.property.properties.BooleanProperty;
import myau.property.properties.IntProperty;
import myau.util.ChatUtil;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.ScaledResolution;
import net.minecraft.network.Packet;
import net.minecraft.network.play.INetHandlerPlayClient;
import net.minecraft.network.play.server.S08PacketPlayerPosLook;
import net.minecraft.network.play.server.S12PacketEntityVelocity;
import net.minecraft.network.play.server.S19PacketEntityStatus;
import net.minecraft.network.play.server.S32PacketConfirmTransaction;

import java.awt.Color;
import java.util.concurrent.ConcurrentLinkedQueue;

public class Freeze extends Module {
    private static final Minecraft mc = Minecraft.getMinecraft();

    // ⚠️ 核心修复：改用高并发、绝对线程安全的链表队列，干掉 remove(0) 带来的死锁隐患
    private final ConcurrentLinkedQueue<Packet<INetHandlerPlayClient>> packets = new ConcurrentLinkedQueue<>();

    private boolean delaying = false;
    private int timeout = 0;
    private boolean isTeleportPending = false; // 替代原本容易被 Tick 误刷掉的 s08 状态标志
    private final int color = new Color(209, 1, 1, 255).getRGB();

    public final BooleanProperty renderTimer = new BooleanProperty("render-timer", true); // 默认开启渲染，方便观察
    public final IntProperty maxTimeout = new IntProperty("max-timeout", 300, 50, 600);

    public Freeze() {
        super("Freeze", false);
    }

    @EventTarget(Priority.HIGHEST)
    public void onPacket(PacketEvent event) {
        if (!this.isEnabled() || mc.thePlayer == null || mc.theWorld == null) {
            return;
        }

        // ── 【入站包拦截接收层】 ──
        if (event.getType() == EventType.RECEIVE && !event.isCancelled()) {
            Packet<?> packet = event.getPacket();

            // 1. 监测服务器发来的强制传送包（S08）
            if (packet instanceof S08PacketPlayerPosLook) {
                if (!this.delaying) {
                    // 如果当前没处于卡包状态，标记当前有传送挂起，用来过滤后续紧跟的击退包
                    this.isTeleportPending = true;
                } else {
                    // 如果正在卡包期间服务器发来 S08，必须将其扣留，否则会打破同步
                    this.packets.add((Packet<INetHandlerPlayClient>) packet);
                    event.setCancelled(true);
                    return;
                }
            }

            // 2. 监测服务器发来的击退/速度包（S12）
            if (packet instanceof S12PacketEntityVelocity) {
                S12PacketEntityVelocity s12 = (S12PacketEntityVelocity) packet;
                if (s12.getEntityID() == mc.thePlayer.getEntityId()) {
                    // 触发现代反作弊过滤：如果是由于传送引发的击退，不予理睬
                    if (this.isTeleportPending) {
                        this.isTeleportPending = false;
                    } else {
                        // 满足卡包条件，瞬间激活延迟延迟状态！
                        this.delaying = true;
                    }
                }
            }

            // 3. 监测玩家受击状态包（S19 判定 opcode 2 为受伤动画）
            if (packet instanceof S19PacketEntityStatus) {
                S19PacketEntityStatus s19 = (S19PacketEntityStatus) packet;
                if (s19.getEntity(mc.theWorld) != null &&
                        s19.getEntity(mc.theWorld).equals(mc.thePlayer) &&
                        s19.getOpCode() == 2) {
                    this.delaying = true;
                }
            }

            // 4. 【核心扣留逻辑】：一旦进入延迟状态，扣留所有会对位置和发包频率产生影响的敏感入站包
            if (this.delaying) {
                if (packet instanceof S12PacketEntityVelocity
                        || packet instanceof S32PacketConfirmTransaction
                        || packet instanceof S08PacketPlayerPosLook) {

                    this.packets.add((Packet<INetHandlerPlayClient>) packet);
                    event.setCancelled(true); // 拦截该包，不让游戏本体现在处理
                }
            }
        }
    }

    @EventTarget
    public void onTick(TickEvent event) {
        if (!this.isEnabled()) return;

        // 统一在 PRE 物理更新层做计数超时判定，避开不确定的 POST 状态污染
        if (event.getType() == EventType.PRE) {
            if (this.delaying) {
                this.timeout++;
                if (this.timeout >= this.maxTimeout.getValue()) {
                    this.flush();
                    ChatUtil.sendFormatted(Myau.clientName + " &cFreeze timed out.");
                }
            }
        }
    }

    @EventTarget
    public void onRender2D(Render2DEvent event) {
        if (!this.isEnabled() || !this.renderTimer.getValue() || mc.thePlayer == null) return;
        if (this.timeout == 0 || mc.currentScreen != null) return;

        this.renderTimer(this.timeout);
    }

    @EventTarget
    public void onLoadWorld(LoadWorldEvent event) {
        // 切换世界时必须无条件清空，防止把上一个图的包带到新图导致瞬间暴毙或闪退
        this.flush();
    }

    private void renderTimer(int ticks) {
        String text = "Frozen §f" + ticks + "t §7" + String.format("%.1fs", ticks / 20.0f);
        // 交给灵动岛显示（或按 HUD 的 indicators 设置堆叠在准星下方）
        myau.ui.glass.Island.live("freeze", myau.ui.glass.Island.P_TIMER, text, 0xff000000 | this.color, -1);
    }

    /**
     * 🚀 释放并刷新被扣留的全部数据包
     */
    private void flush() {
        this.delaying = false;
        this.isTeleportPending = false;
        this.timeout = 0;

        if (this.packets.isEmpty()) return;

        // 利用队列的 poll() 特性，从头到尾高精度依次释放并恢复执行
        while (!this.packets.isEmpty()) {
            Packet<INetHandlerPlayClient> packet = this.packets.poll();
            if (packet != null && mc.getNetHandler() != null) {
                try {
                    // 让网络处理器安全消化这些迟到的包
                    packet.processPacket(mc.getNetHandler());
                } catch (Exception e) {
                    e.printStackTrace();
                }
            }
        }
    }

    @Override
    public void onEnabled() {
        this.packets.clear();
        this.delaying = false;
        this.timeout = 0;
        this.isTeleportPending = false;
    }

    @Override
    public void onDisabled() {
        this.flush();
    }

    @Override
    public String[] getSuffix() {
        if (this.delaying && this.timeout > 0) {
            return new String[]{this.timeout + "t"};
        }
        return new String[]{"Idle"};
    }
}