package myau.module.modules;

import myau.Myau;
import myau.event.EventManager;
import myau.event.EventTarget;
import myau.event.types.EventType;
import myau.event.types.Priority;
import myau.events.*;
import myau.mixin.IAccessorC03PacketPlayer;
import myau.module.Module;
import myau.property.properties.BooleanProperty;
import myau.util.ChatUtil;
import myau.util.PacketUtil;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiDownloadTerrain;
import net.minecraft.client.gui.inventory.GuiInventory;
import net.minecraft.init.Items;
import net.minecraft.item.ItemStack;
import net.minecraft.network.Packet;
import net.minecraft.network.play.INetHandlerPlayClient;
import net.minecraft.network.play.client.C03PacketPlayer;
import net.minecraft.network.play.client.C0DPacketCloseWindow;
import net.minecraft.network.play.client.C0EPacketClickWindow;
import net.minecraft.network.play.client.C16PacketClientStatus;
import net.minecraft.network.play.server.*;
import net.minecraft.potion.Potion;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentLinkedQueue;

/**
 * Anticheat disablers, each one an independent toggle.
 * <ul>
 *     <li>prediction-inventory / post: LeaderClient (https://github.com/Mornly/LeaderClient), GPLv3</li>
 *     <li>hypixel-motion / hypixel-scaffold: LiquidBounce Nextgen (DisablerHypixel by @localp, bypass by @billionaire;
 *     DisablerHypixelScaffold), GPLv3</li>
 *     <li>watchdog-inventory: LiquidBounce Legacy (via AirClient), GPLv3</li>
 * </ul>
 */
public class Disabler extends Module {
    private static final Minecraft mc = Minecraft.getMinecraft();

    public final BooleanProperty predictionInventory = new BooleanProperty("prediction-inventory", true);
    public final BooleanProperty hypixelMotion = new BooleanProperty("hypixel-motion", false);
    public final BooleanProperty notWithStar = new BooleanProperty("not-with-star", true, this.hypixelMotion::getValue);
    public final BooleanProperty hypixelScaffold = new BooleanProperty("hypixel-scaffold", false);
    public final BooleanProperty watchdogInventory = new BooleanProperty("watchdog-inventory", false);
    public final BooleanProperty post = new BooleanProperty("post", false);
    public final BooleanProperty debug = new BooleanProperty("debug", false);

    // prediction-inventory
    private final List<Packet<?>> inventoryPackets = new ArrayList<>();
    // post (Grim): inbound packets held by MixinNetworkManager until the next PreMotion
    private static final ConcurrentLinkedQueue<Packet<INetHandlerPlayClient>> storedPackets = new ConcurrentLinkedQueue<>();
    private static boolean releasing = false;
    // hypixel-motion
    private int flags = 0;
    private int motionAirTicks = 0;
    private boolean execute = false;
    private boolean jump = false;
    private boolean nudgeX = false;
    private static final double NUDGE_X = 0.095;
    private double appliedNudge = 0.0;
    // hypixel-scaffold
    private boolean scaffoldDisabled = false;
    private boolean scaffoldJumped = false;
    private boolean scaffoldReady = false;
    // watchdog-inventory
    private boolean c16 = false;
    private boolean c0d = false;

    public Disabler() {
        super("Disabler", false);
    }

    private void dbg(String msg) {
        if (this.debug.getValue()) {
            ChatUtil.sendFormatted(Myau.clientName + "Disabler: " + msg);
        }
    }

    private static boolean hotbarHas(String nameFragment) {
        for (int i = 0; i < 9; i++) {
            ItemStack stack = mc.thePlayer.inventory.getStackInSlot(i);
            if (stack != null && stack.getUnlocalizedName().toLowerCase().contains(nameFragment)) {
                return true;
            }
        }
        return false;
    }

    private static boolean hotbarHasStar() {
        for (int i = 0; i < 9; i++) {
            ItemStack stack = mc.thePlayer.inventory.getStackInSlot(i);
            if (stack != null && stack.getItem() == Items.nether_star) {
                return true;
            }
        }
        return false;
    }

    private boolean isScaffolding() {
        return LeaderScaffold.isAnyScaffoldEnabled();
    }

    // ───────────────────────────── post (called from MixinNetworkManager) ─────────────────────────────

    /** LeaderClient grimPostDelay(): which inbound packets are held until PreMotion. */
    private static boolean isPostPacket(Packet<?> packet) {
        if (mc.thePlayer == null || mc.currentScreen instanceof GuiDownloadTerrain) {
            return false;
        }
        if (packet instanceof S12PacketEntityVelocity) {
            return ((S12PacketEntityVelocity) packet).getEntityID() == mc.thePlayer.getEntityId();
        }
        return packet instanceof S27PacketExplosion || packet instanceof S32PacketConfirmTransaction
                || packet instanceof S08PacketPlayerPosLook || packet instanceof S18PacketEntityTeleport
                || packet instanceof S19PacketEntityStatus || packet instanceof S04PacketEntityEquipment
                || packet instanceof S23PacketBlockChange || packet instanceof S22PacketMultiBlockChange
                || packet instanceof S13PacketDestroyEntities || packet instanceof S00PacketKeepAlive
                || packet instanceof S06PacketUpdateHealth || packet instanceof S14PacketEntity
                || packet instanceof S0FPacketSpawnMob || packet instanceof S3FPacketCustomPayload;
    }

