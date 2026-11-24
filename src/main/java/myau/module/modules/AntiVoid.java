package myau.module.modules;

import myau.events.Render2DEvent;
import com.google.common.base.CaseFormat;
import myau.Myau;
import myau.enums.BlinkModules;
import myau.event.EventTarget;
import myau.event.types.EventType;
import myau.event.types.Priority;
import myau.events.BlockBBEvent;
import myau.events.KeyEvent;
import myau.events.LoadWorldEvent;
import myau.events.PacketEvent;
import myau.events.PlayerUpdateEvent;
import myau.events.Render3DEvent;
import myau.events.UpdateEvent;
import myau.module.Module;
import myau.util.FallingPlayer;
import myau.util.FxRenderUtil;
import myau.util.MoveUtil;
import myau.util.PacketUtil;
import myau.util.PlayerUtil;
import myau.util.RandomUtil;
import myau.property.properties.BooleanProperty;
import myau.property.properties.FloatProperty;
import myau.property.properties.IntProperty;
import myau.property.properties.ModeProperty;
import net.minecraft.block.BlockAir;
import net.minecraft.client.Minecraft;
import net.minecraft.client.entity.EntityPlayerSP;
import net.minecraft.client.gui.FontRenderer;
import net.minecraft.client.gui.Gui;
import net.minecraft.client.renderer.GlStateManager;
import net.minecraft.item.ItemEnderPearl;
import net.minecraft.item.ItemStack;
import net.minecraft.network.play.client.C03PacketPlayer;
import net.minecraft.network.play.client.C03PacketPlayer.C04PacketPlayerPosition;
import net.minecraft.network.play.server.S08PacketPlayerPosLook;
import net.minecraft.util.AxisAlignedBB;
import net.minecraft.util.BlockPos;
import org.lwjgl.opengl.GL11;

public class AntiVoid extends Module {
    private static final Minecraft mc = Minecraft.getMinecraft();
    private static final int BLINK = 0;
    private static final int TELEPORT_BACK = 1;
    private static final int FLY_FLAG = 2;
    private static final int ON_GROUND_SPOOF = 3;
    private static final int MOTION_TELEPORT_FLAG = 4;
    private static final int GHOST_BLOCK = 5;
    private boolean isInVoid = false;
    private boolean wasInVoid = false;
    private double[] lastSafePosition = null;

    // ---- state of the modes ported from xw ----
    /** Where the fall simulation lands, null when nothing is hit within 60 ticks. */
    private BlockPos detectedLocation = null;
    /** Fall distance at the last tick a landing spot within max-fall-distance was still in sight. */
    private float lastFound = 0.0F;
    private double prevX = 0.0;
    private double prevY = 0.0;
    private double prevZ = 0.0;
    private boolean hasGroundPosition = false;
    private volatile boolean shouldSimulateBlock = false;

    public final ModeProperty mode = new ModeProperty(
            "mode", 0, new String[]{"BLINK", "TELEPORT_BACK", "FLY_FLAG", "ON_GROUND_SPOOF", "MOTION_TELEPORT_FLAG", "GHOST_BLOCK"}
    );
    public final FloatProperty distance = new FloatProperty("distance", 5.0F, 0.0F, 16.0F, () -> this.mode.getValue() == BLINK);
    public final IntProperty maxFallDistance = new IntProperty("max-fall-distance", 10, 2, 255, () -> this.mode.getValue() != BLINK);
    public final FloatProperty maxDistanceToSetback = new FloatProperty(
            "max-distance-to-setback", 2.5F, 1.0F, 30.0F, () -> this.mode.getValue() != BLINK
    );
    public final BooleanProperty indicator = new BooleanProperty("indicator", true, () -> this.mode.getValue() != BLINK);

    private void resetBlink() {
        Myau.blinkManager.setBlinkState(false, BlinkModules.ANTI_VOID);
        this.lastSafePosition = null;
    }

