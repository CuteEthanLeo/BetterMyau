package myau.module.modules;

import myau.Myau;
import myau.enums.BlinkModules;
import myau.enums.ChatColors;
import myau.event.EventTarget;
import myau.event.types.EventType;
import myau.events.Render2DEvent;
import myau.events.TickEvent;
import myau.mixin.IAccessorGuiChat;
import myau.module.Module;
import myau.util.ColorUtil;
import myau.util.RenderUtil;
import myau.util.BlurUtil;
import myau.property.properties.*;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Gui;
import net.minecraft.client.gui.GuiChat;
import net.minecraft.client.gui.ScaledResolution;
import net.minecraft.client.renderer.GlStateManager;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.util.ResourceLocation;
import org.lwjgl.opengl.GL11;

import javax.imageio.ImageIO;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.io.InputStream;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.stream.Collectors;

public class HUD extends Module {
    private static final Minecraft mc = Minecraft.getMinecraft();
    private List<Module> activeModules = new ArrayList<>();
    // Per-frame row data for the module list, reused between frames.
    private String[] rowNames = new String[0];
    private String[][] rowSuffixes = new String[0][];
    private float[] rowWidths = new float[0];
    private final java.util.IdentityHashMap<Module, Integer> rowSlot = new java.util.IdentityHashMap<>();
    // How far each row of the module list has slid in (0..1); rows linger here while sliding out.
    private final java.util.LinkedHashMap<Module, myau.ui.glass.Anim.Smooth> listShow = new java.util.LinkedHashMap<>();

    // 使用线程安全的队列存储通知
    private static final CopyOnWriteArrayList<Notification> notifications = new CopyOnWriteArrayList<>();

    // 🖼 更安全、更符合 MC 管线的原生动态纹理
    private DynamicTexture iconTexture;
    private ResourceLocation iconResourceLocation;

    public final ModeProperty colorMode = new ModeProperty(
            "color", 3, new String[]{"RAINBOW", "CHROMA", "ASTOLFO", "CUSTOM1", "CUSTOM12", "CUSTOM123"}
    );
    public final FloatProperty colorSpeed = new FloatProperty("color-speed", 1.0F, 0.5F, 1.5F);
    public final PercentProperty colorSaturation = new PercentProperty("color-saturation", 50);
    public final PercentProperty colorBrightness = new PercentProperty("color-brightness", 100);
    public final ColorProperty custom1 = new ColorProperty("custom-color-1", Color.WHITE.getRGB(), () -> this.colorMode.getValue() == 3 || this.colorMode.getValue() == 4 || this.colorMode.getValue() == 5);
    public final ColorProperty custom2 = new ColorProperty("custom-color-2", Color.WHITE.getRGB(), () -> this.colorMode.getValue() == 4 || this.colorMode.getValue() == 5);
    public final ColorProperty custom3 = new ColorProperty("custom-color-3", Color.WHITE.getRGB(), () -> this.colorMode.getValue() == 5);
    public final ModeProperty posX = new ModeProperty("position-x", 0, new String[]{"LEFT", "RIGHT"});
    public final ModeProperty posY = new ModeProperty("position-y", 0, new String[]{"TOP", "BOTTOM"});
    public final IntProperty offsetX = new IntProperty("offset-x", 2, 0, 255);
    public final IntProperty offsetY = new IntProperty("offset-y", 15, 0, 255);
    public final FloatProperty scale = new FloatProperty("scale", 1.0F, 0.5F, 1.5F);

    public final BooleanProperty blurBackground = new BooleanProperty("blur-background", true);
    public final PercentProperty background = new PercentProperty("background", 25);

