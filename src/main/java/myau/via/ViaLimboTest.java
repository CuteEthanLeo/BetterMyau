package myau.via;

import com.viaversion.viaversion.api.protocol.version.ProtocolVersion;
import io.netty.channel.nio.NioEventLoopGroup;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * Offline check of the 1.20.2+ configuration phase as anti-bot limbos run it: a fake 1.21.10
 * (and 26.3) server takes the login, then keeps the player in configuration, sends a keep-alive with a
 * timestamp id (as servers do) and only finishes configuration once that keep-alive is answered;
 * it also records whether the client's settings and brand arrived. A second fake server shows a
 * dialog in configuration (1.21.6+) and only finishes once the dialog's button is answered with
 * what was typed into it. The headless 1.8 client ({@link ViaTestClient}) connects through the
 * real translation and must get through both.
 */
final class ViaLimboTest {
    private ViaLimboTest() { }

    private static final long KEEP_ALIVE_ID = 1732460000123L;

    /** 1.21.10 and the newest version (26.3): their configuration ids for these packets are the same. */
    static void run(List<String> failures) throws Exception {
        run(failures, ProtocolVersion.v1_21_9);
        ProtocolVersion newest = ViaVersions.newestRelease();
        if (newest != null && !newest.equals(ProtocolVersion.v1_21_9)) run(failures, newest);

        // Regression (1.13.3): a server that shows a dialog in configuration (1.21.6+) and waits for
        // its button; the dialog must reach the client and the answer must be the configuration packet.
        int click = com.viaversion.viaversion.protocols.v1_21_7to1_21_9.packet.ServerboundConfigurationPackets1_21_9.CUSTOM_CLICK_ACTION.getId();
        runDialog(failures, ProtocolVersion.v1_21_9,
                com.viaversion.viaversion.protocols.v1_21_7to1_21_9.packet.ClientboundConfigurationPackets1_21_9.SHOW_DIALOG.getId(), click);
        if (ProtocolVersion.v26_3.equals(newest)) {
            // 26.3 moved the clientbound ids; its serverbound configuration packets are still 1.21.9's.
            runDialog(failures, newest,
                    com.viaversion.viaversion.protocols.v26_2to26_3.packet.ClientboundConfigurationPackets26_3.SHOW_DIALOG.getId(), click);
        }
    }

    private static final String DIALOG_ACTION = "limbo:accept", DIALOG_ANSWER = "s3cret-answer";

    /** The dialog the fake server shows: some text, a text input and one button that sends the input back. */
    private static byte[] dialog() {
        com.viaversion.nbt.tag.CompoundTag dialog = new com.viaversion.nbt.tag.CompoundTag();
        dialog.putString("type", "minecraft:notice");
        dialog.putString("title", "Rules");
        com.viaversion.nbt.tag.CompoundTag message = new com.viaversion.nbt.tag.CompoundTag();
        message.putString("type", "minecraft:plain_message");
        message.putString("contents", "Accept the rules to play");
        com.viaversion.nbt.tag.ListTag<com.viaversion.nbt.tag.CompoundTag> body = new com.viaversion.nbt.tag.ListTag<>(com.viaversion.nbt.tag.CompoundTag.class);
        body.add(message);
        dialog.put("body", body);
        com.viaversion.nbt.tag.CompoundTag input = new com.viaversion.nbt.tag.CompoundTag();
        input.putString("type", "minecraft:text");
        input.putString("key", "code");
        input.putString("label", "Code");
        com.viaversion.nbt.tag.ListTag<com.viaversion.nbt.tag.CompoundTag> inputs = new com.viaversion.nbt.tag.ListTag<>(com.viaversion.nbt.tag.CompoundTag.class);
        inputs.add(input);
        dialog.put("inputs", inputs);
        com.viaversion.nbt.tag.CompoundTag click = new com.viaversion.nbt.tag.CompoundTag();
        click.putString("type", "minecraft:dynamic/custom");
        click.putString("id", DIALOG_ACTION);
        com.viaversion.nbt.tag.CompoundTag button = new com.viaversion.nbt.tag.CompoundTag();
        button.putString("label", "Accept");
        button.put("action", click);
        dialog.put("action", button);
        dialog.putString("after_action", "wait_for_response");

        io.netty.buffer.ByteBuf buffer = io.netty.buffer.Unpooled.buffer();
        try {
            com.viaversion.viaversion.api.type.Types.TRUSTED_COMPOUND_TAG.write(buffer, dialog);
            byte[] bytes = new byte[buffer.readableBytes()];
            buffer.readBytes(bytes);
            return bytes;
        } finally {
            buffer.release();
        }
    }

