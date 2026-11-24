package myau.module.modules;

import myau.Myau;
import myau.event.EventTarget;
import myau.event.types.EventType;
import myau.events.AttackEvent;
import myau.events.PacketEvent;
import myau.module.Module;
import myau.property.properties.BooleanProperty;
import myau.property.properties.ColorProperty;
import myau.property.properties.FloatProperty;
import myau.property.properties.ModeProperty;
import myau.property.properties.PercentProperty;
import myau.ui.GlassTheme;
import myau.ui.glass.Anim;
import myau.ui.glass.Draw;
import myau.ui.glass.GlState;
import myau.ui.glass.UiSound;
import myau.util.MoveUtil;
import myau.util.TeamUtil;
import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityLivingBase;
import net.minecraft.entity.item.EntityArmorStand;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.inventory.IInventory;
import net.minecraft.item.ItemBow;
import net.minecraft.item.ItemStack;
import net.minecraft.network.play.server.S19PacketEntityStatus;
import net.minecraft.util.MovingObjectPosition;

/**
 * Replaces the vanilla crosshair with a drawn one: four shapes, a spread that follows movement
 * and attacks, a tint while an enemy is under it, a hit marker and a bow charge arc.
 * Drawn from {@code MixinGuiIngameForge} in the vanilla crosshair's own layer.
 */
public class Crosshair extends Module {
    private static final int DOT_RING = 0, CROSS = 1, RING = 2, T = 3;
    private static final int SHADE = 0x000000;

    public final ModeProperty style = new ModeProperty("style", DOT_RING, new String[]{"DOT_RING", "CROSS", "RING", "T"});
    public final FloatProperty size = new FloatProperty("size", 4.5F, 2.0F, 9.0F);
    public final FloatProperty gap = new FloatProperty("gap", 2.5F, 0.0F, 8.0F, () -> this.style.getValue() == CROSS || this.style.getValue() == T);
    public final FloatProperty thickness = new FloatProperty("thickness", 1.0F, 0.5F, 3.0F);
    public final BooleanProperty dot = new BooleanProperty("dot", true, () -> this.style.getValue() != DOT_RING);
    public final BooleanProperty outline = new BooleanProperty("outline", true);
    public final PercentProperty opacity = new PercentProperty("opacity", 92);
    public final ModeProperty colorMode = new ModeProperty("color", 0, new String[]{"WHITE", "HUD", "CUSTOM"});
    public final ColorProperty customColor = new ColorProperty("custom-color", 0xFFFFFF, () -> this.colorMode.getValue() == 2);
    // Spread follows movement, jumping and attacks
    public final BooleanProperty dynamic = new BooleanProperty("dynamic", true);
    public final BooleanProperty targetColor = new BooleanProperty("target-color", true);
    public final ColorProperty targetTint = new ColorProperty("target-tint", 0xFF453A, () -> this.targetColor.getValue());
    public final BooleanProperty hitMarker = new BooleanProperty("hit-marker", true);
    public final BooleanProperty hitSound = new BooleanProperty("hit-sound", false, () -> this.hitMarker.getValue());
    public final BooleanProperty bowCharge = new BooleanProperty("bow-charge", true);
    public final BooleanProperty thirdPerson = new BooleanProperty("third-person", true);

    private final Anim.Spring spread = Anim.spring(0, 0.22f, 0.62f);
    private final Anim.Spring hitPop = Anim.spring(0, 0.2f, 0.5f);
    private final Anim.Smooth tint = new Anim.Smooth(0, 16);
    private final Anim.Smooth charge = new Anim.Smooth(0, 20);
    private float hitShow;

    // Written from the network thread, read while drawing.
    private volatile int attackedId = -1;
    private volatile long attackedAt;
    private volatile boolean hitPending;

    public Crosshair() {
        super("Crosshair", true, true);
    }

    /** The module, or null before the client has started. */
    public static Crosshair instance() {
        return Myau.moduleManager == null ? null : (Crosshair) Myau.moduleManager.modules.get(Crosshair.class);
    }

    @EventTarget
    public void onAttack(AttackEvent event) {
        if (!this.isEnabled() || event.getTarget() == null) return;
        attackedId = event.getTarget().getEntityId();
        attackedAt = System.nanoTime();
        if (this.dynamic.getValue()) spread.kick(38);
    }