    public final BooleanProperty showBar = new BooleanProperty("bar", true);
    public final BooleanProperty shadow = new BooleanProperty("shadow", true);
    public final BooleanProperty suffixes = new BooleanProperty("suffixes", true);
    public final BooleanProperty lowerCase = new BooleanProperty("lower-case", false);
    public final BooleanProperty chatOutline = new BooleanProperty("chat-outline", true);
    public final BooleanProperty blinkTimer = new BooleanProperty("blink-timer", true);
    public final BooleanProperty toggleSound = new BooleanProperty("toggle-sounds", true);
    public final BooleanProperty uiSounds = new BooleanProperty("ui-sounds", true);
    public final PercentProperty uiSoundVolume = new PercentProperty("ui-sound-volume", 60);
    // Glass styling for vanilla screens (pause menu, options, server/world lists, containers)
    public final BooleanProperty glassVanillaScreens = new BooleanProperty("glass-vanilla-screens", true);
    // Inventory, chests, furnace and the like: a glass panel with slot wells instead of the grey texture
    public final BooleanProperty glassContainers = new BooleanProperty("glass-containers", true, () -> this.glassVanillaScreens.getValue());
    // Item and button hover text as a glass card
    public final BooleanProperty glassTooltips = new BooleanProperty("glass-tooltips", true, () -> this.glassVanillaScreens.getValue());
    // The multiplayer screen rebuilt in glass: search, drag to reorder, details panel, quick connect
    public final BooleanProperty glassServerList = new BooleanProperty("glass-server-list", true, () -> this.glassVanillaScreens.getValue());
    // The main menu redrawn as a left-aligned menu over the scene (off: the centred glass panel)
    public final BooleanProperty glassMainMenu = new BooleanProperty("glass-main-menu", true, () -> this.glassVanillaScreens.getValue());
    // The singleplayer world list rebuilt in glass: search, cards, details panel
    public final BooleanProperty glassWorldList = new BooleanProperty("glass-world-list", true, () -> this.glassVanillaScreens.getValue());
    // Motion: every glass animation follows these two
    public final BooleanProperty animations = new BooleanProperty("animations", true);
    public final FloatProperty animationSpeed = new FloatProperty("animation-speed", 1.0F, 0.5F, 2.0F, () -> this.animations.getValue());
    // Liquid glass: refraction, dispersion and rim light on panels
    public final BooleanProperty liquidGlass = new BooleanProperty("liquid-glass", true);
    // Liquid glass NextGen (once "Better"; the setting keeps its name for old configs): one clear
    // slab that magnifies the scene and bends it at its edge, highlights follow the panel's place,
    // the camera and the surroundings
    public final BooleanProperty betterLiquidGlass = new BooleanProperty("better-liquid-glass", true, () -> this.liquidGlass.getValue());
    public final PercentProperty liquidStrength = new PercentProperty("liquid-strength", 60, () -> this.liquidGlass.getValue());
    // How clear NextGen glass is: 0 a bright light frost, 100 nearly clear
    public final PercentProperty liquidClarity = new PercentProperty("liquid-clarity", 70, () -> this.liquidGlass.getValue() && this.betterLiquidGlass.getValue());
    public final BooleanProperty hoverSound = new BooleanProperty("ui-hover-sound", false, () -> this.uiSounds.getValue());
    // The black loading screen with the name and a progress bar, instead of Forge's (takes effect on the next start)
    public final BooleanProperty bootScreen = new BooleanProperty("boot-screen", true);
    // The one-time opening of the main menu and its startup sound
    public final BooleanProperty intro = new BooleanProperty("intro", true);
    // The scene behind the main menu and every screen outside a world (see MenuBackdrop)
    public final ModeProperty menuBackground = new ModeProperty("menu-background", 4, new String[]{"AURORA", "NIGHT", "PANORAMA", "MINIMAL", "PLASMA"});
    // Picking up and placing items, switching hotbar slots, containers opening
    public final BooleanProperty inventorySounds = new BooleanProperty("inventory-sounds", true, () -> this.uiSounds.getValue());
    // Input method (Chinese and the like) only while typing: off in game so WASD are never eaten by it
    public final BooleanProperty imeAutoSwitch = new BooleanProperty("ime-auto-switch", true);
    // F11 makes a borderless window the size of the monitor instead of exclusive fullscreen
    public final BooleanProperty borderlessFullscreen = new BooleanProperty("borderless-fullscreen", true);

    // 【商业化通知开关】开启后激活右下角毛玻璃高端弹窗
    public final BooleanProperty toggleAlerts = new BooleanProperty("toggle-alerts", true);

    // 🏝 灵动岛开关
    public final BooleanProperty dynamicIsland = new BooleanProperty("dynamic-island", true);
    // Size of the island, percent of its designed size
    public final IntProperty islandScale = new IntProperty("island-scale", 100, 80, 180, () -> this.dynamicIsland.getValue());
    // Holding the player list key grows the island into the player list instead of drawing the vanilla one over it
    public final BooleanProperty islandTabList = new BooleanProperty("island-tab-list", true, () -> this.dynamicIsland.getValue());
    // Where toggle alerts and notifications appear
    public final ModeProperty notifyMode = new ModeProperty("notifications", 0, new String[]{"ISLAND", "CORNER", "BOTH"}, () -> this.toggleAlerts.getValue());
    // Where block counts, break progress and timers appear
    public final ModeProperty indicators = new ModeProperty("indicators", 0, new String[]{"ISLAND", "CROSSHAIR", "BOTH"});

    public HUD() {
        super("HUD", true, true);
    }

    @Override
    public void onEnabled() {
        // 首次开启加载纹理
        loadIconTexture();
    }