    private static void runDialog(List<String> failures, final ProtocolVersion target, final int showDialogId, final int clickId) throws Exception {
        final List<String> seen = new ArrayList<>(), shown = java.util.Collections.synchronizedList(new ArrayList<String>());
        final String[] problem = new String[1];
        final boolean[] passed = new boolean[1], closed = new boolean[1];
        ViaDialogView.Presenter before = ViaDialogView.presenter;
        // The player: reads the dialog, types into its input and presses its button.
        ViaDialogView.presenter = new ViaDialogView.Presenter() {
            @Override
            public void show(ViaDialogView view) {
                shown.add(view.title + " / " + view.items.size() + " items / " + view.footer.size() + " buttons");
                for (ViaDialogView.Item item : view.items) {
                    if (item instanceof ViaDialogView.Field) ((ViaDialogView.Field) item).set(DIALOG_ANSWER);
                }
                if (!view.footer.isEmpty()) view.footer.get(0).click();
            }

            @Override
            public void close(ViaDialogView view) {
                closed[0] = true;
            }

            @Override
            public void openUrl(String url) {
            }

            @Override
            public void copy(String text) {
            }
        };
        try (final ServerSocket server = new ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"))) {
            Thread limbo = new Thread(() -> {
                try (Socket socket = server.accept()) {
                    socket.setSoTimeout(15000);
                    passed[0] = serveDialog(socket, target, showDialogId, clickId, seen);
                } catch (Throwable t) {
                    problem[0] = t.toString();
                }
            }, "ViaLimboTest-dialog-server");
            limbo.setDaemon(true);
            limbo.start();

            NioEventLoopGroup group = new NioEventLoopGroup(1);
            String clientSide;
            try {
                ViaTestClient client = ViaTestClient.connect(group, "127.0.0.1", server.getLocalPort(), target, "DialogTest");
                limbo.join(20000);
                client.close();
                clientSide = "client " + (client.failure != null ? client.failure + ", " : "") + "got " + client.received + ", sent " + client.sent
                        + ", dialogs shown " + shown;
            } finally {
                group.shutdownGracefully(0, 1, TimeUnit.SECONDS);
            }
            if (!passed[0]) problem[0] = (problem[0] != null ? problem[0] : "the dialog was not answered") + "; " + clientSide;
            else if (!closed[0]) problem[0] = "the dialog stayed open after configuration finished; " + clientSide;
        } finally {
            ViaDialogView.presenter = before;
        }
        if (problem[0] != null) failures.add("dialog limbo " + target.getName() + ": " + problem[0] + " (server saw " + seen + ")");
        else System.out.println("[ViaSelfTest] configuration dialog (" + target.getName() + "): " + seen + ", shown " + shown);
    }

    /** One connection: login, then a dialog; configuration only finishes once its button's answer arrives. */
    private static boolean serveDialog(Socket socket, ProtocolVersion target, int showDialogId, int clickId, List<String> seen) throws IOException {
        DataInputStream in = new DataInputStream(socket.getInputStream());
        OutputStream out = socket.getOutputStream();
        login(in, out, target, seen);

        ByteArrayOutputStream show = new ByteArrayOutputStream();
        writeVarInt(show, showDialogId);
        byte[] dialog = dialog();
        show.write(dialog, 0, dialog.length);
        send(out, show);

        boolean answered = false;
        while (true) {
            DataInputStream packet = frame(in);
            int id = varInt(packet);
            if (id == clickId) {
                String action = string(packet);
                byte[] rest = new byte[packet.available()];
                packet.readFully(rest);
                String payload = new String(rest, StandardCharsets.ISO_8859_1);
                seen.add("dialog click " + action + (payload.contains(DIALOG_ANSWER) ? " with the typed text" : " WITHOUT the typed text"));
                if (!DIALOG_ACTION.equals(action) || !payload.contains("code") || !payload.contains(DIALOG_ANSWER)) return false;
                answered = true;
                ByteArrayOutputStream finish = new ByteArrayOutputStream();
                writeVarInt(finish, 0x03);
                send(out, finish);
            } else if (id == 0x03) {
                seen.add("finish acknowledged");
                return answered;
            } else {
                seen.add("configuration 0x" + Integer.toHexString(id));
            }
        }
    }

    private static void run(List<String> failures, final ProtocolVersion target) throws Exception {
        final List<String> seen = new ArrayList<>();
        final String[] problem = new String[1];
        final boolean[] passed = new boolean[1];
        try (final ServerSocket server = new ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"))) {
            Thread limbo = new Thread(() -> {
                try (Socket socket = server.accept()) {
                    socket.setSoTimeout(15000);
                    passed[0] = serve(socket, target, seen);
                } catch (Throwable t) {
                    problem[0] = t.toString();
                }
            }, "ViaLimboTest-server");
            limbo.setDaemon(true);
            limbo.start();

            NioEventLoopGroup group = new NioEventLoopGroup(1);
            String clientSide;
            try {
                ViaTestClient client = ViaTestClient.connect(group, "127.0.0.1", server.getLocalPort(), target, "LimboTest");
                limbo.join(20000);
                client.close();
                clientSide = "client " + (client.failure != null ? client.failure + ", " : "") + "got " + client.received + ", sent " + client.sent;
            } finally {
                group.shutdownGracefully(0, 1, TimeUnit.SECONDS);
            }
            if (!passed[0]) problem[0] = (problem[0] != null ? problem[0] : "configuration did not finish") + "; " + clientSide;
        }
        if (problem[0] != null) failures.add("limbo " + target.getName() + ": " + problem[0] + " (server saw " + seen + ")");
        else System.out.println("[ViaSelfTest] configuration limbo (" + target.getName() + "): " + seen);
    }

