package myau.ui.glass;

import myau.Myau;
import myau.module.modules.Crosshair;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiMainMenu;
import net.minecraft.client.gui.GuiMultiplayer;
import net.minecraft.client.settings.KeyBinding;
import net.minecraft.client.gui.inventory.GuiChest;
import net.minecraft.client.gui.inventory.GuiCrafting;
import net.minecraft.client.gui.inventory.GuiFurnace;
import net.minecraft.client.gui.inventory.GuiInventory;
import net.minecraft.inventory.InventoryBasic;
import net.minecraft.util.ScreenShotHelper;
import net.minecraft.world.WorldSettings;
import net.minecraft.world.WorldType;
import org.lwjgl.BufferUtils;
import org.lwjgl.opengl.GL11;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.File;
import java.nio.ByteBuffer;

/**
 * Developer tool, inert unless the game is started with {@code -Dmyau.visualcheck=<directory>}.
 * It then walks the client through the screens the glass UI restyles (loading screen, main menu
 * opening, the multiplayer screen, a test world's HUD with the island grown into the player
 * list and each crosshair style, inventory, chest, furnace, crafting table, an item's hover
 * text), saves a screenshot of each into the directory and quits. For checking a change to
 * the UI without clicking through all of it by hand.
 */
public final class VisualCheck {
    public static final boolean ON = System.getProperty("myau.visualcheck") != null;
    private static final File DIRECTORY = ON ? new File(System.getProperty("myau.visualcheck")) : null;
    private static final Minecraft mc = Minecraft.getMinecraft();

    private static int stage, sub;
    private static long stageAt;
    private static boolean bootSaved;

    private VisualCheck() {
    }

    /** From the loading screen's own thread, after it drew a frame: saved once, about half way. */
    static void bootFrame(int width, int height, float progress) {
        if (bootSaved || progress < 0.45f) return;
        bootSaved = true;
        try {
            ByteBuffer pixels = BufferUtils.createByteBuffer(width * height * 4);
            GL11.glReadPixels(0, 0, width, height, GL11.GL_RGBA, GL11.GL_UNSIGNED_BYTE, pixels);
            BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
            for (int y = 0; y < height; y++) {
                for (int x = 0; x < width; x++) {
                    int i = (x + (height - 1 - y) * width) * 4;
                    image.setRGB(x, y, (pixels.get(i) & 255) << 16 | (pixels.get(i + 1) & 255) << 8 | (pixels.get(i + 2) & 255));
                }
            }
            DIRECTORY.mkdirs();
            ImageIO.write(image, "png", new File(DIRECTORY, "00-boot.png"));
        } catch (Throwable e) {
            Myau.LOGGER.warn("[VisualCheck] could not save the loading screen", e);
        }
    }

