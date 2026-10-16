package lol.hub.testutils;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;

import java.io.IOException;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/** Two runs start at once on an empty Paper repo, the way two Gradle builds on a fresh CI runner would. */
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class SharedRepoTest {

    private static final List<String> NO_NETWORK = List.of(
            "-Dhttps.proxyHost=127.0.0.1", "-Dhttps.proxyPort=9", "-Dhttp.proxyHost=127.0.0.1", "-Dhttp.proxyPort=9");

    private static Path root;
    private static Path repo;
    private static Path logA;
    private static Path logB;
    private static TestServer a;
    private static TestServer b;
    private static boolean aClosed;

    @BeforeAll
    static void twoParallelColdRuns() throws Exception {
        // A cold repo downloads Mojang's jar and the libraries, so this class is the one that can't run offline.
        assumeTrue(canResolve("fill.papermc.io"), "no network, the cold start has nothing to download from");
        root = Files.createTempDirectory("testutils-repo-test");
        repo = root.resolve("repo");
        logA = root.resolve("a/server.log");
        logB = root.resolve("b/server.log");
        CompletableFuture<TestServer> starting = CompletableFuture.supplyAsync(() -> start(logA));
        b = start(logB);
        a = starting.get();
    }

    @AfterAll
    static void cleanUp() throws IOException {
        try {
            if (a != null && !aClosed) a.close();
            if (b != null) b.close();
        } finally {
            if (root != null) deleteTree(root);
        }
    }

    @Test
    @Order(1)
    void bothColdRunsAnswer() {
        assertTrue(a.command("list").contains("players online"));
        assertTrue(b.command("list").contains("players online"));
    }

    @Test
    @Order(2)
    void thePaperclipOutputLivesInTheSharedRepo() {
        for (String dir : List.of("cache", "libraries", "versions")) {
            assertTrue(Files.isDirectory(repo.resolve(dir)), dir + " in " + repo);
        }
    }

    @Test
    @Order(3)
    void aWarmRepoNeedsNoNetwork() throws IOException {
        PaperRepo.patch(Path.of(System.getProperty("testutils.paperJar")), repo, NO_NETWORK);
    }

    @Test
    @Order(4)
    void bootAndTeardownAreReportedForColdAndWarmStarts() throws IOException {
        a.close();
        aClosed = true;
        try (TestServer warm = start(root.resolve("c/server.log"))) {
            assertTrue(warm.command("list").contains("players online"));
        }
        String first = Files.readString(logA.resolveSibling("timing.txt"));
        String second = Files.readString(root.resolve("c/timing.txt"));
        for (String report : List.of(first, second)) {
            assertTrue(report.matches("(?s)boot_seconds=\\d+\\.\\d\\ntotal_seconds=\\d+\\.\\d\\nteardown_seconds=\\d+\\.\\d\\npaper_cache=(cold|warm)\\n"), report);
        }
        assertTrue(first.endsWith("paper_cache=cold\n"), "started on an empty repo: " + first);
        assertTrue(second.endsWith("paper_cache=warm\n"), "started on a patched repo: " + second);
    }

    private static boolean canResolve(String host) {
        try {
            InetAddress.getByName(host);
            return true;
        } catch (UnknownHostException e) {
            return false;
        }
    }

    private static TestServer start(Path log) {
        try {
            return TestServer.start(PortSource.LOOPBACK, log, repo);
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    private static void deleteTree(Path dir) throws IOException {
        try (Stream<Path> files = Files.walk(dir)) {
            for (Path p : (Iterable<Path>) files.sorted(Comparator.reverseOrder())::iterator) Files.delete(p);
        }
    }
}
