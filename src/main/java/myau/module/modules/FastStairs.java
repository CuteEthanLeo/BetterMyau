package myau.module.modules;

import myau.Myau;
import myau.event.EventTarget;
import myau.event.types.EventType;
import myau.events.UpdateEvent;
import myau.module.Module;
import myau.property.properties.BooleanProperty;
import myau.property.properties.ModeProperty;
import myau.util.MoveUtil;
import net.minecraft.block.BlockStairs;
import net.minecraft.client.Minecraft;
import net.minecraft.client.entity.EntityPlayerSP;
import net.minecraft.util.BlockPos;

public class FastStairs extends Module {
    private static final Minecraft mc = Minecraft.getMinecraft();
    private static final int STEP = 0, NCP = 1, AAC310 = 2, AAC336 = 3, AAC3313 = 4;

    public final ModeProperty mode = new ModeProperty("mode", NCP, new String[]{"Step", "NCP", "AAC3.1.0", "AAC3.3.6", "AAC3.3.13"});
    public final BooleanProperty longJump = new BooleanProperty("long-jump", false, () -> this.isAACMode());

    private boolean canJump = false;
    private boolean walkingDown = false;

    public FastStairs() {
        super("FastStairs", false);
    }

    private boolean isAACMode() {
        return this.mode.getModeString().startsWith("AAC");
    }

    @Override
    public void onDisabled() {
        this.canJump = false;
        this.walkingDown = false;
    }

    @EventTarget
    public void onUpdate(UpdateEvent event) {
        if (!this.isEnabled() || event.getType() != EventType.PRE) {
            return;
        }
        EntityPlayerSP player = mc.thePlayer;
        if (player == null || mc.theWorld == null) {
            return;
        }
        Module speedModule = Myau.moduleManager.modules.get(Speed.class);
        if (!MoveUtil.isMoving() || speedModule != null && speedModule.isEnabled()) {
            return;
        }

        if (player.fallDistance > 0.0F && !this.walkingDown) {
            this.walkingDown = true;
        } else if (player.posY > player.prevChasingPosY) {
            this.walkingDown = false;
        }

        int mode = this.mode.getValue();

        if (!player.onGround) {
            return;
        }

        BlockPos blockPos = new BlockPos(player);

        if (mc.theWorld.getBlockState(blockPos).getBlock() instanceof BlockStairs && !this.walkingDown) {
            player.setPosition(player.posX, player.posY + 0.5, player.posZ);

            double motion;
            switch (mode) {
                case NCP:
                    motion = 1.4;
                    break;
                case AAC310:
                    motion = 1.5;
                    break;
                case AAC3313:
                    motion = 1.2;
                    break;
                default:
                    motion = 1.0;
                    break;
            }

            player.motionX *= motion;
            player.motionZ *= motion;
        }

        if (mc.theWorld.getBlockState(blockPos.down()).getBlock() instanceof BlockStairs) {
            if (this.walkingDown) {
                if (mode == NCP) {
                    player.motionY = -1.0;
                } else if (mode == AAC3313) {
                    player.motionY -= 0.014;
                }
                return;
            }

            double motion;
            switch (mode) {
                case AAC336:
                    motion = 1.48;
                    break;
                case AAC3313:
                    motion = 1.52;
                    break;
                default:
                    motion = 1.3;
                    break;
            }

            player.motionX *= motion;
            player.motionZ *= motion;
            this.canJump = true;
        } else if (this.isAACMode() && this.canJump) {
            if (this.longJump.getValue()) {
                MoveUtil.tryJump();
                player.motionX *= 1.35;
                player.motionZ *= 1.35;
            }

            this.canJump = false;
        }
    }

    @Override
    public String[] getSuffix() {
        return new String[]{this.mode.getModeString()};
    }
}
