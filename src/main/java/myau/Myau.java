package myau;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import me.ksyz.accountmanager.AccountManager;
import myau.command.CommandManager;
import myau.command.commands.*;
import myau.config.ConfigManager;
import myau.event.EventManager;
import myau.management.*;
import myau.module.Module;
import myau.module.ModuleManager;
import myau.module.modules.*;
import myau.property.Property;
import myau.property.PropertyManager;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.io.InputStreamReader;
import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Objects;

public class Myau {
    public static final Logger LOGGER = LogManager.getLogger("BetterMyau");
    public static String clientName = "&8[&bBetterMyau&8]&r ";
    public static String version;
    public static RotationManager rotationManager;
    public static FloatManager floatManager;
    public static BlinkManager blinkManager;
    public static DelayManager delayManager;
    public static LagManager lagManager;
    public static PlayerStateManager playerStateManager;
    public static FriendManager friendManager;
    public static TargetManager targetManager;
    public static PropertyManager propertyManager;
    public static ModuleManager moduleManager;
    public static CommandManager commandManager;

    public Myau() {
        this.init();
    }

    public void init() {
        long t0 = System.currentTimeMillis();

        rotationManager = new RotationManager();
        floatManager = new FloatManager();
        blinkManager = new BlinkManager();
        delayManager = new DelayManager();
        lagManager = new LagManager();
        playerStateManager = new PlayerStateManager();
        friendManager = new FriendManager();
        targetManager = new TargetManager();
        propertyManager = new PropertyManager();
        moduleManager = new ModuleManager();
        commandManager = new CommandManager();

        // 🚀 批量注册所有管理器和模块（单次反射扫描 + 每事件类型仅排序一次）
        java.util.List<Object> registrants = new ArrayList<>();
        Collections.addAll(registrants, rotationManager, floatManager, blinkManager,
            delayManager, lagManager, moduleManager, commandManager, myau.util.TickScheduler.INSTANCE);
        EventManager.registerAll(registrants);

        moduleManager.modules.put(AimAssist.class, new AimAssist());
        moduleManager.modules.put(AntiAFK.class, new AntiAFK());
        moduleManager.modules.put(AntiDebuff.class, new AntiDebuff());
        moduleManager.modules.put(AntiFireball.class, new AntiFireball());
        moduleManager.modules.put(AntiObbyTrap.class, new AntiObbyTrap());
        moduleManager.modules.put(AntiObfuscate.class, new AntiObfuscate());
        moduleManager.modules.put(AntiVoid.class, new AntiVoid());
        moduleManager.modules.put(AutoClicker.class, new AutoClicker());
        moduleManager.modules.put(AutoAnduril.class, new AutoAnduril());
        moduleManager.modules.put(AutoHeal.class, new AutoHeal());
        moduleManager.modules.put(AutoTool.class, new AutoTool());
        moduleManager.modules.put(BedNuker.class, new BedNuker());
        moduleManager.modules.put(BedESP.class, new BedESP());
        moduleManager.modules.put(BedTracker.class, new BedTracker());
        moduleManager.modules.put(Blink.class, new Blink());
        moduleManager.modules.put(Chams.class, new Chams());
        moduleManager.modules.put(ChestESP.class, new ChestESP());
        moduleManager.modules.put(ChestStealer.class, new ChestStealer());
        moduleManager.modules.put(Crosshair.class, new Crosshair());
        moduleManager.modules.put(DynamicCamera.class, new DynamicCamera());
        moduleManager.modules.put(Eagle.class, new Eagle());
        moduleManager.modules.put(ESP.class, new ESP());
        moduleManager.modules.put(FakeLag.class, new FakeLag());
        moduleManager.modules.put(FastPlace.class, new FastPlace());
        moduleManager.modules.put(FlagCheck.class, new FlagCheck());
        moduleManager.modules.put(Freeze.class, new Freeze());
        moduleManager.modules.put(Fly.class, new Fly());
        moduleManager.modules.put(FullBright.class, new FullBright());
        moduleManager.modules.put(GhostHand.class, new GhostHand());
        moduleManager.modules.put(GuiModule.class, new GuiModule());
        moduleManager.modules.put(HitSelect.class, new HitSelect());
        moduleManager.modules.put(Hotbar.class, new Hotbar());
        moduleManager.modules.put(HUD.class, new HUD());
        moduleManager.modules.put(MoreKB.class, new MoreKB());
        moduleManager.modules.put(Indicators.class, new Indicators());
        moduleManager.modules.put(InventoryClicker.class, new InventoryClicker());
        moduleManager.modules.put(InvManager.class, new InvManager());
        moduleManager.modules.put(InvWalk.class, new InvWalk());
        moduleManager.modules.put(ItemESP.class, new ItemESP());
        moduleManager.modules.put(Jesus.class, new Jesus());
        moduleManager.modules.put(KeepSprint.class, new KeepSprint());
        moduleManager.modules.put(HitBox.class, new HitBox());
        moduleManager.modules.put(KillAura.class, new KillAura());
        moduleManager.modules.put(LagRange.class, new LagRange());
        moduleManager.modules.put(LightningTracker.class, new LightningTracker());
        moduleManager.modules.put(LongJump.class, new LongJump());
        moduleManager.modules.put(MCF.class, new MCF());
        moduleManager.modules.put(NameTags.class, new NameTags());
        moduleManager.modules.put(NickHider.class, new NickHider());
        moduleManager.modules.put(NoFall.class, new NoFall());
        moduleManager.modules.put(NoHitDelay.class, new NoHitDelay());
        moduleManager.modules.put(NoHurtCam.class, new NoHurtCam());
        moduleManager.modules.put(NoJumpDelay.class, new NoJumpDelay());
        moduleManager.modules.put(NoRotate.class, new NoRotate());
        moduleManager.modules.put(NoSlow.class, new NoSlow());
        moduleManager.modules.put(Radar.class, new Radar());
        moduleManager.modules.put(Reach.class, new Reach());
        moduleManager.modules.put(Refill.class, new Refill());
        moduleManager.modules.put(SafeWalk.class, new SafeWalk());
        moduleManager.modules.put(Scaffold.class, new Scaffold());
        moduleManager.modules.put(LeaderScaffold.class, new LeaderScaffold());
        moduleManager.modules.put(AutoBlockIn.class, new AutoBlockIn());
        moduleManager.modules.put(Clutch.class, new Clutch());
        moduleManager.modules.put(BackTrack.class, new BackTrack());
        moduleManager.modules.put(Spammer.class, new Spammer());
        moduleManager.modules.put(Speed.class, new Speed());
        moduleManager.modules.put(SpeedMine.class, new SpeedMine());
        moduleManager.modules.put(Sprint.class, new Sprint());
        moduleManager.modules.put(TargetHUD.class, new TargetHUD());
        moduleManager.modules.put(Keystrokes.class, new Keystrokes());
        // Music player: settings + screen (MusicPlayer) and the island capsule / lyrics (MusicHud).
        moduleManager.modules.put(MusicPlayer.class, new MusicPlayer());
        moduleManager.modules.put(MusicHud.class, new MusicHud());
        moduleManager.modules.put(TargetStrafe.class, new TargetStrafe());
        moduleManager.modules.put(Tracers.class, new Tracers());
        moduleManager.modules.put(Trajectories.class, new Trajectories());
        moduleManager.modules.put(TNTTimer.class, new TNTTimer());
        moduleManager.modules.put(Velocity.class, new Velocity());
        moduleManager.modules.put(Disabler.class, new Disabler());
        moduleManager.modules.put(ViewClip.class, new ViewClip());
        moduleManager.modules.put(FreeLook.class, new FreeLook());
        moduleManager.modules.put(SaveMoveKeys.class, new SaveMoveKeys());
        moduleManager.modules.put(Wtap.class, new Wtap());
        moduleManager.modules.put(Xray.class, new Xray());
        moduleManager.modules.put(PerformanceOptimizer.class, new PerformanceOptimizer());
        // ── ported from xw (AirClient) ──
        moduleManager.modules.put(Animations.class, new Animations());
        moduleManager.modules.put(ItemPhysics.class, new ItemPhysics());
        moduleManager.modules.put(Cape.class, new Cape());
        moduleManager.modules.put(Breadcrumbs.class, new Breadcrumbs());
        moduleManager.modules.put(DamageParticle.class, new DamageParticle());
        moduleManager.modules.put(HitBubbles.class, new HitBubbles());
        moduleManager.modules.put(MotionBlur.class, new MotionBlur());
        moduleManager.modules.put(AttackEffect.class, new AttackEffect());
        moduleManager.modules.put(BlockOverlay.class, new BlockOverlay());
        moduleManager.modules.put(AntiBot.class, new AntiBot());
        moduleManager.modules.put(Teams.class, new Teams());
        moduleManager.modules.put(StaffDetector.class, new StaffDetector());
        moduleManager.modules.put(AnticheatDetector.class, new AnticheatDetector());
        moduleManager.modules.put(GameDetector.class, new GameDetector());
        moduleManager.modules.put(AutoDisable.class, new AutoDisable());
        moduleManager.modules.put(Notifier.class, new Notifier());
        moduleManager.modules.put(AutoL.class, new AutoL());
        moduleManager.modules.put(AutoAccount.class, new AutoAccount());
        moduleManager.modules.put(PacketDebugger.class, new PacketDebugger());
        moduleManager.modules.put(ResourcePackSpoof.class, new ResourcePackSpoof());
        moduleManager.modules.put(ClientFixes.class, new ClientFixes());
        moduleManager.modules.put(NoWeb.class, new NoWeb());
        moduleManager.modules.put(Criticals.class, new Criticals());
        moduleManager.modules.put(AutoBlock.class, new AutoBlock());
        moduleManager.modules.put(BlockNotify.class, new BlockNotify());
        moduleManager.modules.put(TNTBlock.class, new TNTBlock());
        moduleManager.modules.put(ProjectileAimbot.class, new ProjectileAimbot());
        moduleManager.modules.put(SmartBlink.class, new SmartBlink());
        moduleManager.modules.put(AutoRod.class, new AutoRod());
        moduleManager.modules.put(AutoProjectile.class, new AutoProjectile());
        moduleManager.modules.put(AutoWeapon.class, new AutoWeapon());
        moduleManager.modules.put(AutoBow.class, new AutoBow());
        moduleManager.modules.put(FastBow.class, new FastBow());
        moduleManager.modules.put(AutoPot.class, new AutoPot());
        moduleManager.modules.put(AutoSoup.class, new AutoSoup());
        moduleManager.modules.put(AutoLeave.class, new AutoLeave());
        moduleManager.modules.put(TickBase.class, new TickBase());
        moduleManager.modules.put(TimerRange.class, new TimerRange());
        moduleManager.modules.put(Step.class, new Step());
        moduleManager.modules.put(ReverseStep.class, new ReverseStep());
        moduleManager.modules.put(Strafe.class, new Strafe());
        moduleManager.modules.put(HighJump.class, new HighJump());
        moduleManager.modules.put(WallClimb.class, new WallClimb());
        moduleManager.modules.put(FastClimb.class, new FastClimb());
        moduleManager.modules.put(IceSpeed.class, new IceSpeed());
        moduleManager.modules.put(BufferSpeed.class, new BufferSpeed());
        moduleManager.modules.put(FastStairs.class, new FastStairs());
        moduleManager.modules.put(Parkour.class, new Parkour());
        moduleManager.modules.put(Sneak.class, new Sneak());
        moduleManager.modules.put(TimerModule.class, new TimerModule());
        moduleManager.modules.put(NoClip.class, new NoClip());
        moduleManager.modules.put(JumpCircle.class, new JumpCircle());
        // ── ported from Zak0 ──
        // ChestAura must stay after AntiFireball / BedNuker: its LOWEST update handler has to run last.
        moduleManager.modules.put(AutoGapple.class, new AutoGapple());
        moduleManager.modules.put(KeyPearl.class, new KeyPearl());
        moduleManager.modules.put(DataCollector.class, new DataCollector());
        moduleManager.modules.put(AIAdvisor.class, new AIAdvisor());
        moduleManager.modules.put(ChestAura.class, new ChestAura());
        long t1 = System.currentTimeMillis();

        // 🚀 批量注册所有模块事件 + Property 字段扫描（使用缓存）
        registrants.clear();
        registrants.addAll(moduleManager.modules.values());
        EventManager.registerAll(registrants);

        for (Module module : moduleManager.modules.values()) {
            ArrayList<Property<?>> properties = new ArrayList<>();
            for (Field field : PropertyManager.getPropertyFields(module.getClass())) {
                try {
                    Property<?> prop = (Property<?>) field.get(module);
                    prop.setOwner(module);
                    properties.add(prop);
                } catch (IllegalAccessException e) {
                    throw new RuntimeException(e);
                }
            }
            propertyManager.properties.put(module.getClass(), properties);
        }
        long t2 = System.currentTimeMillis();

        commandManager.commands.add(new BindCommand());
        commandManager.commands.add(new ConfigCommand());
        commandManager.commands.add(new DenickCommand());
        commandManager.commands.add(new FriendCommand());
        commandManager.commands.add(new HelpCommand());
        commandManager.commands.add(new HideCommand());
        commandManager.commands.add(new IgnCommand());
        commandManager.commands.add(new ItemCommand());
        commandManager.commands.add(new ListCommand());
        commandManager.commands.add(new ModuleCommand());
        commandManager.commands.add(new PlayerCommand());
        commandManager.commands.add(new ShowCommand());
        commandManager.commands.add(new TargetCommand());
        commandManager.commands.add(new ToggleCommand());
        commandManager.commands.add(new VclipCommand());
        commandManager.commands.add(new MusicCommand());
        commandManager.commands.add(new GuideCommand());
        commandManager.commands.add(new MLCommand());
        commandManager.commands.add(new OwnBedCommand());
        commandManager.commands.add(new AICommand());
        commandManager.commands.add(new ViaCommand());

        // 🚀 延迟加载配置：不触发 onEnabled()，进服后才唤醒模块
        // Starts from the config used last time (falls back to default).
        ConfigManager.loadDeferred(ConfigManager.init());
        ConfigManager.ready();
        myau.ui.glass.MenuBackdrop.migrateDefault();
        if (friendManager.file.exists()) {
            friendManager.load();
        }
        if (targetManager.file.exists()) {
            targetManager.load();
        }
        Runtime.getRuntime().addShutdownHook(new Thread(Myau::saveActiveConfig, "BetterMyau-config-save"));

        try (InputStreamReader reader = new InputStreamReader(Objects.requireNonNull(Myau.class.getResourceAsStream("/version.json")), StandardCharsets.UTF_8)) {
            JsonObject modInfo = new JsonParser().parse(reader).getAsJsonObject();
            version = modInfo.get("version").getAsString();
        } catch (Exception e) {
            version = "dev";
        }

        AccountManager.init();
        // 跨版本 Via：后台加载（不拖慢启动），装了 ViaForge 时让给它。
        myau.via.ViaHooks.start(new java.io.File("./config/Myau/via"), version);
        long t3 = System.currentTimeMillis();

        LOGGER.info("Init: modules={}ms, properties={}ms, config+etc={}ms, total={}ms", t1 - t0, t2 - t1, t3 - t2, t3 - t0);
    }

    /**
     * Shutdown hook: with auto-save on, saves the live module state back into the config the
     * user last loaded or saved (never blindly into default.json); with it off, writes nothing.
     */
    private static void saveActiveConfig() {
        ConfigManager.onShutdown();
    }
}
