package myau.ml;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import myau.config.Config;
import myau.util.RotationUtil;
import net.minecraft.client.Minecraft;
import net.minecraft.entity.EntityLivingBase;
import net.minecraft.util.AxisAlignedBB;
import net.minecraft.util.MathHelper;
import net.minecraft.util.MovingObjectPosition;

import java.io.File;
import java.io.FileInputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;

/**
 * KillAura's ML rotation mode: a small GRU, trained on aim recorded by {@link myau.module.modules.DataCollector},
 * proposes how far to turn each tick; the result is pulled toward the target and kept from overshooting.
 * Ported from Zak0. Features, constants and the JSON model format are unchanged, so models trained for
 * Zak0 load here too.
 */
public class MLAim {
    private static final Minecraft mc = Minecraft.getMinecraft();

    /** Trained models ({@code *.json}). */
    public static final File MODEL_DIR = new File(Config.CONFIG_DIR, "ML");
    /** Recorded aim ({@code *.csv}), written by DataCollector, read by the training script. */
    public static final File DATA_DIR = new File(Config.CONFIG_DIR, "CollectData");
    /** Model setting meaning "the newest file in {@link #MODEL_DIR}". */
    public static final String LATEST = "latest";

    static final int INPUT_SIZE = 20;
    static final int OUTPUT_SIZE = 2;
    static final float NORM_DELTA_YAW = 180.0F;
    static final float NORM_DELTA_PITCH = 90.0F;
    static final float NORM_MOVE = 45.0F;
    static final float NORM_VEL = 45.0F;
    static final float NORM_SPEED = 10.0F;

    // ---------------------------------------------------------------- model
    private int hiddenSize;
    private float[][] weightIH;
    private float[][] weightHH;
    private float[] biasIH;
    private float[] biasHH;
    private float[][] outputWeight;
    private float[] outputBias;
    private float[] hiddenState = new float[0];
    private boolean modelLoaded = false;
    /** The file the model came from, null when none is loaded. */
    private File loadedFile;
    /** Set when the model setting changed or a reload was asked for; checked lazily on the next use. */
    private boolean dirty = true;
    private String lastProblem;

    // ---------------------------------------------------------------- per-target state
    private boolean hasPrev = false;
    private float prevDeltaYaw = 0.0F;
    private float prevDeltaPitch = 0.0F;
    private float prevTargetYaw = 0.0F;
    private float prevTargetPitch = 0.0F;
    private int combatTick = 0;
    private int lastTargetId = -1;
    private int lastUseTick = Integer.MIN_VALUE;
    private float smoothMoveYaw = 0.0F;
    private float smoothMovePitch = 0.0F;

    // ---------------------------------------------------------------- files

    /** Model files in {@link #MODEL_DIR}, sorted by name. */
    public static List<String> listModelFiles() {
        List<String> names = new ArrayList<>();
        File[] files = MODEL_DIR.listFiles((dir, name) -> name.toLowerCase(Locale.ROOT).endsWith(".json"));
        if (files != null) {
            for (File file : files) names.add(file.getName());
        }
        names.sort(String::compareToIgnoreCase);
        return names;
    }

    /** The file a model setting points at: the newest model for {@code latest} (or blank), else that file name. */
    public static File resolve(String setting) {
        String name = setting == null ? "" : setting.trim();
        if (name.isEmpty() || name.equalsIgnoreCase(LATEST)) {
            File[] files = MODEL_DIR.listFiles((dir, n) -> n.toLowerCase(Locale.ROOT).endsWith(".json"));
            File newest = null;
            if (files != null) {
                for (File file : files) {
                    if (newest == null || file.lastModified() > newest.lastModified()) newest = file;
                }
            }
            return newest;
        }
        if (!name.toLowerCase(Locale.ROOT).endsWith(".json")) name += ".json";
        File file = new File(MODEL_DIR, name);
        return file.isFile() ? file : null;
    }

    // ---------------------------------------------------------------- loading

    public boolean isModelLoaded() {
        return this.modelLoaded;
    }

    public File getLoadedFile() {
        return this.loadedFile;
    }

    /** Load again on the next use (the setting changed, or a model was trained). */
    public void markDirty() {
        this.dirty = true;
    }

