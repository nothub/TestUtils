package lol.hub.testutils;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import com.google.gson.JsonArray;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import io.papermc.paper.datacomponent.DataComponentTypes;
import org.bukkit.Location;
import org.bukkit.NamespacedKey;
import org.bukkit.World;
import org.bukkit.block.Container;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.craftbukkit.block.data.CraftBlockData;
import org.bukkit.craftbukkit.entity.CraftPlayer;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

public final class Plugin extends JavaPlugin {

    private static final Map<String, String> USAGE = new LinkedHashMap<>();

    static {
        USAGE.put("spawn", "spawn <name> [x y z [dimension]]");
        USAGE.put("remove", "remove <name>");
        USAGE.put("as", "as <name> <command...>");
        USAGE.put("open", "open <name> <x> <y> <z>");
        USAGE.put("close", "close <name>");
        USAGE.put("use", "use <name> <x> <y> <z> [face]");
        USAGE.put("inventory", "inventory <name>");
        USAGE.put("block", "block <x> <y> <z> [dimension]");
        USAGE.put("container", "container <x> <y> <z> [dimension]");
    }

    /** A reply the caller can fix: it goes out as {"ok":false,"error":...}, not as an exception. */
    private static final class CommandError extends RuntimeException {
        CommandError(String message) {
            super(message, null, false, false);
        }
    }

    private final Map<String, Player> fakes = new HashMap<>();

    @Override
    public void onDisable() {
        fakes.values().forEach(FakePlayer::remove);
        fakes.clear();
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        JsonObject reply = new JsonObject();
        try {
            reply.addProperty("ok", true);
            run(sender, args, reply);
        } catch (CommandError e) {
            reply = new JsonObject();
            reply.addProperty("ok", false);
            reply.addProperty("error", e.getMessage());
        }
        sender.sendMessage(reply.toString());
        return true;
    }

    private void run(CommandSender sender, String[] args, JsonObject reply) {
        if (args.length == 0) throw new CommandError("usage: tu <" + String.join("|", USAGE.keySet()) + "> <name> ...");
        String action = args[0].toLowerCase(Locale.ROOT);
        if (!USAGE.containsKey(action)) {
            throw new CommandError("unknown action '" + args[0] + "', expected " + String.join("|", USAGE.keySet()));
        }
        switch (action) {
            case "spawn" -> arity(action, args, 2, 5, 6);
            case "remove", "close", "inventory" -> arity(action, args, 2);
            case "open" -> arity(action, args, 5);
            case "block", "container" -> arity(action, args, 4, 5);
            case "use" -> arity(action, args, 5, 6);
            case "as" -> {
                if (args.length < 3) throw usage(action);
            }
            default -> throw new AssertionError(action);
        }
        if (action.equals("block") || action.equals("container")) {
            query(action, sender, args, reply);
            return;
        }
        String name = args[1];
        reply.addProperty("name", name);

        if (action.equals("spawn")) {
            if (fakes.containsKey(name)) throw new CommandError(name + " already exists");
            World world = args.length == 6 ? dimension(args[5]) : worldOf(sender);
            Location loc = args.length >= 5
                    ? new Location(world, number(args[2]), number(args[3]), number(args[4]))
                    : world.getSpawnLocation();
            requireInside(loc);
            Player fake = FakePlayer.spawn(name, loc);
            fakes.put(name, fake);
            reply.addProperty("x", loc.getBlockX());
            reply.addProperty("y", loc.getBlockY());
            reply.addProperty("z", loc.getBlockZ());
            reply.addProperty("dimension", fake.getWorld().getKey().toString());
            return;
        }

        Player fake = fakes.get(name);
        if (fake == null) throw new CommandError("no fake player named " + name);

        switch (action) {
            case "remove" -> FakePlayer.remove(fakes.remove(name));
            case "as" -> {
                String cmd = String.join(" ", Arrays.copyOfRange(args, 2, args.length));
                reply.addProperty("command", cmd);
                reply.addProperty("success", fake.performCommand(cmd));
            }
            case "open" -> {
                int x = integer(args[2]), y = integer(args[3]), z = integer(args[4]);
                requireInside(new Location(fake.getWorld(), x, y, z));
                var block = fake.getWorld().getBlockAt(x, y, z);
                if (!(block.getState() instanceof Container container)) {
                    throw new CommandError("no container at " + x + " " + y + " " + z);
                }
                fake.openInventory(container.getInventory());
                reply.addProperty("block", block.getType().getKey().toString());
            }
            case "close" -> fake.closeInventory();
            case "inventory" -> {
                ItemStack hand = fake.getInventory().getItemInMainHand();
                reply.add("main_hand", hand.isEmpty() ? JsonNull.INSTANCE : describe(hand));
                reply.add("items", slots(fake.getInventory().getContents()));
            }
            case "use" -> {
                BlockPos pos = new BlockPos(integer(args[2]), integer(args[3]), integer(args[4]));
                requireInside(new Location(fake.getWorld(), pos.getX(), pos.getY(), pos.getZ()));
                Direction face = args.length == 6 ? Direction.byName(args[5]) : Direction.UP;
                if (face == null) throw new CommandError("unknown face " + args[5]);
                reply.addProperty("item", fake.getInventory().getItemInMainHand().getType().getKey().toString());
                JsonArray posJson = new JsonArray();
                posJson.add(pos.getX());
                posJson.add(pos.getY());
                posJson.add(pos.getZ());
                reply.add("pos", posJson);
                // Goes through vanilla's right-click handler, so plugins see the
                // same PlayerInteractEvent a real client's click would fire.
                ServerPlayer nms = ((CraftPlayer) fake).getHandle();
                Vec3 hit = Vec3.atCenterOf(pos).relative(face, 0.5);
                var result = nms.gameMode.useItemOn(nms, nms.level(), nms.getMainHandItem(), InteractionHand.MAIN_HAND,
                        new BlockHitResult(hit, face, pos, false));
                reply.addProperty("result", result.getClass().getSimpleName());
            }
            default -> throw new AssertionError(action);
        }
    }

