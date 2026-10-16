package lol.hub.testutils;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.util.Arrays;
import java.util.List;
import java.util.Properties;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.stream.Stream;

/**
 * Keeps a server from outliving its test run. A JVM shutdown hook stops it when the test JVM is
 * interrupted or terminated. A SIGKILL runs no hooks, so every server also gets a record (its pid, its
 * owner's pid, both start times) and a run marker on its command line, and the next run reaps
 * the servers whose owner is gone. A process without our marker, such as an interactive
 * {@code runServer}, is never touched, even if a record names its pid.
 */
final class ServerRegistry {

    static final String MARKER_PROPERTY = "testutils.run";
    private static final Duration STOP_TIMEOUT = Duration.ofSeconds(10);

    /** A server and the test JVM that owns it. Start times tell a pid from a later process that reused it. */
    record Entry(long serverPid, long serverStart, long ownerPid, long ownerStart, String marker) {

        String serverArg() {
            return "-D" + MARKER_PROPERTY + "=" + marker;
        }
    }

    /** The record and the shutdown hook of one running server. */
    final class Registration implements AutoCloseable {

        private final Path record;
        private final Thread hook;

        private Registration(Process server, Path record) {
            this.record = record;
            this.hook = new Thread(() -> {
                stop(server.toHandle());
                try {
                    Files.deleteIfExists(record);
                } catch (IOException ignored) {
                    // A stale record is dropped by the next run.
                }
            }, "testutils-stop-server");
            Runtime.getRuntime().addShutdownHook(hook);
        }

        /** The server is gone or about to be: drop the hook and the record. */
        @Override
        public void close() throws IOException {
            try {
                Runtime.getRuntime().removeShutdownHook(hook);
            } catch (IllegalStateException shuttingDown) {
                // The hook is already running.
            }
            Files.deleteIfExists(record);
        }
    }

    private final Path dir;

    ServerRegistry(Path dir) {
        this.dir = dir;
    }

    /** Beside the Paper repo, which Gradle keeps under its user home. */
    static ServerRegistry at(Path paperRepo) {
        return new ServerRegistry(paperRepo.resolveSibling("servers"));
    }

    Path dir() {
        return dir;
    }

    /** A new marker for a run, to put on the server's command line as {@link Entry#serverArg()}. */
    static String newMarker() {
        return java.util.UUID.randomUUID().toString();
    }

    Registration register(Process server, String marker) throws IOException {
        ProcessHandle self = ProcessHandle.current();
        Entry entry = new Entry(server.pid(), startOf(server.toHandle()), self.pid(), startOf(self), marker);
        return new Registration(server, write(entry));
    }

    Path write(Entry entry) throws IOException {
        Files.createDirectories(dir);
        Properties properties = new Properties();
        properties.setProperty("server.pid", Long.toString(entry.serverPid()));
        properties.setProperty("server.start", Long.toString(entry.serverStart()));
        properties.setProperty("owner.pid", Long.toString(entry.ownerPid()));
        properties.setProperty("owner.start", Long.toString(entry.ownerStart()));
        properties.setProperty("marker", entry.marker());
        Path file = dir.resolve(entry.serverPid() + ".properties");
        // Renamed into place, so a run reaping at the same moment never reads half a record.
        Path partial = Files.createTempFile(dir, "record-", ".tmp");
        try (Writer out = Files.newBufferedWriter(partial)) {
            properties.store(out, null);
        }
        Files.move(partial, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        return file;
    }

    /** Stops every marked server whose owner is dead and drops the records of the servers that are gone. */
    void reapOrphans() throws IOException {
        if (!Files.isDirectory(dir)) return;
        List<Path> records;
        try (Stream<Path> files = Files.list(dir)) {
            records = files.filter(file -> file.getFileName().toString().endsWith(".properties")).toList();
        }
        for (Path file : records) {
            Entry entry = read(file);
            if (entry == null || !isRunning(entry.ownerPid(), entry.ownerStart())) {
                if (entry != null) reap(entry);
                Files.deleteIfExists(file);
            }
        }
    }

    private static void reap(Entry entry) {
        ProcessHandle.of(entry.serverPid())
                .filter(server -> isRunning(server, entry.serverStart()))
                .filter(server -> server.info().arguments().map(args -> Arrays.asList(args).contains(entry.serverArg())).orElse(false))
                .ifPresent(ServerRegistry::stop);
    }

    private static Entry read(Path file) throws IOException {
        Properties properties = new Properties();
        try (Reader in = Files.newBufferedReader(file)) {
            properties.load(in);
            return new Entry(Long.parseLong(properties.getProperty("server.pid")), Long.parseLong(properties.getProperty("server.start")),
                    Long.parseLong(properties.getProperty("owner.pid")), Long.parseLong(properties.getProperty("owner.start")),
                    properties.getProperty("marker"));
        } catch (NumberFormatException | java.nio.file.NoSuchFileException unreadable) {
            // Not ours, or removed by another run in the meantime.
            return null;
        }
    }

    private static boolean isRunning(long pid, long start) {
        return ProcessHandle.of(pid).map(handle -> isRunning(handle, start)).orElse(false);
    }

    private static boolean isRunning(ProcessHandle handle, long start) {
        return handle.isAlive() && handle.info().startInstant().map(started -> started.toEpochMilli() == start).orElse(false);
    }

    private static long startOf(ProcessHandle handle) {
        return handle.info().startInstant().orElseThrow(() -> new IllegalStateException("cannot read the start time of process " + handle.pid())).toEpochMilli();
    }

    /** SIGTERM first, which makes Paper save and stop cleanly, then a kill. */
    private static void stop(ProcessHandle process) {
        process.destroy();
        try {
            process.onExit().get(STOP_TIMEOUT.toSeconds(), TimeUnit.SECONDS);
        } catch (TimeoutException | ExecutionException e) {
            process.destroyForcibly();
        } catch (InterruptedException e) {
            process.destroyForcibly();
            Thread.currentThread().interrupt();
        }
    }
}
