package myau.ui;

import com.google.gson.JsonElement;
import myau.util.FileUtil;
/*
 * Click GUI ported from Raven B3 (https://github.com/blowsy/Raven-b3) by blowsy,
 * adapted for BetterMyau. Distributed under the GNU GPL v3 together with this project.
 */

import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import myau.Myau;
import myau.module.Module;
import myau.module.modules.*;
import myau.ui.components.CategoryComponent;
import net.minecraft.client.gui.GuiScreen;
import net.minecraft.client.renderer.GlStateManager;
// 新增的导入：用于支持原生着色器
import net.minecraft.client.renderer.OpenGlHelper;
import net.minecraft.util.ResourceLocation;
import net.minecraft.entity.player.EntityPlayer;
import org.lwjgl.input.Mouse;
import org.lwjgl.opengl.GL11;

import java.awt.*;
import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.IOException;
import java.util.*;
import java.util.List;

public class ClickGui extends GuiScreen {
    private static ClickGui instance;
    private final File configFile = new File("./config/Myau/", "clickgui.txt");
    private final ArrayList<CategoryComponent> categoryList;

    // 🌈 Apple 风格 HELLO~ 欢迎动画
    private long welcomeStart = -1;
    private static final long WELCOME_DURATION = 2200;  // 总时长 2.2s
    private static final String WELCOME_TEXT = "HELLO~";

