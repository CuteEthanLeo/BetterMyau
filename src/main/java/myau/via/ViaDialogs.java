package myau.via;

import com.viaversion.nbt.tag.CompoundTag;
import com.viaversion.nbt.tag.ListTag;
import com.viaversion.nbt.tag.NumberTag;
import com.viaversion.nbt.tag.StringTag;
import com.viaversion.nbt.tag.Tag;
import com.viaversion.viabackwards.protocol.v1_21_6to1_21_5.Protocol1_21_6To1_21_5;
import com.viaversion.viabackwards.protocol.v1_21_6to1_21_5.data.Button;
import com.viaversion.viabackwards.protocol.v1_21_6to1_21_5.data.Dialog;
import com.viaversion.viabackwards.protocol.v1_21_6to1_21_5.data.input.BooleanInput;
import com.viaversion.viabackwards.protocol.v1_21_6to1_21_5.data.input.NumberRangeInput;
import com.viaversion.viabackwards.protocol.v1_21_6to1_21_5.data.input.SingleOptionInput;
import com.viaversion.viabackwards.protocol.v1_21_6to1_21_5.data.input.TextInput;
import com.viaversion.viabackwards.protocol.v1_21_6to1_21_5.data.widget.ItemWidget;
import com.viaversion.viabackwards.protocol.v1_21_6to1_21_5.data.widget.TextWidget;
import com.viaversion.viabackwards.protocol.v1_21_6to1_21_5.data.widget.Widget;
import com.viaversion.viabackwards.protocol.v1_21_6to1_21_5.storage.RegistryAndTags;
import com.viaversion.viabackwards.protocol.v1_21_6to1_21_5.storage.ServerLinks;
import com.viaversion.viaversion.api.connection.UserConnection;
import com.viaversion.viaversion.api.protocol.packet.PacketWrapper;
import com.viaversion.viaversion.api.protocol.packet.State;
import com.viaversion.viaversion.api.type.Types;
import com.viaversion.viaversion.protocols.v1_21_5to1_21_6.packet.ServerboundConfigurationPackets1_21_6;
import com.viaversion.viaversion.protocols.v1_21_5to1_21_6.packet.ServerboundPackets1_21_6;
import com.viaversion.viaversion.util.Key;
import io.netty.channel.Channel;

import java.util.HashMap;
import java.util.Map;

/**
 * Server dialogs (1.21.6+) while the server configures the player, i.e. before Join Game.
 * <p>
 * A server can show a dialog in the configuration phase (rules to accept, a login, a check) and
 * hold the player there until a button is pressed. ViaBackwards shows dialogs to older clients as
 * a chest window, which needs a player in a world: before Join Game there is none, the dialog is
 * lost and the server waits forever. So in configuration the dialog is shown by the client itself
 * ({@link ViaDialogView}), and a pressed button is answered with the configuration phase's own
 * click packet. Dialogs during play keep going through ViaBackwards' chest.
 */
public final class ViaDialogs {
    private ViaDialogs() { }

    /** True while this connection's dialogs are shown by the client itself. */
    public static boolean handles(UserConnection connection) {
        return connection.getProtocolInfo().getServerState() == State.CONFIGURATION;
    }

    /** The server shows a dialog. Called on the connection's thread. */
    public static void open(UserConnection connection, Dialog dialog) {
        show(connection, dialog, null);
    }

    /** The server takes its dialog away again. */
    public static void clear(UserConnection connection) {
        dismiss(ViaSession.of(connection.getChannel()));
    }

    /** Configuration is over (or the connection is): whatever dialog is still up goes away. */
    static void dismiss(ViaSession session) {
        if (session == null) return;
        ViaDialogView view = session.dialog;
        if (view == null) return;
        session.dialog = null;
        ViaDialogView.presenter.close(view);
    }

    /** The connecting screen's step while a dialog's answer is on its way, or null. */
    static String stage(ViaSession session) {
        ViaDialogView view = session.dialog;
        return view != null && view.waiting ? "等待服务器回应…" : null;
    }

    private static void show(UserConnection connection, Dialog dialog, ViaDialogView parent) {
        ViaSession session = ViaSession.of(connection.getChannel());
        ViaDialogView view = build(connection, dialog, parent);
        if (session != null) session.dialog = view;
        ViaClient.LOGGER.info("Server dialog in configuration: {}", describe(view));
        ViaDialogView.presenter.show(view);
    }

