package myau.mixin;

import net.minecraft.client.gui.GuiPlayerTabOverlay;
import net.minecraft.scoreboard.ScoreObjective;
import net.minecraft.scoreboard.Scoreboard;
import net.minecraftforge.fml.relauncher.Side;
import net.minecraftforge.fml.relauncher.SideOnly;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@SideOnly(Side.CLIENT)
@Mixin({GuiPlayerTabOverlay.class})
public abstract class MixinGuiPlayerTabOverlay {
    /**
     * While the dynamic island has grown into the player list, the vanilla list (drawn later in
     * the frame, right over the island) stays away.
     */
    @Inject(method = {"renderPlayerlist"}, at = {@At("HEAD")}, cancellable = true)
    private void bettermyau$islandPlayerList(int width, Scoreboard scoreboard, ScoreObjective objective, CallbackInfo callbackInfo) {
        if (myau.ui.glass.Island.ownsTab()) {
            callbackInfo.cancel();
        }
    }
}