    private World worldOf(CommandSender sender) {
        return sender instanceof Player p ? p.getWorld() : getServer().getWorlds().getFirst();
    }

    private World dimension(String id) {
        NamespacedKey key;
        try {
            key = NamespacedKey.fromString(id);
        } catch (IllegalArgumentException e) {
            // "minecraft:" and ":" get past the null checks and fail validation of the empty key.
            throw new CommandError("not a dimension id: " + id);
        }
        if (key == null) throw new CommandError("not a dimension id: " + id);
        World world = getServer().getWorld(key);
        if (world == null) throw new CommandError("unknown dimension " + id);
        return world;
    }

    /** Answers for a block position, which needs no fake player. */
    private void query(String action, CommandSender sender, String[] args, JsonObject reply) {
        World world = args.length == 5 ? dimension(args[4]) : worldOf(sender);
        int x = integer(args[1]), y = integer(args[2]), z = integer(args[3]);
        requireInside(new Location(world, x, y, z));
        var block = world.getBlockAt(x, y, z);
        JsonArray pos = new JsonArray();
        pos.add(x);
        pos.add(y);
        pos.add(z);
        reply.add("pos", pos);
        if (action.equals("block")) {
            JsonObject state = new JsonObject();
            ((CraftBlockData) block.getBlockData()).getState().getValues()
                    .forEach(value -> state.addProperty(value.property().getName(), value.valueName()));
            reply.addProperty("type", block.getType().getKey().toString());
            reply.add("state", state);
        } else {
            if (!(block.getState() instanceof Container container)) {
                throw new CommandError("no container at " + x + " " + y + " " + z);
            }
            reply.addProperty("block", block.getType().getKey().toString());
            reply.addProperty("size", container.getInventory().getSize());
            reply.add("items", slots(container.getInventory().getContents()));
        }
    }

    /** The non-empty slots, numbered by their index in the inventory. */
    private static JsonArray slots(ItemStack[] contents) {
        JsonArray items = new JsonArray();
        for (int slot = 0; slot < contents.length; slot++) {
            if (contents[slot] == null || contents[slot].isEmpty()) continue;
            JsonObject item = describe(contents[slot]);
            item.addProperty("slot", slot);
            items.add(item);
        }
        return items;
    }

    private static JsonObject describe(ItemStack stack) {
        JsonObject item = new JsonObject();
        item.addProperty("id", stack.getType().getKey().toString());
        item.addProperty("count", stack.getAmount());
        item.addProperty("damage", stack.getDataOrDefault(DataComponentTypes.DAMAGE, 0));
        item.addProperty("item_model", stack.getData(DataComponentTypes.ITEM_MODEL).asString());
        return item;
    }

    private static CommandError usage(String action) {
        return new CommandError("usage: tu " + USAGE.get(action));
    }

    private static void arity(String action, String[] args, int... allowed) {
        if (Arrays.stream(allowed).noneMatch(n -> n == args.length)) throw usage(action);
    }

    private static int integer(String text) {
        try {
            return Integer.parseInt(text);
        } catch (NumberFormatException e) {
            throw new CommandError("not an integer: " + text);
        }
    }

    private static double number(String text) {
        try {
            double value = Double.parseDouble(text);
            if (Double.isFinite(value)) return value;
        } catch (NumberFormatException ignored) {
            // reported below
        }
        throw new CommandError("not a number: " + text);
    }

    // Past the world border or height limits the server logs an ERROR, and far enough out it crashes.
    private static void requireInside(Location loc) {
        World world = loc.getWorld();
        if (!world.getWorldBorder().isInside(loc) || loc.getY() < world.getMinHeight() || loc.getY() >= world.getMaxHeight()) {
            throw new CommandError("outside the world: " + loc.getX() + " " + loc.getY() + " " + loc.getZ());
        }
    }
}
