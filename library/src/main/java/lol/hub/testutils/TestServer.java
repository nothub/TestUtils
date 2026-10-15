package lol.hub.testutils;

import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.google.gson.JsonParser;

import java.io.IOException;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;

/** A real Paper server in a fresh temp directory, controlled over RCON. */
public final class TestServer implements AutoCloseable {

    private static final String RCON_PASSWORD = "testutils";
    // A cold start downloads and patches the vanilla jar first.
    private static final Duration READY_TIMEOUT = Duration.ofMinutes(5);
    private static final Duration STOP_TIMEOUT = Duration.ofSeconds(60);

    // Nothing here is random: no fluids, no features, no structures, so every test sees the same ground (top block y=63).
    private static final String FLAT_WORLD = "{\"layers\":[{\"block\":\"minecraft:bedrock\",\"height\":1},"
            + "{\"block\":\"minecraft:dirt\",\"height\":126},{\"block\":\"minecraft:grass_block\",\"height\":1}],"
            + "\"biome\":\"minecraft:plains\",\"features\":false,\"lakes\":false,\"structure_overrides\":[]}";

    /** A command that fixes part of the world, the query that reads it back, and what the query must answer. */
    private record WorldSetting(String set, String query, String expected) {

        static WorldSetting gamerule(String name, String value) {
            return new WorldSetting("gamerule " + name + " " + value, "gamerule " + name,
                    "Game rule " + name + " is currently set to " + value);
        }
    }

    // advance_time goes before the time, so the clock cannot move on after it was set.
    private static final List<WorldSetting> WORLD_SETTINGS = List.of(
            WorldSetting.gamerule("random_tick_speed", "0"),
            WorldSetting.gamerule("advance_time", "false"),
            WorldSetting.gamerule("spawn_mobs", "false"),
            new WorldSetting("difficulty peaceful", "difficulty", "The difficulty is Peaceful"),
            new WorldSetting("time set 6000", "time query day", "Timeline minecraft:day is at 6000 tick(s)"));

    private final Path dir;
    private final Process process;
    private final Rcon rcon;
    private final AtomicInteger nextAreaIndex = new AtomicInteger();
    private final AtomicInteger nextBotCounter = new AtomicInteger();

    private TestServer(Path dir, Process process, Rcon rcon) {
        this.dir = dir;
        this.process = process;
        this.rcon = rcon;
    }

    static TestServer start() throws IOException {
        Path dir = Files.createTempDirectory("testutils-");
        Path plugins = Files.createDirectory(dir.resolve("plugins"));
        // The plugin under test may be TestUtils itself: a second copy would clash on the plugin name.
        Set<Path> jars = new LinkedHashSet<>();
        jars.add(Path.of(property("testutils.pluginJar")).toRealPath());
        jars.add(Path.of(property("testutils.testutilsJar")).toRealPath());
        for (Path jar : jars) Files.copy(jar, plugins.resolve(jar.getFileName()));

        int gamePort = freePort();
        int rconPort = freePort();
        Files.writeString(dir.resolve("eula.txt"), "eula=true\n");
        Files.writeString(dir.resolve("server.properties"), String.join("\n",
                "online-mode=false",
                "white-list=false",
                "server-ip=127.0.0.1",
                "server-port=" + gamePort,
                "enable-rcon=true",
                "rcon.port=" + rconPort,
                "rcon.password=" + RCON_PASSWORD,
                "level-type=minecraft:flat",
                "generator-settings=" + FLAT_WORLD,
                ""));

        Path java = Path.of(System.getProperty("java.home"), "bin", "java");
        Process process = new ProcessBuilder(java.toString(), "-jar", property("testutils.paperJar"), "--nogui")
                .directory(dir.toFile())
                .redirectErrorStream(true)
                .redirectOutput(dir.resolve("server.log").toFile())
                .start();
        try {
            awaitReady(process, dir.resolve("server.log"));
            Rcon rcon = connect(rconPort);
            try {
                setUpWorld(rcon);
            } catch (IOException | RuntimeException e) {
                rcon.close();
                throw e;
            }
            return new TestServer(dir, process, rcon);
        } catch (IOException | RuntimeException e) {
            process.destroyForcibly();
            throw e;
        }
    }

