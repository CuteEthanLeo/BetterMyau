package myau.mixin;

import myau.via.ViaHooks;
import net.minecraft.network.play.client.C01PacketChatMessage;
import net.minecraftforge.fml.relauncher.Side;
import net.minecraftforge.fml.relauncher.SideOnly;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.Constant;
import org.spongepowered.asm.mixin.injection.ModifyConstant;

/** Cross-version: 1.11+ servers take chat messages of up to 256 characters, not 100. */
@SideOnly(Side.CLIENT)
@Mixin({C01PacketChatMessage.class})
public abstract class MixinC01PacketChatMessage {
    @ModifyConstant(method = "<init>(Ljava/lang/String;)V", constant = @Constant(intValue = 100), require = 0)
    private int bettermyau$chatLimit(int limit) {
        return ViaHooks.chatLimit(limit);
    }
}