    /**
     * A module was switched. On the island this is a toggle, which is shown at once instead of
     * waiting behind the previous one (fast on/off used to lag seconds behind).
     */
    public static void showNotification(String moduleName, boolean enabled) {
        HUD hud = instance();
        int mode = hud == null ? 1 : hud.notifyMode.getValue();
        boolean island = hud != null && hud.dynamicIsland.getValue() && mc.thePlayer != null;
        if (mode != 1 && island) {
            myau.ui.glass.Island.toggle(moduleName, enabled);
        }
        if (mode != 0 || !island) {
            notifications.add(new Notification(moduleName, enabled ? "Enabled" : "Disabled", enabled));
        }
    }

    /** General notification (not a module toggle); plays the notification sound. */
    public static void notify(String title, String message, boolean positive) {
        post(title, message, positive, 2.5f);
        myau.ui.glass.UiSound.NOTIFY.play();
    }

    /** True when the multiplayer screen should be replaced by the glass one. */
    public static boolean glassServerList() {
        try {
            if (Myau.moduleManager == null) return true;
            HUD hud = (HUD) Myau.moduleManager.modules.get(HUD.class);
            return hud == null || (hud.glassVanillaScreens.getValue() && hud.glassServerList.getValue());
        } catch (RuntimeException e) {
            return true;
        }
    }

    /** True when the main menu should be replaced by the left-aligned glass one. */
    public static boolean glassMainMenu() {
        try {
            if (Myau.moduleManager == null) return true;
            HUD hud = (HUD) Myau.moduleManager.modules.get(HUD.class);
            return hud == null || (hud.glassVanillaScreens.getValue() && hud.glassMainMenu.getValue());
        } catch (RuntimeException e) {
            return true;
        }
    }

    /** True when the singleplayer world list should be replaced by the glass one. */
    public static boolean glassWorldList() {
        try {
            if (Myau.moduleManager == null) return true;
            HUD hud = (HUD) Myau.moduleManager.modules.get(HUD.class);
            return hud == null || (hud.glassVanillaScreens.getValue() && hud.glassWorldList.getValue());
        } catch (RuntimeException e) {
            return true;
        }
    }

    /** True when vanilla screens should get the glass treatment. */
    public static boolean glassScreens() {
        try {
            if (Myau.moduleManager == null) return true;
            HUD hud = (HUD) Myau.moduleManager.modules.get(HUD.class);
            return hud == null || hud.glassVanillaScreens.getValue();
        } catch (RuntimeException e) {
            return true;
        }
    }

    /** True when the standard containers should lose their grey texture (needs {@link #glassScreens()} too). */
    public static boolean glassContainers() {
        try {
            HUD hud = instance();
            return hud == null || hud.glassContainers.getValue();
        } catch (RuntimeException e) {
            return true;
        }
    }

    /** True when the input method should be switched off whenever nothing takes text. */
    public static boolean imeAutoSwitch() {
        try {
            HUD hud = instance();
            return hud == null || hud.imeAutoSwitch.getValue();
        } catch (RuntimeException e) {
            return true;
        }
    }

    /** True when fullscreen should be a borderless window (the window code reads it before configs load too). */
    public static boolean borderlessFullscreen() {
        try {
            HUD hud = instance();
            return hud == null ? myau.util.BorderlessWindow.rememberedOn() : hud.borderlessFullscreen.getValue();
        } catch (RuntimeException e) {
            return true;
        }
    }

    /** True when hover text should be drawn as a glass card (needs {@link #glassScreens()} too). */
    public static boolean glassTooltips() {
        try {
            HUD hud = instance();
            return hud == null || hud.glassTooltips.getValue();
        } catch (RuntimeException e) {
            return true;
        }
    }

    private String getModuleName(Module module) {
        String moduleName = module.getName();
        if (this.lowerCase.getValue()) {
            moduleName = moduleName.toLowerCase(Locale.ROOT);
        }
        return moduleName;
    }

    private String[] getModuleSuffix(Module module) {
        String[] moduleSuffix = module.getSuffix();
        if (this.lowerCase.getValue()) {
            for (int i = 0; i < moduleSuffix.length; i++) {
                moduleSuffix[i] = moduleSuffix[i].toLowerCase();
            }
        }
        return moduleSuffix;
    }

    private int getModuleWidth(Module module) {
        return this.calculateStringWidth(this.getModuleName(module), this.getModuleSuffix(module));
    }

    private int calculateStringWidth(String string, String[] arr) {
        return (int) Math.ceil(listWidth(string, arr));
    }

    private float listWidth(String string, String[] arr) {
        myau.ui.glass.SmoothFont font = myau.ui.glass.SmoothFont.regular(9);
        float width = font.width(string);
        if (this.suffixes.getValue()) {
            for (String str : arr) {
                width += 3 + font.width(str);
            }
        }
        return width;
    }