    /** The dialog in one log line: what it says and what can be pressed. */
    private static String describe(ViaDialogView view) {
        StringBuilder out = new StringBuilder("\"").append(view.title).append("\"");
        for (ViaDialogView.Item item : view.items) {
            out.append(" | ").append(item.getClass().getSimpleName()).append(": ").append(item.label.replace('\n', ' '));
        }
        for (ViaDialogView.Button button : view.footer) out.append(" | [").append(button.label).append("]");
        return out.length() > 800 ? out.substring(0, 800) + "…" : out.toString();
    }

    // ------------------------------------------------------------------ building

    private static ViaDialogView build(final UserConnection connection, final Dialog dialog, ViaDialogView parent) {
        final ViaDialogView view = new ViaDialogView(text(dialog.title()), dialog.canCloseWithEscape(), parent);
        for (Widget widget : dialog.widgets()) {
            if (widget instanceof TextWidget) {
                view.items.add(new ViaDialogView.Text(text(((TextWidget) widget).label())));
            } else if (widget instanceof ItemWidget) {
                ItemWidget item = (ItemWidget) widget;
                String id = item.item() == null ? null : item.item().getString("id");
                String description = item.description() == null ? "" : text(item.description().label());
                String shown = id == null ? description : "[" + Key.stripMinecraftNamespace(id) + "] " + description;
                if (!shown.trim().isEmpty()) view.items.add(new ViaDialogView.Text(shown));
            } else if (widget instanceof TextInput) {
                final TextInput input = (TextInput) widget;
                String value = input.value() != null ? input.value() : input.initial();
                view.items.add(new ViaDialogView.Field(text(input.label()), value, input.maxLength(), input::setClampedValue));
            } else if (widget instanceof BooleanInput) {
                final BooleanInput input = (BooleanInput) widget;
                view.items.add(new ViaDialogView.Toggle(text(input.label()), input.value(), input::setValue));
            } else if (widget instanceof SingleOptionInput) {
                final SingleOptionInput input = (SingleOptionInput) widget;
                SingleOptionInput.Entry[] entries = input.options();
                String[] options = new String[entries.length];
                for (int i = 0; i < entries.length; i++) {
                    String shown = text(entries[i].display());
                    options[i] = shown.isEmpty() ? entries[i].id() : shown;
                }
                view.items.add(new ViaDialogView.Choice(text(input.label()), options, input.value(), input::setClampedValue));
            } else if (widget instanceof NumberRangeInput) {
                final NumberRangeInput input = (NumberRangeInput) widget;
                view.items.add(new ViaDialogView.Range(text(input.label()), input.start(), input.end(), step(input), input.value(),
                        input.valueAsString(), value -> {
                            input.setClampedValue(value);
                            return input.valueAsString();
                        }));
            } else if (widget instanceof Button) {
                view.items.add(button(connection, dialog, view, (Button) widget));
            } else if (widget instanceof Dialog) {
                // A list of dialogs: each entry opens that dialog and comes back here.
                final Dialog sub = (Dialog) widget;
                String label = text(sub.externalTitle() != null ? sub.externalTitle() : sub.title());
                view.items.add(new ViaDialogView.Button(label, null, () -> show(connection, sub, view)));
            }
        }
        if (dialog.yesButton() != null) view.footer.add(button(connection, dialog, view, dialog.yesButton()));
        if (dialog.noButton() != null) view.footer.add(button(connection, dialog, view, dialog.noButton()));
        if (dialog.actionButton() != null) view.footer.add(button(connection, dialog, view, dialog.actionButton()));

        // Esc is the dialog's own way out: "no" for a question, else its (exit) button; then it closes.
        final Button cancel = dialog.noButton() != null ? dialog.noButton() : dialog.actionButton();
        view.onEscape = () -> click(connection, view, cancel, Dialog.AfterAction.CLOSE);
        view.onDisconnect = () -> disconnect(connection.getChannel());
        view.connected = () -> connection.getChannel().isOpen();
        return view;
    }

    private static float step(NumberRangeInput input) {
        try {
            return input.step();
        } catch (RuntimeException e) {
            return 0;
        }
    }

