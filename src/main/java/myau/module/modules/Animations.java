package myau.module.modules;

import com.google.common.base.CaseFormat;
import myau.Myau;
import myau.module.Module;
import myau.property.properties.BooleanProperty;
import myau.property.properties.FloatProperty;
import myau.property.properties.IntProperty;
import myau.property.properties.ModeProperty;
import net.minecraft.client.renderer.GlStateManager;
import net.minecraft.util.MathHelper;

/**
 * Sword block / swing animations for the first person view.
 * <p>
 * {@code MixinItemRenderer} hands the BLOCK branch of {@code ItemRenderer.renderItemInFirstPerson}
 * to {@link #applyBlock(float, float)}, calls {@link #applyHand()} once per frame right after the
 * hand matrix is pushed, and asks {@link #skipSwingOffset()} before the normal swing offset.
 * {@code MixinEntityLivingBaseAnimations} reads {@link #swingDuration(int)}.
 */
public class Animations extends Module {
    private static final int ONE_SEVEN = 0, OLD_PUSHDOWN = 1, NEW_PUSHDOWN = 2, OLD = 3, HELIUM = 4, ARGON = 5,
            CESIUM = 6, SULFUR = 7, SPIN = 8, MODEL_SPIN = 9, ASTOLFO = 10, ETHEREAL = 11, EXHIBITION = 12,
            INTERIA = 13, LEAKED = 14, MOON = 15, OLD_EXHIBITION = 16, DOT_SEVEN = 17, PUNCH = 18, PUSH = 19,
            SIGMA = 20, SLIDE = 21, SMALL = 22, SMOOTH = 23, SPINNING = 24, STELLA = 25, STYLES = 26, SWAING = 27,
            SWANK = 28, SWANG = 29, SWING = 30, SWONG = 31, XINXIN = 32;
    private static final String[] MODES = new String[]{
            "ONE_SEVEN", "OLD_PUSHDOWN", "NEW_PUSHDOWN", "OLD", "HELIUM", "ARGON", "CESIUM", "SULFUR", "SPIN",
            "MODEL_SPIN", "ASTOLFO", "ETHEREAL", "EXHIBITION", "INTERIA", "LEAKED", "MOON", "OLD_EXHIBITION", "1.7",
            "PUNCH", "PUSH", "SIGMA", "SLIDE", "SMALL", "SMOOTH", "SPINNING", "STELLA", "STYLES", "SWAING", "SWANK",
            "SWANG", "SWING", "SWONG", "XINXIN"
    };
    private static final float PI = 3.1415927F;
    /** Longest frame gap the spinning modes advance by, so a pause does not show up as a jump. */
    private static final float MAX_FRAME = 0.1F;

    public final ModeProperty mode = new ModeProperty("mode", NEW_PUSHDOWN, MODES);
    public final BooleanProperty oddSwing = new BooleanProperty("odd-swing", false);
    public final IntProperty swingSpeed = new IntProperty("swing-speed", 15, 0, 20);
    public final BooleanProperty cancelEquip = new BooleanProperty("cancel-equip", false, () -> this.mode.getValue() == SPIN);
    public final FloatProperty scale = new FloatProperty("scale", 0.0F, -5.0F, 5.0F, () -> this.mode.getValue() == SPIN);
    public final IntProperty spinSpeed = new IntProperty("spin-speed", 72, 1, 360, () -> this.mode.getValue() == MODEL_SPIN);
    public final BooleanProperty autoCenter = new BooleanProperty("auto-center", true, () -> this.mode.getValue() == MODEL_SPIN);
    public final FloatProperty centerX = new FloatProperty("center-x", 0.0F, -2.0F, 2.0F, () -> this.mode.getValue() == MODEL_SPIN && !this.autoCenter.getValue());
    public final FloatProperty centerY = new FloatProperty("center-y", -0.4F, -2.0F, 2.0F, () -> this.mode.getValue() == MODEL_SPIN && !this.autoCenter.getValue());
    public final FloatProperty centerZ = new FloatProperty("center-z", 0.0F, -2.0F, 2.0F, () -> this.mode.getValue() == MODEL_SPIN && !this.autoCenter.getValue());
    public final BooleanProperty rotation = new BooleanProperty("rotation", false, () -> this.mode.getValue() == XINXIN);
    public final IntProperty rotationSpeed = new IntProperty("rotation-speed", 2, 1, 10, () -> this.mode.getValue() == XINXIN && this.rotation.getValue());
    public final FloatProperty xinxinScale = new FloatProperty("xinxin-scale", 1.0F, 0.1F, 2.0F, () -> this.mode.getValue() == XINXIN);
    // Applied to the whole hand (item, map and bare arm) every frame.
    public final FloatProperty itemScale = new FloatProperty("item-scale", 0.0F, -5.0F, 5.0F);
    public final FloatProperty x = new FloatProperty("x", 0.0F, -5.0F, 5.0F);
    public final FloatProperty y = new FloatProperty("y", 0.0F, -5.0F, 5.0F);
    public final FloatProperty positionRotationX = new FloatProperty("position-rotation-x", 0.0F, -50.0F, 50.0F);
    public final FloatProperty positionRotationY = new FloatProperty("position-rotation-y", 0.0F, -50.0F, 50.0F);
    public final FloatProperty positionRotationZ = new FloatProperty("position-rotation-z", 0.0F, -50.0F, 50.0F);

