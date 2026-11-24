package myau.module.modules.flymodes;

import myau.Myau;
import myau.events.MoveEvent;
import myau.mixin.IAccessorRenderManager;
import myau.module.modules.Fly;
import myau.util.ChatUtil;
import myau.util.MoveUtil;
import myau.util.PacketUtil;
import net.minecraft.block.Block;
import net.minecraft.block.BlockLadder;
import net.minecraft.block.material.Material;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.GlStateManager;
import net.minecraft.client.renderer.Tessellator;
import net.minecraft.client.renderer.WorldRenderer;
import net.minecraft.client.renderer.vertex.DefaultVertexFormats;
import net.minecraft.network.Packet;
import net.minecraft.network.play.INetHandlerPlayClient;
import net.minecraft.network.play.client.C03PacketPlayer;
import net.minecraft.util.AxisAlignedBB;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

/** Helpers shared by the fly modes. */
public final class FlyUtil {
    private static final Minecraft mc = Minecraft.getMinecraft();

    private FlyUtil() {
    }

    public static String[] concat(String[] first, String[] second) {
        String[] result = new String[first.length + second.length];
        System.arraycopy(first, 0, result, 0, first.length);
        System.arraycopy(second, 0, result, first.length, second.length);
        return result;
    }

    /**
     * Sets the horizontal speed into the input direction. Without input nothing happens, unless
     * {@code stopWhenNoInput}: then the horizontal motion (and that of the event) is zeroed.
     */
    public static void strafe(double speed, boolean stopWhenNoInput, MoveEvent event) {
        if (mc.thePlayer == null) {
            return;
        }
        if (!MoveUtil.isMoving()) {
            if (stopWhenNoInput) {
                if (event != null) {
                    event.zeroXZ();
                }
                MoveUtil.stopXZ();
            }
            return;
        }
        MoveUtil.strafe(speed);
        if (event != null) {
            event.setX(mc.thePlayer.motionX);
            event.setZ(mc.thePlayer.motionZ);
        }
    }

    public static void strafe(double speed, boolean stopWhenNoInput) {
        FlyUtil.strafe(speed, stopWhenNoInput, null);
    }

    private static java.lang.reflect.Field speedInAirField;

    /** EntityPlayer.speedInAir is protected and has no accessor mixin, so it is reset reflectively. */
    public static void resetSpeedInAir() {
        if (mc.thePlayer == null) {
            return;
        }
        try {
            if (speedInAirField == null) {
                speedInAirField = net.minecraftforge.fml.relauncher.ReflectionHelper.findField(
                        net.minecraft.entity.player.EntityPlayer.class, "speedInAir", "field_71102_ce"
                );
            }
            speedInAirField.setFloat(mc.thePlayer, 0.02F);
        } catch (Exception ignored) {
        }
    }

    /** Sends a position packet through the normal pipeline (packet events fire). */
    public static void sendPosition(double x, double y, double z, boolean onGround) {
        PacketUtil.sendPacket(new C03PacketPlayer.C04PacketPlayerPosition(x, y, z, onGround));
    }

    /** Blocks the Collide / VulcanGhost / Jump modes put a fake collision box into. */
    public static boolean isPassable(Block block) {
        Material material = block.getMaterial();
        return !material.blocksMovement()
                && material != Material.carpet
                && material != Material.vine
                && material != Material.snow
                && !(block instanceof BlockLadder);
    }

    /** Chat line with the client prefix; safe to call from the network thread. */
    public static void chat(final String message) {
        if (mc.isCallingFromMinecraftThread()) {
            ChatUtil.sendFormatted(Myau.clientName + message + "&r");
        } else {
            mc.addScheduledTask(new Runnable() {
                @Override
                public void run() {
                    ChatUtil.sendFormatted(Myau.clientName + message + "&r");
                }
            });
        }
    }

    /** Turns Fly off; from the network thread this is deferred to the client thread. */
    public static void disable(final Fly fly) {
        if (mc.isCallingFromMinecraftThread()) {
            fly.setEnabled(false);
        } else {
            mc.addScheduledTask(new Runnable() {
                @Override
                public void run() {
                    fly.setEnabled(false);
                }
            });
        }
    }

