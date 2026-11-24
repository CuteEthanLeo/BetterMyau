package myau.module.modules;

import myau.Myau;
import myau.module.Module;
import myau.property.properties.BooleanProperty;
import myau.property.properties.FloatProperty;

/**
 * Dropped items tumble while they fall and lie flat on the ground instead of hovering and spinning.
 * Drawn by {@code MixinRenderEntityItem}, which replaces {@code RenderEntityItem.doRender} while this is on.
 */
public class ItemPhysics extends Module {
    // Lying items are tilted by their yaw instead of lying perfectly flat
    public final BooleanProperty realistic = new BooleanProperty("realistic", false);
    // How far the tumbling axis leans away from the item's face
    public final FloatProperty weight = new FloatProperty("weight", 0.5F, 0.1F, 3.0F);
    public final FloatProperty rotationSpeed = new FloatProperty("rotation-speed", 1.0F, 0.01F, 3.0F);

    public ItemPhysics() {
        super("ItemPhysics", false);
    }

    /** The module, or null before the client has started or while it is not registered. */
    public static ItemPhysics instance() {
        return Myau.moduleManager == null ? null : (ItemPhysics) Myau.moduleManager.modules.get(ItemPhysics.class);
    }
}