    private long lastFrame;
    private float spinAngle;
    private float modelSpinAngle;
    private float spinningAngle;
    private float xinxinAngle;

    public Animations() {
        super("Animations", false);
    }

    /** The module, or null before the client has started or while it is not registered. */
    public static Animations instance() {
        return Myau.moduleManager == null ? null : (Animations) Myau.moduleManager.modules.get(Animations.class);
    }

    /** Offset and rotation of the whole first person hand. */
    public void applyHand() {
        GlStateManager.translate(this.x.getValue(), this.y.getValue(), this.itemScale.getValue());
        rotate(this.positionRotationX.getValue(), 1.0F, 0.0F, 0.0F);
        rotate(this.positionRotationY.getValue(), 0.0F, 1.0F, 0.0F);
        rotate(this.positionRotationZ.getValue(), 0.0F, 0.0F, 1.0F);
    }

    /** True when the swing must not move the item sideways (only the swing rotation is left). */
    public boolean skipSwingOffset() {
        return this.oddSwing.getValue();
    }

    /** Length of the local player's arm swing in ticks; {@code base} is the vanilla 6. */
    public int swingDuration(int base) {
        return 2 + (20 - this.swingSpeed.getValue());
    }

    /**
     * Replaces vanilla's {@code transformFirstPersonItem(equip, 0)} + {@code doBlockTransformations()}
     * for a held item that is being used to block.
     *
     * @param equip 0 when the item is fully raised, 1 when it is fully lowered
     * @param swing swing progress of the arm, 0..1
     */
    public void applyBlock(float equip, float swing) {
        float frame = this.frameTime();
        float root = MathHelper.sin(MathHelper.sqrt_float(swing) * PI);
        float square = MathHelper.sin(swing * swing * PI);
        switch (this.mode.getValue()) {
            case ONE_SEVEN:
                item(equip, swing);
                block();
                GlStateManager.translate(-0.5F, 0.2F, 0.0F);
                break;
            case OLD_PUSHDOWN:
                GlStateManager.translate(0.56, -0.52, -0.5);
                GlStateManager.translate(0.0, -equip * 0.3, 0.0);
                rotate(45.5F, 0.0F, 1.0F, 0.0F);
                GlStateManager.scale(0.32, 0.32, 0.32);
                rotate(-root * 125.0F / 1.75F, 3.95F, 0.35F, 8.0F);
                rotate(-root * 35.0F, 0.0F, root / 100.0F, -10.0F);
                GlStateManager.translate(-1.0, 0.6, 0.0);
                rotate(30.0F, 0.0F, 1.0F, 0.0F);
                rotate(-80.0F, 1.0F, 0.0F, 0.0F);
                rotate(60.0F, 0.0F, 1.0F, 0.0F);
                GlStateManager.translate(1.05, 0.35, 0.4);
                break;
            case NEW_PUSHDOWN:
                GlStateManager.translate(-0.08, 0.12, 0.0);
                item(equip / 1.4F, 0.0F);
                rotate(-root * 65.0F / 2.0F, root / 2.0F, 1.0F, 4.0F);
                rotate(-root * 60.0F, 1.0F, root / 3.0F, 0.0F);
                block();
                break;
            case OLD:
            case DOT_SEVEN:
                item(equip, swing);
                block();
                break;
            case HELIUM:
                item(equip, 0.0F);
                rotate(-root * 55.0F, 30.0F, MathHelper.sin(swing * equip * PI) / 5.0F, 0.0F);
                block();
                break;
            case ARGON: {
                item(equip / 2.5F, swing);
                float cos = MathHelper.cos(MathHelper.sqrt_float(equip) * PI);
                rotate(cos * 50.0F / 10.0F, -root, 0.0F, 100.0F);
                rotate(root * 50.0F, 200.0F, -root / 2.0F, 0.0F);
                GlStateManager.translate(0.0, 0.3, 0.0);
                block();
                break;
            }
            case CESIUM:
                item(equip, 0.0F);
                rotate(-root * 10.0F / 20.0F, root / 2.0F, 0.0F, 4.0F);
                rotate(-root * 30.0F, 0.0F, root / 3.0F, 0.0F);
                rotate(-root * 10.0F, 1.0F, root / 10.0F, 0.0F);
                GlStateManager.translate(0.0, 0.2, 0.0);
                block();
                break;
            case SULFUR: {
                float cos = MathHelper.cos(MathHelper.sqrt_float(swing) * PI);
                item(equip, 0.0F);
                rotate(-root * 30.0F, root / 10.0F, cos / 10.0F, 0.0F);
                GlStateManager.translate(root / 1.5, 0.2, 0.0);
                block();
                break;
            }
            case SPIN: {
                // One turn every 850 ms around the view axis.
                rotate(this.spinAngle, 0.0F, 0.0F, -0.1F);
                item(this.cancelEquip.getValue() ? 0.0F : equip / 1.5F, 0.0F);
                this.spinAngle = (this.spinAngle + frame * 360.0F / 0.85F) % 360.0F;
                block();
                float size = this.scale.getValue() + 1.0F;
                GlStateManager.scale(size, size, size);
                break;
            }
            case MODEL_SPIN: {
                boolean auto = this.autoCenter.getValue();
                double cx = auto ? 0.0 : this.centerX.getValue();
                double cy = auto ? -0.4 : this.centerY.getValue();
                double cz = auto ? 0.0 : this.centerZ.getValue();
                GlStateManager.translate(cx, cy, cz);
                rotate(this.modelSpinAngle, 0.0F, 1.0F, 0.0F);
                GlStateManager.translate(-cx, -cy, -cz);
                item(equip, swing);
                block();
                // spin-speed is degrees per second.
                this.modelSpinAngle = (this.modelSpinAngle + frame * this.spinSpeed.getValue()) % 360.0F;
                break;
            }
            case ASTOLFO:
                item(0.0F, 0.0F);
                GlStateManager.translate(-0.08F, 0.12F, 0.0F);
                rotate(-root * 58.0F / 2.0F, root / 2.0F, 1.0F, 0.5F);
                rotate(-root * 43.0F, 1.0F, root / 3.0F, 0.0F);
                block();
                break;
            case ETHEREAL:
                item(equip, 0.0F);
                block();
                GlStateManager.translate(-0.05F, 0.2F, 0.2F);
                rotate(-square * 70.0F / 2.0F, -8.0F, 0.0F, 9.0F);
                rotate(-square * 70.0F, 1.0F, -0.4F, 0.0F);
                break;
            case EXHIBITION:
                GlStateManager.translate(-0.03, root * 0.062F, 0.0);
                GlStateManager.translate(0.025, 0.09615, 0.0);
                item(equip / 3.0F, 0.0F);
                rotate(-root * 9.0F, -root / 20.0F, -root / 20.0F, 1.0F);
                rotate(-root * 55.0F, 1.2F, root / 4.0F, 0.36F);
                if (mc.thePlayer != null && mc.thePlayer.isSneaking()) {
                    GlStateManager.translate(-0.05, -0.05, 0.0);
                }
                block();
                break;
            case INTERIA:
                item(0.05F, swing);
                GlStateManager.translate(-0.5F, 0.5F, 0.0F);
                rotate(30.0F, 0.0F, 1.0F, 0.0F);
                rotate(-80.0F, 1.0F, 0.0F, 0.0F);
                rotate(60.0F, 0.0F, 1.0F, 0.0F);
                break;
            case LEAKED:
                item(0.0F, 0.0F);
                GlStateManager.translate(0.08F, 0.02F, 0.0F);
                block();
                rotate(-root * 41.0F, 1.1F, 0.8F, -0.3F);
                break;
            case MOON:
                item(0.0F, 0.0F);
                GlStateManager.translate(-0.08F, 0.12F, 0.0F);
                rotate(-root * 65.0F / 2.0F, root / 2.0F, 1.0F, 4.0F);
                rotate(-root * 60.0F, 1.0F, root / 3.0F, 0.0F);
                block();
                break;
            case OLD_EXHIBITION:
                GlStateManager.translate(-0.04, 0.13, 0.0);
                item(equip / 2.5F, 0.0F);
                rotate(-square * 40.0F / 2.0F, square / 2.0F, 1.0F, 4.0F);
                rotate(-square * 30.0F, 1.0F, square / 3.0F, 0.0F);
                block();
                break;
            case PUNCH:
                item(equip, 0.0F);
                block();
                GlStateManager.translate(0.1F, 0.2F, 0.3F);
                rotate(-square * 30.0F, -5.0F, 0.0F, 9.0F);
                rotate(-square * 10.0F, 1.0F, -0.4F, -0.5F);
                break;
            case PUSH:
                // The item drops with the swing instead of with the equip progress.
                GlStateManager.translate(0.56F, -0.52F, -0.71999997F);
                GlStateManager.translate(0.0F, swing * -0.6F, 0.0F);
                rotate(45.0F, 0.0F, 1.0F, 0.0F);
                rotate(square * -20.0F, 0.0F, 1.0F, 0.0F);
                rotate(root * -20.0F, 0.0F, 0.0F, 1.0F);
                GlStateManager.scale(0.4F, 0.4F, 0.4F);
                block();
                break;
            case SIGMA:
                item(equip * 0.5F, 0.0F);
                rotate(-square * 55.0F / 2.0F, -8.0F, 0.0F, 9.0F);
                rotate(-square * 45.0F, 1.0F, square / 2.0F, 0.0F);
                block();
                GlStateManager.translate(1.2, 0.3, 0.5);
                break;
            case SLIDE:
                item(equip, 0.0F);
                block();
                GlStateManager.translate(-0.4F, 0.3F, 0.0F);
                rotate(-square * 35.0F, -8.0F, 0.0F, 9.0F);
                rotate(-square * 70.0F, 1.0F, -0.4F, 0.0F);
                break;
            case SMALL:
                GlStateManager.translate(-0.01F, 0.03F, -0.24F);
                item(0.0F, swing);
                block();
                break;
            case SMOOTH:
                item(equip / 1.5F, 0.0F);
                block();
                GlStateManager.translate(-0.05F, 0.3F, 0.3F);
                rotate(-square * 140.0F, 8.0F, 0.0F, 8.0F);
                rotate(square * 90.0F, 8.0F, 0.0F, 8.0F);
                break;
            case SPINNING:
                // 300 degrees per second around the blade.
                this.spinningAngle = (this.spinningAngle + frame * 300.0F) % 360.0F;
                GlStateManager.translate(-0.04, 0.1, 0.0);
                item(equip / 2.5F, 0.0F);
                rotate(-90.0F, 1.0F, 0.0F, 0.2F);
                rotate(this.spinningAngle, 0.0F, -1.0F, 0.0F);
                break;
            case STELLA:
                item(-0.1F, swing);
                GlStateManager.translate(-0.5F, 0.4F, -0.2F);
                rotate(30.0F, 0.0F, 1.0F, 0.0F);
                rotate(-70.0F, 1.0F, 0.0F, 0.0F);
                rotate(40.0F, 0.0F, 1.0F, 0.0F);
                break;
            case STYLES:
                item(equip, 0.0F);
                block();
                GlStateManager.translate(-0.05F, 0.2F, 0.0F);
                rotate(-root * 70.0F / 2.0F, -8.0F, 0.0F, 9.0F);
                rotate(-root * 70.0F, 1.0F, -0.4F, 0.0F);
                break;
            case SWAING:
                item(equip / 2.0F, -0.2F);
                rotate(-square / 19.0F, square / 20.0F, 0.0F, 9.0F);
                rotate(-square * 30.0F, 10.0F, square / 50.0F, 0.0F);
                GlStateManager.translate(-0.5F, 0.4F, 0.0F);
                rotate(30.0F, 0.0F, 1.0F, 0.0F);
                rotate(-80.0F, 1.0F, 0.0F, 0.0F);
                rotate(60.0F, 0.0F, 1.0F, 0.0F);
                break;
            case SWANK:
                item(equip / 2.0F, swing);
                rotate(root * 30.0F, -root, 0.0F, 9.0F);
                rotate(root * 40.0F, 1.0F, -root, 0.0F);
                GlStateManager.translate(-0.5F, 0.4F, 0.0F);
                rotate(30.0F, 0.0F, 1.0F, 0.0F);
                rotate(-80.0F, 1.0F, 0.0F, 0.0F);
                rotate(60.0F, 0.0F, 1.0F, 0.0F);
                break;
            case SWANG:
                sway(equip, square, -0.1F, 20.0F);
                break;
            case SWING:
                sway(equip, square, -0.2F, 40.0F);
                break;
            case SWONG:
                sway(equip, square, -0.3F, 60.0F);
                break;
            case XINXIN: {
                if (this.rotation.getValue()) {
                    // rotation-speed is degrees per 16 ms.
                    this.xinxinAngle = (this.xinxinAngle + frame * this.rotationSpeed.getValue() * 62.5F) % 360.0F;
                    rotate(this.xinxinAngle, 0.0F, 1.0F, 0.0F);
                }
                GlStateManager.translate(0.0, 0.1, 0.0);
                item(equip / 2.0F, 0.0F);
                float size = this.xinxinScale.getValue();
                GlStateManager.scale(size, size, size);
                block();
                break;
            }
            default:
                // Unknown mode: vanilla 1.8 blocking.
                item(equip, 0.0F);
                block();
        }
    }

