package lol.hub.testutils;

import java.io.IOException;
import java.net.InetAddress;
import java.net.ServerSocket;

/** Where a server run gets its ports from. Tests swap it to force a port clash. */
@FunctionalInterface
interface PortSource {

    /** Asks the OS for a loopback port nobody holds right now. Another process can still take it before the server binds. */
    PortSource LOOPBACK = () -> {
        try (ServerSocket socket = new ServerSocket(0, 1, InetAddress.getLoopbackAddress())) {
            return socket.getLocalPort();
        }
    };

    int next() throws IOException;
}
