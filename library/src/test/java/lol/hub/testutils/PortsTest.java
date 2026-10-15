package lol.hub.testutils;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import java.io.IOException;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.nio.file.Path;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.IntFunction;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@ExtendWith(TestServerExtension.class)
class PortsTest {

    @Test
    void anotherRunsRconPortRejectsOurPassword(TestServer server) {
        // A run that picked a port another run already holds must not get in with a password it could guess.
        IOException e = assertThrows(IOException.class, () -> new Rcon(server.rconPort(), "testutils").close());
        assertTrue(e.getMessage().contains("rejected"), e.getMessage());
    }

    @Test
    void twoServersStartedAtOnceBothAnswer() throws Exception {
        CompletableFuture<TestServer> a = CompletableFuture.supplyAsync(() -> extra("parallel-a", PortSource.LOOPBACK));
        CompletableFuture<TestServer> b = CompletableFuture.supplyAsync(() -> extra("parallel-b", PortSource.LOOPBACK));
        try (TestServer first = a.get(); TestServer second = b.get()) {
            assertTrue(first.command("list").contains("players online"));
            assertTrue(second.command("list").contains("players online"));
            assertNotEquals(first.rconPort(), second.rconPort());
        }
    }

    @Test
    void occupiedGamePortRestartsOnNewPorts() throws Exception {
        assertRestartsPast(occupied -> new Ports(occupied, 0));
    }

    @Test
    void occupiedRconPortRestartsOnNewPorts() throws Exception {
        assertRestartsPast(occupied -> new Ports(0, occupied));
    }

    private static void assertRestartsPast(IntFunction<Ports> ports) throws Exception {
        try (ServerSocket taken = new ServerSocket(0, 1, InetAddress.getLoopbackAddress())) {
            Ports source = ports.apply(taken.getLocalPort());
            try (TestServer server = extra("occupied", source)) {
                assertTrue(server.command("list").contains("players online"));
                assertNotEquals(taken.getLocalPort(), server.rconPort());
                assertEquals(4, source.asked.get(), "two ports for the failed attempt, two for the one that worked");
            }
        }
    }

    /** Hands out the given ports (0 means a free one) on the first attempt, free ports after that, and counts the asks. */
    private static final class Ports implements PortSource {
        final AtomicInteger asked = new AtomicInteger();
        private final int firstGame;
        private final int firstRcon;

        Ports(int firstGame, int firstRcon) {
            this.firstGame = firstGame;
            this.firstRcon = firstRcon;
        }

        @Override
        public int next() throws IOException {
            return switch (asked.getAndIncrement()) {
                case 0 -> firstGame != 0 ? firstGame : PortSource.LOOPBACK.next();
                case 1 -> firstRcon != 0 ? firstRcon : PortSource.LOOPBACK.next();
                default -> PortSource.LOOPBACK.next();
            };
        }
    }

    private static TestServer extra(String name, PortSource ports) {
        try {
            return TestServer.start(ports, Path.of("build", "testutils", name + ".log"));
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }
}