    /** Handles held inbound packets on the client thread without firing packet events. */
    public static void processPackets(Collection<Packet<?>> packets) {
        if (packets.isEmpty()) {
            return;
        }
        final List<Packet<?>> copy = new ArrayList<Packet<?>>(packets);
        Runnable task = new Runnable() {
            @Override
            @SuppressWarnings("unchecked")
            public void run() {
                if (mc.getNetHandler() == null) {
                    return;
                }
                for (Packet<?> packet : copy) {
                    try {
                        ((Packet<INetHandlerPlayClient>) packet).processPacket(mc.getNetHandler());
                    } catch (Exception ignored) {
                    }
                }
            }
        };
        if (mc.isCallingFromMinecraftThread()) {
            task.run();
        } else {
            mc.addScheduledTask(task);
        }
    }

    /**
     * Flat translucent slab around the camera at world height {@code y}
     * ({@code size} blocks to each side, 0.02 thick). Call from a Render3D handler.
     */
    public static void drawPlatform(double y, int red, int green, int blue, int alpha, double size) {
        double renderY = y - ((IAccessorRenderManager) mc.getRenderManager()).getRenderPosY();
        AxisAlignedBB box = AxisAlignedBB.fromBounds(size, renderY + 0.02, size, -size, renderY, -size);
        GlStateManager.pushMatrix();
        GlStateManager.enableBlend();
        GlStateManager.tryBlendFuncSeparate(770, 771, 1, 0);
        GlStateManager.disableTexture2D();
        GlStateManager.disableLighting();
        GlStateManager.disableCull();
        GlStateManager.disableDepth();
        GlStateManager.depthMask(false);
        Tessellator tessellator = Tessellator.getInstance();
        WorldRenderer renderer = tessellator.getWorldRenderer();
        renderer.begin(7, DefaultVertexFormats.POSITION_COLOR);
        // bottom
        renderer.pos(box.minX, box.minY, box.minZ).color(red, green, blue, alpha).endVertex();
        renderer.pos(box.maxX, box.minY, box.minZ).color(red, green, blue, alpha).endVertex();
        renderer.pos(box.maxX, box.minY, box.maxZ).color(red, green, blue, alpha).endVertex();
        renderer.pos(box.minX, box.minY, box.maxZ).color(red, green, blue, alpha).endVertex();
        // top
        renderer.pos(box.minX, box.maxY, box.minZ).color(red, green, blue, alpha).endVertex();
        renderer.pos(box.minX, box.maxY, box.maxZ).color(red, green, blue, alpha).endVertex();
        renderer.pos(box.maxX, box.maxY, box.maxZ).color(red, green, blue, alpha).endVertex();
        renderer.pos(box.maxX, box.maxY, box.minZ).color(red, green, blue, alpha).endVertex();
        // north
        renderer.pos(box.minX, box.minY, box.minZ).color(red, green, blue, alpha).endVertex();
        renderer.pos(box.minX, box.maxY, box.minZ).color(red, green, blue, alpha).endVertex();
        renderer.pos(box.maxX, box.maxY, box.minZ).color(red, green, blue, alpha).endVertex();
        renderer.pos(box.maxX, box.minY, box.minZ).color(red, green, blue, alpha).endVertex();
        // south
        renderer.pos(box.minX, box.minY, box.maxZ).color(red, green, blue, alpha).endVertex();
        renderer.pos(box.maxX, box.minY, box.maxZ).color(red, green, blue, alpha).endVertex();
        renderer.pos(box.maxX, box.maxY, box.maxZ).color(red, green, blue, alpha).endVertex();
        renderer.pos(box.minX, box.maxY, box.maxZ).color(red, green, blue, alpha).endVertex();
        // west
        renderer.pos(box.minX, box.minY, box.minZ).color(red, green, blue, alpha).endVertex();
        renderer.pos(box.minX, box.minY, box.maxZ).color(red, green, blue, alpha).endVertex();
        renderer.pos(box.minX, box.maxY, box.maxZ).color(red, green, blue, alpha).endVertex();
        renderer.pos(box.minX, box.maxY, box.minZ).color(red, green, blue, alpha).endVertex();
        // east
        renderer.pos(box.maxX, box.minY, box.minZ).color(red, green, blue, alpha).endVertex();
        renderer.pos(box.maxX, box.maxY, box.minZ).color(red, green, blue, alpha).endVertex();
        renderer.pos(box.maxX, box.maxY, box.maxZ).color(red, green, blue, alpha).endVertex();
        renderer.pos(box.maxX, box.minY, box.maxZ).color(red, green, blue, alpha).endVertex();
        tessellator.draw();
        GlStateManager.depthMask(true);
        GlStateManager.enableDepth();
        GlStateManager.enableCull();
        GlStateManager.enableTexture2D();
        GlStateManager.disableBlend();
        GlStateManager.color(1.0F, 1.0F, 1.0F, 1.0F);
        GlStateManager.popMatrix();
    }
}
