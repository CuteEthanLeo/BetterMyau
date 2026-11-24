package myau.module.modules;

import myau.event.EventTarget;
import myau.event.types.EventType;
import myau.events.LoadWorldEvent;
import myau.events.UpdateEvent;
import myau.mixin.IAccessorPlayerControllerMP;
import myau.module.Module;
import myau.property.properties.BooleanProperty;
import myau.property.properties.FloatProperty;
import myau.property.properties.IntProperty;
import myau.property.properties.ModeProperty;
import myau.util.FallingPlayer;
import myau.util.MoveUtil;
import myau.util.RandomUtil;
import myau.util.RotationUtil;
import myau.util.TickScheduler;
import myau.util.TimerUtil;
import net.minecraft.client.Minecraft;
import net.minecraft.client.entity.EntityPlayerSP;
import net.minecraft.item.ItemPotion;
import net.minecraft.item.ItemStack;
import net.minecraft.potion.Potion;
import net.minecraft.potion.PotionEffect;
import net.minecraft.util.MathHelper;

import java.util.List;

/** Looks down and throws useful splash potions from the hotbar. Restocking the hotbar is left to Refill. */
public class AutoPot extends Module {
    private static final Minecraft mc = Minecraft.getMinecraft();
    private static final int ROTATION_PRIORITY = 7;

    public final FloatProperty health = new FloatProperty(
            "health", 15.0F, 1.0F, 20.0F, () -> this.healPotion.getValue() || this.regenerationPotion.getValue()
    );
    public final IntProperty delay = new IntProperty("delay", 500, 500, 1000);
    public final BooleanProperty healPotion = new BooleanProperty("heal-potion", true);
    public final BooleanProperty regenerationPotion = new BooleanProperty("regen-potion", true);
    public final BooleanProperty fireResistancePotion = new BooleanProperty("fire-res-potion", true);
    public final BooleanProperty strengthPotion = new BooleanProperty("strength-potion", true);
    public final BooleanProperty jumpPotion = new BooleanProperty("jump-potion", true);
    public final BooleanProperty speedPotion = new BooleanProperty("speed-potion", true);
    public final FloatProperty groundDistance = new FloatProperty("ground-distance", 2.0F, 0.0F, 5.0F);
    public final ModeProperty mode = new ModeProperty("mode", 0, new String[]{"Normal", "Jump", "Port"});
    public final FloatProperty turnSpeed = new FloatProperty("turn-speed", 180.0F, 1.0F, 180.0F);

    private final TimerUtil timer = new TimerUtil();
    private int potion = -1;
    /** Pitch of the last movement packet, NaN until one has been seen. */
    private float serverPitch = Float.NaN;

    public AutoPot() {
        super("AutoPot", false);
    }

    @Override
    public void onEnabled() {
        this.potion = -1;
        this.serverPitch = Float.NaN;
    }

    @Override
    public void onDisabled() {
        this.potion = -1;
    }

    @EventTarget
    public void onLoadWorld(LoadWorldEvent event) {
        this.potion = -1;
        this.serverPitch = Float.NaN;
    }

    @EventTarget
    public void onUpdate(UpdateEvent event) {
        if (!this.isEnabled()) {
            return;
        }
        if (event.getType() == EventType.POST) {
            this.serverPitch = event.getPitch();
            return;
        }
        if (!this.timer.hasTimeElapsed(this.delay.getValue()) || mc.playerController.isInCreativeMode() || mc.theWorld == null) {
            return;
        }
        EntityPlayerSP player = mc.thePlayer;
        int potionInHotbar = this.findPotion();
        if (potionInHotbar == -1) {
            return;
        }
        if (player.onGround) {
            switch (this.mode.getValue()) {
                case 1:
                    MoveUtil.tryJump();
                    break;
                case 2:
                    player.moveEntity(0.0, 0.42, 0.0);
                    break;
                default:
                    break;
            }
        }
        // Do not throw potions into the void or from high up.
        FallingPlayer.CollisionResult collision = new FallingPlayer(player).findCollision(20);
        if (collision == null || player.posY - (double) collision.getPos().getY() - 1.0 > (double) this.groundDistance.getValue()) {
            return;
        }
        this.potion = potionInHotbar;
        if (player.rotationPitch <= 80.0F) {
            float speed = this.turnSpeed.getValue();
            float wanted = RandomUtil.nextFloat(80.0F, 90.0F);
            float pitch = event.getPitch() + MathHelper.clamp_float(wanted - event.getPitch(), -speed, speed);
            event.setRotation(player.rotationYaw, RotationUtil.quantizeAngle(MathHelper.clamp_float(pitch, -90.0F, 90.0F)), ROTATION_PRIORITY);
        }
        // Throw at the start of the next tick, once the downward rotation has reached the server.
        TickScheduler.nextTick(this::throwPotion);
    }

