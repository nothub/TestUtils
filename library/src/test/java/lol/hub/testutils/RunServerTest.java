package lol.hub.testutils;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.time.Duration;
import java.util.List;
import java.util.Properties;
import java.util.Set;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * Runs the real {@code :plugin:runServer} task on a copy of the project, so the developer's own
 * {@code plugin/run} stays untouched. The copy starts with a {@code server.properties} that
 * contradicts every setting the task owns and holds one it doesn't.
 */
class RunServerTest {

    private static final int GAME_PORT = 25565;
    private static final int RCON_PORT = 25575;
    private static final String RCON_PASSWORD = "testutils";
    private static final Set<String> NOT_COPIED = Set.of("build", "run", ".gradle", ".git", ".idea");

    private static Path copy;
    private static Process gradle;
    private static Rcon rcon;
    private static Properties root;

    @BeforeAll
    static void bootRunServer() throws Exception {
        for (int port : List.of(GAME_PORT, RCON_PORT)) {
            try (ServerSocket ignored = new ServerSocket(port, 1, InetAddress.getLoopbackAddress())) {
                // free
            } catch (IOException e) {
                fail("port " + port + " is taken, stop the server that holds it before running this test");
            }
        }

        Path source = Path.of("..").toRealPath();
        copy = Files.createTempDirectory("testutils-runserver");
        copyTree(source, copy);
        root = new Properties();
        try (var in = Files.newBufferedReader(copy.resolve("gradle.properties"))) {
            root.load(in);
        }

        Path run = Files.createDirectories(copy.resolve("plugin/run"));
        Files.writeString(run.resolve("server.properties"), String.join("\n",
                "server-ip=10.1.2.3", "server-port=1234", "online-mode=true", "white-list=true", "enforce-whitelist=true",
                "enable-rcon=false", "rcon.port=1235", "rcon.password=old", "motd=kept", ""));

        gradle = new ProcessBuilder("./gradlew", "--console", "plain", "--no-daemon", "--offline", ":plugin:runServer")
                .directory(copy.toFile())
                .redirectErrorStream(true)
                .redirectOutput(copy.resolve("gradle.log").toFile())
                .start();

        long deadline = System.nanoTime() + Duration.ofMinutes(8).toNanos();
        while (rcon == null) {
            if (!gradle.isAlive()) fail("runServer exited with " + gradle.exitValue() + ":\n" + Files.readString(copy.resolve("gradle.log")));
            if (System.nanoTime() > deadline) fail("runServer did not open RCON in time:\n" + Files.readString(copy.resolve("gradle.log")));
            try {
                rcon = new Rcon(RCON_PORT, RCON_PASSWORD);
            } catch (IOException notYet) {
                Thread.sleep(500);
            }
        }
    }

    @AfterAll
    static void stopRunServer() throws Exception {
        try {
            if (rcon != null) {
                try {
                    rcon.command("stop");
                } catch (IOException ignored) {
                    // the server closes the connection while stopping
                }
                rcon.close();
            }
            if (gradle != null && !gradle.waitFor(2, TimeUnit.MINUTES)) fail("runServer did not stop after RCON stop");
        } finally {
            if (gradle != null) {
                gradle.descendants().forEach(ProcessHandle::destroyForcibly);
                gradle.destroyForcibly();
            }
            if (copy != null) deleteTree(copy);
        }
    }

    @Test
    void bootsOnThePinnedBuild() throws IOException {
        // `version` answers a tick later, past what RCON captures, so read the boot banner.
        String pinned = "Loading Paper " + root.getProperty("minecraft_version") + "-" + root.getProperty("paper_build") + "-";
        String log = Files.readString(copy.resolve("gradle.log"));
        assertTrue(log.contains(pinned), "expected a boot line containing '" + pinned + "' in:\n" + log);
    }

    @Test
    void answersRconCommandsWithTheirOutput() throws IOException {
        assertTrue(rcon.command("list").startsWith("There are 0 of a max of"), "list");
        assertTrue(rcon.command("plugins").contains("TestUtils"), "plugins");
    }

    @Test
    void writesTheServerPropertiesOverWhatRunHeld() throws IOException {
        Properties properties = new Properties();
        try (var in = Files.newBufferedReader(copy.resolve("plugin/run/server.properties"))) {
            properties.load(in);
        }
        assertEquals("127.0.0.1", properties.getProperty("server-ip"));
        assertEquals("25565", properties.getProperty("server-port"));
        assertEquals("false", properties.getProperty("online-mode"));
        assertEquals("false", properties.getProperty("white-list"));
        assertEquals("false", properties.getProperty("enforce-whitelist"));
        assertEquals("true", properties.getProperty("enable-rcon"));
        assertEquals("25575", properties.getProperty("rcon.port"));
        assertEquals(RCON_PASSWORD, properties.getProperty("rcon.password"));
        assertEquals("kept", properties.getProperty("motd"));
    }

    private static void copyTree(Path from, Path to) throws IOException {
        Files.walkFileTree(from, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) throws IOException {
                if (!dir.equals(from) && NOT_COPIED.contains(dir.getFileName().toString())) return FileVisitResult.SKIP_SUBTREE;
                Files.createDirectories(to.resolve(from.relativize(dir)));
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                Files.copy(file, to.resolve(from.relativize(file)), java.nio.file.StandardCopyOption.COPY_ATTRIBUTES);
                return FileVisitResult.CONTINUE;
            }
        });
    }

    private static void deleteTree(Path dir) throws IOException {
        Files.walkFileTree(dir, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                Files.delete(file);
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult postVisitDirectory(Path d, IOException e) throws IOException {
                Files.delete(d);
                return FileVisitResult.CONTINUE;
            }
        });
    }
}
