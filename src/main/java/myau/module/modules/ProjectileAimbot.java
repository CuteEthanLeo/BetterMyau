package myau.module.modules;

import myau.Myau;
import myau.event.EventTarget;
import myau.event.types.EventType;
import myau.events.LoadWorldEvent;
import myau.events.Render3DEvent;
import myau.events.UpdateEvent;
import myau.mixin.IAccessorRenderManager;
import myau.module.Module;
import myau.property.properties.BooleanProperty;
import myau.property.properties.FloatProperty;
import myau.property.properties.ModeProperty;
import myau.util.RenderUtil;
import myau.util.RotationUtil;
import myau.util.TargetUtil;
import net.minecraft.client.Minecraft;
import net.minecraft.client.entity.EntityPlayerSP;
import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityLivingBase;
import net.minecraft.item.Item;
import net.minecraft.item.ItemBow;
import net.minecraft.item.ItemEgg;
import net.minecraft.item.ItemEnderPearl;
import net.minecraft.item.ItemSnowball;
import net.minecraft.item.ItemStack;
import net.minecraft.util.AxisAlignedBB;
import net.minecraft.util.MathHelper;

/** Silently aims bows and thrown items so the projectile's arc hits the target. */
public class ProjectileAimbot extends Module {
    private static final Minecraft mc = Minecraft.getMinecraft();
    private static final int ROTATION_PRIORITY = 2;

    public final BooleanProperty bow = new BooleanProperty("bow", true);
    public final BooleanProperty egg = new BooleanProperty("egg", true);
    public final BooleanProperty snowball = new BooleanProperty("snowball", true);
    public final BooleanProperty pearl = new BooleanProperty("ender-pearl", false);
    public final BooleanProperty otherItems = new BooleanProperty("other-items", false);
    public final FloatProperty range = new FloatProperty("range", 10.0F, 0.0F, 30.0F);
    public final BooleanProperty throughWalls = new BooleanProperty("through-walls", false);
    public final FloatProperty throughWallsRange = new FloatProperty("through-walls-range", 10.0F, 0.0F, 30.0F, this.throughWalls::getValue);
    public final ModeProperty priority = new ModeProperty("priority", 2, new String[]{"Health", "Distance", "Direction"});
    public final ModeProperty gravityType = new ModeProperty("gravity-type", 1, new String[]{"None", "Projectile"});
    public final BooleanProperty predict = new BooleanProperty("predict", true, () -> this.gravityType.getValue() == 1);
    public final FloatProperty predictSize = new FloatProperty(
            "predict-size", 2.0F, 0.1F, 5.0F, () -> this.predict.getValue() && this.gravityType.getValue() == 1
    );
    public final FloatProperty turnSpeed = new FloatProperty("turn-speed", 180.0F, 1.0F, 180.0F);
    public final BooleanProperty mark = new BooleanProperty("mark", true);
    public final BooleanProperty players = new BooleanProperty("players", true);
    public final BooleanProperty mobs = new BooleanProperty("mobs", false);
    public final BooleanProperty animals = new BooleanProperty("animals", false);
    public final BooleanProperty teams = new BooleanProperty("teams", true);
    public final BooleanProperty botCheck = new BooleanProperty("bot-check", true);

    private EntityLivingBase target = null;
    private boolean aimed = false;

    public ProjectileAimbot() {
        super("ProjectileAimbot", false);
    }

    /** A visible target is locked and the last rotation this module set points at it. */
    public boolean hasTarget() {
        return this.target != null && this.aimed && mc.thePlayer != null && mc.thePlayer.canEntityBeSeen(this.target);
    }

    @Override
    public void onDisabled() {
        this.target = null;
        this.aimed = false;
    }

    @EventTarget
    public void onLoadWorld(LoadWorldEvent event) {
        this.target = null;
        this.aimed = false;
    }

