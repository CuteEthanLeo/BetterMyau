package myau.module.modules;

import myau.event.EventTarget;
import myau.event.types.EventType;
import myau.events.AttackEvent;
import myau.events.LoadWorldEvent;
import myau.events.PacketEvent;
import myau.events.TickEvent;
import myau.mixin.IAccessorPlayerControllerMP;
import myau.module.Module;
import myau.property.properties.BooleanProperty;
import myau.property.properties.IntProperty;
import myau.util.ItemUtil;
import myau.util.PacketUtil;
import net.minecraft.client.Minecraft;
import net.minecraft.item.ItemStack;
import net.minecraft.item.ItemSword;
import net.minecraft.item.ItemTool;
import net.minecraft.network.play.client.C02PacketUseEntity;
import net.minecraft.network.play.client.C09PacketHeldItemChange;

/** Switches to the best weapon in the hotbar right before an attack packet goes out. */
public class AutoWeapon extends Module {
    private static final Minecraft mc = Minecraft.getMinecraft();
    public final BooleanProperty onlySword = new BooleanProperty("only-sword", false);
    public final BooleanProperty spoof = new BooleanProperty("spoof-item", false);
    public final IntProperty spoofTicks = new IntProperty("spoof-ticks", 10, 1, 20, this.spoof::getValue);

    private boolean attackEnemy = false;
    /** Slot the server holds while spoofing (-1 = not spoofing); the client keeps showing {@link #spoofOriginal}. */
    private int spoofSlot = -1;
    private int spoofOriginal = -1;
    private int spoofTicksLeft = 0;

    public AutoWeapon() {
        super("AutoWeapon", false);
    }

    private int findBestWeapon() {
        int best = -1;
        double bestDamage = -Double.MAX_VALUE;
        for (int i = 0; i < 9; i++) {
            ItemStack stack = mc.thePlayer.inventory.getStackInSlot(i);
            if (stack == null) {
                continue;
            }
            boolean weapon = stack.getItem() instanceof ItemSword || !this.onlySword.getValue() && stack.getItem() instanceof ItemTool;
            if (!weapon) {
                continue;
            }
            double damage = ItemUtil.getAttackBonus(stack);
            if (damage > bestDamage) {
                bestDamage = damage;
                best = i;
            }
        }
        return best;
    }

    /** Puts the server back on the slot the client really holds. */
    private void endSpoof(boolean sendPacket) {
        if (this.spoofSlot != -1 && sendPacket && mc.thePlayer != null && mc.getNetHandler() != null
                && mc.thePlayer.inventory.currentItem != this.spoofSlot) {
            PacketUtil.sendPacket(new C09PacketHeldItemChange(mc.thePlayer.inventory.currentItem));
        }
        this.spoofSlot = -1;
        this.spoofOriginal = -1;
        this.spoofTicksLeft = 0;
    }

    @Override
    public void onDisabled() {
        this.attackEnemy = false;
        this.endSpoof(true);
    }

    @EventTarget
    public void onLoadWorld(LoadWorldEvent event) {
        this.attackEnemy = false;
        this.endSpoof(false);
    }

    @EventTarget
    public void onAttack(AttackEvent event) {
        if (this.isEnabled()) {
            this.attackEnemy = true;
        }
    }

    @EventTarget
    public void onTick(TickEvent event) {
        if (!this.isEnabled() || event.getType() != EventType.PRE || this.spoofSlot == -1 || mc.thePlayer == null) {
            return;
        }
        if (mc.thePlayer.inventory.currentItem != this.spoofOriginal) {
            // The user switched slots: vanilla already told the server about the new one.
            this.endSpoof(false);
        } else if (--this.spoofTicksLeft <= 0) {
            this.endSpoof(true);
        }
    }

    @EventTarget
    public void onPacket(PacketEvent event) {
        if (!this.isEnabled() || event.getType() != EventType.SEND || mc.thePlayer == null) {
            return;
        }
        if (!(event.getPacket() instanceof C02PacketUseEntity)
                || ((C02PacketUseEntity) event.getPacket()).getAction() != C02PacketUseEntity.Action.ATTACK
                || !this.attackEnemy) {
            return;
        }
        this.attackEnemy = false;
        AutoRod autoRod = (AutoRod) myau.Myau.moduleManager.modules.get(AutoRod.class);
        if (autoRod != null && autoRod.isBusy()) {
            // Switching off the rod now would make the server drop AutoRod's hook mid-flight.
            return;
        }
        Clutch clutch = (Clutch) myau.Myau.moduleManager.modules.get(Clutch.class);
        if (clutch != null && clutch.isBusy()) {
            // Clutch needs the block in hand until we land.
            return;
        }
        int slot = this.findBestWeapon();
        if (slot == -1) {
            return;
        }
        int current = mc.thePlayer.inventory.currentItem;
        if (this.spoof.getValue()) {
            if (slot == current) {
                if (this.spoofSlot != -1) {
                    this.endSpoof(true);
                }
                return;
            }
            // Sent from inside the attack packet's send hook, so the slot change reaches the server first.
            if (this.spoofSlot != slot) {
                PacketUtil.sendPacket(new C09PacketHeldItemChange(slot));
            }
            this.spoofSlot = slot;
            this.spoofOriginal = current;
            this.spoofTicksLeft = this.spoofTicks.getValue();
            return;
        }
        if (this.spoofSlot != -1) {
            this.endSpoof(true);
        }
        if (slot == current) {
            return;
        }
        mc.thePlayer.inventory.currentItem = slot;
        ((IAccessorPlayerControllerMP) mc.playerController).callSyncCurrentPlayItem();
    }
}
