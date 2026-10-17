package lol.hub.testutils;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import java.time.Duration;

import static lol.hub.testutils.Waiting.holdsFor;
import static org.junit.jupiter.api.Assertions.assertEquals;

@ExtendWith(TestServerExtension.class)
class WorldTest {

    private static final String NOON = "Timeline minecraft:day is at 6000 tick(s)";

    @Test
    void rulesReadBackWithTheirExpectedValues(TestServer server) {
        assertEquals("Game rule random_tick_speed is currently set to 0", server.command("gamerule random_tick_speed"));
        assertEquals("Game rule advance_time is currently set to false", server.command("gamerule advance_time"));
        assertEquals("Game rule spawn_mobs is currently set to false", server.command("gamerule spawn_mobs"));
        assertEquals("The difficulty is Peaceful", server.command("difficulty"));
        assertEquals(NOON, server.command("time query day"));
    }

    @Test
    void rulesHoldInTheNetherTheEndAndDeclaredDimensions(TestServer server) {
        for (String dimension : new String[]{"minecraft:the_nether", "minecraft:the_end", "tu:dim", "tu:end"}) {
            String in = "execute in " + dimension + " run ";
            assertEquals("Game rule random_tick_speed is currently set to 0", server.command(in + "gamerule random_tick_speed"), dimension);
            assertEquals("Game rule advance_time is currently set to false", server.command(in + "gamerule advance_time"), dimension);
            assertEquals("Game rule spawn_mobs is currently set to false", server.command(in + "gamerule spawn_mobs"), dimension);
            assertEquals("The difficulty is Peaceful", server.command(in + "difficulty"), dimension);
        }
    }

    @Test
    void timeIsSetInADeclaredOverworldDimension(TestServer server) {
        assertEquals(NOON, server.command("execute in tu:dim run time query day"));
    }

    @Test
    void timeStandsStill(TestServer server) {
        holdsFor(Duration.ofSeconds(2), () -> server.command("time query day"), NOON::equals);
    }

    @Test
    void sampledChunksAreFlatDryAndEmpty(TestServer server) {
        for (int[] at : new int[][]{{5000, -7000}, {-30000, 20000}, {-500, 300}}) {
            int x = at[0], z = at[1];
            server.command("forceload add " + x + " " + z);
            assertBlock(server, x, 63, z, "grass_block");
            assertBlock(server, x, 62, z, "dirt");
            assertBlock(server, x, -63, z, "dirt");
            assertBlock(server, x, -64, z, "bedrock");
            assertBlock(server, x, 64, z, "air");
            // `fill ... replace` only reports "No blocks were filled" when the region holds none of that fluid.
            int chunkX = Math.floorDiv(x, 16) * 16, chunkZ = Math.floorDiv(z, 16) * 16;
            String region = chunkX + " -64 " + chunkZ + " " + (chunkX + 15) + " 63 " + (chunkZ + 15);
            assertEquals("No blocks were filled", server.command("fill " + region + " air replace water"));
            assertEquals("No blocks were filled", server.command("fill " + region + " air replace lava"));
            // Spawning is off, but chunk generation can still place animals. A bare @e would also see the other dimensions.
            assertEquals(new CheckResult(false, 0), server.check("if entity @e[type=!minecraft:player,x=" + chunkX + ",y=-64,z=" + chunkZ + ",dx=15,dy=383,dz=15]"));
        }
    }

    private static void assertBlock(TestServer server, int x, int y, int z, String block) {
        assertEquals(new CheckResult(true, 1), server.check("if block " + x + " " + y + " " + z + " " + block), block + " at " + x + " " + y + " " + z);
    }
}
