package myau.module.modules;

import myau.Myau;
import myau.event.EventTarget;
import myau.event.types.EventType;
import myau.events.Render2DEvent;
import myau.events.TickEvent;
import myau.ml.MLAim;
import myau.module.Module;
import myau.property.properties.FloatProperty;
import myau.property.properties.IntProperty;
import myau.property.properties.ModeProperty;
import myau.util.ChatUtil;
import myau.util.RotationUtil;
import myau.util.TeamUtil;
import net.minecraft.client.Minecraft;
import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityLivingBase;
import net.minecraft.entity.item.EntityArmorStand;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.util.AxisAlignedBB;
import net.minecraft.util.MathHelper;
import net.minecraft.util.MovingObjectPosition;

import java.io.BufferedWriter;
import java.io.File;
import java.io.FileOutputStream;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.Locale;

/**
 * Records how you aim at the nearest enemy, one CSV row per tick, as training data for KillAura's ML
 * rotation mode ({@code .ml train}). Ported from Zak0; the columns are unchanged because the training
 * script reads them by position.
 */
public class DataCollector extends Module {
    private static final Minecraft mc = Minecraft.getMinecraft();
    private static final int HISTORY_SIZE = 16;

    public final ModeProperty sort = new ModeProperty("sort", 3, new String[]{"DISTANCE", "HEALTH", "HURT_TIME", "FOV"});
    public final FloatProperty range = new FloatProperty("range", 16.0F, 3.0F, 64.0F);
    public final IntProperty fov = new IntProperty("fov", 360, 30, 360);

    private BufferedWriter writer;
    private File outputFile;
    private boolean failed = false;
    private int rows = 0;
    private boolean hasPrev = false;
    private float prevYaw = 0.0F;
    private float prevPitch = 0.0F;
    private float prevDeltaYaw = 0.0F;
    private float prevDeltaPitch = 0.0F;
    private float prevTargetYaw = 0.0F;
    private float prevTargetPitch = 0.0F;
    private final float[] moveYawHistory = new float[HISTORY_SIZE];
    private final float[] movePitchHistory = new float[HISTORY_SIZE];
    private int combatTick = 0;
    private int lastTargetId = -1;
    private boolean recordingNow = false;

    public DataCollector() {
        super("DataCollector", false);
    }

    @Override
    public void onEnabled() {
        this.hasPrev = false;
        this.combatTick = 0;
        this.lastTargetId = -1;
        this.rows = 0;
        this.failed = false;
        this.recordingNow = false;
        // the file is created with the first row, so switching on without fighting leaves nothing behind
    }

    @Override
    public void onDisabled() {
        this.closeWriter();
        this.recordingNow = false;
    }

    @Override
    public String[] getSuffix() {
        return this.writer != null ? new String[]{String.valueOf(this.rows)} : new String[0];
    }

    private boolean openWriter() {
        if (this.writer != null) return true;
        if (this.failed) return false;
        try {
            File dir = MLAim.DATA_DIR;
            if (!dir.exists() && !dir.mkdirs()) throw new java.io.IOException("cannot create " + dir.getPath());
            String timestamp = new SimpleDateFormat("yyyyMMdd_HHmmss").format(new Date());
            this.outputFile = new File(dir, "aim_data_" + timestamp + ".csv");
            this.writer = new BufferedWriter(new OutputStreamWriter(new FileOutputStream(this.outputFile), StandardCharsets.UTF_8));
            StringBuilder header = new StringBuilder(
                    "deltaYaw,deltaPitch,distance,hurtTime,combatTick,attackState,aimRatioX,aimRatioY,hitEntity,targetVelYaw,targetVelPitch,playerOnGround,playerSpeed,playerMoving,deltaYawRate,deltaPitchRate,targetSpeed,targetOnGround,targetDeltaX,targetDeltaZ");
            for (int i = 0; i < HISTORY_SIZE; i++) {
                header.append(",histMoveYaw").append(i).append(",histMovePitch").append(i);
            }
            header.append(",moveYaw,movePitch");
            this.writer.write(header.toString());
            this.writer.newLine();
            this.writer.flush();
            ChatUtil.sendFormatted(String.format("%sRecording: &o%s&r", Myau.clientName, this.outputFile.getName()));
            return true;
        } catch (Exception e) {
            this.failed = true;
            this.writer = null;
            ChatUtil.sendFormatted(String.format("%sDataCollector could not start: %s", Myau.clientName, e.getMessage()));
            return false;
        }
    }