    /** @return true if the packet was taken over (the caller must cancel it). */
    @SuppressWarnings("unchecked")
    public static boolean holdPostPacket(Packet<?> packet) {
        if (releasing || Myau.moduleManager == null) {
            return false;
        }
        Disabler disabler = (Disabler) Myau.moduleManager.modules.get(Disabler.class);
        if (disabler == null || !disabler.isEnabled() || !disabler.post.getValue() || !isPostPacket(packet)) {
            return false;
        }
        storedPackets.add((Packet<INetHandlerPlayClient>) packet);
        return true;
    }

    /** Fixed version of LeaderClient releasePost(): one RECEIVE event per packet, then process it. */
    private static void releasePost() {
        if (mc.getNetHandler() == null) {
            storedPackets.clear();
            return;
        }
        releasing = true;
        try {
            Packet<INetHandlerPlayClient> packet;
            while ((packet = storedPackets.poll()) != null) {
                PacketEvent event = new PacketEvent(EventType.RECEIVE, packet);
                EventManager.call(event);
                if (event.isCancelled()) {
                    continue;
                }
                try {
                    packet.processPacket(mc.getNetHandler());
                } catch (Exception e) {
                    e.printStackTrace();
                }
            }
        } finally {
            releasing = false;
        }
    }

    // ───────────────────────────── events ─────────────────────────────

    @EventTarget
    public void onPreMotion(PreMotionEvent event) {
        if (!this.isEnabled()) {
            return;
        }
        // post is released every tick; a sub-toggle switched off still drains what it already holds
        if (this.post.getValue() || !storedPackets.isEmpty()) {
            releasePost();
        }
        if (!this.predictionInventory.getValue() && !this.inventoryPackets.isEmpty()) {
            this.flushInventoryPackets();
        }
        // hypixel-motion: runs after this tick's movement, right before the C03 goes out (Nextgen network tick PRE)
        this.nudgeX = false;
        if (this.hypixelMotion.getValue() && !(this.notWithStar.getValue() && hotbarHasStar())) {
            if (mc.thePlayer.onGround) {
                this.motionAirTicks = 0;
            } else {
                this.motionAirTicks++;
            }
            if (this.execute && this.motionAirTicks >= 10) {
                this.nudgeX = this.motionAirTicks % 2 == 0;
                mc.thePlayer.motionX = 0.0;
                mc.thePlayer.motionY = 0.0;
                mc.thePlayer.motionZ = 0.0;
            }
        }
        // Nextgen offsets the network position before vanilla picks the packet type. Shifting posX here (HEAD of
        // onUpdateWalkingPlayer) makes vanilla see a 0.095 move, send a position packet carrying it and store it as
        // lastReportedPosX; posX is put back in PostMotion, so the next packet reports the real x again.
        if (this.nudgeX) {
            this.nudgeX = false;
            this.appliedNudge = NUDGE_X;
            mc.thePlayer.posX += NUDGE_X;
        }
    }

    @EventTarget(Priority.HIGHEST)
    public void onPostMotion(PostMotionEvent event) {
        // not gated on isEnabled(): the offset must be undone even if the module was switched off in between
        if (this.appliedNudge != 0.0 && mc.thePlayer != null) {
            mc.thePlayer.posX -= this.appliedNudge;
        }
        this.appliedNudge = 0.0;
    }

    @EventTarget(Priority.LOW)
    public void onPacket(PacketEvent event) {
        if (!this.isEnabled() || mc.thePlayer == null) {
            return;
        }
        Packet<?> packet = event.getPacket();
        if (event.getType() == EventType.SEND) {
            // prediction-inventory: send the whole inventory session (open + clicks) together with the close
            if (this.predictionInventory.getValue() && !hotbarHas("compass")) {
                // only the inventory-open C16; PERFORM_RESPAWN / REQUEST_STATS must go straight through
                if (isOpenInventory(packet) || packet instanceof C0EPacketClickWindow) {
                    event.setCancelled(true);
                    this.inventoryPackets.add(packet);
                } else if (packet instanceof C0DPacketCloseWindow) {
                    this.flushInventoryPackets();
                }
            }
            if (this.watchdogInventory.getValue()) {
                if (isOpenInventory(packet)) {
                    if (this.c16) {
                        event.setCancelled(true);
                    }
                    this.c16 = true;
                }
                if (packet instanceof C0DPacketCloseWindow) {
                    if (this.c0d) {
                        event.setCancelled(true);
                    }
                    this.c0d = true;
                }
            }
            if (packet instanceof C03PacketPlayer && !event.isCancelled()) {
                this.handleOutgoingMovement((C03PacketPlayer) packet);
            }
        } else {
            if (this.hypixelMotion.getValue()) {
                if (packet instanceof S07PacketRespawn) {
                    this.flags = 0;
                    this.execute = false;
                    this.jump = true;
                    this.dbg("respawn -> arming motion disabler");
                } else if (packet instanceof S08PacketPlayerPosLook) {
                    if (++this.flags >= 20) {
                        this.execute = false;
                        this.flags = 0;
                        this.dbg("too many setbacks, stopped");
                    }
                }
            }
        }
    }

