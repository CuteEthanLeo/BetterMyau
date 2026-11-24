package myau.module.modules;

import myau.event.EventTarget;
import myau.event.types.EventType;
import myau.events.UpdateEvent;
import myau.mixin.IAccessorPlayerControllerMP;
import myau.module.Module;
import myau.property.properties.BooleanProperty;
import myau.property.properties.FloatProperty;
import myau.property.properties.IntProperty;
import myau.util.KeyBindUtil;
import net.minecraft.client.Minecraft;
import net.minecraft.client.entity.EntityPlayerSP;
import net.minecraft.entity.Entity;
import net.minecraft.entity.item.EntityTNTPrimed;
import net.minecraft.item.ItemStack;
import net.minecraft.item.ItemSword;

/** Sword-blocks while primed TNT about to explode is nearby. */
public class TNTBlock extends Module {
    private static final Minecraft mc = Minecraft.getMinecraft();
    public final IntProperty fuse = new IntProperty("fuse", 10, 0, 80);
    public final FloatProperty range = new FloatProperty("range", 9.0F, 1.0F, 20.0F);
    public final BooleanProperty autoSword = new BooleanProperty("auto-sword", true);
    private boolean blocked = false;

    public TNTBlock() {
        super("TNTBlock", false);
    }

    private boolean isDangerousTntNearby() {
        float rangeSq = this.range.getValue() * this.range.getValue();
        for (Entity entity : mc.theWorld.loadedEntityList) {
            if (entity instanceof EntityTNTPrimed
                    && ((EntityTNTPrimed) entity).fuse <= this.fuse.getValue()
                    && mc.thePlayer.getDistanceSqToEntity(entity) <= (double) rangeSq) {
                return true;
            }
        }
        return false;
    }

    private void releaseIfNotHeld() {
        int useKey = mc.gameSettings.keyBindUseItem.getKeyCode();
        if (this.blocked && !KeyBindUtil.isKeyDown(useKey)) {
            KeyBindUtil.setKeyBindState(useKey, false);
            this.blocked = false;
        }
    }

    @EventTarget
    public void onUpdate(UpdateEvent event) {
        if (!this.isEnabled() || event.getType() != EventType.PRE || mc.theWorld == null) {
            return;
        }
        EntityPlayerSP player = mc.thePlayer;
        if (!this.isDangerousTntNearby()) {
            this.releaseIfNotHeld();
            return;
        }
        if (this.autoSword.getValue()) {
            int slot = -1;
            float bestDamage = 1.0F;
            for (int i = 0; i < 9; i++) {
                ItemStack stack = player.inventory.getStackInSlot(i);
                if (stack != null && stack.getItem() instanceof ItemSword) {
                    float damage = ((ItemSword) stack.getItem()).getDamageVsEntity() + 4.0F;
                    if (damage > bestDamage) {
                        bestDamage = damage;
                        slot = i;
                    }
                }
            }
            if (slot != -1 && slot != player.inventory.currentItem) {
                player.inventory.currentItem = slot;
                ((IAccessorPlayerControllerMP) mc.playerController).callSyncCurrentPlayItem();
            }
        }
        ItemStack held = player.getHeldItem();
        if (held != null && held.getItem() instanceof ItemSword) {
            KeyBindUtil.setKeyBindState(mc.gameSettings.keyBindUseItem.getKeyCode(), true);
            this.blocked = true;
        }
    }

    @Override
    public void onDisabled() {
        this.releaseIfNotHeld();
        this.blocked = false;
    }
}
