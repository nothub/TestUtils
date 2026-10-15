package lol.hub.testutils;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import java.io.IOException;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

@ExtendWith(TestServerExtension.class)
class TuCommandsTest {

    private static JsonObject json(String literal) {
        return JsonParser.parseString(literal).getAsJsonObject();
    }

    private static void assertError(JsonObject reply, String error) {
        assertEquals(json("{\"ok\":false,\"error\":\"" + error + "\"}"), reply);
    }

    @Test
    void spawnAndRemoveReply(TestServer server) {
        assertEquals(json("{\"ok\":true,\"name\":\"spawner\",\"x\":1000,\"y\":64,\"z\":1000}"), server.tu("spawn spawner 1000 64 1000"));
        assertError(server.tu("spawn spawner 1000 64 1000"), "spawner already exists");
        assertEquals(json("{\"ok\":true,\"name\":\"spawner\"}"), server.tu("remove spawner"));
        assertError(server.tu("remove spawner"), "no fake player named spawner");
    }

    @Test
    void asReportsWhetherTheCommandRan(TestServer server) {
        server.tu("spawn runner 1010 64 1000");
        assertEquals(json("{\"ok\":true,\"name\":\"runner\",\"command\":\"help\",\"success\":true}"), server.tu("as runner help"));
        assertEquals(json("{\"ok\":true,\"name\":\"runner\",\"command\":\"nosuchcommand\",\"success\":false}"), server.tu("as runner nosuchcommand"));
    }

    @Test
    void openAndCloseReply(TestServer server) {
        server.command("forceload add 1020 1000");
        server.command("setblock 1020 64 1000 chest");
        server.command("setblock 1022 64 1000 stone");
        server.tu("spawn opener 1020 64 1002");
        assertEquals(json("{\"ok\":true,\"name\":\"opener\",\"block\":\"minecraft:chest\"}"), server.tu("open opener 1020 64 1000"));
        assertEquals(json("{\"ok\":true,\"name\":\"opener\"}"), server.tu("close opener"));
        assertError(server.tu("open opener 1022 64 1000"), "no container at 1022 64 1000");
    }

    @Test
    void useReplies(TestServer server) {
        server.command("forceload add 1030 1000");
        server.command("setblock 1030 64 1000 grass_block");
        server.command("setblock 1030 65 1000 air");
        server.tu("spawn user 1030 65 1002");
        server.command("item replace entity user weapon.mainhand with minecraft:iron_hoe");
        assertEquals(json("{\"ok\":true,\"name\":\"user\",\"item\":\"minecraft:iron_hoe\",\"pos\":[1030,64,1000],\"result\":\"Success\"}"),
                server.tu("use user 1030 64 1000"));
        assertTrue(server.check("if block 1030 64 1000 farmland").passed(), "the hoe tilled the grass");
    }

    @Test
    void badArgumentsAnswerWithAnErrorAndLogNothing(TestServer server) throws IOException {
        server.tu("spawn badargs 1040 64 1000");
        List<String> wrongShape = List.of("", "bogus badargs", "spawn", "spawn x 1", "spawn x 1 2", "spawn x 1 2 3 4", "spawn x a b c",
                "remove", "close", "as badargs", "open badargs", "open badargs a b c", "open badargs 1 2",
                "use badargs 1 2", "use badargs a b c");
        List<String> outsideTheWorld = List.of("spawn out1 NaN 64 0", "spawn out2 Infinity 64 0", "spawn out3 1e400 64 0",
                "spawn out4 0 64 1e10", "spawn out5 4000 100000 4000", "spawn out6 4000 -100000 4000",
                "open badargs 2000000000 64 0", "use badargs 0 100000 0");
        List<String> unknown = List.of("open nobody 1 2 3", "use nobody 1 2 3", "as nobody help", "close nobody", "remove nobody",
                "use badargs 1040 64 1000 sideways");
        for (String args : List.of(wrongShape, outsideTheWorld, unknown).stream().flatMap(List::stream).toList()) assertErrorShape(server, args);

        for (String name : List.of("x", "out1", "out2", "out3", "out4", "out5", "out6")) {
            assertError(server.tu("remove " + name), "no fake player named " + name);
        }
        assertTrue(server.tu("remove badargs").get("ok").getAsBoolean(), "the bot survived the bad calls");
        List<String> errors = ServerLog.errors(server);
        assertTrue(errors.isEmpty(), () -> "server log has errors:\n" + String.join("\n", errors));
    }

    private static void assertErrorShape(TestServer server, String args) {
        JsonObject reply = server.tu(args);
        assertEquals(Set.of("ok", "error"), reply.keySet(), "tu " + args + " -> " + reply);
        assertFalse(reply.get("ok").getAsBoolean(), "tu " + args);
        assertFalse(reply.get("error").getAsString().isBlank(), "tu " + args);
    }
}
