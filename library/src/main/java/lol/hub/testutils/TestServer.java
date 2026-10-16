package lol.hub.testutils;

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
    private static final Duration STOP_TIMEOUT = Duration.ofSeconds(60);
    private static final Duration MARKER_TIMEOUT = Duration.ofSeconds(30);
    // Relative to the test JVM's working directory, which Gradle sets to the project directory.
    static final Path LOG_COPY = Path.of("build", "testutils", "server.log");

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

    private TestServer(Path dir, Path logCopy, int rconPort, Process process, Rcon rcon, LogWatcher watcher, Duration boot, boolean coldRepo,
                       ServerRegistry.Registration registration) {
        this.registration = registration;
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
        long begin = System.nanoTime();
        ServerRegistry registry = ServerRegistry.at(paperRepo);
        registry.reapOrphans();
        boolean cold = PaperRepo.patch(Path.of(property("testutils.paperJar")), paperRepo, List.of());
        for (int attempt = 0; attempt < MAX_PORT_ATTEMPTS; attempt++) {
            try {
                return boot(ports.next(), ports.next(), logCopy, paperRepo, registry, begin, cold);
            } catch (PortInUse e) {
                // Next attempt picks new ports.
            }
        }
        throw new IllegalStateException("server could not bind its ports in " + MAX_PORT_ATTEMPTS + " attempts");
    }

    private static TestServer boot(int gamePort, int rconPort, Path logCopy, Path paperRepo, ServerRegistry registry, long begin, boolean cold) throws IOException, PortInUse {
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
                "generator-settings=" + FLAT_WORLD,
                ""));

        Path java = Path.of(System.getProperty("java.home"), "bin", "java");
        String runMarker = ServerRegistry.newMarker();
        Process process = new ProcessBuilder(java.toString(), PaperRepo.jvmArg(paperRepo), "-D" + ServerRegistry.MARKER_PROPERTY + "=" + runMarker, "-jar", property("testutils.paperJar"), "--nogui")
                .directory(dir.toFile())
                .redirectErrorStream(true)
                .redirectOutput(dir.resolve("server.log").toFile())
                .start();
        ServerRegistry.Registration registration;
        try {
            // Fails once the JVM is shutting down, e.g. a test thread that kept going after Ctrl-C.
            registration = registry.register(process, runMarker);
        } catch (IOException | RuntimeException e) {
            process.destroyForcibly();
            throw e;
        }
        try {
            awaitReady(process, dir.resolve("server.log"), rconPort);
            Rcon rcon = connect(rconPort, rconPassword);
            try {
                setUpWorld(rcon);
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
            return new TestServer(dir, logCopy, rconPort, process, rcon, watcher, Duration.ofNanos(System.nanoTime() - begin), cold, registration);
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

    /** The block at the position, loading its chunk if needed. */
    public Block block(Pos pos) {
        JsonObject reply = query("block " + pos);
        Map<String, String> state = new HashMap<>();
        reply.getAsJsonObject("state").asMap().forEach((property, value) -> state.put(property, value.getAsString()));
        return new Block(reply.get("type").getAsString(), state);
    }

    /** The slots of the container at the position. Throws when the block is no container. */
    public Container container(Pos pos) {
        JsonObject reply = query("container " + pos);
        return new Container(reply.get("block").getAsString(), reply.get("size").getAsInt(), Item.slots(reply.getAsJsonArray("items")));
    }

    private JsonObject query(String args) {
        JsonObject reply = tu(args);
        if (!reply.get("ok").getAsBoolean()) throw new IllegalStateException("tu " + args + " failed: " + reply.get("error").getAsString());
        return reply;
    }

    /** A fresh {@link Area} no other test has used. Close it to remove its bots. */
    public Area area() {
        return new Area(this, nextAreaIndex.getAndIncrement());
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

    /**
     * Ready means "Done (" and our own "RCON running on" line. A taken RCON port only logs a warning and the
     * server still finishes booting, so without the second line we would talk to whoever holds the port.
     */
    private static void awaitReady(Process process, Path log, int rconPort) throws IOException, PortInUse {
        Instant deadline = Instant.now().plus(READY_TIMEOUT);
        while (Instant.now().isBefore(deadline)) {
            String text = Files.exists(log) ? Files.readString(log) : "";
            if (text.contains("FAILED TO BIND TO PORT")) throw new PortInUse();
            if (text.contains("Done (")) {
                if (text.contains("RCON running on 127.0.0.1:" + rconPort)) return;
                throw new PortInUse();
            }
            if (!process.isAlive()) throw new IllegalStateException("server exited early, see " + log);
            Waiting.pause(Duration.ofMillis(100));
        }
        throw new IllegalStateException("server not ready after " + READY_TIMEOUT + ", see " + log);
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
