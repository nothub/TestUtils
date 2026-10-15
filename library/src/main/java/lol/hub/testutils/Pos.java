package lol.hub.testutils;

/** A block position. Prints as {@code "x y z"}, so it drops straight into a command. */
public record Pos(int x, int y, int z) {

    @Override
    public String toString() {
        return x + " " + y + " " + z;
    }
}