    private float getColorCycle(long long3, long long4) {
        long speed = (long) (3000.0 / Math.pow(Math.min(Math.max(0.5F, this.colorSpeed.getValue()), 1.5F), 3.0));
        return 1.0F - (float) (Math.abs(long3 - long4 * 300L) % speed) / (float) speed;
    }

    public Color getColor(long time) {
        return this.getColor(time, 0L);
    }

    public Color getColor(long time, long offset) {
        Color color = Color.white;
        switch (this.colorMode.getValue()) {
            case 0:
                color = ColorUtil.fromHSB(this.getColorCycle(time, offset), 1.0F, 1.0F);
                break;
            case 1:
                color = ColorUtil.fromHSB(this.getColorCycle(time / 3L, 0L), 1.0F, 1.0F);
                break;
            case 2:
                float cycle = this.getColorCycle(time, offset);
                if (cycle % 1.0F < 0.5F) {
                    cycle = 1.0F - cycle % 1.0F;
                }
                color = ColorUtil.fromHSB(cycle, 1.0F, 1.0F);
                break;
            case 3:
                color = new Color(this.custom1.getValue());
                break;
            case 4:
                double cycle1 = this.getColorCycle(time, offset);
                color = ColorUtil.interpolate(
                        (float) (2.0 * Math.abs(cycle1 - Math.floor(cycle1 + 0.5))),
                        new Color(this.custom1.getValue()),
                        new Color(this.custom2.getValue())
                );
                break;
            case 5:
                double cycle2 = this.getColorCycle(time, offset);
                float floor = (float) (2.0 * Math.abs(cycle2 - Math.floor(cycle2 + 0.5)));
                if (floor <= 0.5F) {
                    color = ColorUtil.interpolate(floor * 2.0F, new Color(this.custom1.getValue()), new Color(this.custom2.getValue()));
                } else {
                    color = ColorUtil.interpolate((floor - 0.5F) * 2.0F, new Color(this.custom2.getValue()), new Color(this.custom3.getValue()));
                }
        }
        float[] hsb = Color.RGBtoHSB(color.getRed(), color.getGreen(), color.getBlue(), null);
        return Color.getHSBColor(
                hsb[0],
                hsb[1] * (this.colorSaturation.getValue().floatValue() / 100.0F),
                hsb[2] * (this.colorBrightness.getValue().floatValue() / 100.0F)
        );
    }

    @EventTarget
    public void onTick(TickEvent event) {
        if (this.isEnabled() && event.getType() == EventType.POST) {
            this.activeModules = Myau.moduleManager.modules.values().stream().filter(module -> module.isEnabled() && !module.isHidden()).collect(Collectors.<Module>toList());
        }
    }

    public void doGlScissor(float x, float y, float width, float height) {
        ScaledResolution sr = new ScaledResolution(mc);
        int scaleFactor = sr.getScaleFactor();
        float currentScale = this.scale.getValue();

        int scissorX = (int) (x * currentScale * scaleFactor);
        int scissorY = (int) (mc.displayHeight - ((y + height) * currentScale * scaleFactor));
        int scissorW = (int) (width * currentScale * scaleFactor);
        int scissorH = (int) (height * currentScale * scaleFactor);

        GL11.glScissor(scissorX, scissorY, scissorW, scissorH);
    }

