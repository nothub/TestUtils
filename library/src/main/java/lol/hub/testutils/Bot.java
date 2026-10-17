package lol.hub.testutils;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;


/** A fake player. A call the server refuses throws, so a test never has to check a reply. {@link #run} is the exception: it returns the command's success. */
public final class Bot {

    private final TestServer server;
    private final String name;
    private boolean removed;

    private Bot(TestServer server, String name) {
        this.server = server;
        this.name = name;
    }

    static Bot spawn(TestServer server, String name, Pos pos) {
        Bot bot = new Bot(server, name);
        String dimension = bot.tu("spawn " + name + " " + pos + " " + pos.dimension()).get("dimension").getAsString();
        if (!dimension.equals(pos.dimension())) {
            bot.remove();
            throw new IllegalStateException(name + " joined " + dimension + " instead of " + pos.dimension());
        }
        return bot;
    }

    public String name() {
        return name;
    }

    /** Puts the item in the main hand, e.g. {@code minecraft:iron_hoe}. */
    public void hold(String item) {
        String reply = server.command("item replace entity " + name + " weapon.mainhand with " + item);
        if (!reply.startsWith("Replaced 1 slot(s)")) throw new IllegalStateException("hold " + item + " answered: " + reply);
    }

    /** Runs a command as the bot. Returns false when the command is unknown or the bot may not run it. */
    public boolean run(String command) {
        return tu("as " + name + " " + command).get("success").getAsBoolean();
    }

    /** Right-clicks the top face of the block with the main-hand item. */
    public void use(Pos pos) {
        tu("use " + name + " " + pos);
    }

    /** Opens the container at the position. */
    public void open(Pos pos) {
        tu("open " + name + " " + pos);
    }

    /** Closes the bot's open container. */
    public void close() {
        tu("close " + name);
    }

    /** What the bot carries right now. */
    public Inventory inventory() {
        JsonObject reply = tu("inventory " + name);
        JsonElement mainHand = reply.get("main_hand");
        return new Inventory(Item.slots(reply.getAsJsonArray("items")), mainHand.isJsonNull() ? null : Item.parse(mainHand.getAsJsonObject()));
    }

    /** Disconnects the bot. Safe to call twice. */
    public void remove() {
        if (removed) return;
        removed = true;
        tu("remove " + name);
    }

    private JsonObject tu(String args) {
        JsonObject reply = server.tu(args);
        if (!reply.get("ok").getAsBoolean()) {
            throw new IllegalStateException("tu " + args + " failed: " + reply.get("error").getAsString());
        }
        return reply;
    }
}