    public ClickGui() {
        instance = this;

        List<Module> combatModules = new ArrayList<>();
        combatModules.add(Myau.moduleManager.getModule(AimAssist.class));
        combatModules.add(Myau.moduleManager.getModule(AutoClicker.class));
        combatModules.add(Myau.moduleManager.getModule(KillAura.class));
        combatModules.add(Myau.moduleManager.getModule(Wtap.class));
        combatModules.add(Myau.moduleManager.getModule(Velocity.class));
        combatModules.add(Myau.moduleManager.getModule(Freeze.class));
        combatModules.add(Myau.moduleManager.getModule(Reach.class));
        combatModules.add(Myau.moduleManager.getModule(TargetStrafe.class));
        combatModules.add(Myau.moduleManager.getModule(NoHitDelay.class));
        combatModules.add(Myau.moduleManager.getModule(AntiFireball.class));
        combatModules.add(Myau.moduleManager.getModule(LagRange.class));
        combatModules.add(Myau.moduleManager.getModule(HitBox.class));
        combatModules.add(Myau.moduleManager.getModule(MoreKB.class));
        combatModules.add(Myau.moduleManager.getModule(Refill.class));
        combatModules.add(Myau.moduleManager.getModule(HitSelect.class));
        combatModules.add(Myau.moduleManager.getModule(BackTrack.class));
        combatModules.add(Myau.moduleManager.getModule(FakeLag.class));
        combatModules.add(Myau.moduleManager.getModule(Criticals.class));
        combatModules.add(Myau.moduleManager.getModule(AutoBlock.class));
        combatModules.add(Myau.moduleManager.getModule(BlockNotify.class));
        combatModules.add(Myau.moduleManager.getModule(TNTBlock.class));
        combatModules.add(Myau.moduleManager.getModule(ProjectileAimbot.class));
        combatModules.add(Myau.moduleManager.getModule(SmartBlink.class));
        combatModules.add(Myau.moduleManager.getModule(AutoRod.class));
        combatModules.add(Myau.moduleManager.getModule(AutoProjectile.class));
        combatModules.add(Myau.moduleManager.getModule(AutoWeapon.class));
        combatModules.add(Myau.moduleManager.getModule(AutoBow.class));
        combatModules.add(Myau.moduleManager.getModule(FastBow.class));
        combatModules.add(Myau.moduleManager.getModule(TickBase.class));
        combatModules.add(Myau.moduleManager.getModule(TimerRange.class));

        List<Module> movementModules = new ArrayList<>();
        movementModules.add(Myau.moduleManager.getModule(AntiAFK.class));
        movementModules.add(Myau.moduleManager.getModule(Fly.class));
        movementModules.add(Myau.moduleManager.getModule(Speed.class));
        movementModules.add(Myau.moduleManager.getModule(LongJump.class));
        movementModules.add(Myau.moduleManager.getModule(Sprint.class));
        movementModules.add(Myau.moduleManager.getModule(SafeWalk.class));
        movementModules.add(Myau.moduleManager.getModule(Jesus.class));
        movementModules.add(Myau.moduleManager.getModule(Blink.class));
        movementModules.add(Myau.moduleManager.getModule(NoFall.class));
        movementModules.add(Myau.moduleManager.getModule(NoSlow.class));
        movementModules.add(Myau.moduleManager.getModule(KeepSprint.class));
        movementModules.add(Myau.moduleManager.getModule(Eagle.class));
        movementModules.add(Myau.moduleManager.getModule(NoJumpDelay.class));
        movementModules.add(Myau.moduleManager.getModule(AntiVoid.class));
        movementModules.add(Myau.moduleManager.getModule(NoWeb.class));
        movementModules.add(Myau.moduleManager.getModule(Step.class));
        movementModules.add(Myau.moduleManager.getModule(ReverseStep.class));
        movementModules.add(Myau.moduleManager.getModule(SaveMoveKeys.class));
        movementModules.add(Myau.moduleManager.getModule(Strafe.class));
        movementModules.add(Myau.moduleManager.getModule(HighJump.class));
        movementModules.add(Myau.moduleManager.getModule(WallClimb.class));
        movementModules.add(Myau.moduleManager.getModule(FastClimb.class));
        movementModules.add(Myau.moduleManager.getModule(IceSpeed.class));
        movementModules.add(Myau.moduleManager.getModule(BufferSpeed.class));
        movementModules.add(Myau.moduleManager.getModule(FastStairs.class));
        movementModules.add(Myau.moduleManager.getModule(Parkour.class));
        movementModules.add(Myau.moduleManager.getModule(Sneak.class));
        movementModules.add(Myau.moduleManager.getModule(TimerModule.class));
        movementModules.add(Myau.moduleManager.getModule(NoClip.class));

        List<Module> renderModules = new ArrayList<>();
        renderModules.add(Myau.moduleManager.getModule(ESP.class));
        renderModules.add(Myau.moduleManager.getModule(Chams.class));
        renderModules.add(Myau.moduleManager.getModule(FullBright.class));
        renderModules.add(Myau.moduleManager.getModule(Tracers.class));
        renderModules.add(Myau.moduleManager.getModule(NameTags.class));
        renderModules.add(Myau.moduleManager.getModule(Xray.class));
        renderModules.add(Myau.moduleManager.getModule(TargetHUD.class));
        renderModules.add(Myau.moduleManager.getModule(Keystrokes.class));
        renderModules.add(Myau.moduleManager.getModule(MusicHud.class));
        renderModules.add(Myau.moduleManager.getModule(Indicators.class));
        renderModules.add(Myau.moduleManager.getModule(Crosshair.class));
        renderModules.add(Myau.moduleManager.getModule(Hotbar.class));
        renderModules.add(Myau.moduleManager.getModule(BedESP.class));
        renderModules.add(Myau.moduleManager.getModule(ItemESP.class));
        renderModules.add(Myau.moduleManager.getModule(ViewClip.class));
        renderModules.add(Myau.moduleManager.getModule(FreeLook.class));
        renderModules.add(Myau.moduleManager.getModule(NoHurtCam.class));
        renderModules.add(Myau.moduleManager.getModule(HUD.class));
        renderModules.add(Myau.moduleManager.getModule(GuiModule.class));
        renderModules.add(Myau.moduleManager.getModule(ChestESP.class));
        renderModules.add(Myau.moduleManager.getModule(DynamicCamera.class));
        renderModules.add(Myau.moduleManager.getModule(Trajectories.class));
        renderModules.add(Myau.moduleManager.getModule(TNTTimer.class));
        renderModules.add(Myau.moduleManager.getModule(Radar.class));
        renderModules.add(Myau.moduleManager.getModule(PerformanceOptimizer.class));
        renderModules.add(Myau.moduleManager.getModule(Animations.class));
        renderModules.add(Myau.moduleManager.getModule(ItemPhysics.class));
        renderModules.add(Myau.moduleManager.getModule(Cape.class));
        renderModules.add(Myau.moduleManager.getModule(Breadcrumbs.class));
        renderModules.add(Myau.moduleManager.getModule(DamageParticle.class));
        renderModules.add(Myau.moduleManager.getModule(HitBubbles.class));
        renderModules.add(Myau.moduleManager.getModule(MotionBlur.class));
        renderModules.add(Myau.moduleManager.getModule(AttackEffect.class));
        renderModules.add(Myau.moduleManager.getModule(BlockOverlay.class));
        renderModules.add(Myau.moduleManager.getModule(JumpCircle.class));

        List<Module> playerModules = new ArrayList<>();
        playerModules.add(Myau.moduleManager.getModule(AutoHeal.class));
        playerModules.add(Myau.moduleManager.getModule(AutoTool.class));
        playerModules.add(Myau.moduleManager.getModule(ChestStealer.class));
        playerModules.add(Myau.moduleManager.getModule(ChestAura.class));
        playerModules.add(Myau.moduleManager.getModule(KeyPearl.class));
        playerModules.add(Myau.moduleManager.getModule(InvManager.class));
        playerModules.add(Myau.moduleManager.getModule(InvWalk.class));
        playerModules.add(Myau.moduleManager.getModule(Scaffold.class));
        playerModules.add(Myau.moduleManager.getModule(LeaderScaffold.class));
        playerModules.add(Myau.moduleManager.getModule(AutoBlockIn.class));
        playerModules.add(Myau.moduleManager.getModule(Clutch.class));
        playerModules.add(Myau.moduleManager.getModule(SpeedMine.class));
        playerModules.add(Myau.moduleManager.getModule(FastPlace.class));
        playerModules.add(Myau.moduleManager.getModule(GhostHand.class));
        playerModules.add(Myau.moduleManager.getModule(MCF.class));
        playerModules.add(Myau.moduleManager.getModule(AntiDebuff.class));
        playerModules.add(Myau.moduleManager.getModule(AutoPot.class));
        playerModules.add(Myau.moduleManager.getModule(AutoSoup.class));

        List<Module> miscModules = new ArrayList<>();
        miscModules.add(Myau.moduleManager.getModule(MusicPlayer.class));
        miscModules.add(Myau.moduleManager.getModule(Spammer.class));
        miscModules.add(Myau.moduleManager.getModule(BedNuker.class));
        miscModules.add(Myau.moduleManager.getModule(BedTracker.class));
        miscModules.add(Myau.moduleManager.getModule(LightningTracker.class));
        miscModules.add(Myau.moduleManager.getModule(NoRotate.class));
        miscModules.add(Myau.moduleManager.getModule(NickHider.class));
        miscModules.add(Myau.moduleManager.getModule(AntiObbyTrap.class));
        miscModules.add(Myau.moduleManager.getModule(AntiObfuscate.class));
        miscModules.add(Myau.moduleManager.getModule(AutoAnduril.class));
        miscModules.add(Myau.moduleManager.getModule(InventoryClicker.class));
        miscModules.add(Myau.moduleManager.getModule(FlagCheck.class));
        miscModules.add(Myau.moduleManager.getModule(AntiBot.class));
        miscModules.add(Myau.moduleManager.getModule(Teams.class));
        miscModules.add(Myau.moduleManager.getModule(StaffDetector.class));
        miscModules.add(Myau.moduleManager.getModule(AnticheatDetector.class));
        miscModules.add(Myau.moduleManager.getModule(GameDetector.class));
        miscModules.add(Myau.moduleManager.getModule(AutoDisable.class));
        miscModules.add(Myau.moduleManager.getModule(Notifier.class));
        miscModules.add(Myau.moduleManager.getModule(AutoL.class));
        miscModules.add(Myau.moduleManager.getModule(AutoAccount.class));
        miscModules.add(Myau.moduleManager.getModule(PacketDebugger.class));
        miscModules.add(Myau.moduleManager.getModule(ResourcePackSpoof.class));
        miscModules.add(Myau.moduleManager.getModule(ClientFixes.class));
        miscModules.add(Myau.moduleManager.getModule(AutoLeave.class));
        miscModules.add(Myau.moduleManager.getModule(Disabler.class));
        miscModules.add(Myau.moduleManager.getModule(AutoGapple.class));
        miscModules.add(Myau.moduleManager.getModule(DataCollector.class));
        miscModules.add(Myau.moduleManager.getModule(AIAdvisor.class));

        Comparator<Module> comparator = Comparator.comparing(m -> m.getName().toLowerCase());
        combatModules.sort(comparator);
        movementModules.sort(comparator);
        renderModules.sort(comparator);
        playerModules.sort(comparator);
        miscModules.sort(comparator);

        Set<Module> registered = new HashSet<>();
        registered.addAll(combatModules);
        registered.addAll(movementModules);
        registered.addAll(renderModules);
        registered.addAll(playerModules);
        registered.addAll(miscModules);

        for (Module module : Myau.moduleManager.modules.values()) {
            if (!registered.contains(module)) {
                throw new RuntimeException(module.getClass().getName() + " is unregistered to click gui.");
            }
        }

        this.categoryList = new ArrayList<>();
        int topOffset = 5;

        CategoryComponent combat = new CategoryComponent("Combat", combatModules);
        combat.setY(topOffset);
        categoryList.add(combat);
        topOffset += 20;

        CategoryComponent movement = new CategoryComponent("Movement", movementModules);
        movement.setY(topOffset);
        categoryList.add(movement);
        topOffset += 20;

        CategoryComponent render = new CategoryComponent("Render", renderModules);
        render.setY(topOffset);
        categoryList.add(render);
        topOffset += 20;

        CategoryComponent player = new CategoryComponent("Player", playerModules);
        player.setY(topOffset);
        categoryList.add(player);
        topOffset += 20;

        CategoryComponent misc = new CategoryComponent("Misc", miscModules);
        misc.setY(topOffset);
        categoryList.add(misc);

        if (getClass() == ClickGui.class) loadPositions();
    }

