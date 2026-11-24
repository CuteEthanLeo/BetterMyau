package myau.mixin;

import myau.Myau;
import myau.event.EventManager;
import myau.event.types.EventType;
import myau.events.*;
import myau.module.modules.NoHitDelay;
import myau.util.WindowFocus;
import net.minecraft.client.Minecraft;
import net.minecraft.client.entity.EntityPlayerSP;
import net.minecraft.client.gui.GuiScreen;
import net.minecraft.client.multiplayer.PlayerControllerMP;
import net.minecraft.client.multiplayer.WorldClient;
import net.minecraft.client.settings.KeyBinding;
import net.minecraft.entity.player.InventoryPlayer;
import net.minecraftforge.fml.relauncher.Side;
import net.minecraftforge.fml.relauncher.SideOnly;
import org.lwjgl.BufferUtils;
import org.lwjgl.opengl.Display;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyVariable;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import javax.imageio.ImageIO;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.InputStream;
import java.nio.ByteBuffer;

@SideOnly(Side.CLIENT)
@Mixin(value = {Minecraft.class}, priority = 9999)
public abstract class MixinMinecraft {
    @Shadow
    private int leftClickCounter;
    @Shadow
    public PlayerControllerMP playerController;
    @Shadow
    public WorldClient theWorld;
    @Shadow
    public EntityPlayerSP thePlayer;
    @Shadow
    public GuiScreen currentScreen;

    @Inject(
            method = {"startGame"},
            at = {@At("RETURN")}
    )
    private void postStartGame(CallbackInfo callbackInfo) {
        new Myau();
        // The first main menu was shown before the config existed (with the glass menu as the default).
        if (this.currentScreen != null && this.currentScreen.getClass() == myau.ui.glass.GlassMainMenu.class
                && !myau.module.modules.HUD.glassMainMenu()) {
            ((Minecraft) (Object) this).displayGuiScreen(new net.minecraft.client.gui.GuiMainMenu());
        }
        Display.setTitle(WINDOW_TITLE);
        setCustomIcon();
    }

    private static final String WINDOW_TITLE = "BetterMyau for Minecraft 1.8.9";

    /**
     * The window gets its title and icon as soon as it exists, not only once loading has finished,
     * and is brought to the front so the boot screen is seen even when the launcher started it minimised.
     */
    @Inject(method = "createDisplay", at = @At("RETURN"), require = 0)
    private void bettermyau$earlyTitle(CallbackInfo callbackInfo) {
        Display.setTitle(WINDOW_TITLE);
        setCustomIcon();
        WindowFocus.bringToFront();
    }

    // ── Borderless fullscreen (HUD "borderless-fullscreen"): F11 and a fullscreen start make a
    //    window the size of the monitor instead of switching to exclusive mode.
    @Shadow
    private boolean fullscreen;

    @Inject(method = "toggleFullscreen", at = @At("HEAD"), cancellable = true)
    private void bettermyau$borderlessToggle(CallbackInfo callbackInfo) {
        boolean enter = !this.fullscreen;
        // Leaving is ours whenever the window is the borderless one, whatever the setting says now.
        if (enter ? !myau.module.modules.HUD.borderlessFullscreen() : !myau.util.BorderlessWindow.active()) return;
        callbackInfo.cancel();
        myau.util.BorderlessWindow.toggle((Minecraft) (Object) this, enter);
    }

    @Inject(method = "setInitialDisplayMode", at = @At("HEAD"), cancellable = true)
    private void bettermyau$borderlessStart(CallbackInfo callbackInfo) throws org.lwjgl.LWJGLException {
        if (!this.fullscreen || !myau.util.BorderlessWindow.rememberedOn()) return;
        callbackInfo.cancel();
        myau.util.BorderlessWindow.startFullscreen((Minecraft) (Object) this);
    }

    @Redirect(method = "createDisplay", at = @At(value = "INVOKE", target = "Lorg/lwjgl/opengl/Display;setResizable(Z)V", remap = false), require = 0)
    private void bettermyau$borderlessNotResizable(boolean resizable) {
        Display.setResizable(resizable && !myau.util.BorderlessWindow.active());
    }

