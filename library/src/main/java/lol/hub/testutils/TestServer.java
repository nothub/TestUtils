package lol.hub.testutils;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.google.gson.JsonParser;

import java.io.IOException;
import java.security.SecureRandom;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.time.Instant;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.LinkedHashSet;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;

/** A real Paper server in a fresh temp directory, controlled over RCON. */
public final class TestServer implements AutoCloseable {

    private static final int MAX_PORT_ATTEMPTS = 5;
    // A cold start downloads and patches the vanilla jar first.
    private static final Duration READY_TIMEOUT = Duration.ofMinutes(5);
    private static final int EXCERPT_LINES = 40;
    private static final Duration STOP_TIMEOUT = Duration.ofSeconds(60);
    private static final Duration MARKER_TIMEOUT = Duration.ofSeconds(30);
    // Relative to the test JVM's working directory, which Gradle sets to the project directory.
    static final Path LOG_COPY = Path.of("build", "testutils", "server.log");

    /** Block y of the grass on top of every flat dimension, and of an area's floor, which replaces it. */
    static final int SURFACE_Y = 63;
    private static final int OVERWORLD_MIN_Y = -64;

    /** A command that fixes part of the world, the query that reads it back, and what the query must answer. */
    private record WorldSetting(String set, String query, String expected) {

        static WorldSetting gamerule(String name, String value) {
            return new WorldSetting("gamerule " + name + " " + value, "gamerule " + name,
                    "Game rule " + name + " is currently set to " + value);
        }
    }

    // Game rules, difficulty and time are per dimension.
    private static final List<WorldSetting> WORLD_SETTINGS = List.of(
            WorldSetting.gamerule("random_tick_speed", "0"),
            WorldSetting.gamerule("advance_time", "false"),
            WorldSetting.gamerule("spawn_mobs", "false"),
            new WorldSetting("difficulty peaceful", "difficulty", "The difficulty is Peaceful"));
    private static final WorldSetting TIME = new WorldSetting("time set 6000", "time query day", "Timeline minecraft:day is at 6000 tick(s)");
    // How the Nether (no clock) and the End (its own clock) answer the time query. Any other reply goes on to set the time,
    // so a reworded reply fails the read-back instead of skipping the time everywhere.
    private static final List<String> NO_DAY_REPLIES = List.of("There is no default clock in dimension ", "Timeline minecraft:day is not valid for clock ");
    private static final List<String> VANILLA_DIMENSIONS = List.of("minecraft:overworld", "minecraft:the_nether", "minecraft:the_end");

    /** The server could not bind a port we picked for it, because another process took it first. */
    private static final class PortInUse extends Exception {
    }

    private final Path dir;
    private final Path logCopy;
    private final int rconPort;
    private final Process process;
    private final Rcon rcon;
    private final LogWatcher watcher;
    private final AtomicInteger nextAreaIndex = new AtomicInteger();
    private final AtomicInteger nextBotCounter = new AtomicInteger();
    private final Duration bootTime;
    private final boolean coldRepo;
    private final ServerRegistry.Registration registration;
    private final List<String> dimensions;

    private TestServer(Path dir, Path logCopy, int rconPort, Process process, Rcon rcon, LogWatcher watcher, Duration boot, boolean coldRepo,
                       ServerRegistry.Registration registration, List<String> dimensions) {
        this.registration = registration;
        this.dimensions = dimensions;
        this.bootTime = boot;
        this.coldRepo = coldRepo;
        this.dir = dir;
        this.logCopy = logCopy;
        this.rconPort = rconPort;
        this.process = process;
        this.rcon = rcon;
        this.watcher = watcher;
    }

    static TestServer start() throws IOException {
        return start(PortSource.LOOPBACK, LOG_COPY);
    }

    static TestServer start(PortSource ports, Path logCopy) throws IOException {
        return start(ports, logCopy, Path.of(property("testutils.paperRepo")));
    }

    /** Boots again on new ports when a bind fails, which happens when parallel runs pick the same port. */
    static TestServer start(PortSource ports, Path logCopy, Path paperRepo) throws IOException {
        return start(ports, logCopy, Path.of(property("testutils.paperJar")), paperRepo, READY_TIMEOUT);
    }