    public static ClickGui getInstance() {
        return instance;
    }

    protected List<CategoryComponent> categories() {
        return categoryList;
    }

    @Override
    public void initGui() {
        super.initGui();
        // 🌈 启动欢迎动画
        welcomeStart = System.currentTimeMillis();
        // 【修改点 1】: 打开 GUI 时加载 MC 原生的高斯模糊着色器
        if (OpenGlHelper.shadersSupported && mc.getRenderViewEntity() instanceof EntityPlayer) {
            if (mc.entityRenderer.getShaderGroup() != null) {
                mc.entityRenderer.stopUseShader();
            }
            mc.entityRenderer.loadShader(new ResourceLocation("shaders/post/blur.json"));
        }
    }

    /**
     * 完美融入 MC 渲染管线的圆角方法，彻底解决列表背景神秘隐身的问题
     */
    public static void drawRoundedRect(float x, float y, float width, float height, float radius, int color) {
        myau.ui.glass.Draw.syncGuiScale();
        myau.ui.glass.Draw.rect(x, y, width, height, radius, color);
    }

    // 🌈 HSL → RGB (Apple 彩虹核心)
    private static int hslToRgb(float h, float s, float l) {
        float c = (1 - Math.abs(2 * l - 1)) * s;
        float x = c * (1 - Math.abs((h / 60) % 2 - 1));
        float m = l - c / 2;
        float r, g, b;
        if (h < 60)      { r = c; g = x; b = 0; }
        else if (h < 120) { r = x; g = c; b = 0; }
        else if (h < 180) { r = 0; g = c; b = x; }
        else if (h < 240) { r = 0; g = x; b = c; }
        else if (h < 300) { r = x; g = 0; b = c; }
        else              { r = c; g = 0; b = x; }
        return new Color(
            Math.min(1, Math.max(0, r + m)),
            Math.min(1, Math.max(0, g + m)),
            Math.min(1, Math.max(0, b + m))
        ).getRGB();
    }