    @EventTarget
    public void onPacket(PacketEvent event) {
        if (!this.isEnabled() || event.getType() != EventType.RECEIVE || !(event.getPacket() instanceof S19PacketEntityStatus)) return;
        S19PacketEntityStatus status = (S19PacketEntityStatus) event.getPacket();
        if (status.getOpCode() != 2 || mc.theWorld == null) return;
        Entity entity = status.getEntity(mc.theWorld);
        // The server confirming damage on what was just attacked.
        if (entity != null && entity.getEntityId() == attackedId && System.nanoTime() - attackedAt < 700_000_000L) hitPending = true;
    }

    /** Same rule as the vanilla crosshair: hidden on the debug screen, and for spectators unless they point at something. */
    private boolean vanillaVisible() {
        if (mc.gameSettings.showDebugInfo && !mc.thePlayer.hasReducedDebug() && !mc.gameSettings.reducedDebugInfo) return false;
        if (!mc.playerController.isSpectator()) return true;
        if (mc.pointedEntity != null) return true;
        MovingObjectPosition over = mc.objectMouseOver;
        return over != null && over.typeOfHit == MovingObjectPosition.MovingObjectType.BLOCK
                && mc.theWorld.getTileEntity(over.getBlockPos()) instanceof IInventory;
    }

    private boolean aimingAtEnemy() {
        MovingObjectPosition over = mc.objectMouseOver;
        if (over != null && over.typeOfHit == MovingObjectPosition.MovingObjectType.ENTITY) {
            Entity entity = over.entityHit;
            if (entity instanceof EntityLivingBase && !(entity instanceof EntityArmorStand) && entity.isEntityAlive()
                    && !(entity instanceof EntityPlayer && TeamUtil.isFriend((EntityPlayer) entity))) return true;
        }
        KillAura aura = (KillAura) Myau.moduleManager.modules.get(KillAura.class);
        return aura != null && aura.isEnabled() && aura.isAttackAllowed() && aura.getTarget() != null;
    }

    /** How far the bow is drawn, 0..1, or 0 when no bow is in use. */
    private float bowPower() {
        if (!mc.thePlayer.isUsingItem()) return 0;
        ItemStack using = mc.thePlayer.getItemInUse();
        if (using == null || !(using.getItem() instanceof ItemBow)) return 0;
        float t = mc.thePlayer.getItemInUseDuration() / 20f;
        return Math.min(1, (t * t + t * 2) / 3);
    }

