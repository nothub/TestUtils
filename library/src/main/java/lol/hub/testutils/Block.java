package lol.hub.testutils;

import java.util.Map;

/** A block's type and state properties, e.g. {@code minecraft:wheat} with {@code age=7}. Property values are vanilla's names. */
public record Block(String type, Map<String, String> state) {
}
