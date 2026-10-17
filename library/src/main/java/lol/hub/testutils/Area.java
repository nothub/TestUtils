package lol.hub.testutils;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.function.BiConsumer;

/**
 * One test's own piece of the shared world: a force-loaded chunk with a stone floor and air above.
 * Areas are spaced far apart, so a plugin that scans around a player never reaches another test's blocks.
 * In the Nether and the End the area is a sealed box of barriers, so their terrain, lava and mobs stay out.
 * Closing the area removes its bots.
 */
public final class Area implements AutoCloseable {

    private static final int SIZE = 16;
    /** Block y of the floor. The flat world's grass is at the same height and gets replaced. */
    private static final int FLOOR_Y = 63;
    // Both are multiples of 16, so every origin is chunk-aligned and the area is exactly one chunk.
    private static final int SPACING = 128;
    private static final int BASE = 10_000;
    private static final Set<String> SEALED = Set.of("minecraft:the_nether", "minecraft:the_end");
    /** Air blocks between the floor and the ceiling of a sealed area. */
    private static final int HEIGHT = 32;

    private final TestServer server;
    private final String dimension;
    private final int x0;
    private final int z0;
    private final List<Bot> bots = new ArrayList<>();

    Area(TestServer server, int index, String dimension) {
        this.server = server;
        this.dimension = dimension;
        this.x0 = BASE + index * SPACING;
        this.z0 = BASE;
        // The chunk stays loaded after the test, so a later test can still read this area's blocks. Indexes are never reused.
        command("forceload add " + x0 + " " + z0);
        if (SEALED.contains(dimension)) seal(index);
        // One chunk of floor is 256 blocks, far under vanilla's 32768-block fill cap.
        String filled = command("fill " + at(0, 0, 0) + " " + at(SIZE - 1, 0, SIZE - 1) + " stone");
        if (!filled.startsWith("Successfully filled")) {
            throw new IllegalStateException("area " + index + " has no floor, fill answered: " + filled);
        }
    }

    private void seal(int index) {
        // The walls stand in the neighbour chunks, and fill only reaches loaded chunks.
        forEachNeighbour((x, z) -> command("forceload add " + x + " " + z));
        // One hollow fill puts up walls and ceiling and clears the inside within one tick, so lava gets no tick to flow in.
        // 18x34x18 blocks, under vanilla's 32768-block fill cap. The floor replaces the bottom layer inside.
        String walls = command("fill " + at(-1, 0, -1) + " " + at(SIZE, HEIGHT + 1, SIZE) + " barrier hollow");
        if (!walls.startsWith("Successfully filled")) {
            throw new IllegalStateException("area " + index + " has no walls, fill answered: " + walls);
        }
        // Plants whose ground the fill replaced drop as items, also inside the walls. Those wake up with a player nearby
        // and get pushed out of the barrier into the box, so the kill covers the walls and ceiling too.
        Pos corner = at(-1, 0, -1);
        String box = "@e[type=!minecraft:player,x=" + corner.x() + ",y=" + corner.y() + ",z=" + corner.z()
                + ",dx=" + (SIZE + 1) + ",dy=" + (HEIGHT + 1) + ",dz=" + (SIZE + 1) + "]";
        // What a killed entity drops are new entities, which the second kill removes.
        command("kill " + box);
        command("kill " + box);
        forEachNeighbour((x, z) -> command("forceload remove " + x + " " + z));
    }

    private void forEachNeighbour(BiConsumer<Integer, Integer> chunk) {
        for (int dx = -SIZE; dx <= SIZE; dx += SIZE) {
            for (int dz = -SIZE; dz <= SIZE; dz += SIZE) {
                if (dx != 0 || dz != 0) chunk.accept(x0 + dx, z0 + dz);
            }
        }
    }

    /** A block of this area. The floor is {@code dy} 0 and the first air block above it is 1. */
    public Pos at(int dx, int dy, int dz) {
        return new Pos(x0 + dx, FLOOR_Y + dy, z0 + dz, dimension);
    }

    /** Runs a console command in this area's dimension and returns everything it wrote back. */
    public String command(String command) {
        return server.command("execute in " + dimension + " run " + command);
    }

    /** Like {@link TestServer#check}, in this area's dimension. */
    public CheckResult check(String condition) {
        return server.check("in " + dimension + " ", condition);
    }

    /** Spawns a bot standing in the middle of the area. */
    public Bot bot() {
        return bot(at(SIZE / 2, 1, SIZE / 2));
    }

    public Bot bot(Pos pos) {
        Bot bot = Bot.spawn(server, "tu" + server.nextBotNumber(), pos);
        bots.add(bot);
        return bot;
    }

    @Override
    public void close() {
        // One failed removal must not leave the other bots online.
        RuntimeException failure = null;
        for (Bot bot : bots) {
            try {
                bot.remove();
            } catch (RuntimeException e) {
                if (failure == null) failure = e;
                else failure.addSuppressed(e);
            }
        }
        bots.clear();
        if (failure != null) throw failure;
    }
}
