package lol.hub.testutils;

import java.util.ArrayList;
import java.util.List;

/**
 * One test's own piece of the shared world: a force-loaded chunk with a stone floor and air above.
 * Areas are spaced far apart, so a plugin that scans around a player never reaches another test's blocks.
 * Closing the area removes its bots.
 */
public final class Area implements AutoCloseable {

    private static final int SIZE = 16;
    /** Block y of the floor. The flat world's grass is at the same height and gets replaced. */
    private static final int FLOOR_Y = 63;
    // Both are multiples of 16, so every origin is chunk-aligned and the area is exactly one chunk.
    private static final int SPACING = 128;
    private static final int BASE = 10_000;

    private final TestServer server;
    private final int x0;
    private final int z0;
    private final List<Bot> bots = new ArrayList<>();

    Area(TestServer server, int index) {
        this.server = server;
        this.x0 = BASE + index * SPACING;
        this.z0 = BASE;
        // The chunk stays loaded after the test, so a later test can still read this area's blocks. Indexes are never reused.
        server.command("forceload add " + x0 + " " + z0);
        // One chunk of floor is 256 blocks, far under vanilla's 32768-block fill cap.
        String filled = server.command("fill " + at(0, 0, 0) + " " + at(SIZE - 1, 0, SIZE - 1) + " stone");
        if (!filled.startsWith("Successfully filled")) {
            throw new IllegalStateException("area " + index + " has no floor, fill answered: " + filled);
        }
    }

    /** A block of this area. The floor is {@code dy} 0 and the first air block above it is 1. */
    public Pos at(int dx, int dy, int dz) {
        return new Pos(x0 + dx, FLOOR_Y + dy, z0 + dz);
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
