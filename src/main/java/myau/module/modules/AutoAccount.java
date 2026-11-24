package myau.module.modules;

import myau.Myau;
import myau.event.EventTarget;
import myau.event.types.EventType;
import myau.events.LoadWorldEvent;
import myau.events.PacketEvent;
import myau.events.TickEvent;
import myau.module.Module;
import myau.property.properties.BooleanProperty;
import myau.property.properties.IntProperty;
import myau.property.properties.TextProperty;
import myau.util.ChatUtil;
import net.minecraft.network.Packet;
import net.minecraft.network.play.server.S02PacketChat;
import net.minecraft.network.play.server.S45PacketTitle;
import net.minecraft.util.IChatComponent;

import java.util.concurrent.ThreadLocalRandom;

/**
 * Answers the /register and /login prompts of cracked servers (AuthMe and the like) with a fixed password.
 */
public class AutoAccount extends Module {
    private static final String DEFAULT_PASSWORD = "axolotlaxolotl";
    private static final String PREFIX = "&7[&a&lAutoAccount&7] ";

    private enum Status {
        WAITING, SENT_COMMAND, STOPPED
    }

    public final BooleanProperty register = new BooleanProperty("auto-register", true);
    public final BooleanProperty login = new BooleanProperty("auto-login", true);
    // Gamster requires 8 characters or more
    public final TextProperty password = new TextProperty("password", DEFAULT_PASSWORD, () -> this.register.getValue() || this.login.getValue());
    // Milliseconds, counted in client ticks. Gamster ignores commands that are sent too early.
    public final IntProperty minSendDelay = new IntProperty("min-send-delay", 150, 0, 500, () -> this.register.getValue() || this.login.getValue());
    public final IntProperty maxSendDelay = new IntProperty("max-send-delay", 300, 0, 500, () -> this.register.getValue() || this.login.getValue());

    private String lastValidPassword = DEFAULT_PASSWORD;

    // Prompts arrive on the network thread; the command is sent from the client tick.
    private volatile Status status = Status.WAITING;
    private volatile String pendingCommand;
    private volatile int delayTicks;
    private volatile boolean worldChanged;

    public AutoAccount() {
        super("AutoAccount", false);
    }

    // Reset when turned off, not when turned on: a module enabled from the startup config gets its
    // onEnabled() on the first tick in the world, when the prompt may already have been answered.
    @Override
    public void onDisabled() {
        this.status = Status.WAITING;
        this.pendingCommand = null;
        this.worldChanged = false;
    }

    @Override
    public void verifyValue(String name) {
        if ("password".equals(name)) {
            String value = this.password.getValue();
            if (value == null) value = "";
            if (value.equals(this.lastValidPassword)) return;
            if (containsWhitespace(value)) {
                ChatUtil.sendFormatted(PREFIX + "&cPassword cannot contain a space!");
                this.password.setValue(this.lastValidPassword);
            } else if (value.equalsIgnoreCase("reset")) {
                ChatUtil.sendFormatted(PREFIX + "&3Password reset to its default value.");
                this.lastValidPassword = DEFAULT_PASSWORD;
                this.password.setValue(DEFAULT_PASSWORD);
            } else if (value.length() < 4) {
                ChatUtil.sendFormatted(PREFIX + "&cPassword must be at least 4 characters long!");
                this.password.setValue(this.lastValidPassword);
            } else {
                this.lastValidPassword = value;
            }
        } else if ("min-send-delay".equals(name)) {
            if (this.minSendDelay.getValue() > this.maxSendDelay.getValue()) {
                this.maxSendDelay.setValue(this.minSendDelay.getValue());
            }
        } else if ("max-send-delay".equals(name)) {
            if (this.maxSendDelay.getValue() < this.minSendDelay.getValue()) {
                this.minSendDelay.setValue(this.maxSendDelay.getValue());
            }
        }
    }

    private static boolean containsWhitespace(String text) {
        for (int i = 0; i < text.length(); i++) {
            if (Character.isWhitespace(text.charAt(i))) {
                return true;
            }
        }
        return false;
    }

    private boolean isActive() {
        return this.register.getValue() || this.login.getValue();
    }