    private void closeWriter() {
        if (this.writer == null) return;
        try {
            this.writer.flush();
            this.writer.close();
            ChatUtil.sendFormatted(String.format("%sSaved %d rows: &o%s&r", Myau.clientName, this.rows, this.outputFile.getAbsolutePath()));
        } catch (Exception e) {
            ChatUtil.sendFormatted(String.format("%sDataCollector save error: %s", Myau.clientName, e.getMessage()));
        }
        this.writer = null;
    }

    @EventTarget
    public void onTick(TickEvent event) {
        if (!this.isEnabled() || event.getType() != EventType.POST || mc.thePlayer == null || mc.theWorld == null) return;
        EntityLivingBase target = this.findBestTarget();
        if (target == null) {
            this.hasPrev = false;
            this.combatTick = 0;
            this.lastTargetId = -1;
            this.recordingNow = false;
            return;
        }
        float currentYaw = mc.thePlayer.rotationYaw;
        float currentPitch = mc.thePlayer.rotationPitch;
        float borderSize = target.getCollisionBorderSize();
        AxisAlignedBB box = target.getEntityBoundingBox().expand(borderSize, borderSize, borderSize);
        float[] targetRots = RotationUtil.getRotationsToBox(box, currentYaw, currentPitch, 180.0F, 0.0F);
        float targetYaw = targetRots[0];
        float targetPitch = targetRots[1];
        int targetId = target.getEntityId();
        if (targetId != this.lastTargetId) {
            this.combatTick = 0;
            this.prevDeltaYaw = 0.0F;
            this.prevDeltaPitch = 0.0F;
            for (int i = 0; i < HISTORY_SIZE; i++) {
                this.moveYawHistory[i] = 0.0F;
                this.movePitchHistory[i] = 0.0F;
            }
            this.hasPrev = false;
            this.lastTargetId = targetId;
        }

        if (this.hasPrev && this.openWriter()) {
            float deltaYaw = MathHelper.wrapAngleTo180_float(this.prevYaw - targetYaw);
            float deltaPitch = this.prevPitch - targetPitch;
            double distance = RotationUtil.distanceToBox(box) / 10.0;
            float hurtTime = target.hurtTime / 10.0F;
            float combatTickNorm = this.combatTick % 100 / 100.0F;
            float aimRatioX = 0.5F;
            float aimRatioY = 0.5F;
            MovingObjectPosition mop = RotationUtil.rayTrace(box, currentYaw, currentPitch, this.range.getValue());
            boolean attackState = mop != null;
            if (mop != null && mop.hitVec != null) {
                aimRatioX = (float) ((mop.hitVec.xCoord - box.minX) / (box.maxX - box.minX));
                aimRatioY = (float) ((mop.hitVec.yCoord - box.minY) / (box.maxY - box.minY));
            }
            float hitEntity = attackState ? 1.0F : 0.0F;
            float moveYaw = MathHelper.wrapAngleTo180_float(currentYaw - this.prevYaw);
            float movePitch = currentPitch - this.prevPitch;
            float targetVelYaw = MathHelper.wrapAngleTo180_float(targetYaw - this.prevTargetYaw);
            float targetVelPitch = targetPitch - this.prevTargetPitch;
            boolean playerOnGround = mc.thePlayer.onGround;
            double dx = mc.thePlayer.posX - mc.thePlayer.lastTickPosX;
            double dz = mc.thePlayer.posZ - mc.thePlayer.lastTickPosZ;
            double playerSpeed = Math.sqrt(dx * dx + dz * dz) * 20.0;
            boolean playerMoving = playerSpeed > 0.1;
            float deltaYawRate = MathHelper.wrapAngleTo180_float(deltaYaw - this.prevDeltaYaw);
            float deltaPitchRate = deltaPitch - this.prevDeltaPitch;
            double tdx = target.posX - target.lastTickPosX;
            double tdz = target.posZ - target.lastTickPosZ;
            float targetSpeed = (float) (Math.sqrt(tdx * tdx + tdz * tdz) * 20.0);
            boolean targetOnGround = target.onGround;
            try {
                StringBuilder sb = new StringBuilder();
                sb.append(String.format(Locale.US,
                        "%.4f,%.4f,%.4f,%.4f,%.4f,%.1f,%.4f,%.4f,%.1f,%.4f,%.4f,%.1f,%.4f,%.1f,%.4f,%.4f,%.4f,%.1f,%.4f,%.4f",
                        deltaYaw, deltaPitch, (float) distance, hurtTime, combatTickNorm, hitEntity, aimRatioX, aimRatioY, hitEntity,
                        targetVelYaw, targetVelPitch, playerOnGround ? 1.0F : 0.0F, (float) playerSpeed, playerMoving ? 1.0F : 0.0F,
                        deltaYawRate, deltaPitchRate, targetSpeed, targetOnGround ? 1.0F : 0.0F, (float) tdx, (float) tdz));
                for (int i = 0; i < HISTORY_SIZE; i++) {
                    sb.append(String.format(Locale.US, ",%.4f,%.4f", this.moveYawHistory[i], this.movePitchHistory[i]));
                }
                sb.append(String.format(Locale.US, ",%.4f,%.4f", moveYaw, movePitch));
                this.writer.write(sb.toString());
                this.writer.newLine();
                this.rows++;
                if (this.rows % 20 == 0) this.writer.flush();
                this.recordingNow = true;
            } catch (Exception e) {
                ChatUtil.sendFormatted(String.format("%sDataCollector write error: %s", Myau.clientName, e.getMessage()));
                this.failed = true;
                this.closeWriter();
            }
            for (int i = HISTORY_SIZE - 1; i > 0; i--) {
                this.moveYawHistory[i] = this.moveYawHistory[i - 1];
                this.movePitchHistory[i] = this.movePitchHistory[i - 1];
            }
            this.moveYawHistory[0] = moveYaw;
            this.movePitchHistory[0] = movePitch;
            this.prevDeltaYaw = deltaYaw;
            this.prevDeltaPitch = deltaPitch;
        }

        this.prevYaw = currentYaw;
        this.prevPitch = currentPitch;
        this.prevTargetYaw = targetYaw;
        this.prevTargetPitch = targetPitch;
        this.hasPrev = true;
        this.combatTick++;
    }