    static TestServer start(PortSource ports, Path logCopy, Path paperJar, Path paperRepo, Duration readyTimeout) throws IOException {
        long begin = System.nanoTime();
        ServerRegistry registry = ServerRegistry.at(paperRepo);
        registry.reapOrphans();
        boolean cold = PaperRepo.patch(paperJar, paperRepo, List.of());
        Launch launch = new Launch(logCopy, paperJar, paperRepo, registry, readyTimeout, VANILLA_DIMENSIONS);
        for (int attempt = 0; attempt < MAX_PORT_ATTEMPTS; attempt++) {
            try {
                return boot(ports.next(), ports.next(), launch, begin, cold);
            } catch (PortInUse e) {
                // Next attempt picks new ports.
            }
        }
        throw new IllegalStateException("server could not bind its ports in " + MAX_PORT_ATTEMPTS + " attempts");
    }

    /** What one start needs besides its ports. */
    private record Launch(Path logCopy, Path paperJar, Path paperRepo, ServerRegistry registry, Duration readyTimeout, List<String> dimensions) {
    }

    private static TestServer boot(int gamePort, int rconPort, Launch launch, long begin, boolean cold) throws IOException, PortInUse {
        Path dir = Files.createTempDirectory("testutils-");
        Path plugins = Files.createDirectory(dir.resolve("plugins"));
        // The plugin under test may be TestUtils itself: a second copy would clash on the plugin name.
        Set<Path> jars = new LinkedHashSet<>();
        Path pluginJar = Path.of(property("testutils.pluginJar")).toRealPath();
        jars.add(pluginJar);
        // Read before the slow boot, so a jar without plugin.yml fails at once.
        PluginErrors pluginErrors = PluginErrors.forJar(pluginJar);
        jars.add(Path.of(property("testutils.testutilsJar")).toRealPath());
        for (Path jar : jars) Files.copy(jar, plugins.resolve(jar.getFileName()));

        // A run that lands on another run's RCON port by mistake cannot log in without this.
        byte[] random = new byte[16];
        new SecureRandom().nextBytes(random);
        String rconPassword = HexFormat.of().formatHex(random);
        Files.writeString(dir.resolve("eula.txt"), "eula=true\n");
        // The checker reaches out at boot and logs ERROR lines when offline. _version stops Paper from migrating the file.
        Files.createDirectory(dir.resolve("config"));
        Files.writeString(dir.resolve("config/paper-global.yml"), "_version: 31\nupdate-checker:\n  enabled: false\n");
        Files.writeString(dir.resolve("server.properties"), String.join("\n",
                "online-mode=false",
                "white-list=false",
                "server-ip=127.0.0.1",
                "server-port=" + gamePort,
                "enable-query=false",
                "enable-rcon=true",
                "rcon.port=" + rconPort,
                "rcon.password=" + rconPassword,
                "level-type=minecraft:flat",
                "generator-settings=" + flatWorld(OVERWORLD_MIN_Y),
                ""));

        Path java = Path.of(System.getProperty("java.home"), "bin", "java");
        String runMarker = ServerRegistry.newMarker();
        Process process = new ProcessBuilder(java.toString(), PaperRepo.jvmArg(launch.paperRepo()), "-D" + ServerRegistry.MARKER_PROPERTY + "=" + runMarker, "-jar", launch.paperJar().toString(), "--nogui")
                .directory(dir.toFile())
                .redirectErrorStream(true)
                .redirectOutput(dir.resolve("server.log").toFile())
                .start();
        ServerRegistry.Registration registration;
        try {
            // Fails once the JVM is shutting down, e.g. a test thread that kept going after Ctrl-C.
            registration = launch.registry().register(process, runMarker);
        } catch (IOException | RuntimeException e) {
            process.destroyForcibly();
            throw e;
        }
        try {
            awaitReady(process, dir.resolve("server.log"), rconPort, launch.readyTimeout());
            Rcon rcon = connect(rconPort, rconPassword);
            try {
                setUpWorld(rcon, launch.dimensions());
            } catch (IOException | RuntimeException e) {
                rcon.close();
                throw e;
            }
            LogWatcher watcher = new LogWatcher(dir.resolve("server.log"), pluginErrors, marker -> {
                try {
                    rcon.command("say " + marker);
                } catch (IOException e) {
                    throw new IllegalStateException("RCON command failed: say " + marker, e);
                }
            }, MARKER_TIMEOUT);
            return new TestServer(dir, launch.logCopy(), rconPort, process, rcon, watcher, Duration.ofNanos(System.nanoTime() - begin), cold, registration,
                    launch.dimensions());
        } catch (PortInUse e) {
            process.destroyForcibly();
            registration.close();
            deleteTree(dir);
            throw e;
        } catch (IOException | RuntimeException e) {
            process.destroyForcibly();
            registration.close();
            throw e;
        }
    }

