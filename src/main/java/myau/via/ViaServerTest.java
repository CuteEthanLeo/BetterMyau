package myau.via;

import com.viaversion.viaversion.api.protocol.version.ProtocolVersion;
import io.netty.channel.nio.NioEventLoopGroup;
import net.minecraft.network.NetworkManager;

import java.io.File;
import java.util.concurrent.TimeUnit;

/**
 * End-to-end check against a real server: the headless 1.8.9 client ({@link ViaTestClient})
 * logs in through the real translation, plays for a few seconds (keep-alives, transactions, the
 * spawn teleport, movement) and must reach Join Game without a disconnect or a decoding error.
 * <p>
 * Args: {@code <host> <port> <version name> [seconds to stay] [resource pack answer delay ms]}.
 * For offline-mode servers.
 */
public final class ViaServerTest {
    private ViaServerTest() { }

    public static void main(String[] args) throws Exception {
        String host = args[0];
        int port = Integer.parseInt(args[1]);
        String versionName = args[2];
        int stay = args.length > 3 ? Integer.parseInt(args[3]) : 8;
        long packDelayMs = args.length > 4 ? Long.parseLong(args[4]) : 0;

        ViaClient.load(new File("via"), "servertest");
        if (!ViaClient.ready()) {
            System.out.println("[ViaServerTest] FAIL: ViaVersion did not load: " + ViaClient.problem());
            System.exit(1);
        }
        ProtocolVersion target = ViaVersions.parse(versionName);
        if (target == null) {
            System.out.println("[ViaServerTest] FAIL: unknown version " + versionName);
            System.exit(1);
        }
        System.out.println("[ViaServerTest] joining " + host + ":" + port + " as " + target.getName());

        NioEventLoopGroup group = new NioEventLoopGroup(1);
        ViaTestClient client;
        try {
            client = ViaTestClient.connect(group, host, port, target, "ViaTest" + (System.currentTimeMillis() % 10000));
            client.packDelayMs = packDelayMs;
            long wait = 30 + packDelayMs / 1000;
            if (!client.joined.await(wait, TimeUnit.SECONDS)) {
                client.fail("no Join Game within " + wait + " s (state " + client.channel.attr(NetworkManager.attrKeyConnectionState).get()
                        + ", received " + client.received + ")");
            } else if (client.failure == null) {
                System.out.println("[ViaServerTest] Join Game after " + (System.currentTimeMillis() - client.started) + " ms; staying " + stay + " s");
                client.done.await(stay, TimeUnit.SECONDS);
            }
            client.close();
        } finally {
            group.shutdownGracefully(0, 1, TimeUnit.SECONDS);
        }

        System.out.println("[ViaServerTest] received: " + client.received);
        System.out.println("[ViaServerTest] sent to the server (packet ids after translation): " + client.sent);
        if (client.failure != null) {
            System.out.println("[ViaServerTest] FAIL: " + client.failure);
            System.exit(1);
        }
        System.out.println("[ViaServerTest] OK: logged in as " + target.getName()
                + (client.configurationHelloSent() ? " (settings + brand sent in configuration)" : "")
                + ", played " + stay + " s, " + client.total + " packets, no errors");
        System.exit(0);
    }
}