    @EventTarget
    public void onRender2D(Render2DEvent event) {
        if (this.chatOutline.getValue() && mc.currentScreen instanceof GuiChat) {
            String text = ((IAccessorGuiChat) mc.currentScreen).getInputField().getText().trim();
            if (Myau.commandManager != null && Myau.commandManager.isTypingCommand(text)) {
                int outline = 0xff000000 | this.getColor(System.currentTimeMillis()).getRGB();
                float ox = 2.0F, oy = mc.currentScreen.height - 14.0F, ow = mc.currentScreen.width - 4.0F, oh = 12.0F;
                myau.ui.glass.Draw.useGuiScale();
                myau.ui.glass.Draw.glow(ox, oy, ow, oh, 3.0F, 5.0F, myau.ui.glass.Draw.alpha(outline, 0.3F));
                myau.ui.glass.Draw.outline(ox, oy, ow, oh, 3.0F, 1.0F, outline, myau.ui.glass.Draw.alpha(outline, 0.6F));
            }
        }

        long l = System.currentTimeMillis();
        long offset = 0L;

        myau.ui.glass.Draw.useGuiScale();
        if (this.isEnabled() && !mc.gameSettings.showDebugInfo) {
            ScaledResolution listSr = new ScaledResolution(mc);
            float s = this.scale.getValue();
            boolean right = this.posX.getValue() == 1, bottom = this.posY.getValue() == 1;
            boolean bar = this.showBar.getValue();
            // Everything below is in HUD-scaled units.
            float anchorX = (right ? listSr.getScaledWidth() - this.offsetX.getValue() : this.offsetX.getValue()) / s + (right ? -1 : 1) * (bar ? 4 : 1);
            float cursor = (bottom ? listSr.getScaledHeight() - this.offsetY.getValue() : this.offsetY.getValue()) / s + (bottom ? -1 : 1);

            long blinkPackets = 0L;
            myau.ui.glass.GlState.hudDefaults();
            GlStateManager.pushMatrix();
            try {
            GlStateManager.scale(s, s, 1.0F);
            GlStateManager.disableDepth();

            myau.ui.glass.SmoothFont titleFont = myau.ui.glass.SmoothFont.bold(14);
            myau.ui.glass.SmoothFont font = myau.ui.glass.SmoothFont.regular(9);
            String titleText = "BetterMyau";
            float titleH = titleFont.height();
            float titleY = bottom ? cursor - titleH : cursor;
            float titleX = right ? anchorX - titleFont.width(titleText) : anchorX;
            int titleColor = 0xff000000 | this.getColor(l, -1).getRGB();
            if (this.shadow.getValue()) titleFont.drawShadowed(titleText, titleX, titleY, titleColor);
            else titleFont.draw(titleText, titleX, titleY, titleColor);
            cursor += (bottom ? -1 : 1) * (titleH + 3);

            boolean listBlur = this.blurBackground.getValue() && BlurUtil.isBlurAvailable();
            float rowH = 12.0F, pad = 4.0F;
            int tint = myau.ui.glass.Draw.alpha(0x0a0c10, this.background.getValue().floatValue() / 100.0F);
            // Rows slide in when a module turns on and out when it turns off; the rest close the gap.
            float listDt = myau.ui.glass.Anim.dt();
            for (Module module : this.activeModules) {
                if (!this.listShow.containsKey(module)) this.listShow.put(module, new myau.ui.glass.Anim.Smooth(0.0F, 14.0F));
            }
            List<Module> shownModules = new ArrayList<>(this.listShow.size());
            for (java.util.Iterator<java.util.Map.Entry<Module, myau.ui.glass.Anim.Smooth>> it = this.listShow.entrySet().iterator(); it.hasNext(); ) {
                java.util.Map.Entry<Module, myau.ui.glass.Anim.Smooth> entry = it.next();
                boolean active = entry.getKey().isEnabled() && !entry.getKey().isHidden();
                entry.getValue().update(active, listDt);
                if (!active && entry.getValue().value < 0.01F) it.remove();
                else shownModules.add(entry.getKey());
            }
            // Name, suffix and width once per row per frame; sorting and the corner rounding reuse them.
            int count = shownModules.size();
            if (this.rowNames.length < count) {
                this.rowNames = new String[count * 2];
                this.rowSuffixes = new String[count * 2][];
                this.rowWidths = new float[count * 2];
            }
            java.util.IdentityHashMap<Module, Integer> slot = this.rowSlot;
            slot.clear();
            for (int i = 0; i < count; i++) {
                Module module = shownModules.get(i);
                this.rowNames[i] = this.getModuleName(module);
                this.rowSuffixes[i] = this.getModuleSuffix(module);
                this.rowWidths[i] = this.listWidth(this.rowNames[i], this.rowSuffixes[i]);
                slot.put(module, i);
            }
            final float[] widths = this.rowWidths;
            shownModules.sort((a, b) -> Float.compare(widths[slot.get(b)], widths[slot.get(a)]));
            for (int i = 0; i < count; i++) {
                Module module = shownModules.get(i);
                int k = slot.get(module);
                float show = this.listShow.get(module).value;
                String moduleName = this.rowNames[k];
                String[] moduleSuffix = this.rowSuffixes[k];
                float totalWidth = this.rowWidths[k];
                int color = 0xff000000 | this.getColor(l, offset).getRGB();

                float rowW = totalWidth + pad * 2;
                float fullRowH = rowH;
                float rowH2 = fullRowH * show;
                float slide = (right ? 1 : -1) * (1.0F - show) * 0.35F * rowW;
                float rowX = (right ? anchorX - rowW : anchorX) + slide;
                float rowY = bottom ? cursor - rowH2 : cursor;
                myau.ui.glass.Draw.pushAlpha(show);
                // Rows shrink away from the anchor: round the corners that stick out.
                float nextW = i + 1 < count ? this.rowWidths[slot.get(shownModules.get(i + 1))] + pad * 2 : 0;
                float step = Math.min(5.0F, Math.max(0.0F, rowW - nextW));
                float first = i == 0 ? 5.0F : 0.0F;
                float tl = 0, tr = 0, br = 0, bl = 0;
                if (right) { if (bottom) { tl = step; bl = first; } else { bl = step; tl = first; } }
                else { if (bottom) { tr = step; br = first; } else { br = step; tr = first; } }

                if (listBlur) myau.ui.glass.Draw.blurred(rowX, rowY, rowW, rowH2, tl, tr, br, bl, 1.0F);
                if (this.background.getValue() > 0) myau.ui.glass.Draw.rect(rowX, rowY, rowW, rowH2, tl, tr, br, bl, tint);
                if (bar) {
                    float barX = right ? anchorX + 1.0F : anchorX - 3.0F;
                    myau.ui.glass.Draw.glow(barX, rowY, 2.0F, rowH2, 0, 4.0F, myau.ui.glass.Draw.alpha(color, 0.45F));
                    myau.ui.glass.Draw.rect(barX, rowY, 2.0F, rowH2, 0, color);
                }

                float textX = rowX + pad, textY = font.centerY(rowY, rowH2) - 0.5F;
                if (this.shadow.getValue()) font.drawShadowed(moduleName, textX, textY, color);
                else font.draw(moduleName, textX, textY, color);
                if (this.suffixes.getValue() && moduleSuffix.length > 0) {
                    float width = font.width(moduleName) + 3.0F;
                    for (String string : moduleSuffix) {
                        font.draw(string, textX + width, textY, 0xffaab0bc);
                        width += font.width(string) + 3.0F;
                    }
                }
                myau.ui.glass.Draw.popAlpha();
                cursor += (bottom ? -1 : 1) * rowH2;
                offset++;
            }

            if (this.blinkTimer.getValue()) {
                BlinkModules blinkingModule = Myau.blinkManager.getBlinkingModule();
                if (blinkingModule != BlinkModules.NONE && blinkingModule != BlinkModules.AUTO_BLOCK) {
                    long movementPacketSize = Myau.blinkManager.countMovement();
                    if (movementPacketSize > 0L) {
                        blinkPackets = movementPacketSize;
                    }
                }
            }
            } finally {
                myau.ui.glass.Draw.syncGuiScale();
                GlStateManager.enableDepth();
                GlStateManager.popMatrix();
            }

            if (blinkPackets > 0L) {
                myau.ui.glass.Island.live("blink", myau.ui.glass.Island.P_TIMER, "Blink §f" + blinkPackets,
                        0xff000000 | this.getColor(l, offset).getRGB(), -1);
            }

            // 🖼 右上角高清圆形图标渲染
            renderIcon();
        }

    }

