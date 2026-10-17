package lol.hub.testutils;

import com.google.gson.JsonObject;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The run's server boots with the dimensions of the test resource testutils-dimensions.json. */
@ExtendWith(TestServerExtension.class)
class DeclaredDimensionsTest {

    @TempDir
    Path temp;

    private static Path paperRepo() {
        return Path.of(System.getProperty("testutils.paperRepo"));
    }

    @Test
    void anOverrideReadsBackOnTheServer(TestServer server) {
        assertEquals(new DimensionType("tu:dim", 5, false), server.dimension("tu:dim"));
    }

    @Test
    void anEndBasedDimensionHasNoDragonFight(TestServer server) {
        assertTrue(server.dimension("minecraft:the_end").hasEnderDragonFight(), "the base has one");
        assertFalse(server.dimension("tu:end").hasEnderDragonFight());
    }

    @Test
    void theDeclarationsArriveAsOneDatapack(TestServer server) {
        String packs = server.command("datapack list enabled");
        assertTrue(packs.contains("file/testutils"), packs);
    }

    @Test
    void aBotSpawnsOnTheGrassAtTheOverworldsSurfaceHeight(TestServer server) {
        String name = "tu" + server.nextBotNumber();
        int stand = TestServer.SURFACE_Y + 1;
        JsonObject reply = server.tu("spawn " + name + " 3000 " + stand + " 3000 tu:dim");
        try {
            assertTrue(reply.get("ok").getAsBoolean(), reply.toString());
            assertEquals("tu:dim", reply.get("dimension").getAsString());
            assertEquals("minecraft:grass_block", server.block(new Pos(3000, TestServer.SURFACE_Y, 3000, "tu:dim")).type());
            assertEquals("minecraft:air", server.block(new Pos(3000, stand, 3000, "tu:dim")).type());
            assertEquals(new CheckResult(true, 1), server.check("in tu:dim ", "if entity @a[name=" + name + ",x=3000,y=" + stand + ",z=3000,distance=..0.5]"));
        } finally {
            server.tu("remove " + name);
        }
    }

    @Test
    void aStartWithoutDeclarationAddsNoDatapack() throws IOException {
        try (TestServer bare = TestServer.start(PortSource.LOOPBACK, temp.resolve("log/server.log"), paperRepo(), null)) {
            String packs = bare.command("datapack list");
            assertFalse(packs.contains("file/testutils"), packs);
        }
    }

    @Test
    void aBadDeclarationFailsBeforeAnyServerBoots() {
        // Every boot attempt asks for ports first, so a start that asks for none booted nothing.
        PortSource noBoot = () -> {
            throw new AssertionError("the start went on to boot a server");
        };
        // No timeout: the patch step before validation can take minutes on a cold repo, and interrupting it would leave Paperclip running.
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> TestServer.start(noBoot, temp.resolve("log/server.log"), paperRepo(), "[{\"id\": \"minecraft:dim\", \"base\": \"minecraft:overworld\"}]"));
        assertTrue(e.getMessage().contains("minecraft:dim"), e.getMessage());
    }
}
