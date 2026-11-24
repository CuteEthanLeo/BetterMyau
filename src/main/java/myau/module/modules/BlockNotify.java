package myau.module.modules;

import myau.Myau;
import myau.event.EventTarget;
import myau.event.types.EventType;
import myau.events.LoadWorldEvent;
import myau.events.PacketEvent;
import myau.events.UpdateEvent;
import myau.module.Module;
import myau.property.properties.BooleanProperty;
import myau.property.properties.IntProperty;
import myau.property.properties.ModeProperty;
import myau.util.ChatUtil;
import net.minecraft.client.Minecraft;
import net.minecraft.client.entity.EntityPlayerSP;
import net.minecraft.entity.Entity;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.network.play.server.S19PacketEntityStatus;
import net.minecraft.util.EnumParticleTypes;

import java.util.HashMap;
import java.util.Map;

/** Sound / particles / chat line whenever a hit lands on a blocking player. */
public class BlockNotify extends Module {
    private static final Minecraft mc = Minecraft.getMinecraft();
    public final ModeProperty checkMode = new ModeProperty("check-mode", 1, new String[]{"Packet", "HurtTime"});
    public final BooleanProperty playSound = new BooleanProperty("play-sound", true);
    public final BooleanProperty showVisual = new BooleanProperty("show-visual", true);
    public final BooleanProperty showChat = new BooleanProperty("show-chat", true);
    public final BooleanProperty onlySelf = new BooleanProperty("only-self", true);
    public final ModeProperty soundMode = new ModeProperty(
            "sound-mode", 0, new String[]{"Anvil", "Exp", "LevelUp", "Pling", "Orb", "BlazeHit", "NoteHarp"}, this.playSound::getValue
    );
    public final ModeProperty visualMode = new ModeProperty(
            "visual-mode", 0,
            new String[]{"Redstone", "Smoke", "Heart", "EnchantmentTable", "FireworksSpark", "VillagerHappy", "MagicCrit"},
            this.showVisual::getValue
    );
    public final IntProperty particleIntensity = new IntProperty("particle-intensity", 10, 1, 50, this.showVisual::getValue);

    private final Map<Integer, Integer> othersHurtTime = new HashMap<>();
    private int prevHurtTime = 0;

    public BlockNotify() {
        super("BlockNotify", false);
    }

    /** The local player is blocking server-side without the client knowing (KillAura / AutoBlock packets). */
    private boolean isSilentlyBlocking() {
        KillAura killAura = (KillAura) Myau.moduleManager.modules.get(KillAura.class);
        if (killAura != null && killAura.isEnabled() && killAura.isPlayerBlocking()) {
            return true;
        }
        AutoBlock autoBlock = (AutoBlock) Myau.moduleManager.modules.get(AutoBlock.class);
        return autoBlock != null && autoBlock.isEnabled() && autoBlock.isBlocking();
    }

    @Override
    public void onEnabled() {
        this.prevHurtTime = 0;
        this.othersHurtTime.clear();
    }

    @EventTarget
    public void onLoadWorld(LoadWorldEvent event) {
        this.prevHurtTime = 0;
        this.othersHurtTime.clear();
    }

    @EventTarget
    public void onPacket(PacketEvent event) {
        if (!this.isEnabled() || event.getType() != EventType.RECEIVE || this.checkMode.getValue() != 0) {
            return;
        }
        if (!(event.getPacket() instanceof S19PacketEntityStatus)) {
            return;
        }
        final S19PacketEntityStatus packet = (S19PacketEntityStatus) event.getPacket();
        if ((packet.getOpCode() & 0xFF) != 2) {
            return;
        }
        // Received on the network thread: touch the world on the client thread.
        mc.addScheduledTask(() -> {
            if (!this.isEnabled() || mc.thePlayer == null || mc.theWorld == null) {
                return;
            }
            Entity entity = packet.getEntity(mc.theWorld);
            if (!(entity instanceof EntityPlayer)) {
                return;
            }
            EntityPlayer player = (EntityPlayer) entity;
            if (this.onlySelf.getValue() && player != mc.thePlayer) {
                return;
            }
            if (player.isBlocking() || player == mc.thePlayer && this.isSilentlyBlocking()) {
                this.onBlockSuccess(player);
            }
        });
    }

