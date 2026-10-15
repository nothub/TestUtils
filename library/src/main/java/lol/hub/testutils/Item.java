package lol.hub.testutils;

/** A stack as the server reports it. {@code damage} is 0 for an item that can't be damaged. */
public record Item(String id, int count, int damage, String itemModel) {
}
