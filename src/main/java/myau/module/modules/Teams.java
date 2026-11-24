package myau.module.modules;

import myau.module.Module;
import myau.property.properties.BooleanProperty;
import net.minecraft.entity.EntityLivingBase;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.item.ItemArmor;
import net.minecraft.item.ItemStack;
import net.minecraft.util.IChatComponent;

/**
 * Team detection by scoreboard team, name colour, leather armour colour or the GommeHD
 * SkyWars "T1 name" prefix. Has no handlers of its own: other code asks {@link #isInYourTeam}.
 */
public class Teams extends Module {
    public final BooleanProperty scoreboard = new BooleanProperty("scoreboard-team", true);
    public final BooleanProperty nameColor = new BooleanProperty("name-color", true);
    public final BooleanProperty armorColor = new BooleanProperty("armor-color", true);
    public final BooleanProperty gommeSW = new BooleanProperty("gomme-sw", false);

    public Teams() {
        super("Teams", false);
    }

    /** @return true when the module is enabled and the entity is on the local player's team */
    public boolean isInYourTeam(EntityLivingBase entity) {
        if (!this.isEnabled() || entity == null) {
            return false;
        }
        EntityPlayer self = mc.thePlayer;
        if (self == null) {
            return false;
        }

        if (this.scoreboard.getValue() && self.getTeam() != null && entity.getTeam() != null
                && self.getTeam().isSameTeam(entity.getTeam())) {
            return true;
        }

        IChatComponent selfDisplay = self.getDisplayName();
        IChatComponent targetDisplay = entity.getDisplayName();
        boolean hasNames = selfDisplay != null && targetDisplay != null;
        String clientName = hasNames ? selfDisplay.getFormattedText().replace("§r", "") : "";
        String targetName = hasNames ? targetDisplay.getFormattedText().replace("§r", "") : "";

        if (this.gommeSW.getValue() && hasNames && targetName.length() > 1 && clientName.length() > 1) {
            if (targetName.startsWith("T") && clientName.startsWith("T")
                    && Character.isDigit(targetName.charAt(1)) && Character.isDigit(clientName.charAt(1))) {
                return targetName.charAt(1) == clientName.charAt(1);
            }
        }

        if (this.nameColor.getValue() && hasNames) {
            // Decides on its own: the armour check below is only reached with name-color off.
            if (clientName.length() < 2 || clientName.charAt(0) != '§') {
                return false;
            }
            return targetName.startsWith("§" + clientName.charAt(1));
        }

        if (this.armorColor.getValue()) {
            for (int i = 0; i < 4; i++) {
                ItemStack ownArmor = self.getCurrentArmor(i);
                ItemStack otherArmor = entity.getCurrentArmor(i);
                if (ownArmor == null || otherArmor == null) continue;
                if (!(ownArmor.getItem() instanceof ItemArmor) || !(otherArmor.getItem() instanceof ItemArmor)) continue;
                ItemArmor ownItem = (ItemArmor) ownArmor.getItem();
                ItemArmor otherItem = (ItemArmor) otherArmor.getItem();
                if (otherItem.getArmorMaterial() == ItemArmor.ArmorMaterial.LEATHER
                        && ownItem.getColor(ownArmor) == otherItem.getColor(otherArmor)) {
                    return true;
                }
            }
        }

        return false;
    }
}
