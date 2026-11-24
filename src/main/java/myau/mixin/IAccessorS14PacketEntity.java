package myau.mixin;

import net.minecraft.network.play.server.S14PacketEntity;
import net.minecraftforge.fml.relauncher.Side;
import net.minecraftforge.fml.relauncher.SideOnly;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** Entity id of a relative-move packet, readable on the network thread without touching the world. */
@SideOnly(Side.CLIENT)
@Mixin({S14PacketEntity.class})
public interface IAccessorS14PacketEntity {
    @Accessor("entityId")
    int getEntityId();
}