    /**
     * Loads the model the setting points at. Returns null when a model is loaded, otherwise why not
     * (no model at all is not an error: the mode then only converges on the target).
     */
    public String load(String setting) {
        this.dirty = false;
        this.modelLoaded = false;
        this.loadedFile = null;
        File file = resolve(setting);
        if (file == null) {
            boolean named = setting != null && !setting.trim().isEmpty() && !setting.trim().equalsIgnoreCase(LATEST);
            return named ? "model not found: " + setting.trim() : "no model in " + MODEL_DIR.getPath();
        }
        try (InputStreamReader reader = new InputStreamReader(new FileInputStream(file), StandardCharsets.UTF_8)) {
            JsonObject root = new JsonParser().parse(reader).getAsJsonObject();
            if (!root.has("type") || !"gru".equalsIgnoreCase(root.get("type").getAsString())) {
                return file.getName() + " is not a GRU model";
            }
            int hidden = root.get("hidden_size").getAsInt();
            if (hidden <= 0) return file.getName() + ": bad hidden_size";
            JsonObject gru = root.getAsJsonObject("gru");
            JsonObject output = root.getAsJsonObject("output");
            this.hiddenSize = hidden;
            this.weightIH = parseMatrix(gru.getAsJsonArray("weight_ih"), hidden * 3, INPUT_SIZE);
            this.weightHH = parseMatrix(gru.getAsJsonArray("weight_hh"), hidden * 3, hidden);
            this.biasIH = parseVector(gru.getAsJsonArray("bias_ih"), hidden * 3);
            this.biasHH = parseVector(gru.getAsJsonArray("bias_hh"), hidden * 3);
            this.outputWeight = parseMatrix(output.getAsJsonArray("weights"), OUTPUT_SIZE, hidden);
            this.outputBias = parseVector(output.getAsJsonArray("biases"), OUTPUT_SIZE);
            this.hiddenState = new float[hidden];
            this.modelLoaded = true;
            this.loadedFile = file;
            return null;
        } catch (Exception e) {
            return file.getName() + ": " + e.getClass().getSimpleName() + (e.getMessage() != null ? " " + e.getMessage() : "");
        }
    }

    private void ensureLoaded(String setting) {
        if (!this.dirty) return;
        String problem = this.load(setting);
        // say it once per distinct problem, not every time the mode is picked up again
        if (problem != null && !problem.equals(this.lastProblem) && !problem.startsWith("no model")) {
            myau.ui.glass.Island.event("ML", problem, myau.ui.GlassTheme.WARN, 3.0F);
        }
        this.lastProblem = problem;
    }

    private static float[][] parseMatrix(JsonArray values, int rows, int cols) {
        float[][] matrix = new float[rows][cols];
        for (int i = 0; i < rows && i < values.size(); i++) {
            JsonArray row = values.get(i).getAsJsonArray();
            for (int j = 0; j < cols && j < row.size(); j++) {
                matrix[i][j] = row.get(j).getAsFloat();
            }
        }
        return matrix;
    }

    private static float[] parseVector(JsonArray values, int size) {
        float[] vector = new float[size];
        for (int i = 0; i < size && i < values.size(); i++) {
            vector[i] = values.get(i).getAsFloat();
        }
        return vector;
    }

    // ---------------------------------------------------------------- rotation