    private static ViaDialogView.Button button(final UserConnection connection, final Dialog dialog, final ViaDialogView view, final Button button) {
        String label = text(button.label());
        if (label.isEmpty()) label = "确定";
        String tooltip = button.tooltip() == null ? null : text(button.tooltip());
        return new ViaDialogView.Button(label, tooltip, () -> click(connection, view, button, dialog.afterAction()));
    }

    // ------------------------------------------------------------------ answering

    /**
     * A button was pressed (client thread). What happens to the dialog comes first, as in the
     * modern client: it closes, stays, or waits for the server; then the button's own action runs.
     */
    private static void click(UserConnection connection, ViaDialogView view, Button button, Dialog.AfterAction after) {
        CompoundTag event = null;
        try {
            // The inputs' current values are filled in by ViaBackwards (templates, custom payloads).
            event = button == null ? null : button.clickEvent();
        } catch (RuntimeException e) {
            ViaClient.LOGGER.warn("Could not work out what the dialog button \"{}\" does", view.title, e);
        }
        if (after == Dialog.AfterAction.CLOSE) close(connection, view);
        else if (after == Dialog.AfterAction.WAIT_FOR_RESPONSE) view.waiting = true;
        if (event == null) return;

        String action = event.getString("action");
        action = action == null ? "" : Key.stripMinecraftNamespace(action);
        if ("custom".equals(action)) {
            sendCustomClick(connection, event.getString("id"), event.getCompoundTag("payload"));
        } else if ("show_dialog".equals(action)) {
            CompoundTag tag = event.getCompoundTag("dialog");
            RegistryAndTags registry = connection.get(RegistryAndTags.class);
            if (tag == null && registry != null && event.getStringTag("dialog") != null) {
                tag = registry.fromRegistry(Key.stripMinecraftNamespace(event.getStringTag("dialog").getValue()));
            }
            if (tag == null) {
                ViaClient.LOGGER.warn("The dialog button points to a dialog that is not known: {}", event);
                return;
            }
            try {
                // Opened from a dialog that stays: closing the new one returns to it.
                show(connection, new Dialog(registry, connection.get(ServerLinks.class), tag), after == Dialog.AfterAction.NONE ? view : view.parent);
            } catch (RuntimeException e) {
                ViaClient.LOGGER.warn("Could not read the dialog a button opens", e);
            }
        } else if ("open_url".equals(action)) {
            String url = event.getString("url");
            if (url != null) ViaDialogView.presenter.openUrl(url);
        } else if ("copy_to_clipboard".equals(action)) {
            String value = event.getString("value");
            if (value != null) ViaDialogView.presenter.copy(value);
        } else if ("run_command".equals(action)) {
            // The modern client cannot run commands before Join Game either.
            ViaClient.LOGGER.warn("A dialog button wants to run a command during configuration, which no client can: {}", event.getString("command"));
        } else {
            ViaClient.LOGGER.warn("A dialog button has an action this client does not know: {}", action);
        }
    }

    private static void close(UserConnection connection, ViaDialogView view) {
        ViaSession session = ViaSession.of(connection.getChannel());
        if (view.parent != null) {
            if (session != null) session.dialog = view.parent;
            ViaDialogView.presenter.show(view.parent);
            return;
        }
        if (session != null && session.dialog == view) session.dialog = null;
        ViaDialogView.presenter.close(view);
    }

    /**
     * The answer to a dialog button, written as the 1.21.6 packet of the state the server is in
     * and translated onward to its version. In configuration that is the configuration packet:
     * ViaBackwards' own answer is the play one, which a configuring server does not read.
     */
    private static void sendCustomClick(final UserConnection connection, final String id, final CompoundTag payload) {
        if (id == null) return;
        final Channel channel = connection.getChannel();
        channel.eventLoop().execute(() -> {
            if (!channel.isOpen()) return;
            try {
                boolean configuring = connection.getProtocolInfo().getServerState() == State.CONFIGURATION;
                PacketWrapper click = PacketWrapper.create(
                        configuring ? ServerboundConfigurationPackets1_21_6.CUSTOM_CLICK_ACTION : ServerboundPackets1_21_6.CUSTOM_CLICK_ACTION, connection);
                click.write(Types.STRING, id);
                click.write(Types.CUSTOM_CLICK_ACTION_TAG, payload);
                click.sendToServer(Protocol1_21_6To1_21_5.class);
                ViaClient.LOGGER.info("Dialog answered: {} ({})", id, configuring ? "configuration" : "play");
            } catch (Throwable t) {
                ViaClient.LOGGER.error("Could not send the dialog answer {}", id, t);
            }
        });
    }