    /** Start of every frame on the main thread; the framebuffer still holds the previous frame. */
    public static void frame() {
        if (!ON) return;
        float t = (System.nanoTime() - stageAt) / 1e9f;
        switch (stage) {
            case 0:
                if (mc.currentScreen instanceof GuiMainMenu && Intro.running()) next();
                else if (mc.currentScreen instanceof GuiMainMenu && stageAt != 0 && t > 3) next();
                else if (mc.currentScreen instanceof GuiMainMenu && stageAt == 0) stageAt = System.nanoTime();
                break;
            case 1:
                if (t >= 0.2f) shot("01-opening-hold");
                break;
            case 2:
                if (t >= 0.45f) shot("02-opening-travel");
                break;
            case 3:
                if (t >= 0.45f) shot("03-opening-arrive");
                break;
            case 4:
                if (sub == 0 && t >= 2f) {
                    snap("04-menu");
                    step();
                    // The multiplayer screen (the glass one replaces it), with the first server selected.
                    mc.addScheduledTask(() -> mc.displayGuiScreen(new GuiMultiplayer(mc.currentScreen)));
                } else if (sub == 1 && t >= 1.2f) {
                    step();
                    mc.addScheduledTask(() -> {
                        if (mc.currentScreen instanceof GuiMultiplayer) ((GuiMultiplayer) mc.currentScreen).selectServer(0);
                    });
                } else if (sub == 2 && t >= 2.5f) {
                    snap("04b-multiplayer");
                    step();
                    mc.addScheduledTask(() -> mc.displayGuiScreen(new GuiMainMenu()));
                } else if (sub == 3 && t >= 0.8f) {
                    sub = 0;
                    next();
                    mc.gameSettings.pauseOnLostFocus = false;
                    mc.addScheduledTask(() -> {
                        WorldSettings settings = new WorldSettings(0L, WorldSettings.GameType.SURVIVAL, false, false, WorldType.FLAT);
                        settings.enableCommands();
                        mc.launchIntegratedServer("visualcheck", "visualcheck", settings);
                    });
                }
                break;
            case 5:
                if (sub == 0) {
                    if (mc.thePlayer == null || mc.theWorld == null || mc.currentScreen != null) {
                        stageAt = System.nanoTime();
                    } else if (t >= 3f) {
                        for (String command : new String[]{"/time set 1000", "/gamerule doMobSpawning false", "/clear",
                                "/give @p diamond_sword", "/give @p wool 64", "/give @p diamond_pickaxe", "/give @p golden_apple 3",
                                "/give @p bow", "/give @p arrow 32", "/give @p iron_chestplate", "/effect @p 22 120 1", "/effect @p 1 120 0",
                                // A list objective, so the player list shows in singleplayer (and has scores).
                                "/scoreboard objectives add vchp health", "/scoreboard objectives setdisplay list vchp"}) {
                            mc.thePlayer.sendChatMessage(command);
                        }
                        step();
                    }
                } else if (sub == 1 && t >= 1.5f) {
                    // Hold the player list key: the island grows into the list.
                    mc.ingameGUI.getChatGUI().clearChatMessages();
                    KeyBinding.setKeyBindState(mc.gameSettings.keyBindPlayerList.getKeyCode(), true);
                    step();
                } else if (sub == 2 && t >= 0.12f) {
                    snap("05a-island-tab-opening");
                    step();
                } else if (sub == 3 && t >= 1.2f) {
                    snap("05b-island-tab");
                    KeyBinding.setKeyBindState(mc.gameSettings.keyBindPlayerList.getKeyCode(), false);
                    step();
                } else if (sub == 4 && t >= 0.12f) {
                    snap("05c-island-tab-closing");
                    step();
                } else if (sub == 5 && t >= 0.8f) {
                    sub = 0;
                    mc.thePlayer.sendChatMessage("/scoreboard objectives setdisplay list");
                    next();
                }
                break;
            case 14:
                if (t >= 1f) {
                    shot("13-tooltip");
                    mc.addScheduledTask(VisualCheck::openClickGui);
                }
                break;
            case 6:
            case 7:
            case 8:
            case 9:
                // The command feedback would sit on top of the status capsule in the first shot.
                if (stage == 6 && t >= 1.2f) mc.ingameGUI.getChatGUI().clearChatMessages();
                if (t >= (stage == 6 ? 2f : 0.5f)) {
                    Crosshair crosshair = Crosshair.instance();
                    int style = stage - 6;
                    shot("0" + (5 + style) + "-hud-crosshair-" + style);
                    if (crosshair != null) crosshair.style.setValue((style + 1) % 4);
                    if (style == 3) mc.addScheduledTask(() -> mc.displayGuiScreen(new GuiInventory(mc.thePlayer)));
                }
                break;
            case 10:
                if (t >= 1.5f) {
                    shot("09-inventory");
                    mc.addScheduledTask(() -> mc.displayGuiScreen(new GuiChest(mc.thePlayer.inventory, new InventoryBasic("Large Chest", false, 54))));
                }
                break;
            case 11:
                if (t >= 1f) {
                    shot("10-chest");
                    mc.addScheduledTask(() -> mc.displayGuiScreen(new GuiFurnace(mc.thePlayer.inventory, new InventoryBasic("Furnace", false, 3))));
                }
                break;
            case 12:
                if (t >= 1f) {
                    shot("11-furnace");
                    mc.addScheduledTask(() -> mc.displayGuiScreen(new GuiCrafting(mc.thePlayer.inventory, mc.theWorld)));
                }
                break;
            case 13:
                if (t >= 1f) {
                    shot("12-crafting");
                    mc.addScheduledTask(() -> mc.displayGuiScreen(new TooltipProbe()));
                }
                break;
            default:
                if (stage >= GUI_FIRST) clickGuiStages(t);
        }
    }

    // ---------------------------------------------------------------- click GUI and capes

    private static final int GUI_FIRST = 15, THEMES = GuiPalette.count(), COMBOS = 3 * THEMES, CAPE_FIRST = 6;
    private static int savedStyle, savedTheme, savedColor, savedCape, savedView;
    private static boolean savedCapeOn, prepared, saved;

    private static <T> T module(Class<T> type) {
        return type.cast(Myau.moduleManager.modules.get(type));
    }

    /** Everything changed below is put back before quitting: this runs in the normal dev profile. */
    private static void openClickGui() {
        myau.module.modules.GuiModule gui = module(myau.module.modules.GuiModule.class);
        myau.module.modules.HUD hud = module(myau.module.modules.HUD.class);
        myau.module.modules.Cape cape = module(myau.module.modules.Cape.class);
        savedStyle = gui.style.getValue();
        savedTheme = gui.theme.getValue();
        savedColor = hud.colorMode.getValue();
        savedCape = cape.cape.getValue();
        savedCapeOn = cape.isEnabled();
        savedView = mc.gameSettings.thirdPersonView;
        saved = true;
        // Looking at the ground, so the scene behind the GUI holds still.
        mc.thePlayer.rotationPitch = 90;
        gui.style.setValue(0);
        gui.theme.setValue(0);
        mc.displayGuiScreen(gui.screen());
    }

