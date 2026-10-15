package lol.hub.testutils;

import java.util.Map;

/**
 * A bot's non-empty slots, numbered like Bukkit's {@code PlayerInventory}: 0-8 hotbar, 9-35 bag,
 * 36-39 boots to helmet, 40 off hand. {@code mainHand} is null when the hand is empty.
 */
public record Inventory(Map<Integer, Item> slots, Item mainHand) {
}
