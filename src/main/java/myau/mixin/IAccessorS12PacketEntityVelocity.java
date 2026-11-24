package myau.mixin;

import net.minecraft.network.play.server.S12PacketEntityVelocity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(S12PacketEntityVelocity.class)
public interface IAccessorS12PacketEntityVelocity {
    @Accessor("motionX") void setMotionX(int value);
    @Accessor("motionY") void setMotionY(int value);
    @Accessor("motionZ") void setMotionZ(int value);
}