    /**
     * Flat generator settings whose grass lands on {@link #SURFACE_Y} in a dimension starting at {@code minY}, which lies below it.
     * Nothing here is random: no fluids, no features, no structures, so every test sees the same ground.
     */
    static JsonObject flatWorld(int minY) {
        JsonArray layers = new JsonArray();
        layers.add(layer("minecraft:bedrock", 1));
        int dirt = SURFACE_Y - minY - 1;
        if (dirt > 0) layers.add(layer("minecraft:dirt", dirt));
        layers.add(layer("minecraft:grass_block", 1));
        JsonObject settings = new JsonObject();
        settings.add("layers", layers);
        settings.addProperty("biome", "minecraft:plains");
        settings.addProperty("features", false);
        settings.addProperty("lakes", false);
        settings.add("structure_overrides", new JsonArray());
        return settings;
    }

    private static JsonObject layer(String block, int height) {
        JsonObject layer = new JsonObject();
        layer.addProperty("block", block);
        layer.addProperty("height", height);
        return layer;
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
        return check("", condition);
    }

    /** {@code subcommands} go between {@code execute} and the condition, such as {@code "in minecraft:the_nether "}. */
    CheckResult check(String subcommands, String condition) {
        if (!condition.startsWith("if ") && !condition.startsWith("unless ")) {
            throw new IllegalArgumentException("a check starts with 'if' or 'unless': " + condition);
        }
        String line = "execute " + subcommands + condition;
        try {
            return CheckResult.parse(command(line));
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException(line + " answered with " + e.getMessage(), e);
        }
    }

    /** The block at the position in its dimension, loading its chunk if needed. */
    public Block block(Pos pos) {
        JsonObject reply = query("block " + pos + " " + pos.dimension());
        Map<String, String> state = new HashMap<>();
        reply.getAsJsonObject("state").asMap().forEach((property, value) -> state.put(property, value.getAsString()));
        return new Block(reply.get("type").getAsString(), state);
    }

    /** The slots of the container at the position in its dimension. Throws when the block is no container. */
    public Container container(Pos pos) {
        JsonObject reply = query("container " + pos + " " + pos.dimension());
        return new Container(reply.get("block").getAsString(), reply.get("size").getAsInt(), Item.slots(reply.getAsJsonArray("items")));
    }

    /** The type values of the dimension with that id, like {@code minecraft:the_nether}. Throws for an unknown dimension. */
    public DimensionType dimension(String id) {
        JsonObject reply = query("dimension " + id);
        return new DimensionType(reply.get("dimension").getAsString(), reply.get("monster_spawn_block_light_limit").getAsInt(),
                reply.get("has_ender_dragon_fight").getAsBoolean());
    }

    private JsonObject query(String args) {
        JsonObject reply = tu(args);
        if (!reply.get("ok").getAsBoolean()) throw new IllegalStateException("tu " + args + " failed: " + reply.get("error").getAsString());
        return reply;
    }

    /** A fresh overworld {@link Area} no other test has used. Close it to remove its bots. */
    public Area area() {
        return area(Pos.OVERWORLD);
    }

    /** A fresh {@link Area} in the dimension with that id, like {@code minecraft:the_nether}. Close it to remove its bots. */
    public Area area(String dimension) {
        return new Area(this, nextAreaIndex.getAndIncrement(), dimension);
    }

    /** The ids of the dimensions this server's launch set up. */
    List<String> dimensions() {
        return dimensions;
    }

    int nextBotNumber() {
        return nextBotCounter.getAndIncrement();
    }

    /** Errors of the plugin under test logged since the previous call, or since boot on the first call. */
    List<String> pluginErrors() {
        return watcher.pluginErrors();
    }

    long pid() {
        return process.pid();
    }

    int rconPort() {
        return rconPort;
    }

    public Path logFile() {
        return dir.resolve("server.log");
    }