    @EventTarget
    public void onUpdate(UpdateEvent event) {
        if (!this.isEnabled() || event.getType() != EventType.PRE || this.checkMode.getValue() != 1) {
            return;
        }
        EntityPlayerSP player = mc.thePlayer;
        if (this.onlySelf.getValue()) {
            boolean blocking = player.isBlocking() || this.isSilentlyBlocking();
            if (player.hurtTime > 0 && this.prevHurtTime == 0 && blocking) {
                this.onBlockSuccess(player);
            }
            this.prevHurtTime = player.hurtTime;
            return;
        }
        if (mc.theWorld == null) {
            return;
        }
        Map<Integer, Integer> seen = new HashMap<>();
        for (EntityPlayer other : mc.theWorld.playerEntities) {
            if (other == player) {
                continue;
            }
            Integer previous = this.othersHurtTime.get(other.getEntityId());
            // hurtTime jumps back up on every new hit: notify once per hit instead of every hurt tick.
            if (other.hurtTime > 0 && (previous == null || other.hurtTime > previous) && other.isBlocking()) {
                this.onBlockSuccess(other);
            }
            seen.put(other.getEntityId(), other.hurtTime);
        }
        this.othersHurtTime.clear();
        this.othersHurtTime.putAll(seen);
    }

    private void onBlockSuccess(EntityPlayer player) {
        if (this.playSound.getValue()) {
            this.playBlockSound();
        }
        if (this.showVisual.getValue()) {
            this.spawnParticles(player);
        }
        if (this.showChat.getValue()) {
            ChatUtil.sendFormatted(Myau.clientName + "&a&l格挡成功");
        }
    }

    private void playBlockSound() {
        if (mc.thePlayer == null) {
            return;
        }
        String soundName;
        switch (this.soundMode.getModeString()) {
            case "Exp":
            case "Orb":
                soundName = "random.orb";
                break;
            case "LevelUp":
                soundName = "random.levelup";
                break;
            case "Pling":
                soundName = "note.pling";
                break;
            case "BlazeHit":
                soundName = "mob.blaze.hit";
                break;
            case "NoteHarp":
                soundName = "note.harp";
                break;
            default:
                soundName = "random.anvil_land";
                break;
        }
        mc.thePlayer.playSound(soundName, 1.0F, 1.0F);
    }

    private void spawnParticles(EntityPlayer player) {
        if (mc.theWorld == null) {
            return;
        }
        EnumParticleTypes particleType;
        switch (this.visualMode.getModeString()) {
            case "Smoke":
                particleType = EnumParticleTypes.SMOKE_LARGE;
                break;
            case "Heart":
                particleType = EnumParticleTypes.HEART;
                break;
            case "EnchantmentTable":
                particleType = EnumParticleTypes.ENCHANTMENT_TABLE;
                break;
            case "FireworksSpark":
                particleType = EnumParticleTypes.FIREWORKS_SPARK;
                break;
            case "VillagerHappy":
                particleType = EnumParticleTypes.VILLAGER_HAPPY;
                break;
            case "MagicCrit":
                particleType = EnumParticleTypes.CRIT_MAGIC;
                break;
            default:
                particleType = EnumParticleTypes.REDSTONE;
                break;
        }
        double posX = player.posX;
        double posY = player.posY + (double) player.getEyeHeight();
        double posZ = player.posZ;
        for (int i = 0; i < this.particleIntensity.getValue(); i++) {
            double offsetX = (Math.random() - 0.5) * 0.5;
            double offsetY = (Math.random() - 0.5) * 0.5;
            double offsetZ = (Math.random() - 0.5) * 0.5;
            mc.theWorld.spawnParticle(particleType, posX + offsetX, posY + offsetY, posZ + offsetZ, 0.0, 0.1, 0.0);
        }
    }
}
