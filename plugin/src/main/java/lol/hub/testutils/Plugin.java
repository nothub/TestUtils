package lol.hub.testutils;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.block.Container;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.craftbukkit.entity.CraftPlayer;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.Arrays;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

public final class Plugin extends JavaPlugin {

    private final Map<String, Player> fakes = new HashMap<>();

    @Override
    public void onDisable() {
        fakes.values().forEach(FakePlayer::remove);
        fakes.clear();
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (args.length < 2) return false;
        String action = args[0].toLowerCase(Locale.ROOT);
        String name = args[1];

        if (action.equals("spawn")) {
            if (fakes.containsKey(name)) {
                sender.sendMessage(name + " already exists");
                return true;
            }
            World world = sender instanceof Player p ? p.getWorld() : getServer().getWorlds().getFirst();
            Location loc = args.length >= 5
                    ? new Location(world, Double.parseDouble(args[2]), Double.parseDouble(args[3]), Double.parseDouble(args[4]))
                    : world.getSpawnLocation();
            fakes.put(name, FakePlayer.spawn(name, loc));
            sender.sendMessage("spawned " + name + " at " + loc.getBlockX() + " " + loc.getBlockY() + " " + loc.getBlockZ());
            return true;
        }

        Player fake = fakes.get(name);
        if (fake == null) {
            sender.sendMessage("no fake player named " + name);
            return true;
        }

        switch (action) {
            case "remove" -> {
                FakePlayer.remove(fakes.remove(name));
                sender.sendMessage("removed " + name);
            }
            case "as" -> {
                if (args.length < 3) return false;
                String cmd = String.join(" ", Arrays.copyOfRange(args, 2, args.length));
                sender.sendMessage(name + " ran /" + cmd + ": " + fake.performCommand(cmd));
            }
            case "open" -> {
                if (args.length < 5) return false;
                var block = fake.getWorld().getBlockAt(Integer.parseInt(args[2]), Integer.parseInt(args[3]), Integer.parseInt(args[4]));
                if (!(block.getState() instanceof Container container)) {
                    sender.sendMessage("no container at " + args[2] + " " + args[3] + " " + args[4]);
                    return true;
                }
                fake.openInventory(container.getInventory());
                sender.sendMessage(name + " opened " + block.getType().getKey());
            }
            case "close" -> {
                fake.closeInventory();
                sender.sendMessage(name + " closed its inventory view");
            }
            case "use" -> {
                if (args.length < 5) return false;
                Direction face = args.length >= 6 ? Direction.byName(args[5]) : Direction.UP;
                if (face == null) {
                    sender.sendMessage("unknown face " + args[5]);
                    return true;
                }
                // Goes through vanilla's right-click handler, so plugins see the
                // same PlayerInteractEvent a real client's click would fire.
                ServerPlayer nms = ((CraftPlayer) fake).getHandle();
                BlockPos pos = new BlockPos(Integer.parseInt(args[2]), Integer.parseInt(args[3]), Integer.parseInt(args[4]));
                Vec3 hit = Vec3.atCenterOf(pos).relative(face, 0.5);
                var result = nms.gameMode.useItemOn(nms, nms.level(), nms.getMainHandItem(), InteractionHand.MAIN_HAND,
                        new BlockHitResult(hit, face, pos, false));
                sender.sendMessage(name + " used " + nms.getMainHandItem().getItem() + " on " + pos.toShortString() + ": " + result);
            }
            default -> {
                return false;
            }
        }
        return true;
    }
}
