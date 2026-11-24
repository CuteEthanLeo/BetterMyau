package myau.module.modules;

import myau.event.EventTarget;
import myau.events.PacketEvent;
import myau.module.Module;
import myau.property.properties.IntProperty;
import myau.property.properties.ModeProperty;
import myau.property.properties.TextProperty;
import myau.util.ChatUtil;
import myau.util.TimerUtil;
import net.minecraft.network.Packet;

import java.lang.reflect.Array;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Prints packets with all their fields (read by reflection) to the chat or as a notification.
 */
public class PacketDebugger extends Module {
    private static final int MAX_VALUE_LENGTH = 160;
    private static final int MAX_ARRAY_ITEMS = 16;
    private static final Map<Class<?>, Field[]> FIELD_CACHE = new ConcurrentHashMap<>();

    public final ModeProperty notifyMode = new ModeProperty("notify", 0, new String[]{"CHAT", "NOTIFICATION"});
    public final ModeProperty packetType = new ModeProperty("packet-type", 0, new String[]{"BOTH", "SERVER", "CLIENT", "CUSTOM"});
    // Simple class names, separated by commas or spaces, e.g. "S08PacketPlayerPosLook, C0FPacketConfirmTransaction"
    public final TextProperty customPackets = new TextProperty("custom-packets", "S08PacketPlayerPosLook", () -> this.packetType.getValue() == 3);
    public final IntProperty delay = new IntProperty("delay", 100, 0, 1000);

    private final TimerUtil timer = new TimerUtil();
    private volatile Set<String> selectedPackets = parseNames("S08PacketPlayerPosLook");

    public PacketDebugger() {
        super("PacketDebugger", false);
    }

    @Override
    public void verifyValue(String name) {
        if ("custom-packets".equals(name)) {
            this.selectedPackets = parseNames(this.customPackets.getValue());
        }
    }

    private static Set<String> parseNames(String text) {
        if (text == null || text.trim().isEmpty()) {
            return Collections.emptySet();
        }
        Set<String> names = new HashSet<>();
        for (String name : text.split("[,;\\s]+")) {
            if (!name.isEmpty()) {
                names.add(name.toLowerCase());
            }
        }
        return names;
    }

    @EventTarget
    public void onPacket(PacketEvent event) {
        if (!this.isEnabled()) return;
        if (mc.thePlayer == null || mc.theWorld == null) return;
        Packet<?> packet = event.getPacket();
        if (packet == null) return;
        if (!this.timer.hasTimeElapsed(this.delay.getValue())) return;

        String className = packet.getClass().getName();
        boolean matches;
        switch (this.packetType.getValue()) {
            case 1:
                matches = className.startsWith("net.minecraft.network.play.server");
                break;
            case 2:
                matches = className.startsWith("net.minecraft.network.play.client");
                break;
            case 3:
                matches = this.selectedPackets.contains(packet.getClass().getSimpleName().toLowerCase());
                break;
            case 0:
            default:
                matches = true;
                break;
        }
        if (matches) {
            // Only a printed packet restarts the delay; otherwise a rare packet would be lost
            // whenever any other packet happened to come first.
            this.timer.reset();
            this.logPacket(event);
        }
    }

    private void logPacket(PacketEvent event) {
        Packet<?> packet = event.getPacket();
        final List<String> lines = new ArrayList<>();
        lines.add("§aPacket: §b" + packet.getClass().getSimpleName() + (event.isCancelled() ? " §7(§cCancelled§7)" : ""));
        lines.add("§aEventType: §b" + event.getType());
        for (Class<?> type = packet.getClass(); type != null && type != Object.class; type = type.getSuperclass()) {
            for (Field field : fieldsOf(type)) {
                String value;
                try {
                    value = describe(field.get(packet));
                } catch (Throwable t) {
                    value = "<" + t.getClass().getSimpleName() + ">";
                }
                lines.add("§a" + field.getName() + ": §b" + value);
            }
        }

        final boolean chat = this.notifyMode.getValue() == 0;
        // Packets are received on the network thread; chat and HUD belong to the client thread.
        mc.addScheduledTask(() -> {
            if (chat) {
                for (String line : lines) {
                    ChatUtil.sendRaw(line);
                }
            } else {
                StringBuilder message = new StringBuilder();
                for (int i = 1; i < lines.size(); i++) {
                    if (i > 1) message.append(", ");
                    message.append(lines.get(i).replaceAll("§.", ""));
                }
                HUD.notify(lines.get(0).replaceAll("§.", ""), message.toString(), true);
            }
        });
    }

    /** Instance fields declared by the class itself, made accessible once. */
    private static Field[] fieldsOf(Class<?> type) {
        Field[] cached = FIELD_CACHE.get(type);
        if (cached == null) {
            List<Field> fields = new ArrayList<>();
            try {
                for (Field field : type.getDeclaredFields()) {
                    if (Modifier.isStatic(field.getModifiers()) || field.isSynthetic()) continue;
                    try {
                        field.setAccessible(true);
                        fields.add(field);
                    } catch (Throwable ignored) {
                    }
                }
            } catch (Throwable ignored) {
            }
            cached = fields.toArray(new Field[0]);
            FIELD_CACHE.put(type, cached);
        }
        return cached;
    }

    private static String describe(Object value) {
        String text;
        if (value == null) {
            text = "null";
        } else if (value.getClass().isArray()) {
            int length = Array.getLength(value);
            StringBuilder builder = new StringBuilder(value.getClass().getComponentType().getSimpleName())
                    .append('[').append(length).append("] {");
            for (int i = 0; i < length && i < MAX_ARRAY_ITEMS; i++) {
                if (i > 0) builder.append(", ");
                builder.append(Array.get(value, i));
            }
            if (length > MAX_ARRAY_ITEMS) builder.append(", ...");
            text = builder.append('}').toString();
        } else {
            text = String.valueOf(value);
        }
        text = text.replace('\n', ' ');
        return text.length() > MAX_VALUE_LENGTH ? text.substring(0, MAX_VALUE_LENGTH) + "..." : text;
    }
}
