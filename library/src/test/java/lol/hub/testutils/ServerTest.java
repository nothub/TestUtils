package lol.hub.testutils;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import java.io.IOException;
import java.nio.file.Files;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@ExtendWith(TestServerExtension.class)
class ServerTest {

    @Test
    void repliesOverFourKilobytesAreReassembled(TestServer server) {
        // Spans several RCON packets: the first command and the last one registered sit in different ones.
        String reply = server.command("minecraft:help");
        assertTrue(reply.length() > 4096, () -> "reply was only " + reply.length() + " chars");
        assertTrue(reply.startsWith("/advancement"), "reply starts mid-way");
        assertTrue(reply.stripTrailing().endsWith("/icanhasbukkit [<args>]"), "reply is cut off");
    }

    @Test
    void theServerDoesNotPhoneHomeForUpdates(TestServer server) throws IOException {
        // Offline, Paper's update checker logs three ERROR lines at boot. Paper rewrites the file with every key, so this is its read-back.
        String config = Files.readString(server.logFile().resolveSibling("config/paper-global.yml"));
        assertTrue(config.contains("update-checker:\n  enabled: false\n"), config);
    }

    @Test
    void blockAndContainerQueriesAnswerWithTypedValues(TestServer server, Area area) {
        Pos stairs = area.at(1, 1, 1);
        Pos chest = area.at(3, 1, 1);
        server.command("setblock " + stairs + " minecraft:oak_stairs[facing=east,half=top,shape=straight,waterlogged=false]");
        server.command("setblock " + chest + " chest{Items:[{Slot:4b,id:\"minecraft:dirt\",count:3}]}");

        assertEquals(new Block("minecraft:oak_stairs", Map.of("facing", "east", "half", "top", "shape", "straight", "waterlogged", "false")), server.block(stairs));
        assertEquals(new Block("minecraft:air", Map.of()), server.block(area.at(5, 1, 5)));
        assertEquals(new Container("minecraft:chest", 27, Map.of(4, new Item("minecraft:dirt", 3, 0, "minecraft:dirt"))), server.container(chest));
        IllegalStateException e = assertThrows(IllegalStateException.class, () -> server.container(stairs));
        assertTrue(e.getMessage().contains("no container at " + stairs), e.getMessage());
    }

    @Test
    void queriesFollowThePositionsDimension(TestServer server) {
        Pos stairs = new Pos(1100, 64, 1000, "minecraft:the_nether");
        Pos chest = new Pos(1102, 64, 1000, "minecraft:the_nether");
        assertEquals("1100 64 1000", stairs.toString());
        assertEquals("minecraft:overworld", new Pos(1100, 64, 1000).dimension());
        String nether = "execute in minecraft:the_nether run ";
        server.command(nether + "forceload add 1100 1000");
        server.command(nether + "setblock " + stairs + " minecraft:oak_stairs[facing=east,half=top,shape=straight,waterlogged=false]");
        server.command(nether + "setblock " + chest + " chest{Items:[{Slot:4b,id:\"minecraft:dirt\",count:3}]}");
        server.command("forceload add 1100 1000");
        server.command("setblock 1100 64 1000 stone");

        assertEquals(new Block("minecraft:oak_stairs", Map.of("facing", "east", "half", "top", "shape", "straight", "waterlogged", "false")), server.block(stairs));
        assertEquals(new Container("minecraft:chest", 27, Map.of(4, new Item("minecraft:dirt", 3, 0, "minecraft:dirt"))), server.container(chest));
        assertEquals(new Block("minecraft:stone", Map.of()), server.block(new Pos(1100, 64, 1000)));
    }

    @Test
    void startupLogHasNoErrors(TestServer server) throws IOException {
        List<String> bad = ServerLog.errors(server);
        assertTrue(bad.isEmpty(), () -> "server log has errors:\n" + String.join("\n", bad));
    }
}
