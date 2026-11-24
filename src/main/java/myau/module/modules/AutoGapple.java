package myau.module.modules;

import myau.Myau;
import myau.event.EventTarget;
import myau.event.types.EventType;
import myau.events.LoadWorldEvent;
import myau.events.MoveInputEvent;
import myau.events.PacketEvent;
import myau.events.Render2DEvent;
import myau.events.UpdateEvent;
import myau.mixin.IAccessorPlayerControllerMP;
import myau.module.Module;
import myau.property.properties.BooleanProperty;
import myau.property.properties.IntProperty;
import myau.util.PacketUtil;
import net.minecraft.client.Minecraft;
import net.minecraft.item.ItemAppleGold;
import net.minecraft.item.ItemStack;
import net.minecraft.item.ItemSword;
import net.minecraft.network.play.client.C03PacketPlayer;
import net.minecraft.network.play.client.C03PacketPlayer.C05PacketPlayerLook;
import net.minecraft.network.play.client.C08PacketPlayerBlockPlacement;
import net.minecraft.network.play.client.C09PacketHeldItemChange;

/**
 * Eats a golden apple in one go when health is low: stands still while holding back the movement
 * packets that eating takes (32), then sends "start eating" followed by all of them at once, so the
 * server finishes the apple instantly. Ported from Zak0.
 *
 * <p>With {@code always-attack}, KillAura keeps hitting (swapping to the sword for each hit) while
 * the packets are being held.
 */
public class AutoGapple extends Module {
    private static final Minecraft mc = Minecraft.getMinecraft();

    /** Movement packets held per apple; 32 ticks is how long eating takes. */
    public final IntProperty c03Packets = new IntProperty("c03-packets", 32, 32, 40);
    /** Eat at or below this much health (half hearts). */
    public final IntProperty health = new IntProperty("health", 12, 1, 40);
    public final BooleanProperty autoEat = new BooleanProperty("auto-eat", true);
    public final BooleanProperty alwaysAttack = new BooleanProperty("always-attack", false);
    public final BooleanProperty progressBar = new BooleanProperty("progress-bar", true);

    private double savedMotionX;
    private double savedMotionY;
    private double savedMotionZ;
    private boolean motionSaved;
    private boolean cancelMove;
    /** Movement packets held so far for the current apple. */
    private int ticks;
    /** A tick of normal movement after each apple. */
    private int pauseTicks;
    private float yaw;
    private float pitch;
    private boolean eating;
    private int gappleSlot = -1;

    public AutoGapple() {
        super("AutoGapple", false);
    }

    @Override
    public void onEnabled() {
        this.resetState();
    }

    @Override
    public void onDisabled() {
        this.resetState();
    }

    @Override
    public String[] getSuffix() {
        return this.eating ? new String[]{String.valueOf(this.ticks)} : new String[0];
    }

    // ---------------------------------------------------------------- state for other modules

    /** Low enough to eat and an apple is on the hotbar: packets are being held. */
    public boolean isEating() {
        return this.eating;
    }

    public float getEatProgress() {
        return Math.min(1.0F, (float) this.ticks / this.c03Packets.getValue());
    }

    public int getGappleSlot() {
        return this.gappleSlot;
    }

    // ---------------------------------------------------------------- events

    @EventTarget
    public void onPacket(PacketEvent event) {
        if (!this.isEnabled() || mc.thePlayer == null || event.getType() != EventType.SEND) return;
        if (event.getPacket() instanceof C03PacketPlayer && this.cancelMove && this.ticks < this.c03Packets.getValue()) {
            // the rotation this tick would have sent; the burst ends on it
            this.yaw = mc.thePlayer.rotationYaw;
            this.pitch = mc.thePlayer.rotationPitch;
            this.ticks++;
            event.setCancelled(true);
        }
    }

    @EventTarget
    public void onMove(MoveInputEvent event) {
        if (!this.isEnabled() || !this.cancelMove || mc.thePlayer == null) return;
        mc.thePlayer.movementInput.moveStrafe = 0.0F;
        mc.thePlayer.movementInput.moveForward = 0.0F;
        mc.thePlayer.movementInput.jump = false;
        mc.thePlayer.setSprinting(false);
        mc.thePlayer.motionX = 0.0;
        mc.thePlayer.motionY = 0.0;
        mc.thePlayer.motionZ = 0.0;
    }

