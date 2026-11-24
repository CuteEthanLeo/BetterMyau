package myau.module.modules;

import myau.Myau;
import myau.event.EventTarget;
import myau.event.types.EventType;
import myau.events.*;
import myau.module.Module;
import myau.property.properties.BooleanProperty;
import myau.util.ChatUtil;
import net.minecraft.client.Minecraft;
import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityLivingBase;
import net.minecraft.network.play.server.S08PacketPlayerPosLook;

import java.text.SimpleDateFormat;
import java.util.Date;

public class FlagCheck extends Module {
    private static final Minecraft mc = Minecraft.getMinecraft();

    // ── Rubberband ──
    private double lastPosX, lastPosY, lastPosZ;
    private double lastMotionX, lastMotionY, lastMotionZ;
    private boolean posInitialized;
    private int rubberbandCount;

    // ── Ghost hit ──
    private Entity trackedTarget;
    private int attackTick;
    private int ghostHitCount;

    // ── Cooldown ──
    private long lastRubberbandAlert;
    private long lastGhostHitAlert;
    private static final long ALERT_COOLDOWN = 1500L; // 降到1.5秒，稍微紧凑一些

    public final BooleanProperty detectRubberband = new BooleanProperty("detect-rubberband", true);
    public final BooleanProperty detectGhostHit = new BooleanProperty("detect-ghost-hit", true);

    private static final SimpleDateFormat TIME_FMT = new SimpleDateFormat("HH:mm:ss");

    // ── 市面上主流客户端标志性配色方案 ──
    private static final String C_PREFIX = "§8[§bBetterMyau§8] §r"; // 客户端前缀：深灰中括号 + 青色名
    private static final String C_FLAG   = "§7[§c§lFLAG§r§7] §r";     // FLAG标签：加粗红字
    private static final String C_TEXT   = "§7";                   // 常规辅助文本：浅灰
    private static final String C_VAL    = "§f";                   // 关键数值：纯白
    private static final String C_DIM    = "§a";                   // 维度信息：淡绿
    private static final String C_TIME   = "§9";                   // 时间信息：淡蓝
    private static final String C_SEP    = " §8| §r";              // 现代化深灰竖线分隔符

    public FlagCheck() {
        super("FlagCheck", false);
    }

    @Override
    public void onEnabled() {
        posInitialized = false;
        rubberbandCount = 0;
        ghostHitCount = 0;
        trackedTarget = null;
        attackTick = 0;
        lastRubberbandAlert = 0;
        lastGhostHitAlert = 0;
    }

    private String getDimensionName() {
        if (mc.thePlayer == null) return "Unknown";
        switch (mc.thePlayer.dimension) {
            case -1: return "Nether";
            case 0:  return "Overworld";
            case 1:  return "End";
            default: return "DIM_" + mc.thePlayer.dimension;
        }
    }

    private String currentTime() {
        return TIME_FMT.format(new Date());
    }

    @EventTarget
    public void onTick(TickEvent event) {
        if (!this.isEnabled() || mc.thePlayer == null || mc.theWorld == null) return;

        if (trackedTarget != null && detectGhostHit.getValue()) {
            int elapsed = mc.thePlayer.ticksExisted - attackTick;
            if (elapsed >= 3) {
                if (trackedTarget instanceof EntityLivingBase) {
                    EntityLivingBase target = (EntityLivingBase) trackedTarget;
                    // 核心修复：更准确的卡刀/假击退判定 (无红字或伤害延迟)
                    if (target.hurtTime <= 0 || target.hurtTime > 9) {
                        alertGhostHit();
                    }
                }
                trackedTarget = null;
            } else if (elapsed >= 1) {
                if (trackedTarget instanceof EntityLivingBase) {
                    EntityLivingBase target = (EntityLivingBase) trackedTarget;
                    if (target.hurtTime > 0 && target.hurtTime <= 9) {
                        trackedTarget = null;
                    }
                }
            }
        }
    }

    @EventTarget
    public void onAttack(AttackEvent event) {
        if (!this.isEnabled() || !detectGhostHit.getValue()) return;
        if (event.getTarget() instanceof EntityLivingBase) {
            trackedTarget = event.getTarget();
            attackTick = mc.thePlayer.ticksExisted;
        }
    }

