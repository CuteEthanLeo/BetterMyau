package myau.module.modules;

import myau.Myau;
import myau.enums.ChatColors;
import myau.event.EventTarget;
import myau.event.types.Priority;
import myau.events.Render2DEvent;
import myau.module.Module;
import myau.property.properties.*;
import myau.util.RenderUtil;
import myau.util.TeamUtil;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.ScaledResolution;
import net.minecraft.client.renderer.GlStateManager;
import net.minecraft.entity.player.EntityPlayer;
import org.lwjgl.opengl.GL11;

import java.awt.*;
import java.util.stream.Collectors;

public class Radar extends Module {
    private static final Minecraft mc = Minecraft.getMinecraft();
    public final ModeProperty colorMode = new ModeProperty("color", 0, new String[]{"DEFAULT", "TEAMS", "HUD"});
    public final IntProperty position = new IntProperty("position", 0, 0, 4);
    public final IntProperty offsetX = new IntProperty("offset-x", 60, 0, 1000, () -> position.getValue() != 4);
    public final IntProperty offsetY = new IntProperty("offset-y", 60, 0, 1000, () -> position.getValue() != 4);
    public final IntProperty radarRadius = new IntProperty("radar-radius", 55, 10, 200);
    public final FloatProperty dotRadius = new FloatProperty("dot-radius", 1.5F, 0.1F, 5.0F);
    public final BooleanProperty showPlayers = new BooleanProperty("players", true);
    public final BooleanProperty showFriends = new BooleanProperty("friends", true);
    public final BooleanProperty showEnemies = new BooleanProperty("enemies", true);
    public final BooleanProperty showBots = new BooleanProperty("bots", false);
    public final BooleanProperty showPVP = new BooleanProperty("show-pvp", false);
    public final ColorProperty fillColor = new ColorProperty("fill-color", Color.GRAY.getRGB());
    public final ColorProperty outlineColor = new ColorProperty("outline-color", Color.DARK_GRAY.getRGB());
    public final ColorProperty crossColor = new ColorProperty("cross-color", Color.LIGHT_GRAY.getRGB());
    public Radar() {
        super("Radar", false);
    }

    private boolean shouldRender(EntityPlayer entityPlayer) {
        if (entityPlayer.deathTime > 0) {
            return false;
        } else if (mc.getRenderViewEntity().getDistanceToEntity(entityPlayer) > 512.0F) {
            return false;
        } else if (entityPlayer != mc.thePlayer && entityPlayer != mc.getRenderViewEntity()) {
            if (TeamUtil.isBot(entityPlayer)) {
                return this.showBots.getValue();
            } else if (TeamUtil.isFriend(entityPlayer)) {
                return this.showFriends.getValue();
            } else {
                return TeamUtil.isTarget(entityPlayer) ? this.showEnemies.getValue() : this.showPlayers.getValue();
            }
        } else {
            return false;
        }
    }

    private Color getEntityColor(EntityPlayer entityPlayer) {
        if (TeamUtil.isFriend(entityPlayer)) {
            Color color = Myau.friendManager.getColor();
            return new Color(color.getRed(), color.getGreen(), color.getBlue(), 255);
        } else if (TeamUtil.isTarget(entityPlayer)) {
            Color color = Myau.targetManager.getColor();
            return new Color(color.getRed(), color.getGreen(), color.getBlue(), 255);
        } else {
            switch (this.colorMode.getValue()) {
                case 0:
                    return TeamUtil.getTeamColor(entityPlayer, 1.0F);
                case 1:
                    int teamColor = TeamUtil.isSameTeam(entityPlayer) ? ChatColors.BLUE.toAwtColor() : ChatColors.RED.toAwtColor();
                    return new Color(teamColor | 255 << 24, true);
                case 2:
                    int color = ((HUD) Myau.moduleManager.modules.get(HUD.class)).getColor(System.currentTimeMillis()).getRGB();
                    return new Color(color | 255 << 24, true);
                default:
                    return Color.WHITE;
            }
        }
    }