    /**
     * Runs after every other module's 2D pass, so the island already holds whatever they
     * reported this frame (block counts, break progress, timers).
     */
    @EventTarget(myau.event.types.Priority.LOWEST)
    public void onRender2DLate(Render2DEvent event) {
        myau.ui.glass.GlState.hudDefaults();
        myau.ui.glass.Draw.useGuiScale();
        if (this.toggleAlerts.getValue() && !notifications.isEmpty()) {
            renderNotifications();
        }
        reportCombat();
        // A container screen blurs and dims everything under it, the island included; there
        // the island is drawn after the screen instead (see renderDeferredIsland).
        this.islandDeferred = mc.currentScreen instanceof net.minecraft.client.gui.inventory.GuiContainer;
        if (!this.islandDeferred) this.drawIsland();
    }

    private boolean islandDeferred;

    private void drawIsland() {
        myau.ui.glass.Draw.useGuiScale();
        float scale = myau.ui.glass.Draw.scale();
        // Same numbers ScaledResolution gives, without allocating one every frame.
        float sw = (float) Math.ceil(mc.displayWidth / scale), sh = (float) Math.ceil(mc.displayHeight / scale);
        int where = this.indicators.getValue(); // 0 island, 1 crosshair, 2 both
        myau.ui.glass.Island.scale(this.islandScale.getValue() / 100.0F);
        myau.ui.glass.Island.tabList(this.islandTabList.getValue());
        myau.ui.glass.Island.render(sw, sh, this.dynamicIsland.getValue(), where != 1, where != 0);
    }

    /**
     * Called right after the current screen drew: puts the island on top of container screens.
     * The island advances its animations when it renders, so it must render exactly once per
     * frame; the flag set in the HUD pass guarantees that.
     */
    public static void renderDeferredIsland() {
        HUD hud = instance();
        if (hud == null || !hud.islandDeferred) return;
        hud.islandDeferred = false;
        try {
            myau.ui.glass.GlState.hudDefaults();
            hud.drawIsland();
        } catch (RuntimeException e) {
            Myau.LOGGER.error("[Glass] island over screen failed", e);
        } finally {
            myau.ui.glass.GlState.hudDefaults();
        }
    }

    /** The target's held item and armour, shown as icons under the KillAura activity. */
    private final net.minecraft.item.ItemStack[] combatIcons = new net.minecraft.item.ItemStack[5];

