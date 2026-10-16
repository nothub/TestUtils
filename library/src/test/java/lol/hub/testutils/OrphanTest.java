package lol.hub.testutils;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Each case runs a real second JVM that owns a real Paper server, then kills that JVM the way a developer or CI would. */
class OrphanTest {

    @TempDir
    Path temp;

    private final List<Process> started = new ArrayList<>();

    /** A test JVM that has booted its server. */
    private record Fixture(Process jvm, long serverPid) {
    }

    private Fixture fixture(String name) throws IOException {
        List<String> command = new ArrayList<>(List.of(Path.of(System.getProperty("java.home"), "bin", "java").toString(),
                "-cp", System.getProperty("java.class.path")));
        System.getProperties().stringPropertyNames().stream().filter(key -> key.startsWith("testutils."))
                .forEach(key -> command.add("-D" + key + "=" + System.getProperty(key)));
        command.addAll(List.of(OrphanFixture.class.getName(), temp.resolve(name + "/server.log").toString()));
        Process jvm = new ProcessBuilder(command).redirectError(ProcessBuilder.Redirect.INHERIT).start();
        started.add(jvm);
        BufferedReader out = new BufferedReader(new InputStreamReader(jvm.getInputStream()));
        long deadline = System.nanoTime() + Duration.ofMinutes(5).toNanos();
        for (String line; System.nanoTime() < deadline && (line = out.readLine()) != null; ) {
            if (line.startsWith("SERVER_PID=")) return new Fixture(jvm, Long.parseLong(line.substring("SERVER_PID=".length())));
        }
        throw new IllegalStateException("the fixture JVM never reported its server");
    }

    private static boolean alive(long pid) {
        return ProcessHandle.of(pid).map(ProcessHandle::isAlive).orElse(false);
    }

    private static void awaitDead(long pid, Duration within) throws InterruptedException {
        long deadline = System.nanoTime() + within.toNanos();
        while (alive(pid) && System.nanoTime() < deadline) Thread.sleep(100);
        assertFalse(alive(pid), "process " + pid + " still runs after " + within);
    }

    @org.junit.jupiter.api.AfterEach
    void cleanUp() throws InterruptedException {
        for (Process jvm : started) {
            jvm.destroy();
            if (!jvm.waitFor(30, TimeUnit.SECONDS)) jvm.destroyForcibly();
        }
    }

    @Test
    void terminatingTheTestJvmStopsItsServer() throws Exception {
        Fixture run = fixture("term");
        assertTrue(alive(run.serverPid()));
        run.jvm().destroy(); // SIGTERM, what Gradle sends a test worker it cancels
        assertTrue(run.jvm().waitFor(15, TimeUnit.SECONDS), "the test JVM exits");
        awaitDead(run.serverPid(), Duration.ofSeconds(15));
    }

    @Test
    void aKilledTestJvmsServerIsReapedByTheNextRun() throws Exception {
        Fixture run = fixture("killed");
        run.jvm().destroyForcibly(); // SIGKILL runs no hooks
        run.jvm().waitFor();
        assertTrue(alive(run.serverPid()), "the orphan outlives its owner");
        try (TestServer next = TestServer.start(PortSource.LOOPBACK, temp.resolve("next/server.log"))) {
            awaitDead(run.serverPid(), Duration.ofSeconds(15));
            assertTrue(next.command("list").contains("players online"), "the new run is not harmed");
        }
    }

    @Test
    void theReaperLeavesLiveRunsAndUnmarkedProcessesAlone() throws Exception {
        ServerRegistry registry = ServerRegistry.at(Path.of(System.getProperty("testutils.paperRepo")));
        Fixture live = fixture("live");
        Process gone = new ProcessBuilder("true").start();
        gone.waitFor();
        // A running runServer has no marker. Its pid could land in a stale record once the pid is reused.
        Process unmarked = new ProcessBuilder("sleep", "600").start();
        started.add(unmarked);
        Path stale = registry.write(new ServerRegistry.Entry(unmarked.pid(), startOf(unmarked.pid()), gone.pid(), 1L, "not-a-run"));

        registry.reapOrphans();

        assertTrue(alive(live.serverPid()), "a live run's server");
        assertTrue(alive(unmarked.pid()), "an unmarked process");
        assertFalse(Files.exists(stale), "the stale record is dropped");
        assertTrue(Files.exists(registry.dir().resolve(live.serverPid() + ".properties")), "the live run's record");
    }

    private static long startOf(long pid) {
        return ProcessHandle.of(pid).orElseThrow().info().startInstant().orElseThrow().toEpochMilli();
    }
}