    /** SWANG / SWING / SWONG: the blocked sword swings out and half way back around its diagonal. */
    private static void sway(float equip, float square, float offset, float angle) {
        item(equip, 0.0F);
        block();
        GlStateManager.translate(offset, 0.2F, 0.0F);
        rotate(-square * angle, 8.0F, 0.0F, 8.0F);
        rotate(square * angle / 2.0F, 8.0F, 0.0F, 8.0F);
    }

    /** Seconds since the previous blocking frame, capped at {@link #MAX_FRAME}. */
    private float frameTime() {
        long now = System.nanoTime();
        float frame = this.lastFrame == 0L ? 0.0F : (now - this.lastFrame) / 1.0E9F;
        this.lastFrame = now;
        return frame < 0.0F ? 0.0F : Math.min(frame, MAX_FRAME);
    }

    /** Same as vanilla {@code ItemRenderer.transformFirstPersonItem}. */
    private static void item(float equip, float swing) {
        GlStateManager.translate(0.56F, -0.52F, -0.71999997F);
        GlStateManager.translate(0.0F, equip * -0.6F, 0.0F);
        GlStateManager.rotate(45.0F, 0.0F, 1.0F, 0.0F);
        float square = MathHelper.sin(swing * swing * PI);
        float root = MathHelper.sin(MathHelper.sqrt_float(swing) * PI);
        GlStateManager.rotate(square * -20.0F, 0.0F, 1.0F, 0.0F);
        GlStateManager.rotate(root * -20.0F, 0.0F, 0.0F, 1.0F);
        GlStateManager.rotate(root * -80.0F, 1.0F, 0.0F, 0.0F);
        GlStateManager.scale(0.4F, 0.4F, 0.4F);
    }

    /** Same as vanilla {@code ItemRenderer.doBlockTransformations}. */
    private static void block() {
        GlStateManager.translate(-0.5F, 0.2F, 0.0F);
        GlStateManager.rotate(30.0F, 0.0F, 1.0F, 0.0F);
        GlStateManager.rotate(-80.0F, 1.0F, 0.0F, 0.0F);
        GlStateManager.rotate(60.0F, 0.0F, 1.0F, 0.0F);
    }

    /** A rotation by nothing is skipped, which also keeps a zero-length axis away from the driver. */
    private static void rotate(float angle, float x, float y, float z) {
        if (angle != 0.0F) {
            GlStateManager.rotate(angle, x, y, z);
        }
    }

    @Override
    public String[] getSuffix() {
        String name = this.mode.getModeString();
        return new String[]{name.indexOf('.') >= 0 ? name : CaseFormat.UPPER_UNDERSCORE.to(CaseFormat.UPPER_CAMEL, name)};
    }
}