    /** Draw the crosshair. @return true when the vanilla one must not be drawn */
    public boolean render() {
        if (mc.thePlayer == null || mc.theWorld == null) return false;
        if (!vanillaVisible()) return true;
        if (!this.thirdPerson.getValue() && mc.gameSettings.thirdPersonView != 0) return true;

        GlState.hudDefaults();
        Draw.useGuiScale();
        float dt = Anim.dt();
        float cx = mc.displayWidth / Draw.scale() / 2f, cy = mc.displayHeight / Draw.scale() / 2f;

        boolean aiming = this.targetColor.getValue() && aimingAtEnemy();
        float power = this.bowCharge.getValue() ? charge.update(bowPower(), dt) : 0;
        float target = 0;
        if (this.dynamic.getValue()) {
            target += (float) Math.min(1, MoveUtil.getSpeed() / 0.28) * 1.5f;
            if (!mc.thePlayer.onGround) target += 1.1f;
            if (mc.thePlayer.isSneaking()) target -= 0.5f;
            if (aiming) target -= 0.7f;
            target -= power * 1.2f;
        }
        float open = Math.max(-1.2f, spread.update(target, dt));

        if (hitPending) {
            hitPending = false;
            if (this.hitMarker.getValue()) {
                hitShow = 1;
                hitPop.snap(0);
                hitPop.kick(34);
                if (this.hitSound.getValue()) UiSound.HIT.play();
            }
        }
        hitPop.update(0, dt);
        hitShow = Anim.reduced() ? 0 : Math.max(0, hitShow - dt / 0.32f);

        int base;
        switch (this.colorMode.getValue()) {
            case 1:
                base = GlassTheme.accent() & 0xffffff;
                break;
            case 2:
                base = this.customColor.getValue() & 0xffffff;
                break;
            default:
                base = 0xffffff;
        }
        float tinted = tint.update(aiming, dt);
        int color = 0xff000000 | (Draw.mix(0xff000000 | base, 0xff000000 | (this.targetTint.getValue() & 0xffffff), tinted) & 0xffffff);
        int shade = Draw.alpha(SHADE, 0.5f);
        boolean edge = this.outline.getValue();
        float thick = this.thickness.getValue(), length = this.size.getValue();
        float outer;

        Draw.pushAlpha(this.opacity.getValue() / 100f);
        int shape = this.style.getValue();
        if (shape == DOT_RING || shape == RING) {
            float radius = Math.max(1.5f, (shape == RING ? length * 0.8f : length) + open);
            ring(cx, cy, radius, thick, color, edge ? shade : 0);
            if (shape == DOT_RING || this.dot.getValue()) dot(cx, cy, Math.max(0.7f, thick * 0.85f), color, edge ? shade : 0);
            outer = radius;
        } else {
            float space = Math.max(0.5f, this.gap.getValue() + open);
            if (shape == CROSS) bar(cx - thick / 2, cy - space - length, thick, length, color, edge ? shade : 0);
            bar(cx - thick / 2, cy + space, thick, length, color, edge ? shade : 0);
            bar(cx - space - length, cy - thick / 2, length, thick, color, edge ? shade : 0);
            bar(cx + space, cy - thick / 2, length, thick, color, edge ? shade : 0);
            if (this.dot.getValue()) dot(cx, cy, Math.max(0.6f, thick * 0.6f), color, edge ? shade : 0);
            outer = space + length;
        }

        if (power > 0.02f) arc(cx, cy, outer + 2.5f, power, power >= 0.99f ? GlassTheme.OK : Draw.alpha(base, 0.9f));
        if (hitShow > 0.01f) {
            Draw.pushAlpha(Math.min(1, hitShow * 1.6f));
            float from = Math.max(2.5f, outer * 0.55f) + hitPop.value, to = from + 3.2f;
            for (int i = 0; i < 4; i++) {
                float dx = i % 2 == 0 ? 0.7071f : -0.7071f, dy = i < 2 ? 0.7071f : -0.7071f;
                if (edge) Draw.polyline(thick + 1, shade, cx + dx * from, cy + dy * from, cx + dx * to, cy + dy * to);
                Draw.polyline(thick, 0xffffffff, cx + dx * from, cy + dy * from, cx + dx * to, cy + dy * to);
            }
            Draw.popAlpha();
        }
        Draw.popAlpha();
        GlState.hudDefaults();
        return true;
    }

    private static void ring(float cx, float cy, float radius, float thick, int color, int shade) {
        if (shade != 0) {
            float r = radius + 0.5f;
            Draw.outline(cx - r, cy - r, r * 2, r * 2, r, thick + 1, shade, shade);
        }
        Draw.outline(cx - radius, cy - radius, radius * 2, radius * 2, radius, thick, color, color);
    }

    private static void dot(float cx, float cy, float radius, int color, int shade) {
        if (shade != 0) Draw.circle(cx, cy, radius + 0.5f, shade);
        Draw.circle(cx, cy, radius, color);
    }

    /** A rounded bar; the shade is the same bar grown by half a unit on every side. */
    private static void bar(float x, float y, float w, float h, int color, int shade) {
        float r = Math.min(w, h) / 2;
        if (shade != 0) Draw.rect(x - 0.5f, y - 0.5f, w + 1, h + 1, r + 0.5f, shade);
        Draw.rect(x, y, w, h, r, color);
    }

    /** Part of a circle starting at the top and running clockwise; {@code amount} 1 closes it. */
    private static void arc(float cx, float cy, float radius, float amount, int color) {
        int segments = Math.max(2, Math.round(36 * amount));
        float[] points = new float[(segments + 1) * 2];
        for (int i = 0; i <= segments; i++) {
            double angle = -Math.PI / 2 + Math.PI * 2 * amount * i / segments;
            points[i * 2] = cx + (float) Math.cos(angle) * radius;
            points[i * 2 + 1] = cy + (float) Math.sin(angle) * radius;
        }
        Draw.polyline(1.2f, color, points);
    }
}
