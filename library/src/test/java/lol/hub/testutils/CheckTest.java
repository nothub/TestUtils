package lol.hub.testutils;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@ExtendWith(TestServerExtension.class)
class CheckTest {

    private static void prepare(TestServer server) {
        server.command("forceload add 2000 2000");
        server.command("setblock 2000 64 2000 stone");
        server.command("setblock 2001 64 2000 stone");
    }

    @Test
    void passWithCountOne(TestServer server) {
        prepare(server);
        assertEquals(new CheckResult(true, 1), server.check("if block 2000 64 2000 stone"));
        assertEquals(new CheckResult(true, 1), server.check("unless block 2000 64 2000 dirt"));
    }

    @Test
    void passWithCountOverOne(TestServer server) {
        prepare(server);
        assertEquals(new CheckResult(true, 2), server.check("if blocks 2000 64 2000 2001 64 2000 2000 64 2000 all"));
    }

    @Test
    void fail(TestServer server) {
        prepare(server);
        assertEquals(new CheckResult(false, 0), server.check("if block 2000 64 2000 dirt"));
        assertEquals(new CheckResult(false, 0), server.check("unless block 2000 64 2000 stone"));
    }

    @Test
    void failWithCount(TestServer server) {
        prepare(server);
        assertEquals(new CheckResult(false, 2), server.check("unless blocks 2000 64 2000 2001 64 2000 2000 64 2000 all"));
    }

    @Test
    void throwsOnAnythingButAnIfOrUnlessCondition(TestServer server) {
        assertThrows(IllegalArgumentException.class, () -> server.check("run say hi"));
        assertThrows(IllegalArgumentException.class, () -> server.check("as @a run say hi"));
        assertThrows(IllegalArgumentException.class, () -> server.check(""));
    }

    @Test
    void throwsOnAnythingElse(TestServer server) {
        prepare(server);
        var typo = assertThrows(IllegalArgumentException.class, () -> server.check("if blokc 2000 64 2000 stone"));
        assertTrue(typo.getMessage().contains("Incorrect argument for command"), typo.getMessage());
        assertThrows(IllegalArgumentException.class, () -> server.check("if block 2000 64 2000 minecraft:nothing"));
        assertThrows(IllegalArgumentException.class, () -> server.check("if entity @x"));
        assertThrows(IllegalArgumentException.class, () -> server.check("if block 2000 64 2000 stone run say hi"));
        var unloaded = assertThrows(IllegalArgumentException.class, () -> server.check("if block 90000 64 90000 stone"));
        assertTrue(unloaded.getMessage().contains("not loaded"), unloaded.getMessage());
    }
}
