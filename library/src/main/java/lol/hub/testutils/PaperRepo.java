package lol.hub.testutils;

import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.ReentrantLock;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * Paperclip's output (cache/, libraries/, versions/), kept in one directory that every run shares
 * instead of re-downloading and re-patching into each server's temp directory.
 */
final class PaperRepo {

    private static final long PATCH_TIMEOUT_MINUTES = 5;
    // FileChannel.lock() refuses a second lock from the same JVM, so threads queue here first.
    private static final ReentrantLock THIS_JVM = new ReentrantLock();

    private PaperRepo() {
    }

    /** The JVM option that makes Paperclip read and write the repo instead of the working directory. */
    static String jvmArg(Path repo) {
        return "-DbundlerRepoDir=" + repo;
    }

    /**
     * The patched server jar that {@link #patch} put into the repo for this Paperclip jar. One repo holds the patched jars
     * of every MC version that used it, so the Paperclip jar's own version list picks the one.
     */
    static Path patchedJar(Path paperJar, Path repo) throws IOException {
        try (ZipFile zip = new ZipFile(paperJar.toFile())) {
            ZipEntry entry = zip.getEntry("META-INF/versions.list");
            if (entry == null) throw new IllegalStateException(paperJar + " has no META-INF/versions.list");
            List<String> lines = new String(zip.getInputStream(entry).readAllBytes(), StandardCharsets.UTF_8).lines().toList();
            // Each line is "<sha256>\t<id>\t<path below versions/>".
            if (lines.size() != 1) throw new IllegalStateException(paperJar + " lists " + lines.size() + " versions, expected one");
            String[] fields = lines.getFirst().split("\t");
            return repo.resolve("versions").resolve(fields[2]);
        }
    }

    /**
     * Fills the repo, which is a no-op for the network once it holds the pinned build. Runs under an
     * exclusive file lock, because two Paperclips writing the same libraries corrupt each other.
     *
     * @return true when the repo was empty, so this was a cold start
     */
    static boolean patch(Path paperJar, Path repo, List<String> jvmArgs) throws IOException {
        Files.createDirectories(repo);
        boolean cold = !Files.isDirectory(repo.resolve("versions"));
        THIS_JVM.lock();
        try (FileChannel channel = FileChannel.open(repo.resolve("patch.lock"), StandardOpenOption.CREATE, StandardOpenOption.WRITE);
             FileLock ignored = channel.lock()) {
            List<String> command = new ArrayList<>();
            command.add(Path.of(System.getProperty("java.home"), "bin", "java").toString());
            command.addAll(jvmArgs);
            command.addAll(List.of("-Dpaperclip.patchonly=true", jvmArg(repo), "-jar", paperJar.toString()));
            Path output = Files.createTempFile(repo, "patch-", ".log");
            try {
                Process process = new ProcessBuilder(command).directory(repo.toFile()).redirectErrorStream(true)
                        .redirectOutput(output.toFile()).start();
                if (!process.waitFor(PATCH_TIMEOUT_MINUTES, TimeUnit.MINUTES)) {
                    process.destroyForcibly();
                    throw new IllegalStateException("patching " + paperJar + " took more than " + PATCH_TIMEOUT_MINUTES + " minutes");
                }
                if (process.exitValue() != 0) {
                    throw new IllegalStateException("patching " + paperJar + " exited with " + process.exitValue() + ":\n" + Files.readString(output));
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("interrupted while patching " + paperJar, e);
            } finally {
                Files.deleteIfExists(output);
            }
        } finally {
            THIS_JVM.unlock();
        }
        return cold;
    }
}