    /**
     * Next rotation toward the target. Call once per tick while aiming at it (the GRU keeps state between calls).
     *
     * @param box          the target's hit box (border-expanded)
     * @param entity       the target
     * @param currentYaw   rotation last sent to the server
     * @param currentPitch rotation last sent to the server
     * @param range        attack range, for the "already on target" feature
     */
    public float[] getRotation(AxisAlignedBB box, EntityLivingBase entity, float currentYaw, float currentPitch,
                               double range, float strength, float maxMove, float smooth, String modelSetting) {
        this.ensureLoaded(modelSetting);
        float[] targetRots = RotationUtil.getRotationsToBox(box, currentYaw, currentPitch, 180.0F, 0.0F);
        float targetYaw = targetRots[0];
        float targetPitch = targetRots[1];
        int targetId = entity.getEntityId();
        // a new target, or aiming resumed after a gap: the remembered motion no longer applies
        int tick = mc.thePlayer.ticksExisted;
        if (targetId != this.lastTargetId || tick - this.lastUseTick != 1) {
            this.resetTargetState(targetId);
        }
        this.lastUseTick = tick;

        float deltaYaw = MathHelper.wrapAngleTo180_float(currentYaw - targetYaw);
        float deltaPitch = currentPitch - targetPitch;
        double distance = RotationUtil.distanceToBox(box) / 10.0;
        float hurtTime = entity.hurtTime / 10.0F;
        float combatTickNorm = this.combatTick % 100 / 100.0F;
        float aimRatioX = 0.5F;
        float aimRatioY = 0.5F;
        MovingObjectPosition mop = RotationUtil.rayTrace(box, currentYaw, currentPitch, range);
        boolean hitEntity = mop != null;
        if (hitEntity && mop.hitVec != null) {
            aimRatioX = (float) ((mop.hitVec.xCoord - box.minX) / (box.maxX - box.minX));
            aimRatioY = (float) ((mop.hitVec.yCoord - box.minY) / (box.maxY - box.minY));
        }

        float targetVelYaw = this.hasPrev ? MathHelper.wrapAngleTo180_float(targetYaw - this.prevTargetYaw) : 0.0F;
        float targetVelPitch = this.hasPrev ? targetPitch - this.prevTargetPitch : 0.0F;
        boolean playerOnGround = mc.thePlayer.onGround;
        double dx = mc.thePlayer.posX - mc.thePlayer.lastTickPosX;
        double dz = mc.thePlayer.posZ - mc.thePlayer.lastTickPosZ;
        float playerSpeed = (float) (Math.sqrt(dx * dx + dz * dz) * 20.0);
        float playerMoving = playerSpeed > 0.1F ? 1.0F : 0.0F;
        float deltaYawRate = this.hasPrev ? MathHelper.wrapAngleTo180_float(deltaYaw - this.prevDeltaYaw) : 0.0F;
        float deltaPitchRate = this.hasPrev ? deltaPitch - this.prevDeltaPitch : 0.0F;
        double tdx = entity.posX - entity.lastTickPosX;
        double tdz = entity.posZ - entity.lastTickPosZ;
        float targetSpeed = (float) (Math.sqrt(tdx * tdx + tdz * tdz) * 20.0);
        boolean targetOnGround = entity.onGround;
        float[] input = new float[]{
                deltaYaw / NORM_DELTA_YAW,
                deltaPitch / NORM_DELTA_PITCH,
                (float) distance,
                hurtTime,
                combatTickNorm,
                hitEntity ? 1.0F : 0.0F,
                aimRatioX,
                aimRatioY,
                hitEntity ? 1.0F : 0.0F,
                targetVelYaw / NORM_VEL,
                targetVelPitch / NORM_VEL,
                playerOnGround ? 1.0F : 0.0F,
                playerSpeed / NORM_SPEED,
                playerMoving,
                deltaYawRate / NORM_DELTA_YAW,
                deltaPitchRate / NORM_DELTA_PITCH,
                targetSpeed / NORM_SPEED,
                targetOnGround ? 1.0F : 0.0F,
                (float) tdx / NORM_SPEED,
                (float) tdz / NORM_SPEED
        };
        float[] output = this.modelLoaded ? this.forwardGru(input) : null;
        float convergeYaw = -deltaYaw * 0.2F;
        float convergePitch = -deltaPitch * 0.2F;
        float moveYaw;
        float movePitch;
        if (output != null) {
            float mlMoveYaw = MathHelper.clamp_float(output[0] * NORM_MOVE * strength, -maxMove, maxMove);
            float mlMovePitch = MathHelper.clamp_float(output[1] * NORM_MOVE * strength, -maxMove, maxMove);
            moveYaw = mlMoveYaw * 0.8F + convergeYaw * 0.2F;
            movePitch = mlMovePitch * 0.8F + convergePitch * 0.2F;
        } else {
            moveYaw = convergeYaw;
            movePitch = convergePitch;
        }

        moveYaw = MathHelper.clamp_float(moveYaw, -maxMove, maxMove);
        movePitch = MathHelper.clamp_float(movePitch, -maxMove, maxMove);
        float absDeltaYaw = Math.abs(deltaYaw);
        float desiredSignYaw = -Math.signum(deltaYaw);
        if (absDeltaYaw > 120.0F) {
            moveYaw = MathHelper.clamp_float(convergeYaw, -maxMove, maxMove);
            this.smoothMoveYaw *= 0.25F;
        }
        // never turn away from the target
        if (desiredSignYaw != 0.0F && Math.signum(moveYaw) != 0.0F && Math.signum(moveYaw) != desiredSignYaw) {
            moveYaw = MathHelper.clamp_float(convergeYaw, -maxMove, maxMove);
            this.smoothMoveYaw *= 0.35F;
        }
        // and never past it
        if (desiredSignYaw != 0.0F && Math.signum(moveYaw) == desiredSignYaw && absDeltaYaw > 0.5F) {
            float limit = absDeltaYaw * 0.9F;
            if (Math.abs(moveYaw) > limit) moveYaw = desiredSignYaw * limit;
        }

        float absDeltaPitch = Math.abs(deltaPitch);
        float desiredSignPitch = -Math.signum(deltaPitch);
        if (desiredSignPitch != 0.0F && Math.signum(movePitch) != 0.0F && Math.signum(movePitch) != desiredSignPitch) {
            movePitch = MathHelper.clamp_float(convergePitch, -maxMove, maxMove);
            this.smoothMovePitch *= 0.35F;
        }
        if (desiredSignPitch != 0.0F && Math.signum(movePitch) == desiredSignPitch && absDeltaPitch > 0.5F) {
            float limit = absDeltaPitch * 0.9F;
            if (Math.abs(movePitch) > limit) movePitch = desiredSignPitch * limit;
        }

        if (absDeltaYaw < 0.5F) {
            moveYaw *= 0.3F;
            this.smoothMoveYaw *= 0.5F;
        }
        if (absDeltaPitch < 0.5F) {
            movePitch *= 0.3F;
            this.smoothMovePitch *= 0.5F;
        }

        this.smoothMoveYaw = this.smoothMoveYaw * smooth + moveYaw * (1.0F - smooth);
        this.smoothMovePitch = this.smoothMovePitch * smooth + movePitch * (1.0F - smooth);
        this.smoothMoveYaw *= 0.92F;
        this.smoothMovePitch *= 0.92F;
        if (desiredSignYaw != 0.0F && Math.signum(this.smoothMoveYaw) != 0.0F && Math.signum(this.smoothMoveYaw) != desiredSignYaw) {
            this.smoothMoveYaw = 0.0F;
        } else if (desiredSignYaw != 0.0F && absDeltaYaw > 0.5F) {
            this.smoothMoveYaw = MathHelper.clamp_float(this.smoothMoveYaw, -absDeltaYaw * 0.9F, absDeltaYaw * 0.9F);
        }
        if (desiredSignPitch != 0.0F && Math.signum(this.smoothMovePitch) != 0.0F && Math.signum(this.smoothMovePitch) != desiredSignPitch) {
            this.smoothMovePitch = 0.0F;
        } else if (desiredSignPitch != 0.0F && absDeltaPitch > 0.5F) {
            this.smoothMovePitch = MathHelper.clamp_float(this.smoothMovePitch, -absDeltaPitch * 0.9F, absDeltaPitch * 0.9F);
        }

        float finalYaw = RotationUtil.quantizeAngle(currentYaw + this.smoothMoveYaw);
        float finalPitch = RotationUtil.quantizeAngle(MathHelper.clamp_float(currentPitch + this.smoothMovePitch, -90.0F, 90.0F));
        this.prevDeltaYaw = deltaYaw;
        this.prevDeltaPitch = deltaPitch;
        this.prevTargetYaw = targetYaw;
        this.prevTargetPitch = targetPitch;
        this.hasPrev = true;
        this.combatTick++;
        return new float[]{finalYaw, finalPitch};
    }

