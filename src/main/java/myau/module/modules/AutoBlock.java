package myau.module.modules;

import myau.Myau;
import myau.event.EventTarget;
import myau.event.types.EventType;
import myau.events.LoadWorldEvent;
import myau.events.PacketEvent;
import myau.events.UpdateEvent;
import myau.module.Module;
import myau.property.properties.BooleanProperty;
import myau.property.properties.FloatProperty;
import myau.property.properties.IntProperty;
import myau.property.properties.ModeProperty;
import myau.util.ChatUtil;
import myau.util.KeyBindUtil;
import myau.util.PacketUtil;
import net.minecraft.client.Minecraft;
import net.minecraft.client.entity.EntityPlayerSP;
import net.minecraft.item.ItemStack;
import net.minecraft.item.ItemSword;
import net.minecraft.network.Packet;
import net.minecraft.network.play.client.C02PacketUseEntity;
import net.minecraft.network.play.client.C07PacketPlayerDigging;
import net.minecraft.network.play.client.C08PacketPlayerBlockPlacement;
import net.minecraft.network.play.client.C0APacketAnimation;
import net.minecraft.network.play.server.S12PacketEntityVelocity;
import net.minecraft.util.BlockPos;
import net.minecraft.util.EnumFacing;
import net.minecraft.util.Vec3;

import java.util.ArrayList;
import java.util.List;

/** Blocks for a few ticks a fixed / predicted time after taking a hit. Stands down while KillAura auto-blocks. */
public class AutoBlock extends Module {
    private static final Minecraft mc = Minecraft.getMinecraft();
    public final ModeProperty mode = new ModeProperty("mode", 0, new String[]{"Custom", "Predict", "Adaptive"});
    public final ModeProperty blockMethod = new ModeProperty("block-method", 0, new String[]{"Packet", "Simulate"});
    public final IntProperty customWaitTicks = new IntProperty("custom-wait-ticks", 10, 1, 100, () -> this.mode.getValue() == 0);
    public final IntProperty blockDuration = new IntProperty("block-time", 3, 1, 20);
    public final BooleanProperty debug = new BooleanProperty("debug", true);
    public final FloatProperty predictMultiplier = new FloatProperty("predict-multiplier", 0.8F, 0.5F, 1.5F, () -> this.mode.getValue() == 1);
    public final IntProperty adaptiveMinWait = new IntProperty("adaptive-min-wait", 5, 1, 50, () -> this.mode.getValue() == 2);
    public final IntProperty adaptiveMaxWait = new IntProperty("adaptive-max-wait", 20, 1, 50, () -> this.mode.getValue() == 2);
    public final FloatProperty adaptiveFactor = new FloatProperty("adaptive-factor", 0.7F, 0.1F, 1.0F, () -> this.mode.getValue() == 2);
    public final BooleanProperty requireSword = new BooleanProperty("require-sword", true);
    public final BooleanProperty stopOnDisable = new BooleanProperty("stop-block-on-disable", true);
    public final BooleanProperty cancelAttackWhileBlocking = new BooleanProperty("cancel-attack-while-blocking", true);

    private final List<Long> damageIntervals = new ArrayList<>();
    private long lastHurtTick = 0L;
    private long currentTick = 0L;
    private boolean blocking = false;
    private long blockStartTick = 0L;
    private int waitTicks = 0;
    private boolean waitingToBlock = false;
    private long waitStartTick = 0L;
    private int prevHurtTime = 0;

    public AutoBlock() {
        super("AutoBlock", false);
    }

    /** True while this module holds the block. */
    public boolean isBlocking() {
        return this.blocking;
    }

    private void info(String message) {
        if (this.debug.getValue()) {
            ChatUtil.sendFormatted(Myau.clientName + message);
        }
    }

    private boolean isHoldingSword() {
        ItemStack held = mc.thePlayer.getHeldItem();
        return held != null && held.getItem() instanceof ItemSword;
    }

    /** KillAura has a target and runs its own auto-block: it owns the block state. */
    private boolean isKillAuraBlocking() {
        KillAura killAura = (KillAura) Myau.moduleManager.modules.get(KillAura.class);
        return killAura != null && killAura.isEnabled() && killAura.autoBlock.getValue() != 0
                && (killAura.getTarget() != null || killAura.isBlocking());
    }

    private void reset() {
        this.lastHurtTick = 0L;
        this.currentTick = 0L;
        this.blocking = false;
        this.blockStartTick = 0L;
        this.waitTicks = 0;
        this.waitingToBlock = false;
        this.waitStartTick = 0L;
        this.damageIntervals.clear();
        this.prevHurtTime = 0;
    }

    @Override
    public void onEnabled() {
        this.reset();
    }

    @Override
    public void onDisabled() {
        if (this.stopOnDisable.getValue() && this.blocking && mc.thePlayer != null) {
            this.stopBlocking();
        }
        this.reset();
    }

    @EventTarget
    public void onLoadWorld(LoadWorldEvent event) {
        this.reset();
    }

    @EventTarget
    public void onPacket(PacketEvent event) {
        if (!this.isEnabled() || mc.thePlayer == null) {
            return;
        }
        Packet<?> packet = event.getPacket();
        if (event.getType() == EventType.SEND) {
            if (this.cancelAttackWhileBlocking.getValue() && this.blocking) {
                if (packet instanceof C02PacketUseEntity) {
                    if (((C02PacketUseEntity) packet).getAction() == C02PacketUseEntity.Action.ATTACK) {
                        event.setCancelled(true);
                        this.info("&c[AutoBlock] &f格挡中取消攻击");
                    }
                } else if (packet instanceof C0APacketAnimation) {
                    event.setCancelled(true);
                }
            }
        } else if (packet instanceof S12PacketEntityVelocity) {
            final int entityId = ((S12PacketEntityVelocity) packet).getEntityID();
            // Received on the network thread: do the check on the client thread.
            mc.addScheduledTask(() -> {
                EntityPlayerSP player = mc.thePlayer;
                if (!this.isEnabled() || player == null || entityId != player.getEntityId()) {
                    return;
                }
                if (player.hurtTime > 0 && this.prevHurtTime == 0) {
                    this.onDamageReceived();
                }
            });
        }
    }

