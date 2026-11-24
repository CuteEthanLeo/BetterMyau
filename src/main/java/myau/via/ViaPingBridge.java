package myau.via;

import com.viaversion.viabackwards.protocol.v1_17to1_16_4.Protocol1_17To1_16_4;
import com.viaversion.viaversion.api.Via;
import com.viaversion.viaversion.api.connection.StorableObject;
import com.viaversion.viaversion.api.connection.UserConnection;
import com.viaversion.viaversion.api.type.Types;
import com.viaversion.viaversion.protocols.v1_16_1to1_16_2.packet.ClientboundPackets1_16_2;
import com.viaversion.viaversion.protocols.v1_16_1to1_16_2.packet.ServerboundPackets1_16_2;
import com.viaversion.viaversion.protocols.v1_16_4to1_17.packet.ClientboundPackets1_17;
import com.viaversion.viaversion.protocols.v1_16_4to1_17.packet.ServerboundPackets1_17;

import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 1.17 replaced window-0 transactions with ping/pong (int ids). Anticheats send pings around
 * movement and velocity and expect the answers in the order the client handled them. ViaBackwards
 * turns a ping into a transaction only when its id fits in a short and answers the others on the
 * spot; here every ping becomes a transaction the 1.8 client answers in order: ids that fit stay
 * as they are (so modules reading transaction ids see the server's), other ids get a stand-in
 * short that maps back to the real id when the client's answer goes out.
 */
public final class ViaPingBridge {
    private ViaPingBridge() { }

    private static volatile boolean installed;

    public static boolean installed() {
        return installed;
    }

    static void install() {
        try {
            Protocol1_17To1_16_4 protocol = Via.getManager().getProtocolManager().getProtocol(Protocol1_17To1_16_4.class);
            if (protocol == null) {
                ViaClient.LOGGER.warn("ViaBackwards 1.17 protocol missing; pings keep ViaBackwards' default handling");
                return;
            }
            protocol.registerClientbound(ClientboundPackets1_17.PING, ClientboundPackets1_16_2.CONTAINER_ACK, wrapper -> {
                int id = wrapper.read(Types.INT);
                short shortId = ids(wrapper.user()).toShort(id);
                wrapper.write(Types.UNSIGNED_BYTE, (short) 0);
                wrapper.write(Types.SHORT, shortId);
                wrapper.write(Types.BOOLEAN, false);
            }, true);
            protocol.registerServerbound(ServerboundPackets1_16_2.CONTAINER_ACK, ServerboundPackets1_17.PONG, wrapper -> {
                byte window = wrapper.read(Types.BYTE);
                short shortId = wrapper.read(Types.SHORT);
                boolean accepted = wrapper.read(Types.BOOLEAN);
                Integer id = window == 0 && accepted ? ids(wrapper.user()).resolve(shortId) : null;
                if (id == null) {
                    // 1.17+ has no inventory transactions: an answer to nothing is dropped.
                    wrapper.cancel();
                    return;
                }
                wrapper.write(Types.INT, id);
            }, true);
            installed = true;
        } catch (Throwable t) {
            ViaClient.LOGGER.error("Could not install the 1.17+ ping bridge; pings keep ViaBackwards' default handling", t);
        }
    }

    private static PingIds ids(UserConnection user) {
        PingIds ids = user.get(PingIds.class);
        if (ids == null) {
            ids = new PingIds();
            user.put(ids);
        }
        return ids;
    }

    /** Ping ids waiting for the client's answer, by the short id the client was given. */
    static final class PingIds implements StorableObject {
        private static final int LIMIT = 4096;
        private final Map<Short, Integer> waiting = new LinkedHashMap<>();
        private short next = Short.MIN_VALUE;

        synchronized short toShort(int id) {
            short shortId = (short) id;
            if (shortId != id || waiting.containsKey(shortId)) shortId = standIn();
            waiting.put(shortId, id);
            if (waiting.size() > LIMIT) {
                // Answers the client never sent (a module held them back for good) are forgotten, oldest first.
                Iterator<Short> oldest = waiting.keySet().iterator();
                oldest.next();
                oldest.remove();
            }
            return shortId;
        }

        synchronized Integer resolve(short shortId) {
            return waiting.remove(shortId);
        }

        private short standIn() {
            for (int tries = 0; tries < 65536; tries++) {
                short candidate = next++;
                if (!waiting.containsKey(candidate)) return candidate;
            }
            return next;
        }
    }
}
