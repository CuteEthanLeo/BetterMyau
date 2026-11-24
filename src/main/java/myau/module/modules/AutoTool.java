package myau.module.modules;

import myau.events.Render2DEvent;
import myau.Myau;
import myau.event.EventTarget;
import myau.event.types.EventType;
import myau.events.TickEvent;
import myau.module.Module;
import myau.util.ItemUtil;
import myau.util.KeyBindUtil;
import myau.property.properties.BooleanProperty;
import myau.property.properties.IntProperty;
import myau.util.TeamUtil;
import net.minecraft.client.Minecraft;
import net.minecraft.util.MovingObjectPosition.MovingObjectType;

public class AutoTool extends Module {
    private static final Minecraft mc = Minecraft.getMinecraft();
    private int currentToolSlot = -1;
    private int previousSlot = -1;
    private int tickDelayCounter = 0;
    public final IntProperty switchDelay = new IntProperty("delay", 0, 0, 5);
    public final BooleanProperty switchBack = new BooleanProperty("switch-back", true);
    public final BooleanProperty sneakOnly = new BooleanProperty("sneak-only", false);

    public AutoTool() {
        super("AutoTool", false);
    }

    public boolean isKillAura() {
        KillAura killAura = (KillAura) Myau.moduleManager.modules.get(KillAura.class);
        if (!killAura.isEnabled()) return false;
        return TeamUtil.isEntityLoaded(killAura.getTarget()) && killAura.isAttackAllowed();
    }

    @EventTarget
    public void onTick(TickEvent event) {
        if (this.isEnabled() && event.getType() == EventType.PRE) {
            if (this.currentToolSlot != -1 && this.currentToolSlot != mc.thePlayer.inventory.currentItem) {
                this.currentToolSlot = -1;
                this.previousSlot = -1;
            }
            if (mc.objectMouseOver != null
                    && mc.objectMouseOver.typeOfHit == MovingObjectType.BLOCK
                    && mc.gameSettings.keyBindAttack.isKeyDown()
                    && !mc.thePlayer.isUsingItem()
                    && !isKillAura()) {
                if (this.tickDelayCounter >= this.switchDelay.getValue()
                        && (!(Boolean) this.sneakOnly.getValue() || KeyBindUtil.isKeyDown(mc.gameSettings.keyBindSneak.getKeyCode()))) {
                    int slot = ItemUtil.findInventorySlot(
                            mc.thePlayer.inventory.currentItem, mc.theWorld.getBlockState(mc.objectMouseOver.getBlockPos()).getBlock()
                    );
                    if (mc.thePlayer.inventory.currentItem != slot) {
                        if (this.previousSlot == -1) {
                            this.previousSlot = mc.thePlayer.inventory.currentItem;
                        }
                        mc.thePlayer.inventory.currentItem = this.currentToolSlot = slot;
                    }
                }
                this.tickDelayCounter++;
            } else {
                if (this.switchBack.getValue() && this.previousSlot != -1) {
                    mc.thePlayer.inventory.currentItem = this.previousSlot;
                }
                this.currentToolSlot = -1;
                this.previousSlot = -1;
                this.tickDelayCounter = 0;
            }
        }
    }

    @Override
    public void onDisabled() {
        this.currentToolSlot = -1;
        this.previousSlot = -1;
        this.tickDelayCounter = 0;
    }

    private final net.minecraft.item.ItemStack[] islandIconRow = new net.minecraft.item.ItemStack[2];

    // ---------------------------------------------------------------- dynamic island


    /** While mining with a tool it picked: the tool and the block as icons, the block's break progress as the bar. */
    @EventTarget
    public void onIsland(Render2DEvent event) {
        if (!this.isEnabled() || mc.thePlayer == null || this.currentToolSlot == -1) return;
        if (mc.objectMouseOver == null || mc.objectMouseOver.typeOfHit != MovingObjectType.BLOCK) return;
        net.minecraft.util.BlockPos pos = mc.objectMouseOver.getBlockPos();
        net.minecraft.block.Block block = mc.theWorld.getBlockState(pos).getBlock();
        net.minecraft.item.ItemStack tool = mc.thePlayer.inventory.getStackInSlot(this.currentToolSlot);
        net.minecraft.item.Item blockItem = net.minecraft.item.Item.getItemFromBlock(block);
        this.islandIconRow[0] = tool;
        this.islandIconRow[1] = blockItem == null ? null : new net.minecraft.item.ItemStack(blockItem, 1, block.getDamageValue(mc.theWorld, pos));
        if (tool == null) {
            this.islandIconRow[0] = this.islandIconRow[1];
            this.islandIconRow[1] = null;
        }
        float progress = ((myau.mixin.IAccessorPlayerControllerMP) mc.playerController).getCurBlockDamageMP();
        myau.ui.glass.Island.live("autotool", myau.ui.glass.Island.P_PROGRESS, "Mining §f" + block.getLocalizedName(), myau.ui.GlassTheme.accent(),
                Math.max(0.0F, Math.min(1.0F, progress)), this.islandIconRow, this.currentToolSlot);
    }
}
