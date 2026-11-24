package myau.module.mode;

import myau.events.BlockBBEvent;
import myau.events.JumpEvent;
import myau.events.MoveEvent;
import myau.events.PacketEvent;
import myau.events.Render2DEvent;
import myau.events.Render3DEvent;
import myau.events.StepEvent;
import myau.events.StrafeEvent;
import myau.events.UpdateEvent;
import myau.module.Module;
import net.minecraft.client.Minecraft;

/**
 * One selectable behaviour of a multi-mode module (Speed, Fly, LongJump, NoFall, NoWeb ...).
 *
 * The owning module declares the settings (properties are only scanned on the module class
 * itself), keeps a {@link ModeList}, and forwards its events to the selected mode after a single
 * isEnabled() check. A mode therefore never sees events while the module is off.
 */
public abstract class ModuleMode<M extends Module> {
    protected static final Minecraft mc = Minecraft.getMinecraft();
    /** Name shown in the mode selector; must be unique within the module and contain no ", ". */
    public final String name;
    protected M parent;

    protected ModuleMode(String name) {
        this.name = name;
    }

    public void bind(M parent) {
        this.parent = parent;
    }

    public void onEnable() {
    }

    public void onDisable() {
    }

    /** UpdateEvent PRE: start of the player tick (silent rotations are set here). */
    public void onUpdate(UpdateEvent event) {
    }

    /** PlayerUpdateEvent: right before the movement packet of this tick is sent. */
    public void onMotion() {
    }

    /** UpdateEvent POST: after the movement packet of this tick was sent. */
    public void onPostMotion() {
    }

    /** TickEvent PRE: start of the game tick. */
    public void onTick() {
    }

    public void onMove(MoveEvent event) {
    }

    public void onStrafe(StrafeEvent event) {
    }

    public void onJump(JumpEvent event) {
    }

    public void onStep(StepEvent event) {
    }

    public void onBB(BlockBBEvent event) {
    }

    /** Both directions; check {@code event.getType()}. The player may be null. */
    public void onPacket(PacketEvent event) {
    }

    public void onRender2D(Render2DEvent event) {
    }

    public void onRender3D(Render3DEvent event) {
    }
}
