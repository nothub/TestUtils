package lol.hub.testutils;

import java.io.Closeable;
import java.io.DataInputStream;
import java.io.EOFException;
import java.io.IOException;
import java.net.InetAddress;
import java.net.Socket;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.regex.Pattern;

/** Source RCON over a plain socket. One reply per command, matched by request id. */
final class Rcon implements Closeable {

    private static final int COMMAND = 2;
    private static final int LOGIN = 3;
    // Minecraft answers any other type with a single packet ("Unknown request 64").
    private static final int UNKNOWN = 100;

    private static final Pattern COLOR_CODE = Pattern.compile("\u00a7.");
    // A wedged server must not hang the test JVM, including the `stop` in close().
    private static final int READ_TIMEOUT_MILLIS = 60_000;

    private final Socket socket;
    private final DataInputStream in;
    private int nextId = 1;

    Rcon(int port, String password) throws IOException {
        socket = new Socket(InetAddress.getLoopbackAddress(), port);
        try {
            socket.setSoTimeout(READ_TIMEOUT_MILLIS);
            in = new DataInputStream(socket.getInputStream());
            int id = nextId++;
            send(id, LOGIN, password);
            if (read().id != id) throw new IOException("RCON login on port " + port + " was rejected");
        } catch (IOException | RuntimeException e) {
            socket.close();
            throw e;
        }
    }

    /**
     * Minecraft splits replies over 4096 bytes into several packets with no end marker.
     * A request it cannot handle follows the command, and its answer closes the reply.
     * The server takes one read as one packet and drops the connection when two arrive
     * together, so the follow-up goes out only after the first reply packet has arrived.
     */
    synchronized String command(String line) throws IOException {
        int id = nextId++;
        int end = nextId++;
        send(id, COMMAND, line);
        StringBuilder reply = new StringBuilder(read().payload);
        send(end, UNKNOWN, "");
        for (Packet p = read(); p.id != end; p = read()) {
            reply.append(p.payload);
        }
        return COLOR_CODE.matcher(reply).replaceAll("");
    }

    @Override
    public void close() throws IOException {
        socket.close();
    }

    private record Packet(int id, String payload) {
    }

    private void send(int id, int type, String payload) throws IOException {
        byte[] body = payload.getBytes(StandardCharsets.UTF_8);
        ByteBuffer packet = ByteBuffer.allocate(4 + 4 + 4 + body.length + 2).order(ByteOrder.LITTLE_ENDIAN);
        packet.putInt(4 + 4 + body.length + 2).putInt(id).putInt(type).put(body).put((byte) 0).put((byte) 0);
        socket.getOutputStream().write(packet.array());
    }

    private Packet read() throws IOException {
        ByteBuffer header = ByteBuffer.wrap(readExactly(12)).order(ByteOrder.LITTLE_ENDIAN);
        int length = header.getInt();
        int id = header.getInt();
        header.getInt(); // type
        byte[] body = readExactly(length - 4 - 4 - 2);
        readExactly(2);
        return new Packet(id, new String(body, StandardCharsets.UTF_8));
    }

    private byte[] readExactly(int n) throws IOException {
        byte[] bytes = in.readNBytes(n);
        if (bytes.length < n) throw new EOFException("RCON connection closed");
        return bytes;
    }
}
