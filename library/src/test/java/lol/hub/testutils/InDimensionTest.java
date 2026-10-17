package lol.hub.testutils;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.extension.ParameterResolutionException;

import java.lang.reflect.Parameter;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@ExtendWith(TestServerExtension.class)
class InDimensionTest {

    private static final List<String> BOT_NAMES = new CopyOnWriteArrayList<>();

    private Area beforeEachNether;
    private Area beforeEachOverworld;

    @BeforeEach
    void rememberAreas(@InDimension("minecraft:the_nether") Area nether, Area overworld) {
        beforeEachNether = nether;
        beforeEachOverworld = overworld;
    }

    @Test
    void sameDimensionSharesTheAreaOtherDimensionsGetTheirOwn(@InDimension("minecraft:the_nether") Area nether,
                                                              @InDimension("minecraft:the_end") Area end,
                                                              @InDimension("minecraft:overworld") Area overworld) {
        assertSame(beforeEachNether, nether);
        assertSame(beforeEachOverworld, overworld);
        assertNotSame(nether, end);
        assertEquals("minecraft:the_nether", nether.at(0, 0, 0).dimension());
        assertEquals("minecraft:the_end", end.at(0, 0, 0).dimension());
        assertEquals("minecraft:overworld", overworld.at(0, 0, 0).dimension());

        BOT_NAMES.add(nether.bot().name());
        BOT_NAMES.add(end.bot().name());
    }

    @SuppressWarnings("unused")
    private void askForMissingDimension(@InDimension("tu:missing") Area area) {
    }

    @Test
    void unknownDimensionFailsResolutionNamingIt(TestServer server) throws NoSuchMethodException {
        Parameter parameter = getClass().getDeclaredMethod("askForMissingDimension", Area.class).getParameters()[0];
        ParameterResolutionException e = assertThrows(ParameterResolutionException.class, () -> TestServerExtension.dimension(parameter, server));
        assertTrue(e.getMessage().contains("tu:missing"), e.getMessage());
    }

    @AfterAll
    static void areasInEveryDimensionAreClosed(TestServer server) {
        assertEquals(2, BOT_NAMES.size(), "one bot per non-overworld area: " + BOT_NAMES);
        for (String name : BOT_NAMES) assertEquals(new CheckResult(false, 0), server.check("if entity " + name), name);
    }
}
