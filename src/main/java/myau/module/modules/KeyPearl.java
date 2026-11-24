package myau.module.modules;

import myau.event.EventTarget;
import myau.event.types.EventType;
import myau.events.TickEvent;
import myau.mixin.IAccessorPlayerControllerMP;
import myau.module.Module;
import myau.property.properties.BooleanProperty;
import myau.property.properties.ModeProperty;
import myau.property.properties.TextProperty;
import myau.util.PacketUtil;
import net.minecraft.client.Minecraft;
import net.minecraft.init.Items;
import net.minecraft.item.ItemStack;
import net.minecraft.network.play.client.C08PacketPlayerBlockPlacement;
import net.minecraft.network.play.client.C09PacketHeldItemChange;
import net.minecraft.world.WorldSettings.GameType;
import org.lwjgl.input.Keyboard;
import org.lwjgl.input.Mouse;

import java.util.Locale;

/**
 * Throws an ender pearl from the hotbar when a key (or mouse button) is pressed, without changing
 * the held item on screen. The pearl flies along the rotation last sent to the server. Ported from Zak0.
 */
public class KeyPearl extends Module {
    private static final Minecraft mc = Minecraft.getMinecraft();

    /** Switch back a tick later instead of in the same tick. */
    public final BooleanProperty delayedSlotSwitch = new BooleanProperty("delayed-slot-switch", true);
    public final BooleanProperty mouse = new BooleanProperty("mouse", false);
    public final ModeProperty mouseButton = new ModeProperty("mouse-button", 2,
            new String[]{"LEFT", "RIGHT", "MIDDLE", "MOUSE4", "MOUSE5"}, this.mouse::getValue);
    /** LWJGL key name, e.g. X, F, LMENU. */
    public final TextProperty key = new TextProperty("key", "X", () -> !this.mouse.getValue());
    public final BooleanProperty noPearlMessage = new BooleanProperty("no-pearl-message", true);

    private boolean wasMouseDown = false;
    private boolean wasKeyDown = false;
    private boolean hasThrown = false;
    private int resetSlot = -1;

    public KeyPearl() {
        super("KeyPearl", false);
    }

    @Override
    public void onEnabled() {
        this.hasThrown = false;
        this.resetSlot = -1;
        this.wasMouseDown = false;
        this.wasKeyDown = false;
    }

    @Override
    public void onDisabled() {
        this.resetSlot();
        this.hasThrown = false;
        this.wasMouseDown = false;
        this.wasKeyDown = false;
    }

    @EventTarget
    public void onTick(TickEvent event) {
        if (!this.isEnabled() || event.getType() != EventType.PRE || mc.thePlayer == null || mc.playerController == null) return;
        if (this.hasThrown) {
            this.resetSlot();
            this.hasThrown = false;
        }
        GameType gameType = mc.playerController.getCurrentGameType();
        if (mc.currentScreen == null && gameType != GameType.SPECTATOR && gameType != GameType.CREATIVE) {
            boolean mouseDown = Mouse.isButtonDown(this.mouseButton.getValue());
            int keyIndex = Keyboard.getKeyIndex(this.key.getValue().trim().toUpperCase(Locale.ROOT));
            boolean keyDown = keyIndex != Keyboard.KEY_NONE && Keyboard.isKeyDown(keyIndex);
            if (this.mouse.getValue() ? mouseDown && !this.wasMouseDown : keyDown && !this.wasKeyDown) {
                this.throwPearl();
            }
            this.wasMouseDown = mouseDown;
            this.wasKeyDown = keyDown;
        } else {
            this.wasMouseDown = false;
            this.wasKeyDown = false;
        }
    }

    private void throwPearl() {
        int pearlSlot = findPearlInHotbar();
        if (pearlSlot == -1) {
            if (this.noPearlMessage.getValue()) {
                myau.ui.glass.Island.event("KeyPearl", "No ender pearl on the hotbar", myau.ui.GlassTheme.WARN, 1.5F);
            }
            return;
        }
        ItemStack pearl = mc.thePlayer.inventory.getStackInSlot(pearlSlot);
        // the slot the server has us on, which is where we go back to
        int serverSlot = ((IAccessorPlayerControllerMP) mc.playerController).getCurrentPlayerItem();
        if (serverSlot == pearlSlot) {
            PacketUtil.sendPacket(new C08PacketPlayerBlockPlacement(pearl));
            return;
        }
        PacketUtil.sendPacket(new C09PacketHeldItemChange(pearlSlot));
        PacketUtil.sendPacket(new C08PacketPlayerBlockPlacement(pearl));
        if (this.delayedSlotSwitch.getValue()) {
            this.resetSlot = serverSlot;
            this.hasThrown = true;
        } else {
            PacketUtil.sendPacket(new C09PacketHeldItemChange(serverSlot));
        }
    }

    private static int findPearlInHotbar() {
        for (int slot = 0; slot < 9; slot++) {
            ItemStack stack = mc.thePlayer.inventory.getStackInSlot(slot);
            if (stack != null && stack.getItem() == Items.ender_pearl) {
                return slot;
            }
        }
        return -1;
    }

    private void resetSlot() {
        if (this.resetSlot >= 0 && this.resetSlot < 9 && mc.getNetHandler() != null) {
            PacketUtil.sendPacket(new C09PacketHeldItemChange(this.resetSlot));
        }
        this.resetSlot = -1;
    }
}