    @Override
    public void close() throws IOException {
        long closing = System.nanoTime();
        Duration teardown = Duration.ZERO;
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
            teardown = Duration.ofNanos(System.nanoTime() - closing);
            registration.close();
        }
        try {
            Files.createDirectories(logCopy.getParent());
            Files.copy(logFile(), logCopy, StandardCopyOption.REPLACE_EXISTING);
            reportTiming(teardown);
        } finally {
            deleteTree(dir);
        }
    }

    /** Boot plus teardown is what a test run pays for the server, so CI can read it next to the log. */
    private void reportTiming(Duration teardown) throws IOException {
        String report = String.format(Locale.ROOT, "boot_seconds=%.1f%ntotal_seconds=%.1f%nteardown_seconds=%.1f%npaper_cache=%s%n",
                bootTime.toMillis() / 1000.0, bootTime.plus(teardown).toMillis() / 1000.0, teardown.toMillis() / 1000.0, coldRepo ? "cold" : "warm");
        Files.writeString(logCopy.resolveSibling("timing.txt"), report);
        System.out.println("TestUtils server " + report.replace('\n', ' ').strip());
    }

    private static void deleteTree(Path dir) throws IOException {
        try (Stream<Path> files = Files.walk(dir)) {
            for (Path p : (Iterable<Path>) files.sorted(Comparator.reverseOrder())::iterator) Files.delete(p);
        }
    }

    /** A rule that was renamed in a new MC version fails here, with the reply, instead of silently doing nothing. */
    private static void setUpWorld(Rcon rcon, List<String> dimensions) throws IOException {
        for (String dimension : dimensions) {
            String in = "execute in " + dimension + " run ";
            // advance_time is among the settings, so a clock cannot move on after it was set.
            for (WorldSetting setting : WORLD_SETTINGS) apply(rcon, in, setting);
            String time = rcon.command(in + TIME.query()).strip();
            if (NO_DAY_REPLIES.stream().noneMatch(time::startsWith)) apply(rcon, in, TIME);
        }
    }

    private static void apply(Rcon rcon, String in, WorldSetting setting) throws IOException {
        String setReply = rcon.command(in + setting.set()).strip();
        String reply = rcon.command(in + setting.query()).strip();
        if (!reply.equals(setting.expected())) {
            throw new IllegalStateException("'" + in + setting.set() + "' answered '" + setReply + "' and did not take: '"
                    + in + setting.query() + "' answered '" + reply + "', expected '" + setting.expected() + "'");
        }
    }

    /**
     * Ready means "Done (" and our own "RCON running on" line. A taken RCON port only logs a warning and the
     * server still finishes booting, so without the second line we would talk to whoever holds the port.
     */
    private static void awaitReady(Process process, Path log, int rconPort, Duration timeout) throws IOException, PortInUse {
        Instant deadline = Instant.now().plus(timeout);
        while (Instant.now().isBefore(deadline)) {
            String text = Files.exists(log) ? Files.readString(log) : "";
            if (text.contains("FAILED TO BIND TO PORT")) throw new PortInUse();
            if (text.contains("Done (")) {
                if (text.contains("RCON running on 127.0.0.1:" + rconPort)) return;
                throw new PortInUse();
            }
            if (!process.isAlive()) throw new IllegalStateException("server exited early with code " + process.exitValue() + "." + excerpt(log));
            Waiting.pause(Duration.ofMillis(100));
        }
        throw new IllegalStateException("server not ready after " + timeout + "." + excerpt(log));
    }

    /** The reason a boot failed is in the last lines of the log, so the failure carries them instead of a path to look up. */
    private static String excerpt(Path log) throws IOException {
        // Decoded leniently: a crashing server can write bytes that are no valid UTF-8.
        List<String> lines = Files.exists(log) ? new String(Files.readAllBytes(log), java.nio.charset.StandardCharsets.UTF_8).lines().toList() : List.of();
        List<String> tail = lines.subList(Math.max(0, lines.size() - EXCERPT_LINES), lines.size());
        return " Last " + tail.size() + " lines of " + log + ":\n" + String.join("\n", tail);
    }

    private static Rcon connect(int port, String password) throws IOException {
        for (int attempt = 0; ; attempt++) {
            try {
                return new Rcon(port, password);
            } catch (IOException e) {
                if (attempt >= 50) throw e;
                Waiting.pause(Duration.ofMillis(100));
            }
        }
    }

    private static String property(String name) {
        String value = System.getProperty(name);
        if (value == null) throw new IllegalStateException("system property " + name + " is not set");
        return value;
    }
}