    private void throwPotion() {
        EntityPlayerSP player = mc.thePlayer;
        if (!this.isEnabled() || player == null || mc.theWorld == null || this.potion < 0) {
            return;
        }
        float pitch = Float.isNaN(this.serverPitch) ? player.rotationPitch : this.serverPitch;
        if (pitch < 75.0F) {
            return;
        }
        int slot = this.potion;
        ItemStack stack = player.inventory.getStackInSlot(slot);
        if (!isSplashPotion(stack)) {
            this.potion = -1;
            return;
        }
        int previous = player.inventory.currentItem;
        player.inventory.currentItem = slot;
        // Syncs the held slot (C09), sends the use packet and applies the use client-side.
        String thrown = stack.getDisplayName();
        float hpBefore = player.getHealth();
        mc.playerController.sendUseItem(player, mc.theWorld, stack);
        myau.ui.glass.Island.event("AutoPot", thrown + " §7@ " + String.format("%.1f", hpBefore) + "hp", myau.ui.GlassTheme.OK, 1.4f);
        player.inventory.currentItem = previous;
        ((IAccessorPlayerControllerMP) mc.playerController).callSyncCurrentPlayItem();
        this.timer.reset();
        this.potion = -1;
    }

    private static boolean isSplashPotion(ItemStack stack) {
        return stack != null && stack.getItem() instanceof ItemPotion && ItemPotion.isSplash(stack.getMetadata());
    }

    private static boolean hasEffect(List<PotionEffect> effects, Potion potion) {
        for (PotionEffect effect : effects) {
            if (effect.getPotionID() == potion.id) {
                return true;
            }
        }
        return false;
    }

    /** Hotbar slot (0-8) of the first splash potion worth throwing right now, or -1. */
    private int findPotion() {
        EntityPlayerSP player = mc.thePlayer;
        boolean lowHealth = player.getHealth() <= this.health.getValue();
        for (int i = 0; i < 9; i++) {
            ItemStack stack = player.inventory.getStackInSlot(i);
            if (!isSplashPotion(stack)) {
                continue;
            }
            List<PotionEffect> effects = ((ItemPotion) stack.getItem()).getEffects(stack);
            if (effects == null) {
                continue;
            }
            if (lowHealth && this.healPotion.getValue() && hasEffect(effects, Potion.heal)) {
                return i;
            }
            if (!player.isPotionActive(Potion.regeneration) && lowHealth && this.regenerationPotion.getValue()
                    && hasEffect(effects, Potion.regeneration)) {
                return i;
            }
            if (!player.isPotionActive(Potion.fireResistance) && this.fireResistancePotion.getValue()
                    && hasEffect(effects, Potion.fireResistance)) {
                return i;
            }
            if (!player.isPotionActive(Potion.moveSpeed) && this.speedPotion.getValue() && hasEffect(effects, Potion.moveSpeed)) {
                return i;
            }
            if (!player.isPotionActive(Potion.jump) && this.jumpPotion.getValue() && hasEffect(effects, Potion.jump)) {
                return i;
            }
            if (!player.isPotionActive(Potion.damageBoost) && this.strengthPotion.getValue() && hasEffect(effects, Potion.damageBoost)) {
                return i;
            }
        }
        return -1;
    }

    @Override
    public String[] getSuffix() {
        return new String[]{String.valueOf(this.health.getValue())};
    }
}