    private void resetFallState() {
        this.detectedLocation = null;
        this.lastFound = 0.0F;
        this.prevX = 0.0;
        this.prevY = 0.0;
        this.prevZ = 0.0;
        this.hasGroundPosition = false;
        this.shouldSimulateBlock = false;
    }

    private boolean canUseAntiVoid() {
        LongJump longJump = (LongJump) Myau.moduleManager.modules.get(LongJump.class);
        return !longJump.isJumping();
    }

    public AntiVoid() {
        super("AntiVoid", false);
    }

    @EventTarget(Priority.LOWEST)
    public void onUpdate(PlayerUpdateEvent event) {
        if (this.isEnabled()) {
            this.isInVoid = !mc.thePlayer.capabilities.allowFlying && PlayerUtil.isInWater();
            if (this.mode.getValue() == 0) {
                if (!this.isInVoid) {
                    this.resetBlink();
                }
                if (this.lastSafePosition != null) {
                    float subWidth = mc.thePlayer.width / 2.0F;
                    float height = mc.thePlayer.height;
                    if (PlayerUtil.checkInWater(
                            new AxisAlignedBB(
                                    this.lastSafePosition[0] - (double) subWidth,
                                    this.lastSafePosition[1],
                                    this.lastSafePosition[2] - (double) subWidth,
                                    this.lastSafePosition[0] + (double) subWidth,
                                    this.lastSafePosition[1] + (double) height,
                                    this.lastSafePosition[2] + (double) subWidth
                            )
                    )) {
                        this.resetBlink();
                    }
                }
                if (!this.wasInVoid && this.isInVoid && this.canUseAntiVoid()) {
                    Myau.blinkManager.setBlinkState(false, BlinkModules.AUTO_BLOCK);
                    if (Myau.blinkManager.setBlinkState(true, BlinkModules.ANTI_VOID)) {
                        this.lastSafePosition = new double[]{mc.thePlayer.prevPosX, mc.thePlayer.prevPosY, mc.thePlayer.prevPosZ};
                    }
                }
                if (Myau.blinkManager.getBlinkingModule() == BlinkModules.ANTI_VOID
                        && this.lastSafePosition != null
                        && this.lastSafePosition[1] - (double) this.distance.getValue().floatValue() > mc.thePlayer.posY) {
                    Myau.blinkManager
                            .blinkedPackets
                            .offerFirst(
                                    new C04PacketPlayerPosition(
                                            this.lastSafePosition[0], this.lastSafePosition[1] - RandomUtil.nextDouble(10.0, 20.0), this.lastSafePosition[2], false
                                    )
                            );
                    this.resetBlink();
                }
            }
            this.wasInVoid = this.isInVoid;
        }
    }

