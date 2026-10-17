package lol.hub.testutils;

import java.util.Objects;

/**
 * A block position in a dimension, named by its id like {@code minecraft:the_nether}.
 * Prints as {@code "x y z"}, so it drops straight into a command.
 */
public record Pos(int x, int y, int z, String dimension) {

    static final String OVERWORLD = "minecraft:overworld";

    public Pos {
        Objects.requireNonNull(dimension, "dimension");
    }

    /** A position in the overworld. */
    public Pos(int x, int y, int z) {
        this(x, y, z, OVERWORLD);
    }

    @Override
    public String toString() {
        return x + " " + y + " " + z;
    }
}
