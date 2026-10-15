package lol.hub.testutils;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@ExtendWith(TestServerExtension.class)
class BotTest {

    private static final List<String> BOT_NAMES = new CopyOnWriteArrayList<>();
    private static final Map<Pos, String> PLACED = new ConcurrentHashMap<>();
    private static final List<Pos> ORIGINS = new CopyOnWriteArrayList<>();

    private Area beforeEachArea;

    @BeforeEach
    void rememberArea(Area area) {
        beforeEachArea = area;
    }

    @Test
    void beforeEachAndTheTestShareOneArea(Area area) {
        assertSame(beforeEachArea, area);
    }

    private static void assertOnline(TestServer server, String name, boolean online) {
        assertEquals(new CheckResult(online, online ? 1 : 0), server.check("if entity " + name), name);
    }

    @ParameterizedTest
    @ValueSource(strings = {"stone_bricks", "glass", "oak_planks"})
    void sameRelativeBlockInEachInvocationStaysItsOwn(String material, TestServer server, Area area) {
        Bot bot = area.bot();
        BOT_NAMES.add(bot.name());
        ORIGINS.add(area.at(0, 0, 0));
        assertTrue(bot.name().length() <= 16, bot.name());
        assertOnline(server, bot.name(), true);

        Pos pos = area.at(3, 1, 3);
        assertTrue(server.command("setblock " + pos + " " + material).startsWith("Changed the block"), "block placed right after spawn");
        PLACED.put(pos, material);
    }

    @Test
    void areaIsAStoneFloorWithAirAbove(TestServer server, Area area) {
        assertEquals(new CheckResult(true, 1), server.check("if block " + area.at(0, 0, 0) + " stone"));
        assertEquals(new CheckResult(true, 1), server.check("if block " + area.at(15, 0, 15) + " stone"));
        assertEquals(new CheckResult(true, 1), server.check("if block " + area.at(8, 1, 8) + " air"));
        assertEquals(new CheckResult(true, 1), server.check("if block " + area.at(8, 15, 8) + " air"));
    }

    @Test
    void botsOfOneTestGetDistinctNames(Area area) {
        Bot a = area.bot();
        Bot b = area.bot(area.at(2, 1, 2));
        BOT_NAMES.add(a.name());
        BOT_NAMES.add(b.name());
        assertNotEquals(a.name(), b.name());
    }

    @Test
    void holdsAnItemAndRunsCommands(TestServer server, Area area) {
        Bot bot = area.bot();
        BOT_NAMES.add(bot.name());
        bot.hold("minecraft:iron_hoe");
        assertEquals(new CheckResult(true, 1), server.check("if items entity " + bot.name() + " weapon.mainhand minecraft:iron_hoe"));
        assertTrue(bot.run("help"));
        assertFalse(bot.run("nosuchcommand"));
    }

    @Test
    void usesABlockWithTheHeldItem(TestServer server, Area area) {
        Bot bot = area.bot();
        BOT_NAMES.add(bot.name());
        Pos above = area.at(4, 1, 4);
        server.command("setblock " + area.at(4, 0, 4) + " grass_block");
        bot.hold("minecraft:iron_hoe");
        bot.use(area.at(4, 0, 4));
        assertEquals(new CheckResult(true, 1), server.check("if block " + area.at(4, 0, 4) + " farmland"));
        assertEquals(new CheckResult(true, 1), server.check("if block " + above + " air"));
    }

    @Test
    void opensAndClosesAContainerAndRejectsOtherBlocks(TestServer server, Area area) {
        Bot bot = area.bot();
        BOT_NAMES.add(bot.name());
        server.command("setblock " + area.at(6, 1, 6) + " chest");
        bot.open(area.at(6, 1, 6));
        bot.close();
        IllegalStateException e = assertThrows(IllegalStateException.class, () -> bot.open(area.at(7, 0, 7)));
        assertTrue(e.getMessage().contains("no container at"), e.getMessage());
    }

    @Test
    void aRemovedBotCanBeRemovedAgainByTheCleanup(TestServer server, Area area) {
        Bot bot = area.bot();
        BOT_NAMES.add(bot.name());
        bot.remove();
        assertOnline(server, bot.name(), false);
    }

    @AfterAll
    static void areasAreDistinctAndBotsAreGone(TestServer server) {
        assertEquals(ORIGINS.size(), Set.copyOf(ORIGINS).size(), "one area per parameterized invocation: " + ORIGINS);
        assertEquals(BOT_NAMES.size(), Set.copyOf(BOT_NAMES).size(), "bot names are unique: " + BOT_NAMES);
        PLACED.forEach((pos, material) ->
                assertEquals(new CheckResult(true, 1), server.check("if block " + pos + " " + material), material + " at " + pos));
        for (String name : BOT_NAMES) assertOnline(server, name, false);
    }
}