    @EventTarget
    public void onUpdate(UpdateEvent event) {
        if (!this.isEnabled() || event.getType() != EventType.PRE) return;
        if (mc.thePlayer == null || mc.theWorld == null || !mc.thePlayer.isEntityAlive()
                || !mc.playerController.getCurrentGameType().isSurvivalOrAdventure()) {
            this.resetState();
            return;
        }
        this.yaw = event.getNewYaw();
        this.pitch = event.getNewPitch();
        this.gappleSlot = this.findGappleSlot();
        this.eating = this.autoEat.getValue() && this.gappleSlot >= 0 && mc.thePlayer.getHealth() <= this.health.getValue();
        if (this.eating) {
            if (this.pauseTicks == 0) {
                this.startStuck();
            } else {
                this.stopStuck();
                this.pauseTicks--;
            }
            if (this.ticks >= this.c03Packets.getValue()) {
                this.consumeGappleBurst();
            }
        } else {
            this.stopStuck();
            this.ticks = 0;
            this.pauseTicks = 0;
        }
    }

    @EventTarget
    public void onWorld(LoadWorldEvent event) {
        this.resetState();
    }

    // ---------------------------------------------------------------- dynamic island


    /** How many of the packets the apple needs are already held. */
    @EventTarget
    public void onIsland(Render2DEvent event) {
        if (!this.isEnabled() || !this.eating || !this.progressBar.getValue() || mc.thePlayer == null || mc.currentScreen != null) return;
        float progress = this.getEatProgress();
        myau.ui.glass.Island.live("gapple", myau.ui.glass.Island.P_PROGRESS,
                "Gapple §f" + Math.round(progress * 100.0F) + "%",
                progress >= 1.0F ? myau.ui.GlassTheme.OK : myau.ui.GlassTheme.accent(), progress);
    }

    // ---------------------------------------------------------------- helpers

    private void startStuck() {
        if (!this.motionSaved) {
            this.savedMotionX = mc.thePlayer.motionX;
            this.savedMotionY = mc.thePlayer.motionY;
            this.savedMotionZ = mc.thePlayer.motionZ;
            this.motionSaved = true;
        }
        this.cancelMove = true;
    }

    private void stopStuck() {
        this.cancelMove = false;
        if (this.motionSaved && mc.thePlayer != null) {
            mc.thePlayer.motionX = this.savedMotionX;
            mc.thePlayer.motionY = this.savedMotionY;
            mc.thePlayer.motionZ = this.savedMotionZ;
        }
        this.motionSaved = false;
    }

    private void consumeGappleBurst() {
        ItemStack gapple = mc.thePlayer.inventory.getStackInSlot(this.gappleSlot);
        if (gapple == null || !(gapple.getItem() instanceof ItemAppleGold)) {
            this.gappleSlot = -1;
            return;
        }
        // the slot the server has us on
        int serverSlot = ((IAccessorPlayerControllerMP) mc.playerController).getCurrentPlayerItem();
        boolean swap = serverSlot != this.gappleSlot;
        if (swap) PacketUtil.sendPacketNoEvent(new C09PacketHeldItemChange(this.gappleSlot));
        PacketUtil.sendPacketNoEvent(new C08PacketPlayerBlockPlacement(gapple));
        PacketUtil.sendPacketNoEvent(new C05PacketPlayerLook(this.yaw, this.pitch, mc.thePlayer.onGround));
        for (int i = 1; i < this.ticks; i++) {
            PacketUtil.sendPacketNoEvent(new C03PacketPlayer(mc.thePlayer.onGround));
        }
        if (swap) PacketUtil.sendPacketNoEvent(new C09PacketHeldItemChange(serverSlot));
        // Was blocking with a sword: the slot switch ended the block on the server, start it again.
        // (Zak0 re-used whatever was held, which throws pearls and potions or draws a bow.)
        ItemStack held = mc.thePlayer.inventory.getStackInSlot(serverSlot);
        if (swap && held != null && held.getItem() instanceof ItemSword && mc.thePlayer.isUsingItem()) {
            PacketUtil.sendPacketNoEvent(new C08PacketPlayerBlockPlacement(held));
        }
        myau.ui.glass.Island.event("AutoGapple", "Gapple §7@ " + String.format("%.1f", mc.thePlayer.getHealth()) + "hp",
                myau.ui.GlassTheme.OK, 1.4F);
        this.pauseTicks++;
        this.ticks = 0;
    }

    private int findGappleSlot() {
        for (int i = 0; i < 9; i++) {
            ItemStack stack = mc.thePlayer.inventory.getStackInSlot(i);
            if (stack != null && stack.getItem() instanceof ItemAppleGold) {
                return i;
            }
        }
        return -1;
    }

    private void resetState() {
        this.stopStuck();
        this.ticks = 0;
        this.pauseTicks = 0;
        this.eating = false;
        this.gappleSlot = -1;
    }

    /** For KillAura's always-attack: eating right now with the option on. */
    public static boolean isCombatEating() {
        if (Myau.moduleManager == null) return false;
        AutoGapple gapple = (AutoGapple) Myau.moduleManager.modules.get(AutoGapple.class);
        return gapple != null && gapple.isEnabled() && gapple.isEating() && gapple.alwaysAttack.getValue();
    }
}