    @EventTarget(Priority.LOWEST)
    public void onRender(Render2DEvent event) {
        if (!this.isEnabled()) return;

        ScaledResolution sr = new ScaledResolution(mc);
        HUD hud = (HUD) Myau.moduleManager.modules.get(HUD.class);

        double centerX, centerY;
        if (position.getValue() == 4) {
            centerX = sr.getScaledWidth() / 2.0F;
            centerY = sr.getScaledHeight() / 2.0F;
        } else {
            centerX = (position.getValue() & 0x1) == 0x1 ? Math.max(sr.getScaledWidth() - offsetX.getValue(), 0) : Math.min(offsetX.getValue(), sr.getScaledWidth());
            centerY = (position.getValue() & 0x2) == 0x2 ? Math.max(sr.getScaledHeight() - offsetY.getValue(), 0) : Math.min(offsetY.getValue(), sr.getScaledHeight());
        }

        GlStateManager.pushMatrix();
        GlStateManager.scale(hud.scale.getValue(), hud.scale.getValue(), 1.0f);
        GlStateManager.translate(centerX, centerY, 0.0f);

        GlStateManager.disableDepth();

        float yaw = (float)Math.toRadians(mc.thePlayer.rotationYaw);
        if (mc.gameSettings.thirdPersonView != 2) {
            yaw += (float)Math.toRadians(180.0F);
        }
        double cos = Math.cos(yaw);
        double sin = Math.sin(yaw);

        // 圆形玻璃表盘：面板 + 用户填充色的淡染 + 描边 + 十字线 + 方位字母
        float radius = radarRadius.getValue();
        myau.ui.glass.Draw.useGuiScale();
        myau.ui.glass.SmoothFont labelFont = myau.ui.glass.SmoothFont.bold(7.5f);
        myau.ui.GlassTheme.panel(-radius, -radius, radius * 2, radius * 2, radius, 0.8f);
        myau.ui.glass.Draw.circle(0, 0, radius, myau.ui.glass.Draw.alpha(fillColor.getValue(), 0.16f));
        int rim = 0xff000000 | outlineColor.getValue();
        myau.ui.glass.Draw.outline(-radius, -radius, radius * 2, radius * 2, radius, 1.0f, myau.ui.glass.Draw.alpha(rim, 0.8f), myau.ui.glass.Draw.alpha(rim, 0.35f));
        float dx1 = (float) Math.sin(yaw), dy1 = (float) Math.cos(yaw);
        float dx2 = (float) Math.sin(yaw + Math.PI / 2), dy2 = (float) Math.cos(yaw + Math.PI / 2);
        int cross = myau.ui.glass.Draw.alpha(crossColor.getValue(), 0.45f);
        float inner = radius - 3;
        myau.ui.glass.Draw.polyline(0.75f, cross, -dx1 * inner, -dy1 * inner, dx1 * inner, dy1 * inner);
        myau.ui.glass.Draw.polyline(0.75f, cross, -dx2 * inner, -dy2 * inner, dx2 * inner, dy2 * inner);
        int labelColor = 0xff000000 | hud.getColor(System.currentTimeMillis()).getRGB();
        float labelAt = radius + 6, labelH = labelFont.height() / 2;
        labelFont.drawCentered("N", -dx1 * labelAt, -dy1 * labelAt - labelH, labelColor);
        labelFont.drawCentered("E", dx2 * labelAt, dy2 * labelAt - labelH, labelColor);
        labelFont.drawCentered("S", dx1 * labelAt, dy1 * labelAt - labelH, labelColor);
        labelFont.drawCentered("W", -dx2 * labelAt, -dy2 * labelAt - labelH, labelColor);
        for (EntityPlayer player : TeamUtil.getRenderEntitiesSorted().stream().filter(entity -> entity instanceof EntityPlayer && this.shouldRender((EntityPlayer) entity)).map(EntityPlayer.class::cast).collect(Collectors.toList())) {
            double dx = (player.lastTickPosX + (player.posX - player.lastTickPosX) * event.getPartialTicks()) - mc.thePlayer.posX;
            double dz = (player.lastTickPosZ + (player.posZ - player.lastTickPosZ) * event.getPartialTicks()) - mc.thePlayer.posZ;

            double relX = dx * cos + dz * sin;
            double relY = dz * cos - dx * sin;

            double dist = Math.sqrt(relX * relX + relY * relY);
            double scale = dist < radarRadius.getValue() ? 1.0F : radarRadius.getValue() / dist;
            double px = relX * scale;
            double py = relY * scale;

            int dot = 0xff000000 | getEntityColor(player).getRGB();
            float dotR = dotRadius.getValue();
            myau.ui.glass.Draw.glow((float) px - dotR, (float) py - dotR, dotR * 2, dotR * 2, dotR, 3.0f, myau.ui.glass.Draw.alpha(dot, 0.5f));
            myau.ui.glass.Draw.circle((float) px, (float) py, dotR, dot);

        }
        if (this.showPVP.getValue()) {
            double dx = - mc.thePlayer.posX;
            double dz = - mc.thePlayer.posZ;

            double relX = dx * cos + dz * sin;
            double relY = dz * cos - dx * sin;

            double dist = Math.sqrt(relX * relX + relY * relY);
            double scale = dist < radarRadius.getValue() * 2 ? 1.0F : radarRadius.getValue() * 2 / dist;
            double px = relX * scale;
            double py = relY * scale;
            // 原实现把坐标再缩小一半，这里保持同样的落点
            myau.ui.glass.SmoothFont pvpFont = myau.ui.glass.SmoothFont.bold(6.5f);
            float half = hud.scale.getValue() / 2.0f;
            pvpFont.drawCentered("PVP", (float) px * half, (float) py * half - pvpFont.height() / 2, myau.ui.GlassTheme.TEXT);
        }
        GlStateManager.enableDepth();
        GlStateManager.popMatrix();
    }
}
