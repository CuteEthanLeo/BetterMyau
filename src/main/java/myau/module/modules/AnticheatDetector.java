package myau.module.modules;

import myau.Myau;
import myau.event.EventTarget;
import myau.event.types.EventType;
import myau.events.PacketEvent;
import myau.events.TickEvent;
import myau.module.Module;
import myau.property.properties.BooleanProperty;
import myau.util.ChatUtil;
import net.minecraft.client.multiplayer.ServerData;
import net.minecraft.network.Packet;
import net.minecraft.network.play.server.S01PacketJoinGame;
import net.minecraft.network.play.server.S32PacketConfirmTransaction;

import java.util.ArrayList;
import java.util.List;

/**
 * Guesses the server's anticheat from the first five transaction ids it sends after a join:
 * every anticheat counts them in its own way.
 */
public class AnticheatDetector extends Module {
    public final BooleanProperty debug = new BooleanProperty("debug", true);

    // Guarded by "this": transactions arrive on the network thread, the timeout runs on the client thread.
    private final List<Integer> actionNumbers = new ArrayList<>();
    private boolean check;
    private int ticksPassed;

    public AnticheatDetector() {
        super("AnticheatDetector", false);
    }

    // Reset when turned off, not when turned on: a module enabled from the startup config gets its
    // onEnabled() on the first tick in the world, after the join packet already started a check.
    @Override
    public void onDisabled() {
        this.reset();
    }

    @EventTarget
    public void onPacket(PacketEvent event) {
        if (!this.isEnabled() || event.getType() != EventType.RECEIVE) return;
        Packet<?> packet = event.getPacket();
        if (packet instanceof S32PacketConfirmTransaction) {
            this.handleTransaction(((S32PacketConfirmTransaction) packet).getActionNumber());
        } else if (packet instanceof S01PacketJoinGame) {
            synchronized (this) {
                this.reset();
                this.check = true;
            }
        }
    }

    @EventTarget
    public void onTick(TickEvent event) {
        if (!this.isEnabled() || event.getType() != EventType.PRE) return;
        boolean timedOut = false;
        synchronized (this) {
            if (this.check && this.ticksPassed++ > 40) {
                this.reset();
                timedOut = true;
            }
        }
        if (timedOut) {
            this.announce("None");
        }
    }

    private void handleTransaction(int action) {
        String result;
        List<Integer> numbers;
        synchronized (this) {
            if (!this.check) return;
            this.actionNumbers.add(action);
            this.ticksPassed = 0;
            if (this.actionNumbers.size() < 5) {
                result = null;
                numbers = null;
            } else {
                numbers = new ArrayList<>(this.actionNumbers);
                result = analyze(numbers, serverIp());
                this.reset();
            }
        }
        final boolean debugging = this.debug.getValue();
        final String detected = result;
        final List<Integer> analyzed = numbers;
        if (!debugging && detected == null) return;
        mc.addScheduledTask(() -> {
            if (debugging) {
                ChatUtil.sendFormatted(Myau.clientName + "&7ID: &f" + action);
            }
            if (detected == null) return;
            if (!detected.isEmpty()) {
                this.announce(detected);
            }
            if (debugging && "Unknown".equals(detected)) {
                ChatUtil.sendFormatted(Myau.clientName + "&7Action Numbers: &f" + join(analyzed));
                ChatUtil.sendFormatted(Myau.clientName + "&7Differences: &f" + join(differences(analyzed)));
            }
        });
    }

    private static String serverIp() {
        ServerData server = mc.getCurrentServerData();
        if (server == null || server.serverIP == null) return "";
        String ip = server.serverIP.toLowerCase();
        int port = ip.lastIndexOf(':');
        return port > 0 ? ip.substring(0, port) : ip;
    }

    private static List<Integer> differences(List<Integer> numbers) {
        List<Integer> diffs = new ArrayList<>();
        for (int i = 1; i < numbers.size(); i++) {
            diffs.add(numbers.get(i) - numbers.get(i - 1));
        }
        return diffs;
    }

    private static boolean inRange(int value, int min, int max) {
        return value >= min && value <= max;
    }

    /** @return the anticheat's name, "Unknown", or "" when the ids count evenly in a step no anticheat is known for */
    private static String analyze(List<Integer> numbers, String ip) {
        List<Integer> diffs = differences(numbers);
        int first = numbers.get(0);

        if (ip.equals("hypixel.net") || ip.endsWith(".hypixel.net")) {
            return "Watchdog";
        }

        boolean constantStep = true;
        for (int diff : diffs) {
            if (diff != diffs.get(0)) {
                constantStep = false;
                break;
            }
        }
        if (constantStep) {
            int step = diffs.get(0);
            if (step == 1) {
                if (inRange(first, -23772, -23762)) return "Vulcan";
                if (inRange(first, 95, 105) || inRange(first, -20005, -19995)) return "Matrix";
                if (inRange(first, -32773, -32762)) return "Grizzly";
                return "Verus";
            }
            if (step == -1) {
                if (inRange(first, -8287, -8280)) return "Errata";
                if (first < -3000) return "Intave";
                if (inRange(first, -5, 0)) return "Grim";
                if (inRange(first, -3000, -2995)) return "Karhu";
                return "Polar";
            }
            return "";
        }

        // Verus sends the first id twice, then counts up.
        if (numbers.get(0).equals(numbers.get(1)) && countsUpFrom(numbers, 2)) {
            return "Verus";
        }

        // Polar: one large jump, then counts down.
        if (diffs.get(0) >= 100 && diffs.get(1) == -1) {
            boolean down = true;
            for (int i = 2; i < diffs.size(); i++) {
                if (diffs.get(i) != -1) {
                    down = false;
                    break;
                }
            }
            if (down) return "Polar";
        }

        if (first < -3000 && numbers.contains(0)) {
            return "Intave";
        }

        if (numbers.get(0) == -30767 && numbers.get(1) == -30766 && numbers.get(2) == -25767 && countsUpFrom(numbers, 3)) {
            return "Old Vulcan";
        }

        return "Unknown";
    }

    private static boolean countsUpFrom(List<Integer> numbers, int start) {
        for (int i = start + 1; i < numbers.size(); i++) {
            if (numbers.get(i) - numbers.get(i - 1) != 1) {
                return false;
            }
        }
        return true;
    }

    private static String join(List<Integer> numbers) {
        StringBuilder builder = new StringBuilder();
        for (int i = 0; i < numbers.size(); i++) {
            if (i > 0) builder.append(", ");
            builder.append(numbers.get(i));
        }
        return builder.toString();
    }

    private void announce(String anticheat) {
        HUD.notify("AnticheatDetector", "Anticheat detected: " + anticheat, true);
    }

    private synchronized void reset() {
        this.actionNumbers.clear();
        this.ticksPassed = 0;
        this.check = false;
    }
}