    private int randomDelayTicks() {
        int min = Math.min(this.minSendDelay.getValue(), this.maxSendDelay.getValue());
        int max = Math.max(this.minSendDelay.getValue(), this.maxSendDelay.getValue());
        int millis = min + ThreadLocalRandom.current().nextInt(max - min + 1);
        return (millis + 49) / 50;
    }

    /** @return true when the message was a register / login prompt and the answer is scheduled */
    private boolean respond(String message) {
        String command;
        String note;
        String pass = this.password.getValue();
        if (this.register.getValue() && message.contains("/reg")) {
            command = "/register " + pass + " " + pass;
            note = "Trying to register.";
        } else if (this.login.getValue() && message.contains("/log")) {
            command = "/login " + pass;
            note = "Trying to log in.";
        } else {
            return false;
        }
        // Status first: the client tick only sends a pending command while the status says so.
        this.delayTicks = this.randomDelayTicks();
        this.status = Status.SENT_COMMAND;
        this.pendingCommand = command;
        this.notifyLater(note, true);
        return true;
    }

    private void notifyLater(final String message, final boolean positive) {
        mc.addScheduledTask(() -> HUD.notify("AutoAccount", message, positive));
    }

    @EventTarget
    public void onPacket(PacketEvent event) {
        if (!this.isEnabled() || event.getType() != EventType.RECEIVE) return;
        Packet<?> packet = event.getPacket();
        IChatComponent component;
        if (packet instanceof S02PacketChat) {
            component = ((S02PacketChat) packet).getChatComponent();
        } else if (packet instanceof S45PacketTitle) {
            component = ((S45PacketTitle) packet).getMessage();
        } else {
            return;
        }
        // Don't respond to register / login prompts again once an attempt finished
        if (component == null || !this.isActive() || this.status == Status.STOPPED) return;
        String message = component.getUnformattedText().toLowerCase();

        if (this.status == Status.WAITING) {
            if (!this.respond(message)) return;
            event.setCancelled(true);
        } else {
            // Check the server's response
            if (message.contains("success") || message.contains("logged") || message.contains("registered")) {
                this.success();
                event.setCancelled(true);
            } else if (message.contains("incorrect") || message.contains("wrong") || message.contains("spatne")) {
                this.fail();
            } else if (message.contains("unknown") || message.contains("command") || message.contains("allow") || message.contains("already")) {
                // Tried /login or /register in a lobby that has no such command: stop trying
                this.status = Status.STOPPED;
                this.pendingCommand = null;
                event.setCancelled(true);
            }
        }
    }

    @EventTarget
    public void onTick(TickEvent event) {
        if (!this.isEnabled() || event.getType() != EventType.PRE) return;
        if (mc.thePlayer == null) return;

        if (this.worldChanged) {
            // A tick in the new world: the server moved the player on, which only happens after a successful login.
            this.worldChanged = false;
            this.success();
        }

        String command = this.pendingCommand;
        if (command == null) return;
        if (this.delayTicks > 0) {
            this.delayTicks--;
            return;
        }
        this.pendingCommand = null;
        if (this.status == Status.SENT_COMMAND) {
            mc.thePlayer.sendChatMessage(command);
        }
    }

    @EventTarget
    public void onLoadWorld(LoadWorldEvent event) {
        if (!this.isEnabled() || !this.isActive()) return;

        // No world yet: a new connection, wait for its prompt
        if (mc.theWorld == null) {
            this.status = Status.WAITING;
            this.pendingCommand = null;
            this.worldChanged = false;
            return;
        }

        // This also fires when the world is unloaded on a disconnect, so the verdict is left to the next
        // tick: there is one after a redirect to a lobby, and none after a kick.
        if (this.status == Status.SENT_COMMAND && this.pendingCommand == null) {
            this.worldChanged = true;
        }
    }

    private void success() {
        if (this.status == Status.SENT_COMMAND) {
            this.status = Status.STOPPED;
            this.notifyLater("Logged in as " + mc.getSession().getUsername(), true);
        }
    }

    private void fail() {
        if (this.status == Status.SENT_COMMAND) {
            this.status = Status.STOPPED;
            this.pendingCommand = null;
            this.notifyLater("Failed to log in as " + mc.getSession().getUsername(), false);
        }
    }
}
