package lol.hub.testutils;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import java.io.IOException;
import java.nio.file.Files;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

@ExtendWith(TestServerExtension.class)
class TuSpawnTest {

    @Test
    void spawnReplies(TestServer server) {
        assertEquals("spawned bot at 0 64 0\n", server.command("tu spawn bot 0 64 0"));
    }

    @Test
    void repliesOverFourKilobytesAreReassembled(TestServer server) {
        // Spans several RCON packets: the first command and the last one registered sit in different ones.
        String reply = server.command("minecraft:help");
        assertTrue(reply.length() > 4096, () -> "reply was only " + reply.length() + " chars");
        assertTrue(reply.startsWith("/advancement"), "reply starts mid-way");
        assertTrue(reply.stripTrailing().endsWith("/icanhasbukkit [<args>]"), "reply is cut off");
    }

    @Test
    void startupLogHasNoErrors(TestServer server) throws IOException {
        List<String> bad = Files.readAllLines(server.logFile()).stream()
                .filter(line -> line.contains(" ERROR]") || line.contains("Ambiguous plugin name"))
                .toList();
        assertTrue(bad.isEmpty(), () -> "server log has errors:\n" + String.join("\n", bad));
    }
}