    /** One connection: login, then configuration until the keep-alive is answered and finish is acknowledged. */
    private static boolean serve(Socket socket, ProtocolVersion target, List<String> seen) throws IOException {
        DataInputStream in = new DataInputStream(socket.getInputStream());
        OutputStream out = socket.getOutputStream();
        login(in, out, target, seen);

        // Configuration: hold the player with a keep-alive, as anti-bot limbos do.
        ByteArrayOutputStream keepAlive = new ByteArrayOutputStream();
        writeVarInt(keepAlive, 0x04);
        writeLong(keepAlive, KEEP_ALIVE_ID);
        send(out, keepAlive);
        boolean info = false, brand = false, answered = false;
        while (true) {
            DataInputStream packet = frame(in);
            int id = varInt(packet);
            if (id == 0x00) {
                info = true;
                seen.add("client information");
            } else if (id == 0x02) {
                String channel = string(packet);
                if ("minecraft:brand".equals(channel)) {
                    brand = true;
                    seen.add("brand " + string(packet));
                } else {
                    seen.add("custom payload " + channel);
                }
            } else if (id == 0x04) {
                long answer = packet.readLong();
                if (answer != KEEP_ALIVE_ID) {
                    seen.add("keep-alive answer " + answer + " (sent " + KEEP_ALIVE_ID + ")");
                    return false;
                }
                answered = true;
                seen.add("keep-alive answered");
                ByteArrayOutputStream finish = new ByteArrayOutputStream();
                writeVarInt(finish, 0x03);
                send(out, finish);
            } else if (id == 0x03) {
                seen.add("finish acknowledged");
                return answered && info && brand;
            } else {
                seen.add("configuration 0x" + Integer.toHexString(id));
            }
        }
    }

    /** Handshake and login, up to the client's acknowledgement: from then on the connection is in configuration. */
    private static void login(DataInputStream in, OutputStream out, ProtocolVersion target, List<String> seen) throws IOException {
        int protocol = target.getOriginalVersion();

        DataInputStream handshake = frame(in);
        if (varInt(handshake) != 0x00) throw new IOException("no handshake");
        int clientProtocol = varInt(handshake);
        if (clientProtocol != protocol) throw new IOException("handshake announced protocol " + clientProtocol);
        DataInputStream hello = frame(in);
        if (varInt(hello) != 0x00) throw new IOException("no login start");
        String name = string(hello);
        seen.add("login start " + name);

        ByteArrayOutputStream finished = new ByteArrayOutputStream();
        writeVarInt(finished, 0x02);
        writeLong(finished, 0x1234L);
        writeLong(finished, 0x5678L);
        writeString(finished, name);
        writeVarInt(finished, 0);
        if (target.newerThanOrEqualTo(ProtocolVersion.v26_2)) {
            // 26.2 added a second UUID to login finished.
            writeLong(finished, 0x9abcL);
            writeLong(finished, 0xdef0L);
        }
        send(out, finished);

        // Login state until the acknowledgement.
        while (true) {
            int id = varInt(frame(in));
            if (id == 0x03) {
                seen.add("login acknowledged");
                break;
            }
            seen.add("login 0x" + Integer.toHexString(id));
        }
    }

    private static DataInputStream frame(DataInputStream in) throws IOException {
        int length = varInt(in);
        if (length < 0 || length > 0x200000) throw new IOException("bad frame length " + length);
        byte[] data = new byte[length];
        in.readFully(data);
        return new DataInputStream(new ByteArrayInputStream(data));
    }

    private static void send(OutputStream out, ByteArrayOutputStream packet) throws IOException {
        ByteArrayOutputStream frame = new ByteArrayOutputStream();
        writeVarInt(frame, packet.size());
        packet.writeTo(frame);
        out.write(frame.toByteArray());
        out.flush();
    }

    private static int varInt(InputStream in) throws IOException {
        int value = 0;
        for (int shift = 0; shift < 35; shift += 7) {
            int b = in.read();
            if (b < 0) throw new IOException("end of stream");
            value |= (b & 0x7F) << shift;
            if ((b & 0x80) == 0) return value;
        }
        throw new IOException("VarInt too long");
    }

    private static String string(DataInputStream in) throws IOException {
        byte[] bytes = new byte[varInt(in)];
        in.readFully(bytes);
        return new String(bytes, StandardCharsets.UTF_8);
    }

    private static void writeVarInt(ByteArrayOutputStream out, int value) {
        while ((value & ~0x7F) != 0) {
            out.write((value & 0x7F) | 0x80);
            value >>>= 7;
        }
        out.write(value);
    }

    private static void writeLong(ByteArrayOutputStream out, long value) {
        for (int i = 7; i >= 0; i--) out.write((int) (value >>> (i * 8)) & 0xFF);
    }

    private static void writeString(ByteArrayOutputStream out, String value) {
        byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
        writeVarInt(out, bytes.length);
        out.write(bytes, 0, bytes.length);
    }
}