    /** Runs a console command and returns everything it wrote back. */
    public String command(String command) {
        try {
            return rcon.command(command);
        } catch (IOException e) {
            throw new IllegalStateException("RCON command failed: " + command, e);
        }
    }

    /**
     * Runs a {@code tu} command and returns its JSON reply. An {@code "ok": false} reply is returned,
     * not thrown, so tests can assert on errors.
     */
    public JsonObject tu(String args) {
        String reply = command(("tu " + args).stripTrailing()).strip();
        try {
            return JsonParser.parseString(reply).getAsJsonObject();
        } catch (JsonParseException | IllegalStateException e) {
            throw new IllegalStateException("tu " + args + " did not answer with one JSON object: " + reply, e);
        }
    }

    /**
     * Runs {@code execute <condition>}, where the condition starts with {@code if} or {@code unless},
     * and throws on any reply that is not a test result: a typo, an unknown block, an unloaded position.
     */
    public CheckResult check(String condition) {
        if (!condition.startsWith("if ") && !condition.startsWith("unless ")) {
            throw new IllegalArgumentException("a check starts with 'if' or 'unless': " + condition);
        }
        try {
            return CheckResult.parse(command("execute " + condition));
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("execute " + condition + " answered with " + e.getMessage(), e);
        }
    }

    /** A fresh {@link Area} no other test has used. Close it to remove its bots. */
    public Area area() {
        return new Area(this, nextAreaIndex.getAndIncrement());
    }

    int nextBotNumber() {
        return nextBotCounter.getAndIncrement();
    }

    public Path logFile() {
        return dir.resolve("server.log");
    }

    @Override
    public void close() throws IOException {
        try {
            try {
                rcon.command("stop");
            } catch (IOException ignored) {
                // The server may drop the connection while it shuts down.
            }
            rcon.close();
            if (!process.waitFor(STOP_TIMEOUT.toSeconds(), TimeUnit.SECONDS)) process.destroyForcibly().waitFor();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } finally {
            process.destroyForcibly();
        }
        try (Stream<Path> files = Files.walk(dir)) {
            for (Path p : (Iterable<Path>) files.sorted(Comparator.reverseOrder())::iterator) Files.delete(p);
        }
    }

    /** A rule that was renamed in a new MC version fails here, with the reply, instead of silently doing nothing. */
    private static void setUpWorld(Rcon rcon) throws IOException {
        for (WorldSetting setting : WORLD_SETTINGS) {
            String setReply = rcon.command(setting.set()).strip();
            String reply = rcon.command(setting.query()).strip();
            if (!reply.equals(setting.expected())) {
                throw new IllegalStateException("'" + setting.set() + "' answered '" + setReply + "' and did not take: '"
                        + setting.query() + "' answered '" + reply + "', expected '" + setting.expected() + "'");
            }
        }
    }

    private static void awaitReady(Process process, Path log) throws IOException {
        Instant deadline = Instant.now().plus(READY_TIMEOUT);
        while (Instant.now().isBefore(deadline)) {
            if (Files.exists(log) && Files.readString(log).contains("Done (")) return;
            if (!process.isAlive()) throw new IllegalStateException("server exited early, see " + log);
            Waiting.pause(Duration.ofMillis(100));
        }
        throw new IllegalStateException("server not ready after " + READY_TIMEOUT + ", see " + log);
    }

    private static Rcon connect(int port) throws IOException {
        for (int attempt = 0; ; attempt++) {
            try {
                return new Rcon(port, RCON_PASSWORD);
            } catch (IOException e) {
                if (attempt >= 50) throw e;
                Waiting.pause(Duration.ofMillis(100));
            }
        }
    }

    private static int freePort() throws IOException {
        try (ServerSocket socket = new ServerSocket(0, 1, InetAddress.getLoopbackAddress())) {
            return socket.getLocalPort();
        }
    }

    private static String property(String name) {
        String value = System.getProperty(name);
        if (value == null) throw new IllegalStateException("system property " + name + " is not set");
        return value;
    }
}