    private void handleOutgoingMovement(C03PacketPlayer packet) {
        // hypixel-scaffold: tiny ground offsets once the jump-in finished
        if (this.hypixelScaffold.getValue() && this.scaffoldDisabled && this.isScaffolding()) {
            if (mc.gameSettings.keyBindJump.isKeyDown()) {
                // towering resets the disabler
                this.scaffoldDisabled = false;
                this.scaffoldJumped = false;
            } else if (mc.thePlayer.onGround && packet.isMoving()) {
                double offset = mc.thePlayer.ticksExisted % 2 == 0 ? 0.03 : 1.0E-5;
                ((IAccessorC03PacketPlayer) packet).setY(packet.getPositionY() + offset);
            }
        }
    }

    @EventTarget
    public void onUpdate(UpdateEvent event) {
        if (!this.isEnabled() || event.getType() != EventType.PRE) {
            return;
        }
        // hypixel-scaffold: jump-in sequence
        if (this.hypixelScaffold.getValue()) {
            if (!this.isScaffolding()) {
                this.scaffoldDisabled = false;
                this.scaffoldJumped = false;
                this.scaffoldReady = false;
            } else if (mc.thePlayer.onGround && this.scaffoldJumped) {
                this.scaffoldDisabled = true;
            } else if (!this.scaffoldReady && mc.thePlayer.onGround) {
                this.scaffoldReady = true;
                mc.thePlayer.motionY = 0.01;
                this.scaffoldJumped = true;
            }
        }
        // watchdog-inventory: close / reopen the inventory on a speed-dependent rhythm
        if (this.watchdogInventory.getValue()) {
            this.c16 = false;
            this.c0d = false;
            if (mc.currentScreen instanceof GuiInventory) {
                int period = mc.thePlayer.isPotionActive(Potion.moveSpeed) ? 3 : 4;
                int phase = mc.thePlayer.ticksExisted % period;
                if (phase == 0) {
                    PacketUtil.sendPacketNoEvent(new C0DPacketCloseWindow());
                } else if (phase == 1) {
                    PacketUtil.sendPacketNoEvent(new C16PacketClientStatus(C16PacketClientStatus.EnumState.OPEN_INVENTORY_ACHIEVEMENT));
                }
            }
        }
    }

    @EventTarget
    public void onMoveInput(MoveInputEvent event) {
        if (this.isEnabled() && this.hypixelMotion.getValue() && this.jump) {
            mc.thePlayer.movementInput.jump = true;
        }
    }

    @EventTarget
    public void onJump(JumpEvent event) {
        if (this.isEnabled() && this.hypixelMotion.getValue() && event.getType() == EventType.POST && this.jump) {
            this.jump = false;
            this.execute = true;
        }
    }

    @EventTarget
    public void onLoadWorld(LoadWorldEvent event) {
        this.inventoryPackets.clear();
        storedPackets.clear();
        this.motionAirTicks = 0;
        this.scaffoldDisabled = false;
        this.scaffoldJumped = false;
        this.scaffoldReady = false;
    }

    private static boolean isOpenInventory(Packet<?> packet) {
        return packet instanceof C16PacketClientStatus
                && ((C16PacketClientStatus) packet).getStatus() == C16PacketClientStatus.EnumState.OPEN_INVENTORY_ACHIEVEMENT;
    }

    private void flushInventoryPackets() {
        for (Packet<?> p : this.inventoryPackets) {
            PacketUtil.sendPacketNoEvent(p);
        }
        this.inventoryPackets.clear();
    }

    @Override
    public void onEnabled() {
        this.inventoryPackets.clear();
        this.flags = 0;
        this.execute = false;
        this.jump = false;
        this.motionAirTicks = 0;
        this.scaffoldDisabled = false;
        this.scaffoldJumped = false;
        this.scaffoldReady = false;
        if (this.predictionInventory.getValue()) {
            ChatUtil.sendFormatted(Myau.clientName + "Disabler: InvWalk VANILLA and silent InvManager are usable now");
        }
    }

    @Override
    public void onDisabled() {
        if (mc.thePlayer != null) {
            this.flushInventoryPackets();
            releasePost();
        }
        this.inventoryPackets.clear();
        storedPackets.clear();
        this.execute = false;
        this.jump = false;
    }
}