    /**
     * Only reached when Forge's animated splash is off (it replaces this call otherwise): the
     * same black screen with the name instead of the Mojang logo.
     */
    @Inject(method = "drawSplashScreen", at = @At("HEAD"), cancellable = true, require = 0)
    private void bettermyau$bootSplash(net.minecraft.client.renderer.texture.TextureManager textureManager, CallbackInfo callbackInfo) {
        if (myau.ui.glass.BootScreen.staticFrame()) callbackInfo.cancel();
    }

    // 🖼 自定义窗口图标
    private static void setCustomIcon() {
        try (InputStream stream = MixinMinecraft.class.getResourceAsStream("/TUBIAO.png")) {
            if (stream == null) return;
            BufferedImage original = ImageIO.read(stream);
            if (original == null) return;

            // 生成 16x16 + 32x32 双尺寸供 Windows 选择
            ByteBuffer[] icons = new ByteBuffer[]{
                toIconBuffer(original, 16),
                toIconBuffer(original, 32)
            };
            Display.setIcon(icons);
        } catch (Exception e) {
            Myau.LOGGER.warn("Failed to set the window icon", e);
        }
    }

    private static ByteBuffer toIconBuffer(BufferedImage src, int size) {
        BufferedImage img = new BufferedImage(size, size, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = img.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
        g.drawImage(src, 0, 0, size, size, null);
        g.dispose();

        ByteBuffer buf = BufferUtils.createByteBuffer(size * size * 4);
        int[] pixels = img.getRGB(0, 0, size, size, null, 0, size);
        for (int y = 0; y < size; y++) {
            for (int x = 0; x < size; x++) {
                int px = pixels[y * size + x];
                buf.put((byte) ((px >> 16) & 0xFF)); // R
                buf.put((byte) ((px >> 8)  & 0xFF)); // G
                buf.put((byte) ( px        & 0xFF)); // B
                buf.put((byte) ((px >> 24) & 0xFF)); // A
            }
        }
        buf.flip();
        return buf;
    }

    @Inject(
            method = {"runTick"},
            at = {@At("HEAD")}
    )
    private void runTick(CallbackInfo callbackInfo) {
        myau.util.BorderlessWindow.tick((Minecraft) (Object) this);
        if (this.theWorld != null && this.thePlayer != null) {
            EventManager.call(new TickEvent(EventType.PRE));
        }
    }

    @Inject(
            method = {"runTick"},
            at = {@At("RETURN")}
    )
    private void postRunTick(CallbackInfo callbackInfo) {
        if (this.theWorld != null && this.thePlayer != null) {
            EventManager.call(new TickEvent(EventType.POST));
        }
    }

    @Inject(
            method = {"loadWorld(Lnet/minecraft/client/multiplayer/WorldClient;Ljava/lang/String;)V"},
            at = {@At("HEAD")}
    )
    private void loadWorld(WorldClient worldClient, String string, CallbackInfo callbackInfo) {
        myau.ui.glass.ScreenTransition.reset();
        EventManager.call(new LoadWorldEvent());
    }

    // ── Screen transitions: remember which screen was replaced. Vanilla closes and opens
    //    screens exactly as before; only state is recorded here (this can run off-thread).
    @org.spongepowered.asm.mixin.Unique
    private GuiScreen bettermyau$replacedScreen;

    // ── Glass versions of the main menu, world list and server list, from wherever they are
    //    opened (main menu, "back" on the disconnect screen, other mods). Subclasses are someone
    //    else's and stay. See ScreenSwap.
    @ModifyVariable(method = {"displayGuiScreen"}, at = @At("HEAD"), argsOnly = true)
    private GuiScreen bettermyau$glassScreens(GuiScreen screen) {
        return myau.ui.glass.ScreenSwap.swap(screen);
    }

    // ── displayGuiScreen(null) without a world builds its own GuiMainMenu after HEAD; catch it
    //    before Forge's GuiOpenEvent, so a custom main menu mod can still replace ours.
    @ModifyVariable(method = {"displayGuiScreen"}, argsOnly = true, require = 0,
            at = @At(value = "NEW", target = "net/minecraftforge/client/event/GuiOpenEvent", remap = false))
    private GuiScreen bettermyau$glassMainMenu(GuiScreen screen) {
        return myau.ui.glass.ScreenSwap.swap(screen);
    }

    // ── Remember the world being started, for the main menu's resume entry.
    @Inject(method = {"launchIntegratedServer"}, at = @At("HEAD"))
    private void bettermyau$rememberWorld(String folderName, String worldName, net.minecraft.world.WorldSettings settings, CallbackInfo ci) {
        try {
            if (!((Minecraft) (Object) this).isDemo()) myau.ui.glass.LastPlayed.world(folderName, worldName);
        } catch (RuntimeException ignored) {
            // Never let bookkeeping stop a world from loading.
        }
    }

    @Inject(method = {"displayGuiScreen"}, at = {@At("HEAD")})
    private void bettermyau$beforeDisplayScreen(GuiScreen screen, CallbackInfo callbackInfo) {
        this.bettermyau$replacedScreen = this.currentScreen;
    }

    @Inject(method = {"displayGuiScreen"}, at = {@At("RETURN")})
    private void bettermyau$afterDisplayScreen(GuiScreen screen, CallbackInfo callbackInfo) {
        GuiScreen old = this.bettermyau$replacedScreen;
        this.bettermyau$replacedScreen = null;
        if (old != this.currentScreen) {
            myau.ui.glass.ScreenTransition.onSwitch(old);
        }
    }

    @Inject(
            method = {"updateFramebufferSize"},
            at = {@At("RETURN")}
    )
    private void updateFramebufferSize(CallbackInfo callbackInfo) {
        myau.ui.glass.ScreenTransition.reset();
        EventManager.call(new ResizeEvent());
    }

    @Inject(
            method = {"clickMouse"},
            at = {@At("HEAD")},
            cancellable = true
    )
    private void clickMouse(CallbackInfo callbackInfo) {
        if (Myau.moduleManager != null && Myau.moduleManager.modules.get(NoHitDelay.class).isEnabled()) {
            this.leftClickCounter = 0;
        }
        LeftClickMouseEvent event = new LeftClickMouseEvent();
        EventManager.call(event);
        if (event.isCancelled()) {
            callbackInfo.cancel();
        }
    }

    @Inject(
            method = {"rightClickMouse"},
            at = {@At("HEAD")},
            cancellable = true
    )
    private void rightClickMouse(CallbackInfo callbackInfo) {
        RightClickMouseEvent event = new RightClickMouseEvent();
        EventManager.call(event);
        if (event.isCancelled()) {
            callbackInfo.cancel();
        }
    }

    @Inject(
            method = {"sendClickBlockToController"},
            at = {@At("HEAD")},
            cancellable = true
    )
    private void sendClickBlockToController(CallbackInfo callbackInfo) {
        HitBlockEvent event = new HitBlockEvent();
        EventManager.call(event);
        if (event.isCancelled()) {
            callbackInfo.cancel();
            this.playerController.resetBlockRemoving();
        }
    }

    @Redirect(
            method = {"runTick"},
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/client/settings/KeyBinding;setKeyBindState(IZ)V"
            )
    )
    private void setKeyBindState(int integer, boolean boolean2) {
        KeyBinding.setKeyBindState(integer, boolean2);
        this.bettermyau$keyOpenedScreen = false;
        if (boolean2 && this.currentScreen == null) {
            EventManager.call(new KeyEvent(integer));
            if (this.currentScreen != null) {
                // A module bound to this key opened a screen (music player, click GUI).
                this.bettermyau$keyOpenedScreen = true;
                myau.util.OpeningKey.opened(integer);
            }
        }
    }

    /** Set while the key event being handled is the one that just opened a screen. */
    @org.spongepowered.asm.mixin.Unique
    private boolean bettermyau$keyOpenedScreen;

    /**
     * Vanilla hands every pressed key to the current screen right after updating the key
     * bindings; when that very key press opened the screen, the screen would get it too, and a
     * player bound to V would open with "v" typed into its search box.
     */
    @Redirect(
            method = {"runTick"},
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/client/gui/GuiScreen;handleKeyboardInput()V"
            ),
            require = 0
    )
    private void bettermyau$keyToScreen(GuiScreen screen) throws java.io.IOException {
        if (this.bettermyau$keyOpenedScreen) {
            this.bettermyau$keyOpenedScreen = false;
            return;
        }
        screen.handleKeyboardInput();
    }

    @Redirect(
            method = {"runTick"},
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/entity/player/InventoryPlayer;changeCurrentItem(I)V"
            )
    )
    private void changeCurrentItem(InventoryPlayer inventoryPlayer, int slot) {
        SwapItemEvent event = new SwapItemEvent(-1, slot);
        EventManager.call(event);
        if (!event.isCancelled()) {
            inventoryPlayer.changeCurrentItem(slot);
        }
    }
}