    // 🌈 Apple 风格幻彩 HELLO~ 欢迎动画
    private void renderWelcome(float partialTicks) {
        long elapsed = System.currentTimeMillis() - welcomeStart;
        if (elapsed > WELCOME_DURATION) return;

        float progress = (float) elapsed / WELCOME_DURATION; // 0.0 → 1.0

        // 缩放曲线：弹性入场 → 微放 → 回弹
        float scale;
        if (progress < 0.2f) {
            // 0-20%: 快速弹入 scale 0.3 → 1.15
            float t = progress / 0.2f;
            scale = 0.3f + (1.15f - 0.3f) * (float)(1 - Math.pow(1 - t, 3));
        } else if (progress < 0.7f) {
            // 20-70%: 缓降至 1.0
            float t = (progress - 0.2f) / 0.5f;
            scale = 1.15f + (1.0f - 1.15f) * t;
        } else {
            // 70-100%: 微缩 + 淡出
            float t = (progress - 0.7f) / 0.3f;
            scale = 1.0f + 0.05f * t;
        }

        // 透明度曲线
        float alpha;
        if (progress < 0.15f) {
            alpha = progress / 0.15f;                    // fade in
        } else if (progress > 0.75f) {
            alpha = 1.0f - (progress - 0.75f) / 0.25f;  // fade out
        } else {
            alpha = 1.0f;
        }

        // 流动色相偏移 (每秒流动 120°)
        float hueFlow = (elapsed * 0.12f) % 360f;

        // 渲染位置：屏幕正中
        float cx = this.width / 2f;
        float cy = this.height / 2f;

        GlStateManager.pushMatrix();
        GlStateManager.translate(cx, cy, 0);
        GlStateManager.scale(scale, scale, 1);

        // 字符间距
        float charWidth = mc.fontRendererObj.getCharWidth('H');
        float totalWidth = WELCOME_TEXT.length() * charWidth;
        float startX = -totalWidth / 2f + charWidth / 2f;

        // 🌟 外层光晕
        GlStateManager.enableBlend();
        GlStateManager.disableTexture2D();
        GL11.glEnable(GL11.GL_POLYGON_SMOOTH);
        for (int ring = 3; ring >= 0; ring--) {
            float ringAlpha = alpha * (0.08f - ring * 0.015f);
            float ringScale = 1.0f + ring * 0.12f;
            GlStateManager.color(0.6f, 0.75f, 1.0f, ringAlpha);
            GL11.glBegin(GL11.GL_TRIANGLE_FAN);
            GL11.glVertex2d(0, 0);
            for (int i = 0; i <= 72; i++) {
                double rad = Math.toRadians(i * 5);
                GL11.glVertex2d(Math.cos(rad) * totalWidth * 0.7 * ringScale,
                               Math.sin(rad) * mc.fontRendererObj.FONT_HEIGHT * 3 * ringScale);
            }
            GL11.glEnd();
        }
        GL11.glDisable(GL11.GL_POLYGON_SMOOTH);
        GlStateManager.enableTexture2D();

        // 🌟 逐字渲染 Apple 彩虹
        for (int i = 0; i < WELCOME_TEXT.length(); i++) {
            String ch = String.valueOf(WELCOME_TEXT.charAt(i));

            // 每个字色相偏移 40°，产生彩虹条带，再叠加全局流动
            float charHue = (hueFlow + i * 40f) % 360f;
            int color = hslToRgb(charHue, 0.9f, 0.55f);

            // 投影
            int shadowAlpha = (int)(alpha * 80) << 24;
            mc.fontRendererObj.drawString(ch,
                (int)(startX + i * charWidth + 1), 1,
                shadowAlpha | (color & 0x00FFFFFF));

            // 主体
            int finalAlpha = (int)(alpha * 255) << 24;
            mc.fontRendererObj.drawString(ch,
                (int)(startX + i * charWidth), 0,
                finalAlpha | (color & 0x00FFFFFF));
        }

        GlStateManager.popMatrix();
    }