    private static void disconnect(Channel channel) {
        Object handler = channel.pipeline().get("packet_handler");
        if (handler instanceof net.minecraft.network.NetworkManager) {
            ((net.minecraft.network.NetworkManager) handler).closeChannel(new net.minecraft.util.ChatComponentText("已断开连接"));
        } else {
            channel.close();
        }
    }

    // ------------------------------------------------------------------ text

    /** The few translation keys dialogs use by default; a 1.8 client has no such keys. */
    private static final Map<String, String> KEYS = new HashMap<>();

    static {
        KEYS.put("gui.ok", "确定");
        KEYS.put("gui.yes", "是");
        KEYS.put("gui.no", "否");
        KEYS.put("gui.cancel", "取消");
        KEYS.put("gui.back", "返回");
        KEYS.put("gui.done", "完成");
        KEYS.put("gui.proceed", "继续");
        KEYS.put("gui.continue", "继续");
        KEYS.put("gui.acknowledge", "我知道了");
        KEYS.put("gui.toTitle", "返回标题画面");
        KEYS.put("menu.disconnect", "断开连接");
        KEYS.put("options.on", "开");
        KEYS.put("options.off", "关");
    }

    /** A text component as plain text (styles are dropped). */
    static String text(Tag tag) {
        if (tag == null) return "";
        StringBuilder out = new StringBuilder();
        try {
            flatten(tag, out, 0);
        } catch (RuntimeException e) {
            return String.valueOf(tag);
        }
        // Legacy colour codes inside the strings are styles too.
        return out.toString().replaceAll("§.", "");
    }

    private static void flatten(Tag tag, StringBuilder out, int depth) {
        if (tag == null || depth > 32) return;
        if (tag instanceof StringTag) {
            out.append(((StringTag) tag).getValue());
        } else if (tag instanceof NumberTag) {
            out.append(((NumberTag) tag).getValue());
        } else if (tag instanceof ListTag) {
            for (Object part : (ListTag<?>) tag) flatten((Tag) part, out, depth + 1);
        } else if (tag instanceof CompoundTag) {
            CompoundTag component = (CompoundTag) tag;
            String text = component.getString("text");
            String key = component.getString("translate");
            if (text != null) {
                out.append(text);
            } else if (key != null) {
                String pattern = KEYS.get(key);
                if (pattern == null) pattern = component.getString("fallback");
                if (pattern == null) pattern = key;
                Tag with = component.get("with");
                if (with instanceof ListTag && pattern.indexOf('%') >= 0) {
                    // Arguments in order; enough for "%s" and "%1$s" style patterns.
                    StringBuilder filled = new StringBuilder();
                    int next = 0;
                    ListTag<?> arguments = (ListTag<?>) with;
                    for (int i = 0; i < pattern.length(); i++) {
                        char c = pattern.charAt(i);
                        if (c != '%' || i + 1 >= pattern.length()) {
                            filled.append(c);
                            continue;
                        }
                        int end = i + 1, index = -1;
                        while (end < pattern.length() && Character.isDigit(pattern.charAt(end))) end++;
                        if (end < pattern.length() && pattern.charAt(end) == '$' && end > i + 1) {
                            index = Integer.parseInt(pattern.substring(i + 1, end)) - 1;
                            end++;
                        } else {
                            end = i + 1;
                        }
                        if (end < pattern.length() && pattern.charAt(end) == 's') {
                            int argument = index >= 0 ? index : next++;
                            if (argument < arguments.size()) flatten((Tag) arguments.get(argument), filled, depth + 1);
                            i = end;
                        } else if (end < pattern.length() && pattern.charAt(end) == '%') {
                            filled.append('%');
                            i = end;
                        } else {
                            filled.append(c);
                        }
                    }
                    out.append(filled);
                } else {
                    out.append(pattern);
                }
            } else if (component.getString("keybind") != null) {
                out.append(component.getString("keybind"));
            } else if (component.getString("selector") != null) {
                out.append(component.getString("selector"));
            }
            flatten(component.get("extra"), out, depth + 1);
        }
    }
}
