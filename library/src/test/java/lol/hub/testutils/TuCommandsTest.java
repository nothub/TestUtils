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
    void inventoryReply(TestServer server) {
        server.tu("spawn packer 1050 64 1000");
        assertEquals(json("{\"ok\":true,\"name\":\"packer\",\"main_hand\":null,\"items\":[]}"), server.tu("inventory packer"));

        server.command("item replace entity packer weapon.mainhand with minecraft:iron_hoe[minecraft:damage=7]");
        server.command("item replace entity packer inventory.1 with minecraft:dirt 3");
        assertEquals(json("{\"ok\":true,\"name\":\"packer\","
                        + "\"main_hand\":{\"id\":\"minecraft:iron_hoe\",\"count\":1,\"damage\":7,\"item_model\":\"minecraft:iron_hoe\"},"
                        + "\"items\":[{\"slot\":0,\"id\":\"minecraft:iron_hoe\",\"count\":1,\"damage\":7,\"item_model\":\"minecraft:iron_hoe\"},"
                        + "{\"slot\":10,\"id\":\"minecraft:dirt\",\"count\":3,\"damage\":0,\"item_model\":\"minecraft:dirt\"}]}"),
                server.tu("inventory packer"));
        assertError(server.tu("inventory nobody"), "no fake player named nobody");
    }

    @Test
    void blockReply(TestServer server) {
        server.command("forceload add 1060 1000");
        server.command("setblock 1060 64 1000 minecraft:oak_stairs[facing=east,half=top,shape=straight,waterlogged=false]");
        server.command("setblock 1061 64 1000 stone");
        server.command("setblock 1062 64 1000 air");
        assertEquals(json("{\"ok\":true,\"pos\":[1060,64,1000],\"type\":\"minecraft:oak_stairs\","
                        + "\"state\":{\"facing\":\"east\",\"half\":\"top\",\"shape\":\"straight\",\"waterlogged\":\"false\"}}"),
                server.tu("block 1060 64 1000"));
        assertEquals(json("{\"ok\":true,\"pos\":[1061,64,1000],\"type\":\"minecraft:stone\",\"state\":{}}"), server.tu("block 1061 64 1000"));
        assertEquals(json("{\"ok\":true,\"pos\":[1062,64,1000],\"type\":\"minecraft:air\",\"state\":{}}"), server.tu("block 1062 64 1000"));
    }

    @Test
    void containerReply(TestServer server) {
        server.command("forceload add 1070 1000");
        server.command("setblock 1070 64 1000 chest{Items:[{Slot:4b,id:\"minecraft:dirt\",count:3},{Slot:26b,id:\"minecraft:iron_hoe\",count:1,components:{\"minecraft:damage\":9}}]}");
        server.command("setblock 1068 64 1000 hopper");
        server.command("setblock 1066 64 1000 stone");
        assertEquals(json("{\"ok\":true,\"pos\":[1070,64,1000],\"block\":\"minecraft:chest\",\"size\":27,\"items\":["
                        + "{\"slot\":4,\"id\":\"minecraft:dirt\",\"count\":3,\"damage\":0,\"item_model\":\"minecraft:dirt\"},"
                        + "{\"slot\":26,\"id\":\"minecraft:iron_hoe\",\"count\":1,\"damage\":9,\"item_model\":\"minecraft:iron_hoe\"}]}"),
                server.tu("container 1070 64 1000"));
        assertEquals(json("{\"ok\":true,\"pos\":[1068,64,1000],\"block\":\"minecraft:hopper\",\"size\":5,\"items\":[]}"), server.tu("container 1068 64 1000"));
        assertError(server.tu("container 1066 64 1000"), "no container at 1066 64 1000");
    }

    @Test
    void blockAndContainerReadTheGivenDimension(TestServer server) {
        String nether = "execute in minecraft:the_nether run ";
        server.command(nether + "forceload add 1080 1000");
        server.command(nether + "setblock 1080 64 1000 minecraft:oak_stairs[facing=east,half=top,shape=straight,waterlogged=false]");
        server.command(nether + "setblock 1082 64 1000 chest{Items:[{Slot:4b,id:\"minecraft:dirt\",count:3}]}");
        server.command("forceload add 1080 1000");
        server.command("setblock 1080 64 1000 stone");
        server.command("setblock 1082 64 1000 stone");

        assertEquals(json("{\"ok\":true,\"pos\":[1080,64,1000],\"type\":\"minecraft:oak_stairs\","
                        + "\"state\":{\"facing\":\"east\",\"half\":\"top\",\"shape\":\"straight\",\"waterlogged\":\"false\"}}"),
                server.tu("block 1080 64 1000 minecraft:the_nether"));
        assertEquals(json("{\"ok\":true,\"pos\":[1082,64,1000],\"block\":\"minecraft:chest\",\"size\":27,\"items\":["
                        + "{\"slot\":4,\"id\":\"minecraft:dirt\",\"count\":3,\"damage\":0,\"item_model\":\"minecraft:dirt\"}]}"),
                server.tu("container 1082 64 1000 minecraft:the_nether"));
        JsonObject overworldStone = json("{\"ok\":true,\"pos\":[1080,64,1000],\"type\":\"minecraft:stone\",\"state\":{}}");
        assertEquals(overworldStone, server.tu("block 1080 64 1000"));
        assertEquals(overworldStone, server.tu("block 1080 64 1000 minecraft:overworld"));
        assertError(server.tu("container 1082 64 1000"), "no container at 1082 64 1000");
    }

    @Test
    void anUnknownOrMalformedDimensionAnswersWithAnError(TestServer server) {
        assertError(server.tu("block 0 64 0 minecraft:nowhere"), "unknown dimension minecraft:nowhere");
        assertError(server.tu("container 0 64 0 tu:nowhere"), "unknown dimension tu:nowhere");
        assertError(server.tu("block 0 64 0 Not:Valid"), "not a dimension id: Not:Valid");
        assertError(server.tu("container 0 64 0 a:b:c"), "not a dimension id: a:b:c");
    }

    @Test
    void badArgumentsAnswerWithAnErrorAndLogNothing(TestServer server) throws IOException {
        server.tu("spawn badargs 1040 64 1000");
        List<String> wrongShape = List.of("", "bogus badargs", "spawn", "spawn x 1", "spawn x 1 2", "spawn x 1 2 3 4", "spawn x a b c",
                "remove", "close", "inventory", "inventory badargs extra", "as badargs", "open badargs", "open badargs a b c", "open badargs 1 2",
                "use badargs 1 2", "use badargs a b c", "block", "block 1 2", "block a b c", "block 1 2 3 4 5", "block a b c minecraft:the_nether", "container", "container 1 2 3 4 5");
        List<String> outsideTheWorld = List.of("spawn out1 NaN 64 0", "spawn out2 Infinity 64 0", "spawn out3 1e400 64 0",
                "spawn out4 0 64 1e10", "spawn out5 4000 100000 4000", "spawn out6 4000 -100000 4000",
                "open badargs 2000000000 64 0", "use badargs 0 100000 0", "block 2000000000 64 0", "block 0 100000 0", "container 0 -100000 0",
                "block 0 300 0 minecraft:the_nether", "container 2000000000 64 0 minecraft:the_end");
        List<String> unknown = List.of("open nobody 1 2 3", "use nobody 1 2 3", "as nobody help", "close nobody", "remove nobody",
                "use badargs 1040 64 1000 sideways", "block 0 64 0 minecraft:nowhere", "block 0 64 0 Not:Valid", "container 0 64 0 :",
                "container 0 64 0 minecraft:");
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
