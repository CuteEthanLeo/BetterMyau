package myau.via;

import com.viaversion.viaversion.api.protocol.version.ProtocolVersion;
import net.minecraft.client.multiplayer.ServerData;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * What the screens show about versions. Every method is safe to call while ViaVersion is still
 * loading, failed, or switched off: the answers then fall back to plain 1.8 behaviour.
 */
public final class ViaUi {
    private ViaUi() { }

    /** 1.8's protocol number. */
    private static final int NATIVE_PROTOCOL = 47;
    private static volatile int revision;

    /** Bumped whenever a version setting changes, so screens know to ping again. */
    public static int revision() {
        return revision;
    }

    public static void setGlobal(String choice) {
        ViaSettings.setGlobal(choice);
        revision++;
    }

    /** Null makes the server follow the global choice. */
    public static void setOverride(String address, String choice) {
        ViaSettings.setOverride(address, choice);
        ViaDetect.forget(ViaSettings.key(address));
        revision++;
    }

    /** "自动", "1.8 原生", "跟随全局" or the version name. */
    public static String label(String choice) {
        if (choice == null) return "跟随全局";
        if (ViaSettings.AUTO.equals(choice)) return "自动";
        if (ViaSettings.NATIVE.equals(choice)) return "1.8 原生";
        return choice;
    }

    public static String globalLabel() {
        return label(ViaSettings.global());
    }

    /** What a server uses: its own choice, or "跟随全局 · <global>". */
    public static String serverLabel(String address) {
        String own = ViaSettings.override(address);
        return own != null ? label(own) : "跟随全局 · " + globalLabel();
    }

    /** "加载中…" / the reason it is off, or null when ready. */
    public static String status() {
        String off = ViaHooks.disabledReason();
        if (off != null) return off;
        try {
            switch (ViaClient.state()) {
                case READY:
                    return null;
                case LOADING:
                    return "Via 加载中…";
                case OFF:
                    return "Via 未启动";
                default:
                    return ViaClient.problem() != null ? ViaClient.problem() : "Via 不可用";
            }
        } catch (Throwable t) {
            return "Via 不可用";
        }
    }

    /**
     * True when the server list entry can be joined: 1.8 itself, or a version that the setting
     * this entry was pinged with will translate to.
     */
    public static boolean compatible(ServerData data) {
        if (data == null || data.version == NATIVE_PROTOCOL) return true;
        try {
            String mode = ViaConnect.pingMode(data);
            if (ViaSettings.AUTO.equals(mode)) return ViaClient.ready() && ViaVersions.fromServerProtocol(data.version) != null;
            return false;
        } catch (Throwable t) {
            return false;
        }
    }

    /** "Via 1.21.4" when joining this entry goes through translation, else null. */
    public static String badge(ServerData data) {
        if (data == null || data.pingToServer < 0) return null;
        try {
            String mode = ViaConnect.pingMode(data);
            if (mode == null || ViaSettings.NATIVE.equals(mode)) return null;
            if (ViaSettings.AUTO.equals(mode)) {
                if (data.version == NATIVE_PROTOCOL || !ViaClient.ready()) return null;
                ProtocolVersion version = ViaVersions.fromServerProtocol(data.version);
                return version == null ? null : "Via " + version.getName();
            }
            // A fixed version: ViaVersion reports 47 when the server matches it.
            return data.version == NATIVE_PROTOCOL ? "Via " + mode : null;
        } catch (Throwable t) {
            return null;
        }
    }

    /** One entry of the version picker. */
    public static final class Choice {
        public final String choice;
        public final String label;
        public final String detail;

        Choice(String choice, String label, String detail) {
            this.choice = choice;
            this.label = label;
            this.detail = detail;
        }
    }

    /** The versions to pick from, grouped (正式版 / 远古版本 / 特殊版本), newest first. Empty until loaded. */
    public static Map<String, List<Choice>> versionGroups() {
        Map<String, List<Choice>> groups = new LinkedHashMap<>();
        groups.put("正式版", new ArrayList<Choice>());
        groups.put("远古版本", new ArrayList<Choice>());
        groups.put("特殊版本", new ArrayList<Choice>());
        try {
            for (ProtocolVersion version : ViaVersions.selectable()) {
                boolean own = version.equals(ViaVersions.nativeVersion());
                String detail = own ? "原生 · 不转换" : "协议 " + version.getOriginalVersion();
                groups.get(ViaVersions.group(version)).add(new Choice(own ? ViaSettings.NATIVE : version.getName(), version.getName(), detail));
            }
        } catch (Throwable t) {
            return Collections.emptyMap();
        }
        groups.values().removeIf(List::isEmpty);
        return groups;
    }

    /** What a typed version means as a choice ("1.20.4" finds "1.20.3-1.20.4"), or null when unknown. */
    public static String resolve(String typed) {
        if (typed == null || typed.trim().isEmpty()) return null;
        String wanted = typed.trim();
        for (List<Choice> group : versionGroups().values()) {
            for (Choice choice : group) {
                if (choice.label.equalsIgnoreCase(wanted)) return choice.choice;
            }
        }
        try {
            ProtocolVersion version = ViaVersions.parse(wanted);
            if (version == null) return null;
            return version.equals(ViaVersions.nativeVersion()) ? ViaSettings.NATIVE : version.getName();
        } catch (Throwable t) {
            return null;
        }
    }

    /** How many versions are on offer (0 until loaded). */
    public static int versionCount() {
        try {
            return ViaVersions.selectable().size();
        } catch (Throwable t) {
            return 0;
        }
    }
}
