package lol.hub.testutils;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.time.Duration;
import java.util.Map;

import static lol.hub.testutils.Waiting.holdsFor;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

@ExtendWith(TestServerExtension.class)
class DimensionAreaTest {

    @ParameterizedTest
    @ValueSource(strings = {"minecraft:the_nether", "minecraft:the_end"})
    void areaIsASealedBoxThatStaysClear(String dimension, TestServer server) {
        try (Area area = server.area(dimension)) {
            assertEquals(dimension, area.at(0, 0, 0).dimension());
            assertEquals(new CheckResult(true, 1), area.check("if block " + area.at(0, 0, 0) + " stone"));
            assertEquals(new CheckResult(true, 1), area.check("if block " + area.at(15, 0, 15) + " stone"));
            assertEquals(new CheckResult(true, 1), area.check("if block " + area.at(8, 32, 8) + " air"));
            assertEquals(new CheckResult(true, 1), area.check("if block " + area.at(8, 33, 8) + " barrier"));
            assertEquals(new CheckResult(true, 1), area.check("if block " + area.at(-1, 16, 8) + " barrier"));
            assertEquals(new CheckResult(true, 1), area.check("if block " + area.at(16, 16, 8) + " barrier"));

            Bot bot = area.bot();
            Pos stand = area.at(8, 1, 8);
            assertEquals(new CheckResult(true, 1), area.check("if entity @a[name=" + bot.name() + ",x=" + stand.x() + ",y=" + stand.y()
                    + ",z=" + stand.z() + ",distance=..2]"));

            // A fill that changes nothing answers "No blocks were filled", so these read the whole box without a per-block query.
            String floor = "fill " + area.at(0, 0, 0) + " " + area.at(15, 0, 15) + " stone";
            String interior = "fill " + area.at(0, 1, 0) + " " + area.at(15, 32, 15) + " air";
            Pos origin = area.at(0, 0, 0);
            String entities = "if entity @e[type=!minecraft:player,x=" + origin.x() + ",y=" + origin.y() + ",z=" + origin.z() + ",dx=15,dy=32,dz=15]";
            holdsFor(Duration.ofSeconds(5), () -> area.command(floor) + " | " + area.command(interior) + " | " + area.check(entities),
                    "No blocks were filled | No blocks were filled | CheckResult[passed=false, count=0]"::equals);

            for (int cx = -1; cx <= 1; cx++) {
                for (int cz = -1; cz <= 1; cz++) {
                    String expected = cx == 0 && cz == 0 ? "is marked for force loading" : "is not marked for force loading";
                    String reply = area.command("forceload query " + (origin.x() + cx * 16) + " " + (origin.z() + cz * 16));
                    assertTrue(reply.endsWith(expected), reply);
                }
            }
        }
    }

    @Test
    void anAreaInADeclaredDimensionIsOpenAndFlatLikeAnOverworldArea(TestServer server, @InDimension("tu:dim") Area area) {
        Pos origin = area.at(0, 0, 0);
        assertEquals("tu:dim", origin.dimension());
        assertEquals("No blocks were filled", area.command("fill " + origin + " " + area.at(15, 0, 15) + " stone"));
        // Open: air goes on past where a sealed area has its ceiling, and its wall columns are the flat world's grass and air.
        // The fill stays in the area's chunk, the only one force-loaded; the block queries load the neighbour chunks.
        assertEquals("No blocks were filled", area.command("fill " + area.at(0, 1, 0) + " " + area.at(15, 40, 15) + " air"));
        assertEquals("minecraft:grass_block", server.block(area.at(-1, 0, 8)).type());
        assertEquals("minecraft:air", server.block(area.at(-1, 16, 8)).type());
        assertEquals("minecraft:air", server.block(area.at(16, 16, 8)).type());

        // The same coordinates in the overworld are untouched flat world, so these answers come from tu:dim.
        Pos overworld = new Pos(origin.x(), origin.y(), origin.z());
        assertEquals("minecraft:stone", server.block(origin).type());
        assertEquals("minecraft:grass_block", server.block(overworld).type());
        Pos chest = area.at(4, 1, 4);
        String placed = area.command("setblock " + chest + " chest");
        assertTrue(placed.startsWith("Changed the block"), placed);
        assertEquals(new Container("minecraft:chest", 27, Map.of()), server.container(chest));
        assertEquals("minecraft:air", server.block(new Pos(chest.x(), chest.y(), chest.z())).type());

        Bot bot = area.bot();
        Pos stand = area.at(8, 1, 8);
        String atStand = "if entity @a[name=" + bot.name() + ",x=" + stand.x() + ",y=" + stand.y() + ",z=" + stand.z() + ",distance=..2]";
        assertEquals(new CheckResult(true, 1), area.check(atStand));
        assertEquals(new CheckResult(false, 0), server.check(atStand));
    }
}