    private void resetTargetState(int targetId) {
        this.combatTick = 0;
        this.prevDeltaYaw = 0.0F;
        this.prevDeltaPitch = 0.0F;
        Arrays.fill(this.hiddenState, 0.0F);
        this.smoothMoveYaw = 0.0F;
        this.smoothMovePitch = 0.0F;
        this.hasPrev = false;
        this.lastTargetId = targetId;
    }

    /** Forget the current target (new target, mode switched, aura toggled). */
    public void reset() {
        this.hasPrev = false;
        Arrays.fill(this.hiddenState, 0.0F);
        this.prevDeltaYaw = 0.0F;
        this.prevDeltaPitch = 0.0F;
        this.prevTargetYaw = 0.0F;
        this.prevTargetPitch = 0.0F;
        this.smoothMoveYaw = 0.0F;
        this.smoothMovePitch = 0.0F;
        this.combatTick = 0;
        this.lastTargetId = -1;
    }

    // ---------------------------------------------------------------- GRU (PyTorch gate order: reset, update, new)

    private float[] forwardGru(float[] input) {
        int h = this.hiddenSize;
        if (this.hiddenState.length != h) this.hiddenState = new float[h];
        float[] nextHidden = new float[h];
        for (int i = 0; i < h; i++) {
            float r = sigmoid(dot(this.weightIH[i], input) + this.biasIH[i] + dot(this.weightHH[i], this.hiddenState) + this.biasHH[i]);
            float z = sigmoid(dot(this.weightIH[h + i], input) + this.biasIH[h + i] + dot(this.weightHH[h + i], this.hiddenState) + this.biasHH[h + i]);
            float nInput = dot(this.weightIH[h * 2 + i], input) + this.biasIH[h * 2 + i];
            float nHidden = dot(this.weightHH[h * 2 + i], this.hiddenState) + this.biasHH[h * 2 + i];
            float n = (float) Math.tanh(nInput + r * nHidden);
            nextHidden[i] = (1.0F - z) * n + z * this.hiddenState[i];
        }
        this.hiddenState = nextHidden;
        float[] output = new float[OUTPUT_SIZE];
        for (int i = 0; i < OUTPUT_SIZE; i++) {
            float sum = this.outputBias[i];
            for (int j = 0; j < h; j++) sum += this.outputWeight[i][j] * this.hiddenState[j];
            output[i] = sum;
        }
        return output;
    }

    private static float dot(float[] weights, float[] values) {
        float sum = 0.0F;
        int length = Math.min(weights.length, values.length);
        for (int i = 0; i < length; i++) sum += weights[i] * values[i];
        return sum;
    }

    private static float sigmoid(float value) {
        return 1.0F / (1.0F + (float) Math.exp(-value));
    }
}