    @EventTarget
    public void onUpdate(UpdateEvent event) {
        if (!this.isEnabled() || mc.thePlayer == null) return;
        if (event.getType() != EventType.PRE) return;

        double px = mc.thePlayer.posX;
        double py = mc.thePlayer.posY;
        double pz = mc.thePlayer.posZ;

        if (!posInitialized) {
            lastPosX = px; lastPosY = py; lastPosZ = pz;
            lastMotionX = mc.thePlayer.motionX;
            lastMotionY = mc.thePlayer.motionY;
            lastMotionZ = mc.thePlayer.motionZ;
            posInitialized = true;
            return;
        }

        if (detectRubberband.getValue()) {
            double expectedX = lastPosX + lastMotionX;
            double expectedY = lastPosY + lastMotionY;
            double expectedZ = lastPosZ + lastMotionZ;

            double dx = px - expectedX;
            double dy = py - expectedY;
            double dz = pz - expectedZ;
            double delta = Math.sqrt(dx * dx + dy * dy + dz * dz);

            double hDelta = Math.sqrt(dx * dx + dz * dz);
            // 过滤掉正常的掉落和传送，防止大面积误报
            if (!mc.thePlayer.capabilities.isFlying && !mc.thePlayer.isRiding()) {
                if (hDelta > 1.8 || Math.abs(dy) > 1.2 || delta > 2.5) {
                    alertRubberband("Prediction", delta, px, py, pz);
                }
            }
        }

        lastPosX = px; lastPosY = py; lastPosZ = pz;
        lastMotionX = mc.thePlayer.motionX;
        lastMotionY = mc.thePlayer.motionY;
        lastMotionZ = mc.thePlayer.motionZ;
    }

    @EventTarget
    public void onPacket(PacketEvent event) {
        if (!this.isEnabled() || !detectRubberband.getValue()) return;
        if (event.getType() != EventType.RECEIVE) return;
        // 进服登录时 S08 可能早于本地玩家实体创建到达
        if (mc.thePlayer == null) return;

        // 强力捕捉服务端的 S08 强制位移包（最权威的拉回依据）
        if (event.getPacket() instanceof S08PacketPlayerPosLook) {
            S08PacketPlayerPosLook s08 = (S08PacketPlayerPosLook) event.getPacket();
            double dx = s08.getX() - mc.thePlayer.posX;
            double dy = s08.getY() - mc.thePlayer.posY;
            double dz = s08.getZ() - mc.thePlayer.posZ;
            double dist = Math.sqrt(dx * dx + dy * dy + dz * dz);

            if (dist > 0.3 && mc.thePlayer.ticksExisted > 20) {
                alertRubberband("S08Packet", dist, mc.thePlayer.posX, mc.thePlayer.posY, mc.thePlayer.posZ);
            }
        }
    }

    private void alertRubberband(String checkType, double delta, double x, double y, double z) {
        long now = System.currentTimeMillis();
        if (now - lastRubberbandAlert < ALERT_COOLDOWN) return;
        lastRubberbandAlert = now;
        rubberbandCount++;

        // 格式化输出：[BetterMyau] [FLAG] Rubberband (#1) | Type: S08Packet | Dist: 4.32 | Pos: 12.3, 64.0, -85.2 | Overworld | 11:17:29
        String msg = C_PREFIX + C_FLAG + "§e§lRubberband §7(#" + C_VAL + rubberbandCount + C_TEXT + ")"
                + C_SEP + C_TEXT + "Type: " + C_VAL + checkType
                + C_SEP + C_TEXT + "Dist: " + C_VAL + String.format("%.2f", delta)
                + C_SEP + C_TEXT + "Pos: " + C_VAL + String.format("%.1f, %.1f, %.1f", x, y, z)
                + C_SEP + C_DIM + getDimensionName()
                + C_SEP + C_TIME + currentTime();
        ChatUtil.sendFormatted(msg);
    }

    private void alertGhostHit() {
        long now = System.currentTimeMillis();
        if (now - lastGhostHitAlert < ALERT_COOLDOWN) return;
        lastGhostHitAlert = now;
        ghostHitCount++;

        String targetName = "Unknown";
        String targetPos = "0.0, 0.0, 0.0";

        if (trackedTarget != null) {
            targetName = trackedTarget.getName();
            targetPos = String.format("%.1f, %.1f, %.1f", trackedTarget.posX, trackedTarget.posY, trackedTarget.posZ);
        }

        // 格式化输出：[BetterMyau] [FLAG] GhostHit (#1) | Target: Player123 | Pos: 10.5, 63.0, -42.1 | Overworld | 11:17:29
        String msg = C_PREFIX + C_FLAG + "§b§lGhostHit §7(#" + C_VAL + ghostHitCount + C_TEXT + ")"
                + C_SEP + C_TEXT + "Target: " + C_VAL + targetName
                + C_SEP + C_TEXT + "Pos: " + C_VAL + targetPos
                + C_SEP + C_DIM + getDimensionName()
                + C_SEP + C_TIME + currentTime();
        ChatUtil.sendFormatted(msg);
    }
}