    /**
     * The KillAura target on the island (whether or not TargetHUD is on): name, health (bar and
     * colour), distance, who is ahead, autoblock, and the target's weapon and armour as icons.
     */
    private void reportCombat() {
        try {
            if (mc.thePlayer == null || !this.dynamicIsland.getValue()) return;
            KillAura aura = (KillAura) Myau.moduleManager.modules.get(KillAura.class);
            if (aura == null || !aura.isEnabled() || !aura.isAttackAllowed()) return;
            net.minecraft.entity.EntityLivingBase target = aura.getTarget();
            if (!myau.util.TeamUtil.isEntityLoaded(target)) return;
            float extra = target.getAbsorptionAmount();
            float health = target.getHealth() + extra, max = Math.max(1.0F, target.getMaxHealth() + extra);
            float ratio = Math.max(0.0F, Math.min(1.0F, health / max));
            int accent = ratio > 0.5F ? myau.ui.GlassTheme.OK : ratio > 0.25F ? myau.ui.GlassTheme.WARN : myau.ui.GlassTheme.BAD;
            float mine = mc.thePlayer.getHealth() + mc.thePlayer.getAbsorptionAmount();
            // Our health lead over the target: green when we are ahead, red when they are.
            float diff = mine - health;
            String lead = Math.abs(diff) < 0.5F ? " §7±0" : String.format(diff > 0 ? " §a+%.1f" : " §c%.1f", diff);
            StringBuilder text = new StringBuilder()
                    .append(myau.util.TeamUtil.stripName(target))
                    .append(" §f").append(String.format("%.1f", health)).append("§7hp")
                    .append(lead)
                    .append(" §8| §7").append(String.format("%.1fm", mc.thePlayer.getDistanceToEntity(target)));
            if (aura.isPlayerBlocking()) text.append(" §8| §bBlock");
            int count = 0;
            if (target.getHeldItem() != null) this.combatIcons[count++] = target.getHeldItem();
            for (int slot = 4; slot >= 1 && count < this.combatIcons.length; slot--) {
                net.minecraft.item.ItemStack armour = target.getEquipmentInSlot(slot);
                if (armour != null) this.combatIcons[count++] = armour;
            }
            for (int i = count; i < this.combatIcons.length; i++) this.combatIcons[i] = null;
            myau.ui.glass.Island.live("combat", myau.ui.glass.Island.P_COMBAT, text.toString(), accent, ratio,
                    this.combatIcons, target.getEntityId());
        } catch (RuntimeException ignored) {
        }
    }

    private static HUD instance() {
        try {
            return Myau.moduleManager == null ? null : (HUD) Myau.moduleManager.modules.get(HUD.class);
        } catch (RuntimeException e) {
            return null;
        }
    }

    /** Route an alert to the island, the corner stack or both, following "notifications". */
    private static void post(String title, String message, boolean positive, float seconds) {
        HUD hud = instance();
        int mode = hud == null ? 1 : hud.notifyMode.getValue();
        boolean island = hud != null && hud.dynamicIsland.getValue() && mc.thePlayer != null;
        if (mode != 1 && island) {
            myau.ui.glass.Island.event(title, message, positive ? myau.ui.GlassTheme.OK : myau.ui.GlassTheme.BAD, seconds);
        }
        if (mode != 0 || !island) {
            notifications.add(new Notification(title, message, positive));
        }
    }

