package myau.via;

import com.viaversion.viaversion.api.Via;
import com.viaversion.viaversion.api.protocol.version.ProtocolVersion;
import com.viaversion.viaversion.api.protocol.version.VersionType;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** The versions on offer, and turning choices and server-reported protocol numbers into them. */
public final class ViaVersions {
    private ViaVersions() { }

    private static volatile List<ProtocolVersion> cache;

    /** The client's own version (1.8.x): joining with it means no translation. */
    public static ProtocolVersion nativeVersion() {
        return ProtocolVersion.v1_8;
    }

    /** Every version a 1.8 client can reach, newest first. Empty until ViaVersion has loaded. */
    public static List<ProtocolVersion> selectable() {
        if (!ViaClient.ready()) return Collections.emptyList();
        List<ProtocolVersion> list = cache;
        if (list != null) return list;
        List<ProtocolVersion> out = new ArrayList<>();
        for (ProtocolVersion version : ProtocolVersion.getProtocols()) {
            if (!version.isKnown()) continue;
            if (version.equals(nativeVersion()) || reachable(version)) out.add(version);
        }
        out.sort(Collections.reverseOrder());
        cache = list = Collections.unmodifiableList(out);
        return list;
    }

    /** True when ViaVersion knows a way from 1.8 to this version. */
    public static boolean reachable(ProtocolVersion version) {
        try {
            return version.equals(nativeVersion())
                    || Via.getManager().getProtocolManager().getProtocolPath(nativeVersion(), version) != null;
        } catch (RuntimeException e) {
            return false;
        }
    }

    /** A stored choice (version name) as a version, or null when it is not one ViaVersion knows. */
    public static ProtocolVersion parse(String name) {
        if (name == null || name.isEmpty()) return null;
        for (ProtocolVersion version : selectable()) {
            if (version.getName().equalsIgnoreCase(name)) return version;
        }
        ProtocolVersion closest = ProtocolVersion.getClosest(name);
        return closest != null && closest.isKnown() && reachable(closest) ? closest : null;
    }

    /**
     * What to join with, given the protocol number a server reported in its status ping: 47 is
     * 1.8 itself; a release ViaVersion knows is used as is; a number newer than any known release
     * means a newer server, so the newest known release is the best guess. Null means 1.8.
     */
    public static ProtocolVersion fromServerProtocol(int protocol) {
        if (protocol == nativeVersion().getVersion()) return null;
        if (ProtocolVersion.isRegistered(VersionType.RELEASE, protocol)) {
            ProtocolVersion version = ProtocolVersion.getProtocol(VersionType.RELEASE, protocol);
            if (version.isKnown() && reachable(version)) return version;
        }
        ProtocolVersion newest = newestRelease();
        if (newest != null && protocol > newest.getVersion() && protocol < 0x40000000) return newest;
        return null;
    }

    public static ProtocolVersion newestRelease() {
        for (ProtocolVersion version : selectable()) {
            if (version.getVersionType() == VersionType.RELEASE) return version;
        }
        return null;
    }

    /** "1.21.4" for a version, "1.8.x" for the client's own. */
    public static String name(ProtocolVersion version) {
        return version == null ? nativeVersion().getName() : version.getName();
    }

    /** Which list a version belongs in. */
    public static String group(ProtocolVersion version) {
        VersionType type = version.getVersionType();
        if (type == VersionType.RELEASE) return "正式版";
        if (type == VersionType.SPECIAL) return "特殊版本";
        return "远古版本";
    }
}