    private EntityLivingBase findBestTarget() {
        EntityLivingBase closest = null;
        double bestScore = Double.MAX_VALUE;
        for (Entity entity : new ArrayList<>(mc.theWorld.loadedEntityList)) {
            if (!(entity instanceof EntityLivingBase)) continue;
            EntityLivingBase living = (EntityLivingBase) entity;
            if (living == mc.thePlayer || living == mc.thePlayer.ridingEntity || living instanceof EntityArmorStand
                    || living.deathTime > 0 || living.isDead || living.getHealth() <= 0.0F) {
                continue;
            }
            double dist = RotationUtil.distanceToEntity(living);
            if (dist > this.range.getValue() || dist < 0.2) continue;
            float angle = RotationUtil.angleToEntity(living);
            if (this.fov.getValue() < 360 && angle > this.fov.getValue()) continue;
            if (living instanceof EntityPlayer) {
                EntityPlayer player = (EntityPlayer) living;
                if (TeamUtil.isSameTeam(player) || TeamUtil.isBot(player)) continue;
            }
            float borderSize = living.getCollisionBorderSize();
            AxisAlignedBB box = living.getEntityBoundingBox().expand(borderSize, borderSize, borderSize);
            // whoever the crosshair is on is the one being aimed at
            if (RotationUtil.rayTrace(box, mc.thePlayer.rotationYaw, mc.thePlayer.rotationPitch, this.range.getValue()) != null) {
                return living;
            }
            double score;
            switch (this.sort.getValue()) {
                case 1:
                    score = living.getHealth();
                    break;
                case 2:
                    score = living.hurtResistantTime;
                    break;
                case 3:
                    score = angle;
                    break;
                default:
                    score = dist;
            }
            if (score < bestScore) {
                bestScore = score;
                closest = living;
            }
        }
        return closest;
    }

    // ---------------------------------------------------------------- dynamic island


    /** Recording dot and the row count while a target is being tracked. */
    @EventTarget
    public void onIsland(Render2DEvent event) {
        if (!this.isEnabled() || mc.thePlayer == null || !this.recordingNow) return;
        myau.ui.glass.Island.live("datacollector", myau.ui.glass.Island.P_INFO,
                "REC §f" + this.rows, myau.ui.GlassTheme.BAD, -1.0F);
    }
}