    /** The modes ported from xw: act once the fall got longer than allowed with no landing spot in sight. */
    @EventTarget
    public void onTickUpdate(UpdateEvent event) {
        if (!this.isEnabled() || event.getType() != EventType.PRE || this.mode.getValue() == BLINK) {
            return;
        }
        this.detectedLocation = null;
        EntityPlayerSP player = mc.thePlayer;
        if (player.onGround && !(mc.theWorld.getBlockState(new BlockPos(player).down()).getBlock() instanceof BlockAir)) {
            this.prevX = player.prevPosX;
            this.prevY = player.prevPosY;
            this.prevZ = player.prevPosZ;
            this.hasGroundPosition = true;
            this.shouldSimulateBlock = false;
            // xw keeps the value of the previous fall here, which delays the next trigger by that much
            this.lastFound = 0.0F;
        }
        if (!player.onGround && !player.isOnLadder() && !player.isInWater()) {
            FallingPlayer.CollisionResult collision = new FallingPlayer(player).findCollision(60);
            this.detectedLocation = collision == null ? null : collision.getPos();
            if (this.detectedLocation != null
                    && Math.abs(player.posY - (double) this.detectedLocation.getY()) + (double) player.fallDistance <= (double) this.maxFallDistance.getValue()) {
                this.lastFound = player.fallDistance;
            }
            if (player.fallDistance - this.lastFound > this.maxDistanceToSetback.getValue() && this.canUseAntiVoid()) {
                if (this.mode.getValue() != BLINK) this.islandRescuedAt = System.currentTimeMillis();
                switch (this.mode.getValue()) {
                    case TELEPORT_BACK:
                        if (this.hasGroundPosition) {
                            player.setPositionAndUpdate(this.prevX, this.prevY, this.prevZ);
                            player.fallDistance = 0.0F;
                            player.motionY = 0.0;
                        }
                        break;
                    case FLY_FLAG:
                        player.motionY += 0.1;
                        player.fallDistance = 0.0F;
                        break;
                    case ON_GROUND_SPOOF:
                        PacketUtil.sendPacket(new C03PacketPlayer(true));
                        break;
                    case MOTION_TELEPORT_FLAG:
                        player.setPositionAndUpdate(player.posX, player.posY + 1.0, player.posZ);
                        PacketUtil.sendPacket(new C04PacketPlayerPosition(player.posX, player.posY, player.posZ, true));
                        player.motionY = 0.1;
                        MoveUtil.strafe();
                        player.fallDistance = 0.0F;
                        break;
                    case GHOST_BLOCK:
                        this.shouldSimulateBlock = true;
                        break;
                    default:
                        break;
                }
            }
        }
    }

    /** GHOST_BLOCK: every block below the feet (air included) collides like a full cube. */
    @EventTarget
    public void onBlockBB(BlockBBEvent event) {
        if (!this.shouldSimulateBlock || !this.isEnabled() || this.mode.getValue() != GHOST_BLOCK || mc.thePlayer == null) {
            return;
        }
        if (event.getY() < (int) mc.thePlayer.posY) {
            event.setBoundingBox(new AxisAlignedBB(
                    event.getX(), event.getY(), event.getZ(),
                    (double) event.getX() + 1.0, (double) event.getY() + 1.0, (double) event.getZ() + 1.0
            ));
        }
    }

    @EventTarget
    public void onPacket(PacketEvent event) {
        if (!this.isEnabled() || mc.thePlayer == null) {
            return;
        }
        // stop treating air as solid once the server set the player back
        if (event.getType() == EventType.RECEIVE && event.getPacket() instanceof S08PacketPlayerPosLook) {
            this.shouldSimulateBlock = false;
        }
    }

    @EventTarget
    public void onRender3D(Render3DEvent event) {
        if (!this.isEnabled() || mc.thePlayer == null || this.mode.getValue() == BLINK || !this.indicator.getValue()) {
            return;
        }
        BlockPos pos = this.detectedLocation;
        if (pos == null || (double) mc.thePlayer.fallDistance + (mc.thePlayer.posY - (double) (pos.getY() + 1)) < 3.0) {
            return;
        }
        double x = (double) pos.getX() - FxRenderUtil.renderX();
        double y = (double) pos.getY() - FxRenderUtil.renderY();
        double z = (double) pos.getZ() - FxRenderUtil.renderZ();

        GlStateManager.enableBlend();
        GlStateManager.tryBlendFuncSeparate(770, 771, 1, 0);
        GlStateManager.disableTexture2D();
        GlStateManager.disableCull();
        GlStateManager.disableDepth();
        GlStateManager.depthMask(false);
        FxRenderUtil.color(0xFF0000, 90);
        FxRenderUtil.filledBox(new AxisAlignedBB(x, y + 1.0, z, x + 1.0, y + 1.2, z + 1.0));
        GlStateManager.depthMask(true);
        GlStateManager.enableDepth();
        GlStateManager.enableCull();
        GlStateManager.enableTexture2D();
        GlStateManager.disableBlend();
        GlStateManager.resetColor();

        int fallDistance = (int) Math.floor((double) mc.thePlayer.fallDistance + (mc.thePlayer.posY - ((double) pos.getY() + 0.5)));
        this.renderNameTag(
                fallDistance + "m (~" + Math.max(0, fallDistance - 3) + " damage)",
                x + 0.5, y + 1.7, z + 0.5
        );
    }

