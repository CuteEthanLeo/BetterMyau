package myau.module.modules;

import myau.Myau;
import myau.event.EventTarget;
import myau.event.types.EventType;
import myau.events.AttackEvent;
import myau.events.PacketEvent;
import myau.mixin.IAccessorC03PacketPlayer;
import myau.mixin.IAccessorEntity;
import myau.module.Module;
import myau.property.properties.FloatProperty;
import myau.property.properties.IntProperty;
import myau.property.properties.ModeProperty;
import myau.util.MoveUtil;
import myau.util.PacketUtil;
import myau.util.TimerUtil;
import net.minecraft.client.Minecraft;
import net.minecraft.client.entity.EntityPlayerSP;
import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityLivingBase;
import net.minecraft.network.play.client.C03PacketPlayer;
import net.minecraft.network.play.client.C03PacketPlayer.C04PacketPlayerPosition;

public class Criticals extends Module {
    private static final Minecraft mc = Minecraft.getMinecraft();
    private final TimerUtil timer = new TimerUtil();
    public final ModeProperty mode = new ModeProperty(
            "mode", 0,
            new String[]{"Packet", "NCPPacket", "BlocksMC", "BlocksMC2", "NoGround", "Hop", "TPHop", "Jump", "LowJump", "CustomMotion", "Visual"}
    );
    public final IntProperty delay = new IntProperty("delay", 0, 0, 500);
    public final IntProperty hurtTime = new IntProperty("hurt-time", 10, 0, 10);
    public final FloatProperty customY = new FloatProperty("custom-y", 0.2F, 0.01F, 0.42F, () -> this.is("CustomMotion"));

    public Criticals() {
        super("Criticals", false);
    }

    private boolean is(String modeName) {
        return this.mode.getModeString().equals(modeName);
    }

    @Override
    public void onEnabled() {
        if (this.is("NoGround") && mc.thePlayer != null) {
            MoveUtil.tryJump();
        }
    }

    @EventTarget
    public void onAttack(AttackEvent event) {
        if (!this.isEnabled() || event.isCancelled()) {
            return;
        }
        Entity target = event.getTarget();
        EntityPlayerSP player = mc.thePlayer;
        if (!(target instanceof EntityLivingBase) || player == null) {
            return;
        }
        EntityLivingBase entity = (EntityLivingBase) target;
        Fly fly = (Fly) Myau.moduleManager.modules.get(Fly.class);
        if (!player.onGround
                || player.isOnLadder()
                || ((IAccessorEntity) player).getIsInWeb()
                || MoveUtil.isInLiquid()
                || player.ridingEntity != null
                || entity.hurtTime > this.hurtTime.getValue()
                || fly != null && fly.isEnabled()
                || !this.timer.hasTimeElapsed(this.delay.getValue())) {
            return;
        }
        double x = player.posX;
        double y = player.posY;
        double z = player.posZ;
        switch (this.mode.getModeString()) {
            case "Packet":
                PacketUtil.sendPacket(new C04PacketPlayerPosition(x, y + 0.0625, z, true));
                PacketUtil.sendPacket(new C04PacketPlayerPosition(x, y, z, false));
                player.onCriticalHit(entity);
                break;
            case "NCPPacket":
                PacketUtil.sendPacket(new C04PacketPlayerPosition(x, y + 0.11, z, false));
                PacketUtil.sendPacket(new C04PacketPlayerPosition(x, y + 0.1100013579, z, false));
                PacketUtil.sendPacket(new C04PacketPlayerPosition(x, y + 0.0000013579, z, false));
                player.onCriticalHit(entity);
                break;
            case "BlocksMC":
                PacketUtil.sendPacket(new C04PacketPlayerPosition(x, y + 0.001091981, z, true));
                PacketUtil.sendPacket(new C04PacketPlayerPosition(x, y, z, false));
                break;
            case "BlocksMC2":
                if (player.ticksExisted % 4 == 0) {
                    PacketUtil.sendPacket(new C04PacketPlayerPosition(x, y + 0.0011, z, true));
                    PacketUtil.sendPacket(new C04PacketPlayerPosition(x, y, z, false));
                }
                break;
            case "Hop":
                player.motionY = 0.1;
                player.fallDistance = 0.1F;
                player.onGround = false;
                break;
            case "TPHop":
                PacketUtil.sendPacket(new C04PacketPlayerPosition(x, y + 0.02, z, false));
                PacketUtil.sendPacket(new C04PacketPlayerPosition(x, y + 0.01, z, false));
                player.setPosition(x, y + 0.01, z);
                break;
            case "Jump":
                player.motionY = 0.42;
                break;
            case "LowJump":
                player.motionY = 0.3425;
                break;
            case "CustomMotion":
                player.motionY = this.customY.getValue().doubleValue();
                break;
            case "Visual":
                player.onCriticalHit(entity);
                break;
            default:
                break;
        }
        this.timer.reset();
    }

    @EventTarget
    public void onPacket(PacketEvent event) {
        if (!this.isEnabled() || event.getType() != EventType.SEND || mc.thePlayer == null) {
            return;
        }
        if (event.getPacket() instanceof C03PacketPlayer && this.is("NoGround")) {
            ((IAccessorC03PacketPlayer) event.getPacket()).setOnGround(false);
        }
    }

    @Override
    public String[] getSuffix() {
        return new String[]{this.mode.getModeString()};
    }
}
