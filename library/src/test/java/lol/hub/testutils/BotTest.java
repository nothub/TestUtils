package lol.hub.testutils;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
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
    void spawnsIntoThePositionsDimension(TestServer server) {
        String nether = "execute in minecraft:the_nether run ";
        server.command(nether + "forceload add 1100 1000");
        server.command(nether + "fill 1099 63 999 1101 66 1001 stone");
        server.command(nether + "fill 1100 64 1000 1100 65 1000 air");
        Bot bot = null;
        try {
            bot = Bot.spawn(server, "tu" + server.nextBotNumber(), new Pos(1100, 64, 1000, "minecraft:the_nether"));
            BOT_NAMES.add(bot.name());
            assertEquals(new CheckResult(true, 1),
                    CheckResult.parse(server.command("execute in minecraft:the_nether if entity @a[name=" + bot.name() + ",x=1100,y=64,z=1000,distance=..2]")));
        } finally {
            if (bot != null) bot.remove();
            server.command(nether + "fill 1099 63 999 1101 66 1001 air");
            server.command(nether + "forceload remove 1100 1000");
        }
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
    void inventoryShowsADamagedToolInTheMainHand(TestServer server, Area area) {
        Bot bot = area.bot();
        BOT_NAMES.add(bot.name());
        assertNull(bot.inventory().mainHand());
        assertEquals(Map.of(), bot.inventory().slots());

        server.command("item replace entity " + bot.name() + " weapon.mainhand with minecraft:iron_hoe[minecraft:damage=7]");
        server.command("item replace entity " + bot.name() + " armor.head with minecraft:iron_helmet");
        server.command("item replace entity " + bot.name() + " weapon.offhand with minecraft:dirt[minecraft:item_model=\"minecraft:stone\"]");
        Item hoe = new Item("minecraft:iron_hoe", 1, 7, "minecraft:iron_hoe");
        Inventory inventory = bot.inventory();
        assertEquals(hoe, inventory.mainHand());
        assertEquals(Map.of(0, hoe,
                39, new Item("minecraft:iron_helmet", 1, 0, "minecraft:iron_helmet"),
                40, new Item("minecraft:dirt", 1, 0, "minecraft:stone")), inventory.slots());
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

    // A barrel shows its viewers in its block state. A server closes a menu its player can't use on
    // the player's next tick, so the window is longer than a few ticks.
    @Test
    void anOpenedContainerStaysOpenUntilClosed(TestServer server, Area area) {
        Bot bot = area.bot();
        BOT_NAMES.add(bot.name());
        Pos barrel = area.at(6, 1, 6);
        server.command("setblock " + barrel + " barrel");

        bot.open(barrel);
        Waiting.waitUntil(Duration.ofSeconds(5), () -> server.block(barrel).state().get("open"), "true"::equals);
        Waiting.holdsFor(Duration.ofSeconds(1), () -> server.block(barrel).state().get("open"), "true"::equals);

        bot.close();
        Waiting.waitUntil(Duration.ofSeconds(5), () -> server.block(barrel).state().get("open"), "false"::equals);
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