    private void renderNotifications() {
        ScaledResolution sr = new ScaledResolution(mc);
        int scaleFactor = sr.getScaleFactor();
        float screenWidth = sr.getScaledWidth();
        float screenHeight = sr.getScaledHeight();

        float notifWidth = 150.0F;
        float notifHeight = 32.0F;
        float edgeSpacing = 6.0F;

        float currentY = screenHeight - notifHeight - edgeSpacing;
        long now = System.currentTimeMillis();

        notifications.removeIf(n -> now > n.endTime);

        myau.ui.glass.Draw.useGuiScale();
        myau.ui.glass.SmoothFont titleFont = myau.ui.glass.SmoothFont.bold(9.5f);
        myau.ui.glass.SmoothFont bodyFont = myau.ui.glass.SmoothFont.regular(8.5f);
        for (Notification n : notifications) {
            long lifeTime = now - n.startTime;
            long timeLeft = n.endTime - now;
            float animation = 1.0F;
            if (lifeTime < 320) {
                animation = myau.ui.glass.Anim.outCubic(lifeTime / 320.0F);
            } else if (timeLeft < 260) {
                animation = myau.ui.glass.Anim.outCubic(timeLeft / 260.0F);
            }

            float targetX = screenWidth - notifWidth - edgeSpacing + (notifWidth * 0.35F + edgeSpacing) * (1.0F - animation);
            int stateColor = n.enabled ? myau.ui.GlassTheme.OK : myau.ui.GlassTheme.BAD;

            myau.ui.glass.Draw.pushAlpha(animation);
            myau.ui.GlassTheme.panel(targetX, currentY, notifWidth, notifHeight, 9, 0.8f);
            myau.ui.glass.Draw.glow(targetX + 8, currentY + 8, 3, notifHeight - 16, 1.5f, 6, myau.ui.glass.Draw.alpha(stateColor, 0.55f));
            myau.ui.glass.Draw.rect(targetX + 8, currentY + 8, 3, notifHeight - 16, 1.5f, stateColor);
            titleFont.draw(titleFont.trim(n.title, notifWidth - 28), targetX + 17.0F, currentY + 5.0F, myau.ui.GlassTheme.TEXT);
            bodyFont.draw(bodyFont.trim(n.message, notifWidth - 28), targetX + 17.0F, currentY + 17.5F, myau.ui.GlassTheme.MUTED);
            float life = Math.max(0.0F, Math.min(1.0F, timeLeft / (float) Math.max(1L, n.endTime - n.startTime)));
            myau.ui.glass.Draw.rect(targetX + 17.0F, currentY + notifHeight - 3.5F, (notifWidth - 30.0F) * life, 1.0F, 0.5f, myau.ui.glass.Draw.alpha(stateColor, 0.7f));
            myau.ui.glass.Draw.popAlpha();

            currentY -= (notifHeight + edgeSpacing) * animation;
        }
    }

    // ==========================================
    // 🖼 TUBIAO 图标 — 修复重构版 (放弃瞎搞，采用原生安全管线)
    // ==========================================
    private void loadIconTexture() {
        if (iconResourceLocation != null) return;

        try (InputStream stream = HUD.class.getResourceAsStream("/TUBIAO.png")) {
            if (stream == null) {
                Myau.LOGGER.warn("[HUD] TUBIAO.png not found in classpath");
                return;
            }
            BufferedImage img = ImageIO.read(stream);
            if (img == null) {
                Myau.LOGGER.warn("[HUD] Failed to decode TUBIAO.png");
                return;
            }

            // ⚠️核心修复：通过 MC 自带的任务队列，确保纹理在主渲染线程(Main Thread)内安全绑定
            mc.addScheduledTask(() -> {
                iconTexture = new DynamicTexture(img);
                iconResourceLocation = mc.getTextureManager().getDynamicTextureLocation("bettermyau_icon", iconTexture);
                Myau.LOGGER.debug("[HUD] Icon texture loaded");
            });
        } catch (Exception e) {
            Myau.LOGGER.warn("[HUD] Failed to load icon texture", e);
        }
    }

    private void renderIcon() {
        // 如果资源还没分配，重新呼叫加载并跳过当前帧
        if (iconResourceLocation == null) {
            loadIconTexture();
            return;
        }

        ScaledResolution sr = new ScaledResolution(mc);
        float iconSize = 32f;
        float padding = 10f;
        float iconX = sr.getScaledWidth() - iconSize - padding;
        float iconY = padding;

        GlStateManager.pushMatrix();
        GlStateManager.disableDepth();
        GlStateManager.disableLighting();
        // 图标下的玻璃底座
        myau.ui.glass.Draw.useGuiScale();
        myau.ui.GlassTheme.panel(iconX - 4f, iconY - 4f, iconSize + 8f, iconSize + 8f, 10f, 0.7f);
        GlStateManager.enableBlend();
        GlStateManager.enableAlpha();
        GlStateManager.color(1f, 1f, 1f, 1f);
        GlStateManager.tryBlendFuncSeparate(GL11.GL_SRC_ALPHA, GL11.GL_ONE_MINUS_SRC_ALPHA, 1, 0);

        // 使用最稳定安全的 Minecraft 原生纹理绑定机制
        mc.getTextureManager().bindTexture(iconResourceLocation);

        // 使用原生的 Gui 绘制法，绝大部分 Mod 都是这么渲染图标的
        Gui.drawModalRectWithCustomSizedTexture(
                (int) iconX, (int) iconY, 0, 0, (int) iconSize, (int) iconSize, iconSize, iconSize
        );

        GlStateManager.enableDepth();
        GlStateManager.popMatrix();
    }

    public static class Notification {
        public final String title;
        public final String message;
        public final boolean enabled;
        public final long startTime;
        public final long endTime;

        public Notification(String title, String message, boolean enabled) {
            this.title = title;
            this.message = message;
            this.enabled = enabled;
            this.startTime = System.currentTimeMillis();
            this.endTime = this.startTime + 2500L;
        }
    }
}