    @Override
    public void drawScreen(int x, int y, float p) {
        // 【修改点 2】: 删除了原来生硬的半透明黑底，换成了透明度极低（50）的暗色遮罩
        // 这样配合底层的高斯模糊，就能产生高级的毛玻璃质感
        drawRect(0, 0, this.width, this.height, new Color(10, 10, 10, 50).getRGB());

        // 🌈 Apple 彩虹欢迎动画 (最上层)
        renderWelcome(p);

        mc.fontRendererObj.drawStringWithShadow("BetterMyau v" + Myau.version, 4, this.height - 3 - mc.fontRendererObj.FONT_HEIGHT * 2, new Color(60, 162, 253).getRGB());
        mc.fontRendererObj.drawStringWithShadow("dev, ksyz", 4, this.height - 3 - mc.fontRendererObj.FONT_HEIGHT, new Color(60, 162, 253).getRGB());

        for (CategoryComponent category : categoryList) {
            category.render(this.fontRendererObj);
            category.handleDrag(x, y);
            for (Component module : category.getModules()) {
                module.update(x, y);
            }
        }

        int wheel = Mouse.getDWheel();
        if (wheel != 0) {
            int scrollDir = wheel > 0 ? 1 : -1;
            for (CategoryComponent category : categoryList) {
                category.onScroll(x, y, scrollDir);
            }
        }
    }

