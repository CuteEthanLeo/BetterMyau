package myau.module.modules;

import myau.event.EventTarget;
import myau.event.types.EventType;
import myau.event.types.Priority;
import myau.events.LoadWorldEvent;
import myau.events.PacketEvent;
import myau.events.PlayerUpdateEvent;
import myau.events.UpdateEvent;
import myau.mixin.IAccessorKeyBinding;
import myau.module.Module;
import myau.property.properties.BooleanProperty;
import myau.property.properties.ModeProperty;
import myau.util.MoveUtil;
import myau.util.PacketUtil;
import net.minecraft.client.Minecraft;
import net.minecraft.client.entity.EntityPlayerSP;
import net.minecraft.client.settings.GameSettings;
import net.minecraft.network.play.client.C0BPacketEntityAction;
import net.minecraft.network.play.client.C0BPacketEntityAction.Action;

public class Sneak extends Module {
    private static final Minecraft mc = Minecraft.getMinecraft();
    private static final int LEGIT = 0, VANILLA = 1, SWITCH = 2, MINE_SECURE = 3;

    public final ModeProperty mode = new ModeProperty("mode", MINE_SECURE, new String[]{"Legit", "Vanilla", "Switch", "MineSecure"});
    public final BooleanProperty stopMove = new BooleanProperty("stop-move", false);

    private boolean sneaking = false;
    /** Mode the current sneak state was produced with (the mode can be switched while enabled). */
    private int activeMode = -1;
    /** Set between the pre and post motion hooks of one tick (there is no pre hook while riding). */
    private boolean preFired = false;
    private int lastTicksExisted = 0;

    // MineSecure hides sprinting from the server. Both sides are tracked from the packets (also while the
    // module is off) so the server can be brought back in line with what the game believes it has sent.
    /** Sprint state the game believes the server has. */
    private boolean gameSprintState = false;
    /** Sprint state the server really has. */
    private boolean serverSprintState = false;

    public Sneak() {
        super("Sneak", false);
    }

    private static void send(Action action) {
        PacketUtil.sendPacketNoEvent(new C0BPacketEntityAction(mc.thePlayer, action));
    }

    /** xw: sprinting is reported as off while MineSecure is active (and stop-move does not pause it). */
    private boolean isSprintHidden() {
        return this.isEnabled() && this.mode.getValue() == MINE_SECURE && !(this.stopMove.getValue() && MoveUtil.isMoving());
    }

    private void restoreSprint() {
        if (this.serverSprintState != this.gameSprintState) {
            send(this.gameSprintState ? Action.START_SPRINTING : Action.STOP_SPRINTING);
            this.serverSprintState = this.gameSprintState;
        }
    }

    /** xw MotionEvent PRE */
    @EventTarget
    public void onPlayerUpdate(PlayerUpdateEvent event) {
        if (!this.isEnabled() || mc.thePlayer == null) {
            return;
        }
        this.preFired = true;
        this.onMotion(true);
    }

    /** xw MotionEvent POST */
    @EventTarget
    public void onUpdate(UpdateEvent event) {
        if (event.getType() != EventType.POST) {
            return;
        }
        boolean hadPre = this.preFired;
        this.preFired = false;
        if (!this.isEnabled() || mc.thePlayer == null || !hadPre) {
            return;
        }
        this.onMotion(false);
    }

    private void onMotion(boolean pre) {
        EntityPlayerSP player = mc.thePlayer;
        int mode = this.mode.getValue();

        // New player entity (respawn): the server forgot the sneak / sprint state
        if (player.ticksExisted < this.lastTicksExisted) {
            this.sneaking = false;
            this.gameSprintState = false;
            this.serverSprintState = false;
        }
        this.lastTicksExisted = player.ticksExisted;

        // Mode switched while enabled: undo what the old mode did first
        if (this.sneaking && this.activeMode != mode) {
            this.stopSneak(this.activeMode);
        }

        if (this.stopMove.getValue() && MoveUtil.isMoving()) {
            if (this.sneaking) {
                this.stopSneak(mode);
            }
            this.restoreSprint();
            return;
        }

        switch (mode) {
            case LEGIT:
                ((IAccessorKeyBinding) mc.gameSettings.keyBindSneak).setPressed(true);
                break;
            case VANILLA:
                if (this.sneaking) {
                    return;
                }
                send(Action.START_SNEAKING);
                break;
            case SWITCH:
                if (pre) {
                    send(Action.START_SNEAKING);
                    send(Action.STOP_SNEAKING);
                } else {
                    send(Action.STOP_SNEAKING);
                    send(Action.START_SNEAKING);
                }
                break;
            case MINE_SECURE:
                if (pre) {
                    if (this.serverSprintState) {
                        send(Action.STOP_SPRINTING);
                        this.serverSprintState = false;
                    }
                    return;
                }
                send(Action.START_SNEAKING);
                break;
            default:
                break;
        }
        if (mode != MINE_SECURE) {
            this.restoreSprint();
        }
        this.sneaking = true;
        this.activeMode = mode;
    }

    // LOWEST: runs last, so packets another module already cancelled are not counted
    @EventTarget(Priority.LOWEST)
    public void onPacket(PacketEvent event) {
        if (event.getType() != EventType.SEND || event.isCancelled() || !(event.getPacket() instanceof C0BPacketEntityAction)) {
            return;
        }
        Action action = ((C0BPacketEntityAction) event.getPacket()).getAction();
        if (action == Action.START_SNEAKING || action == Action.STOP_SNEAKING) {
            if (this.isEnabled() && this.mode.getValue() != LEGIT) {
                // The game's own sneak packets are replaced by the ones this module sends
                event.setCancelled(true);
            }
        } else if (action == Action.START_SPRINTING || action == Action.STOP_SPRINTING) {
            boolean sprinting = action == Action.START_SPRINTING;
            this.gameSprintState = sprinting;
            if (this.isSprintHidden() && mc.thePlayer != null && (sprinting || !this.serverSprintState)) {
                event.setCancelled(true);
            } else {
                this.serverSprintState = sprinting;
            }
        }
    }

    @EventTarget
    public void onLoadWorld(LoadWorldEvent event) {
        this.sneaking = false;
        this.preFired = false;
        this.lastTicksExisted = 0;
        this.gameSprintState = false;
        this.serverSprintState = false;
    }

    @Override
    public void onDisabled() {
        this.preFired = false;
        if (mc.thePlayer == null || mc.getNetHandler() == null) {
            this.sneaking = false;
            return;
        }
        this.stopSneak(this.sneaking && this.activeMode >= 0 ? this.activeMode : this.mode.getValue());
        this.restoreSprint();
    }

    private void stopSneak(int mode) {
        EntityPlayerSP player = mc.thePlayer;
        if (mode == LEGIT) {
            if (!GameSettings.isKeyDown(mc.gameSettings.keyBindSneak)) {
                ((IAccessorKeyBinding) mc.gameSettings.keyBindSneak).setPressed(false);
            }
        } else {
            // The game's own sneak packets were swallowed meanwhile, so leave the server in the state the game expects
            send(player.isSneaking() ? Action.START_SNEAKING : Action.STOP_SNEAKING);
        }
        this.sneaking = false;
    }

    @Override
    public String[] getSuffix() {
        return new String[]{this.mode.getModeString()};
    }
}