    private static void restore() {
        if (!saved) return;
        module(myau.module.modules.GuiModule.class).style.setValue(savedStyle);
        module(myau.module.modules.GuiModule.class).theme.setValue(savedTheme);
        module(myau.module.modules.HUD.class).colorMode.setValue(savedColor);
        myau.module.modules.Cape cape = module(myau.module.modules.Cape.class);
        cape.cape.setValue(savedCape);
        if (cape.isEnabled() != savedCapeOn) cape.setEnabled(savedCapeOn);
        mc.gameSettings.thirdPersonView = savedView;
        Draw.setOptimised(true);
    }

    private static void revealOnce() {
        if (prepared || !(mc.currentScreen instanceof ClickGuiBase)) return;
        prepared = true;
        ((ClickGuiBase) mc.currentScreen).reveal(module(myau.module.modules.HUD.class));
    }

    private static void clickGuiStages(float t) {
        myau.module.modules.GuiModule gui = module(myau.module.modules.GuiModule.class);
        int n = stage - GUI_FIRST;
        if (n < COMBOS) {
            // Each layout in each theme, with a module's settings open.
            if (t >= 0.4f) revealOnce();
            if (t >= 1.6f) {
                shot("14-gui-" + gui.style.getModeString().toLowerCase() + "-" + gui.theme.getModeString().toLowerCase());
                prepared = false;
                int next = n + 1;
                gui.style.setValue(next < COMBOS ? next / THEMES : 1);
                gui.theme.setValue(next < COMBOS ? next % THEMES : 0);
                // The comparison that follows needs an accent colour that does not move with time.
                if (next >= COMBOS) module(myau.module.modules.HUD.class).colorMode.setValue(3);
            }
        } else if (n == COMBOS) {
            // Same frame twice, with and without the draw-call optimisations: must be identical.
            if (t >= 0.4f) revealOnce();
            if (t >= 4f) {
                shot("15-ab-optimised");
                Myau.LOGGER.info("[GuiPerf] --- optimisations off ---");
                Draw.setOptimised(false);
            }
        } else if (n == COMBOS + 1) {
            if (t >= 4f) {
                shot("16-ab-plain");
                Myau.LOGGER.info("[GuiPerf] --- optimisations on ---");
                Draw.setOptimised(true);
                prepared = false;
                mc.addScheduledTask(() -> {
                    mc.displayGuiScreen(null);
                    mc.gameSettings.thirdPersonView = 1;
                    mc.thePlayer.rotationPitch = 12;
                    myau.module.modules.Cape cape = module(myau.module.modules.Cape.class);
                    cape.cape.setValue(CAPE_FIRST);
                    if (!cape.isEnabled()) cape.setEnabled(true);
                });
            }
        } else {
            // Every flag cape, seen from behind.
            myau.module.modules.Cape cape = module(myau.module.modules.Cape.class);
            if (t >= 0.8f) {
                int index = cape.cape.getValue();
                shot("17-cape-" + cape.cape.getModeString().toLowerCase());
                // ModeProperty does not range-check, so the end of the list is tested here.
                if (index + 1 < cape.cape.getValuePrompt().split(", ").length) {
                    cape.cape.setValue(index + 1);
                } else {
                    restore();
                    mc.addScheduledTask(mc::shutdown);
                    stage = Integer.MIN_VALUE;
                }
            }
        }
    }

    /** Shows an item's hover text without needing the mouse over a slot. */
    private static final class TooltipProbe extends net.minecraft.client.gui.GuiScreen {
        @Override
        public void drawScreen(int mouseX, int mouseY, float partialTicks) {
            this.drawDefaultBackground();
            net.minecraft.item.ItemStack sword = new net.minecraft.item.ItemStack(net.minecraft.init.Items.diamond_sword);
            sword.addEnchantment(net.minecraft.enchantment.Enchantment.sharpness, 4);
            sword.addEnchantment(net.minecraft.enchantment.Enchantment.fireAspect, 2);
            this.renderToolTip(sword, this.width / 2 - 60, this.height / 2);
        }
    }

    private static void next() {
        stage++;
        stageAt = System.nanoTime();
    }

    /** A step inside the current stage. */
    private static void step() {
        sub++;
        stageAt = System.nanoTime();
    }

    private static void shot(String name) {
        snap(name);
        next();
    }

    private static void snap(String name) {
        try {
            DIRECTORY.mkdirs();
            ScreenShotHelper.saveScreenshot(DIRECTORY, name + ".png", mc.displayWidth, mc.displayHeight, mc.getFramebuffer());
        } catch (Throwable e) {
            Myau.LOGGER.warn("[VisualCheck] could not save " + name, e);
        }
    }
}
