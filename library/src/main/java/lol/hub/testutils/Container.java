package lol.hub.testutils;

import java.util.Map;

/** The non-empty slots of the container block at a position. {@code size} counts every slot, empty ones too. */
public record Container(String block, int size, Map<Integer, Item> slots) {
}