    public void mouseClicked(int x, int y, int mouseButton) {
        Iterator<CategoryComponent> btnCat = categoryList.iterator();
        while (btnCat.hasNext()) {
            CategoryComponent category = btnCat.next();
            if (category.insideArea(x, y) && !category.isHovered(x, y) && !category.mousePressed(x, y) && mouseButton == 0) {
                category.mousePressed(true);
                category.xx = x - category.getX();
                category.yy = y - category.getY();
            }
            if (category.mousePressed(x, y) && mouseButton == 0) {
                category.setOpened(!category.isOpened());
            }
            if (category.isHovered(x, y) && mouseButton == 0) {
                category.setPin(!category.isPin());
            }
            if (category.isOpened()) {
                for (Component c : category.getModules()) {
                    c.mouseDown(x, y, mouseButton);
                }
            }
        }
    }

    public void mouseReleased(int x, int y, int mouseButton) {
        for (CategoryComponent categoryComponent : categoryList) {
            if (mouseButton == 0) categoryComponent.mousePressed(false);
            if (categoryComponent.isOpened()) {
                for (Component component : categoryComponent.getModules()) {
                    component.mouseReleased(x, y, mouseButton);
                }
            }
        }
    }

    public void keyTyped(char typedChar, int key) {
        if (key == 1) {
            this.mc.displayGuiScreen(null);
        } else {
            for (CategoryComponent cat : categoryList) {
                if (cat.isOpened()) {
                    for (Component component : cat.getModules()) {
                        component.keyTyped(typedChar, key);
                    }
                }
            }
        }
    }

    @Override
    public void onGuiClosed() {
        savePositions();
        // 【修改点 3】: 关闭 GUI 时，务必停止渲染着色器，否则游戏画面会一直模糊
        if (mc.entityRenderer.getShaderGroup() != null) {
            mc.entityRenderer.stopUseShader();
        }
    }

    public boolean doesGuiPauseGame() {
        return false;
    }

    private void savePositions() {
        JsonObject json = new JsonObject();
        for (CategoryComponent cat : categoryList) {
            JsonObject pos = new JsonObject();
            pos.addProperty("x", cat.getX());
            pos.addProperty("y", cat.getY());
            pos.addProperty("open", cat.isOpened());
            json.add(cat.getName(), pos);
        }
        try {
            FileUtil.writeAtomic(configFile, new GsonBuilder().setPrettyPrinting().create().toJson(json));
        } catch (IOException e) {
            Myau.LOGGER.error("Failed to save click GUI layout", e);
        }
    }

    /**
     * Tolerates empty, truncated or partially filled layout files: every field falls back to
     * the component's current value, and a broken file is kept as a .corrupt copy.
     */
    private void loadPositions() {
        if (!configFile.exists()) return;
        JsonObject json;
        try {
            JsonElement parsed = new JsonParser().parse(FileUtil.readString(configFile));
            if (parsed == null || !parsed.isJsonObject()) {
                return;
            }
            json = parsed.getAsJsonObject();
        } catch (Exception e) {
            Myau.LOGGER.warn("Click GUI layout is unreadable, kept a copy at {}", FileUtil.quarantine(configFile), e);
            return;
        }
        for (CategoryComponent cat : categoryList) {
            JsonElement element = json.get(cat.getName());
            if (element == null || !element.isJsonObject()) {
                continue;
            }
            JsonObject pos = element.getAsJsonObject();
            try {
                if (pos.has("x") && pos.get("x").isJsonPrimitive()) cat.setX(pos.get("x").getAsInt());
                if (pos.has("y") && pos.get("y").isJsonPrimitive()) cat.setY(pos.get("y").getAsInt());
                if (pos.has("open") && pos.get("open").isJsonPrimitive()) cat.setOpened(pos.get("open").getAsBoolean());
            } catch (RuntimeException e) {
                Myau.LOGGER.warn("Ignoring invalid layout entry for {}", cat.getName());
            }
        }
    }
}