    @EventTarget
    public void onUpdate(UpdateEvent event) {
        if (!this.isEnabled() || event.getType() != EventType.PRE) {
            return;
        }
        EntityPlayerSP player = mc.thePlayer;
        this.currentTick++;
        if (player.hurtTime > 0 && this.prevHurtTime == 0) {
            this.onDamageReceived();
        }
        this.prevHurtTime = player.hurtTime;
        if (this.isKillAuraBlocking()) {
            // Hand the block over before KillAura (lower priority) runs its own logic this tick.
            if (this.blocking) {
                this.stopBlocking();
            }
            this.waitingToBlock = false;
            return;
        }
        if (this.requireSword.getValue() && !this.isHoldingSword()) {
            if (this.blocking) {
                this.stopBlocking();
            }
            return;
        }
        if (this.waitingToBlock) {
            int elapsed = (int) (this.currentTick - this.waitStartTick);
            if (elapsed % 5 == 0) {
                int remaining = this.waitTicks - elapsed;
                if (remaining > 0) {
                    this.info("&e[AutoBlock] &f等待格挡: &c" + remaining + " &ftick");
                }
            }
            if (elapsed >= this.waitTicks) {
                this.waitingToBlock = false;
                this.startBlocking();
            }
        }
        if (this.blocking) {
            int blockElapsed = (int) (this.currentTick - this.blockStartTick);
            if (blockElapsed >= this.blockDuration.getValue()) {
                this.stopBlocking();
            }
        }
    }

    private void onDamageReceived() {
        if (mc.thePlayer == null || this.requireSword.getValue() && !this.isHoldingSword()) {
            return;
        }
        if (this.lastHurtTick > 0L) {
            this.damageIntervals.add(this.currentTick - this.lastHurtTick);
            if (this.damageIntervals.size() > 10) {
                this.damageIntervals.remove(0);
            }
        }
        this.lastHurtTick = this.currentTick;
        this.waitTicks = this.calculateWaitTicks();
        this.waitingToBlock = true;
        this.waitStartTick = this.currentTick;
        this.info("&e[AutoBlock] &f受伤! 将在 &c" + this.waitTicks + " &ftick 后格挡");
    }

    /** Average of the last {@code count} recorded hit intervals. */
    private double averageInterval(int count) {
        int size = this.damageIntervals.size();
        int from = Math.max(0, size - count);
        double sum = 0.0;
        for (int i = from; i < size; i++) {
            sum += this.damageIntervals.get(i);
        }
        return sum / (double) (size - from);
    }

    private int calculateWaitTicks() {
        switch (this.mode.getValue()) {
            case 1:
                if (!this.damageIntervals.isEmpty()) {
                    int predicted = (int) (this.averageInterval(this.damageIntervals.size()) * this.predictMultiplier.getValue());
                    return Math.max(1, Math.min(50, predicted));
                }
                return this.customWaitTicks.getValue();
            case 2:
                if (!this.damageIntervals.isEmpty()) {
                    int calculated = (int) (this.averageInterval(3) * this.adaptiveFactor.getValue());
                    int min = Math.min(this.adaptiveMinWait.getValue(), this.adaptiveMaxWait.getValue());
                    int max = Math.max(this.adaptiveMinWait.getValue(), this.adaptiveMaxWait.getValue());
                    return Math.max(min, Math.min(max, calculated));
                }
                return this.adaptiveMinWait.getValue();
            default:
                return this.customWaitTicks.getValue();
        }
    }

    private void startBlocking() {
        EntityPlayerSP player = mc.thePlayer;
        if (this.blocking || player == null) {
            return;
        }
        if (this.requireSword.getValue() && !this.isHoldingSword()) {
            return;
        }
        if (this.blockMethod.getValue() == 0) {
            PacketUtil.sendPacket(new C08PacketPlayerBlockPlacement(player.getHeldItem()));
        } else {
            KeyBindUtil.setKeyBindState(mc.gameSettings.keyBindUseItem.getKeyCode(), true);
            if (mc.playerController != null) {
                mc.playerController.onPlayerRightClick(player, mc.theWorld, player.getHeldItem(), BlockPos.ORIGIN, EnumFacing.UP, new Vec3(0.0, 0.0, 0.0));
            }
        }
        this.blocking = true;
        this.blockStartTick = this.currentTick;
        this.info("&a[AutoBlock] &f开始格挡! 持续 &c" + this.blockDuration.getValue() + " &ftick");
    }

    private void stopBlocking() {
        if (!this.blocking) {
            return;
        }
        if (this.blockMethod.getValue() == 0) {
            PacketUtil.sendPacket(new C07PacketPlayerDigging(C07PacketPlayerDigging.Action.RELEASE_USE_ITEM, BlockPos.ORIGIN, EnumFacing.DOWN));
        } else {
            KeyBindUtil.setKeyBindState(mc.gameSettings.keyBindUseItem.getKeyCode(), false);
        }
        this.blocking = false;
        this.info("&c[AutoBlock] &f停止格挡");
    }

    @Override
    public String[] getSuffix() {
        return new String[]{this.mode.getModeString()};
    }
}
