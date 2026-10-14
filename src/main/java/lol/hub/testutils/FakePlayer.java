package lol.hub.testutils;

import com.mojang.authlib.GameProfile;
import io.netty.channel.Channel;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.ChannelOutboundHandlerAdapter;
import io.netty.channel.ChannelPromise;
import io.netty.channel.embedded.EmbeddedChannel;
import io.netty.util.ReferenceCountUtil;
import net.minecraft.network.Connection;
import net.minecraft.network.protocol.PacketFlow;
import net.minecraft.network.protocol.game.ClientboundPlayerInfoUpdatePacket;
import net.minecraft.server.level.ClientInformation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.CommonListenerCookie;
import net.minecraft.server.players.PlayerList;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.craftbukkit.CraftServer;
import org.bukkit.craftbukkit.CraftWorld;
import org.bukkit.craftbukkit.entity.CraftPlayer;
import org.bukkit.entity.Player;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;

final class FakePlayer {

    private FakePlayer() {
    }

    static Player spawn(String name, Location loc) {
        // Offline-mode UUID, so a fake player keeps its identity (and any plugin
        // state keyed by UUID) across respawns, same as a real offline-mode join.
        UUID uuid = UUID.nameUUIDFromBytes(("OfflinePlayer:" + name).getBytes(StandardCharsets.UTF_8));
        GameProfile profile = new GameProfile(uuid, name);
        ServerLevel level = ((CraftWorld) loc.getWorld()).getHandle();
        ServerPlayer player = new ServerPlayer(level.getServer(), level, profile, ClientInformation.createDefault());
        player.setPos(loc.getX(), loc.getY(), loc.getZ());

        Connection connection = new Connection(PacketFlow.SERVERBOUND);
        new EmbeddedChannel(new ChannelInitializer<Channel>() {
            @Override
            protected void initChannel(Channel channel) {
                // Closest to the sink, so every outbound packet gets dropped here
                // instead of piling up in EmbeddedChannel's unbounded queue.
                channel.pipeline().addFirst(new ChannelOutboundHandlerAdapter() {
                    @Override
                    public void write(ChannelHandlerContext ctx, Object msg, ChannelPromise promise) {
                        ReferenceCountUtil.release(msg);
                        promise.trySuccess();
                    }
                });
                Connection.configureInMemoryPipeline(channel.pipeline(), PacketFlow.SERVERBOUND);
                connection.configurePacketHandler(channel.pipeline());
            }
        });

        playerList().placeNewPlayer(connection, player, CommonListenerCookie.createInitial(profile, false));

        // placeNewPlayer returns early when the connection isn't accepting
        // messages, which the embedded channel never is. That skips the profile
        // broadcast and the entity tracker registration, so finish both here.
        if (player.suppressTrackerForLogin) {
            player.suppressTrackerForLogin = false;
            Player bukkit = player.getBukkitEntity();
            var packet = ClientboundPlayerInfoUpdatePacket.createPlayerInitializing(List.of(player));
            for (Player online : Bukkit.getOnlinePlayers()) {
                if (online.equals(bukkit) || !online.canSee(bukkit)) continue;
                ((CraftPlayer) online).getHandle().connection.send(packet);
            }
            level.getChunkSource().addEntity(player);
        }

        return player.getBukkitEntity();
    }

    static void remove(Player player) {
        playerList().remove(((CraftPlayer) player).getHandle());
    }

    private static PlayerList playerList() {
        return ((CraftServer) Bukkit.getServer()).getHandle();
    }

}
