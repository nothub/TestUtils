package lol.hub.testutils;

import java.io.IOException;
import java.io.RandomAccessFile;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.function.Consumer;

/**
 * Reads the server log in windows. Each call to {@link #pluginErrors()} has the server say a unique marker
 * and reads up to the marker's line. The server writes its log asynchronously, so only the marker proves that
 * everything logged before the call is on disk.
 */
final class LogWatcher {

    private final Path log;
    private final PluginErrors plugin;
    private final Consumer<String> say;
    private final Duration timeout;
    private long offset;
    private int markers;

    LogWatcher(Path log, PluginErrors plugin, Consumer<String> say, Duration timeout) {
        this.log = log;
        this.plugin = plugin;
        this.say = say;
        this.timeout = timeout;
    }

    /** The plugin's errors logged since the previous call, or since the start of the log on the first call. */
    synchronized List<String> pluginErrors() {
        String marker = "testutils-marker-" + markers++;
        say.accept(marker);
        long deadline = System.nanoTime() + timeout.toNanos();
        while (true) {
            byte[] unread = unread();
            // Latin-1 maps one char to one byte, so indexes into the string are offsets into the file.
            String bytes = new String(unread, StandardCharsets.ISO_8859_1);
            int at = bytes.indexOf(marker);
            int lineEnd = at < 0 ? -1 : bytes.indexOf('\n', at);
            if (lineEnd >= 0) {
                offset += lineEnd + 1;
                int lineStart = bytes.lastIndexOf('\n', at) + 1;
                return plugin.in(new String(unread, 0, lineStart, StandardCharsets.UTF_8).lines().toList());
            }
            if (System.nanoTime() - deadline >= 0) {
                throw new IllegalStateException("server did not log " + marker + " within " + timeout.toMillis() + " ms, see " + log);
            }
            Waiting.pause(Duration.ofMillis(20));
        }
    }

    private byte[] unread() {
        if (!Files.exists(log)) return new byte[0];
        try (RandomAccessFile file = new RandomAccessFile(log.toFile(), "r")) {
            file.seek(offset);
            byte[] bytes = new byte[(int) (file.length() - offset)];
            file.readFully(bytes);
            return bytes;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
