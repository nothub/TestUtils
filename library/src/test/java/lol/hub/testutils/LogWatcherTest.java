package lol.hub.testutils;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Duration;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LogWatcherTest {

    private static final PluginErrors PLUGIN = new PluginErrors("ExamplePlugin", "com.example.plugin.");

    @TempDir
    Path dir;

    private void append(Path log, String text) {
        try {
            Files.writeString(log, text, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** A watcher whose "server" writes the marker into the log the moment it is asked to say it. */
    private LogWatcher watcher(Path log) {
        return new LogWatcher(log, PLUGIN, marker -> append(log, "[22:15:09 INFO]: [Rcon] " + marker + "\n"), Duration.ofSeconds(2));
    }

    @Test
    void returnsErrorsSinceTheLastCheckpointOnce() {
        Path log = dir.resolve("server.log");
        LogWatcher watcher = watcher(log);
        append(log, "[22:15:01 INFO]: boot\n[22:15:02 ERROR]: [ExamplePlugin] first\n");
        assertEquals(List.of("[22:15:02 ERROR]: [ExamplePlugin] first"), watcher.pluginErrors());
        append(log, "[22:15:03 INFO]: quiet\n");
        assertEquals(List.of(), watcher.pluginErrors());
        append(log, "[22:15:04 ERROR]: [ExamplePlugin] second\n");
        assertEquals(List.of("[22:15:04 ERROR]: [ExamplePlugin] second"), watcher.pluginErrors());
    }

    @Test
    void aTraceThatEndsRightBeforeTheMarkerIsComplete() {
        Path log = dir.resolve("server.log");
        LogWatcher watcher = watcher(log);
        append(log, "[22:15:02 ERROR]: Could not pass event X to ExamplePlugin v1\njava.lang.Error\n\tat com.example.plugin.A.b(A.java:1)\n");
        List<String> found = watcher.pluginErrors();
        assertEquals(1, found.size());
        assertTrue(found.getFirst().endsWith("\tat com.example.plugin.A.b(A.java:1)"), found.getFirst());
    }

    @Test
    void failsWithTheLogPathWhenTheMarkerNeverArrives() {
        Path log = dir.resolve("server.log");
        LogWatcher silent = new LogWatcher(log, PLUGIN, marker -> {
        }, Duration.ofMillis(300));
        append(log, "[22:15:01 INFO]: boot\n");
        IllegalStateException e = assertThrows(IllegalStateException.class, silent::pluginErrors);
        assertTrue(e.getMessage().contains(log.toString()), e.getMessage());
    }
}