    /** @param x,y,z position relative to the camera */
    private void renderNameTag(String text, double x, double y, double z) {
        FontRenderer font = mc.fontRendererObj;
        GlStateManager.pushMatrix();
        GlStateManager.translate(x, y, z);
        GL11.glNormal3f(0.0F, 1.0F, 0.0F);
        FxRenderUtil.billboard();
        GlStateManager.scale(-0.05F, -0.05F, 0.05F);
        GlStateManager.disableLighting();
        GlStateManager.disableDepth();
        GlStateManager.enableBlend();
        GlStateManager.tryBlendFuncSeparate(770, 771, 1, 0);
        int width = font.getStringWidth(text) / 2;
        Gui.drawRect(-width - 1, -1, width + 1, font.FONT_HEIGHT, Integer.MIN_VALUE);
        font.drawString(text, (float) -width, 0.0F, 0xFFFFFFFF, true);
        GlStateManager.enableDepth();
        GlStateManager.disableBlend();
        GlStateManager.resetColor();
        GlStateManager.popMatrix();
    }

    @EventTarget
    public void onKey(KeyEvent event) {
        if (event.getKey() == mc.gameSettings.keyBindUseItem.getKeyCode()) {
            ItemStack currentItem = mc.thePlayer.inventory.getCurrentItem();
            if (currentItem != null && currentItem.getItem() instanceof ItemEnderPearl) {
                this.resetBlink();
            }
        }
    }

    @EventTarget
    public void onLoadWorld(LoadWorldEvent event) {
        this.resetFallState();
    }

    @Override
    public void onEnabled() {
        this.isInVoid = false;
        this.wasInVoid = false;
        this.resetBlink();
        this.resetFallState();
    }

    @Override
    public void onDisabled() {
        Myau.blinkManager.setBlinkState(false, BlinkModules.ANTI_VOID);
        this.resetFallState();
    }

    @Override
    public void verifyValue(String mode) {
        if (this.isEnabled()) {
            this.onDisabled();
        }
    }

    @Override
    public String[] getSuffix() {
        return new String[]{CaseFormat.UPPER_UNDERSCORE.to(CaseFormat.UPPER_CAMEL, this.mode.getModeString())};
    }

    /** When AntiVoid last pulled you back, for the island. */
    private long islandRescuedAt;

    // ---------------------------------------------------------------- dynamic island


    /** Falling with no ground below: how close the setback is; then a short "rescued". (BLINK mode is HUD's blink.) */
    @EventTarget
    public void onIsland(Render2DEvent event) {
        if (!this.isEnabled() || mc.thePlayer == null) return;
        if (System.currentTimeMillis() - this.islandRescuedAt < 1500L) {
            myau.ui.glass.Island.live("antivoid", myau.ui.glass.Island.P_ALERT, "AntiVoid §frescued", myau.ui.GlassTheme.OK, -1);
            return;
        }
        if (this.mode.getValue() == BLINK || mc.thePlayer.onGround || this.detectedLocation != null) return;
        float over = mc.thePlayer.fallDistance - this.lastFound;
        float max = Math.max(0.1F, (float) this.maxDistanceToSetback.getValue());
        if (over < 0.5F) return;
        myau.ui.glass.Island.live("antivoid", myau.ui.glass.Island.P_ALERT, "Void §f" + String.format("%.1f", over) + "§7/" + String.format("%.1f", max),
                myau.ui.GlassTheme.BAD, Math.min(1.0F, over / max));
    }
}
