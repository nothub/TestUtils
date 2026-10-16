package lol.hub.testutils;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.util.Map;
import java.util.TreeMap;

/** A stack as the server reports it. {@code damage} is 0 for an item that can't be damaged. */
public record Item(String id, int count, int damage, String itemModel) {

    static Item parse(JsonObject json) {
        return new Item(json.get("id").getAsString(), json.get("count").getAsInt(), json.get("damage").getAsInt(),
                json.get("item_model").getAsString());
    }

    /** The {@code items} array of a reply, keyed by each entry's {@code slot}. */
    static Map<Integer, Item> slots(JsonArray items) {
        Map<Integer, Item> slots = new TreeMap<>();
        for (JsonElement entry : items) {
            JsonObject json = entry.getAsJsonObject();
            slots.put(json.get("slot").getAsInt(), parse(json));
        }
        return slots;
    }
}