    @EventTarget
    public void onUpdate(UpdateEvent event) {
        if (!this.isEnabled() || event.getType() != EventType.PRE) {
            return;
        }
        EntityPlayerSP player = mc.thePlayer;
        this.target = null;
        this.aimed = false;
        ItemStack held = player.getHeldItem();
        if (held == null || held.getItem() == null) {
            return;
        }
        Item item = held.getItem();
        float gravity;
        float velocity;
        if (item instanceof ItemBow) {
            if (!this.bow.getValue() || !player.isUsingItem()) {
                return;
            }
            gravity = 0.05F;
            // Negative = derive the launch speed from the bow charge; FastBow always releases at full charge.
            FastBow fastBow = (FastBow) Myau.moduleManager.modules.get(FastBow.class);
            velocity = fastBow != null && fastBow.isEnabled() ? 1.0F : -1.0F;
        } else {
            boolean allowed;
            if (item instanceof ItemEgg) {
                allowed = this.egg.getValue();
            } else if (item instanceof ItemSnowball) {
                allowed = this.snowball.getValue();
            } else if (item instanceof ItemEnderPearl) {
                allowed = this.pearl.getValue();
            } else {
                allowed = this.otherItems.getValue();
            }
            if (!allowed) {
                return;
            }
            gravity = 0.03F;
            velocity = 0.5F;
        }
        EntityLivingBase found = this.findTarget();
        if (found == null) {
            return;
        }
        float[] rotation = this.gravityType.getValue() == 1
                ? RotationUtil.faceTrajectory(found, this.predict.getValue(), this.predictSize.getValue(), gravity, velocity)
                : RotationUtil.getRotationsToEntity(found);
        this.target = found;
        if (Float.isNaN(rotation[0]) || Float.isNaN(rotation[1])) {
            // Out of reach for this launch speed.
            return;
        }
        float speed = this.turnSpeed.getValue();
        float yawDiff = MathHelper.wrapAngleTo180_float(rotation[0] - event.getYaw());
        float pitchDiff = MathHelper.clamp_float(rotation[1], -90.0F, 90.0F) - event.getPitch();
        boolean reached = Math.abs(yawDiff) <= speed && Math.abs(pitchDiff) <= speed;
        float yaw = event.getYaw() + MathHelper.clamp_float(yawDiff, -speed, speed);
        float pitch = MathHelper.clamp_float(event.getPitch() + MathHelper.clamp_float(pitchDiff, -speed, speed), -90.0F, 90.0F);
        event.setRotation(RotationUtil.quantizeAngle(yaw), RotationUtil.quantizeAngle(pitch), ROTATION_PRIORITY);
        // False while turn-speed is still catching up. AutoBow reads this before the next update of
        // this module, i.e. after the rotation set here has been sent with this tick's movement packet.
        this.aimed = reached;
    }

    private EntityLivingBase findTarget() {
        EntityPlayerSP player = mc.thePlayer;
        EntityLivingBase best = null;
        double bestScore = Double.MAX_VALUE;
        for (Entity entity : mc.theWorld.loadedEntityList) {
            if (!TargetUtil.isSelected(entity, this.players.getValue(), this.mobs.getValue(), this.animals.getValue(),
                    this.teams.getValue(), this.botCheck.getValue())) {
                continue;
            }
            EntityLivingBase living = (EntityLivingBase) entity;
            double distance = RotationUtil.distanceToEntity(living);
            if (distance > (double) this.range.getValue()) {
                continue;
            }
            if (!this.throughWalls.getValue() && !(player.canEntityBeSeen(living) && distance <= (double) this.throughWallsRange.getValue())) {
                continue;
            }
            double score;
            switch (this.priority.getValue()) {
                case 0:
                    score = living.getHealth();
                    break;
                case 1:
                    score = distance;
                    break;
                default:
                    float[] rotation = RotationUtil.getRotationsToEntity(living);
                    score = RotationUtil.getRotationDifference(rotation[0], rotation[1], player.rotationYaw, player.rotationPitch);
                    break;
            }
            if (score < bestScore) {
                bestScore = score;
                best = living;
            }
        }
        return best;
    }

    @EventTarget
    public void onRender3D(Render3DEvent event) {
        if (!this.isEnabled() || !this.mark.getValue() || mc.thePlayer == null) {
            return;
        }
        EntityLivingBase entity = this.target;
        if (entity == null) {
            return;
        }
        float partialTicks = event.getPartialTicks();
        IAccessorRenderManager renderManager = (IAccessorRenderManager) mc.getRenderManager();
        double x = RenderUtil.lerpDouble(entity.posX, entity.lastTickPosX, partialTicks) - entity.posX - renderManager.getRenderPosX();
        double y = RenderUtil.lerpDouble(entity.posY, entity.lastTickPosY, partialTicks) - entity.posY - renderManager.getRenderPosY();
        double z = RenderUtil.lerpDouble(entity.posZ, entity.lastTickPosZ, partialTicks) - entity.posZ - renderManager.getRenderPosZ();
        AxisAlignedBB box = entity.getEntityBoundingBox().offset(x, y, z);
        AxisAlignedBB platform = new AxisAlignedBB(box.minX, box.maxY + 0.2, box.minZ, box.maxX, box.maxY + 0.26, box.maxZ);
        RenderUtil.enableRenderState();
        RenderUtil.drawFilledBox(platform, 37, 126, 255);
        RenderUtil.disableRenderState();
    }
}
