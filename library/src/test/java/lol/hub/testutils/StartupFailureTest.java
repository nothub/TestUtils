package lol.hub.testutils;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.jar.Attributes;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;
import java.util.jar.Manifest;

import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Starts "servers" that fail in the ways a real one does, and reads what the failure tells the test author. */
class StartupFailureTest {

    // Both fakes stand alone in their jar, so they can't call anything of this class. Paperclip's
    // patch pass must succeed for a start to get as far as booting.
    public static final class ExitsEarly {
        public static void main(String[] args) {
            if (Boolean.getBoolean("paperclip.patchonly")) return;
            System.out.println("[main/INFO]: Loading properties");
            System.out.println("[main/ERROR]: Failed to bind: the world is cursed");
            System.exit(3);
        }
    }

    public static final class NeverReady {
        public static void main(String[] args) throws InterruptedException {
            if (Boolean.getBoolean("paperclip.patchonly")) return;
            System.out.println("[main/INFO]: Loading properties");
            System.out.println("[main/INFO]: Preparing level \"world\"");
            Thread.sleep(Long.MAX_VALUE);
        }
    }

    public static final class RejectsElement {
        public static void main(String[] args) {
            if (Boolean.getBoolean("paperclip.patchonly")) return;
            System.out.println("[main/ERROR]: >> Errors in element tu:far:");
            for (int i = 0; i < 50; i++) System.out.println("\tat net.minecraft.Frame" + i);
            System.out.println("[main/WARN]: Failed to load datapacks, can't proceed with server load.");
        }
    }

    @TempDir
    Path temp;

    private IllegalStateException startFailing(Path jar, Duration readyTimeout) {
        return assertThrows(IllegalStateException.class, () -> TestServer.start(PortSource.LOOPBACK, temp.resolve("log/server.log"), jar, temp.resolve("repo"), null, readyTimeout));
    }

    @Test
    void aCorruptPaperJarFailsFastWithTheLauncherOutput() throws IOException {
        Path jar = Files.writeString(temp.resolve("corrupt.jar"), "this is not a jar file");
        IllegalStateException e = assertTimeoutPreemptively(Duration.ofSeconds(30), () -> startFailing(jar, Duration.ofMinutes(5)));
        assertTrue(e.getMessage().contains("corrupt"), e.getMessage());
    }

    @Test
    void aServerThatExitsEarlyReportsItsExitCodeAndLastLogLines() throws IOException {
        IllegalStateException e = assertTimeoutPreemptively(Duration.ofSeconds(30), () -> startFailing(fakeServer(ExitsEarly.class), Duration.ofMinutes(5)));
        assertTrue(e.getMessage().contains("exited early with code 3"), e.getMessage());
        assertTrue(e.getMessage().contains("Failed to bind: the world is cursed"), e.getMessage());
    }

    @Test
    void aServerThatNeverGetsReadyReportsTheTimeoutAndLastLogLines() throws IOException {
        IllegalStateException e = assertTimeoutPreemptively(Duration.ofSeconds(30), () -> startFailing(fakeServer(NeverReady.class), Duration.ofSeconds(3)));
        assertTrue(e.getMessage().contains("not ready after PT3S"), e.getMessage());
        assertTrue(e.getMessage().contains("Preparing level \"world\""), e.getMessage());
    }

    @Test
    void anElementErrorAboveTheLastLinesIsKeptInTheFailure() throws IOException {
        IllegalStateException e = assertTimeoutPreemptively(Duration.ofSeconds(30), () -> startFailing(fakeServer(RejectsElement.class), Duration.ofMinutes(5)));
        assertTrue(e.getMessage().contains("Errors in element tu:far"), e.getMessage());
        assertTrue(e.getMessage().contains("can't proceed with server load"), e.getMessage());
    }

    @Test
    void aDeclarationTheServerRejectsFailsWithTheLineNamingIt() {
        // Passes Declarations' checks, but the server's codec takes 0 to 15 only.
        String declaration = "[{\"id\": \"tu:rejected\", \"base\": \"minecraft:overworld\", \"overrides\": {\"monster_spawn_block_light_limit\": 99}}]";
        Path paperRepo = Path.of(System.getProperty("testutils.paperRepo"));
        IllegalStateException e = assertThrows(IllegalStateException.class, () -> TestServer.start(PortSource.LOOPBACK, temp.resolve("log/server.log"), paperRepo, declaration));
        assertTrue(e.getMessage().contains("exited early"), e.getMessage());
        assertTrue(e.getMessage().contains("Errors in element tu:rejected"), e.getMessage());
    }

    private Path fakeServer(Class<?> main) throws IOException {
        Manifest manifest = new Manifest();
        manifest.getMainAttributes().put(Attributes.Name.MANIFEST_VERSION, "1.0");
        manifest.getMainAttributes().put(Attributes.Name.MAIN_CLASS, main.getName());
        Path jar = temp.resolve(main.getSimpleName() + ".jar");
        String resource = main.getName().replace('.', '/') + ".class";
        try (JarOutputStream out = new JarOutputStream(Files.newOutputStream(jar), manifest);
             InputStream in = main.getClassLoader().getResourceAsStream(resource)) {
            out.putNextEntry(new JarEntry(resource));
            in.transferTo(out);
            out.closeEntry();
        }
        return jar;
    }
}
