package lol.hub.testutils;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PluginErrorsTest {

    private static final PluginErrors AUTO_SORT = PluginErrors.of("""
            name: ExamplePlugin
            version: '1.2.0'
            main: com.example.plugin.Plugin
            api-version: '26.3'
            """);

    private static List<String> errors(String log) {
        return AUTO_SORT.in(log.lines().toList());
    }

    @Test
    void readsNameAndPackageFromPluginYml() {
        assertEquals(new PluginErrors("ExamplePlugin", "com.example.plugin."), PluginErrors.of("main: \"com.example.plugin.Plugin\"\nname: 'ExamplePlugin'\n"));
    }

    @Test
    void pluginYmlWithoutNameOrMainIsRejected() {
        assertThrows(IllegalArgumentException.class, () -> PluginErrors.of("name: ExamplePlugin\n"));
        assertThrows(IllegalArgumentException.class, () -> PluginErrors.of("main: com.example.plugin.Plugin\n"));
    }

    @Test
    void catchesErrorsLoggedByThePlugin() {
        List<String> found = errors("""
                [22:15:03 INFO]: [ExamplePlugin] Enabling ExamplePlugin v1.2.0
                [22:15:04 ERROR]: [ExamplePlugin] Could not read data.yml
                [22:15:05 INFO]: Done
                """);
        assertEquals(List.of("[22:15:04 ERROR]: [ExamplePlugin] Could not read data.yml"), found);
    }

    @Test
    void catchesAnEventExceptionWithThePluginsFramesAndKeepsTheWholeTrace() {
        List<String> found = errors("""
                [22:15:04 ERROR]: Could not pass event InventoryCloseEvent to ExamplePlugin v1.2.0
                org.bukkit.event.EventException: null
                \tat org.bukkit.plugin.java.JavaPluginLoader$1.execute(JavaPluginLoader.java:306) ~[paper-api.jar:?]
                \tat com.example.plugin.Worker.sort(Worker.java:41) ~[example-1.2.0.jar:?]
                Caused by: java.lang.NullPointerException
                \tat com.example.plugin.Worker.sort(Worker.java:41) ~[example-1.2.0.jar:?]
                [22:15:05 INFO]: next line
                """);
        assertEquals(1, found.size());
        assertTrue(found.getFirst().startsWith("[22:15:04 ERROR]: Could not pass event"), found.getFirst());
        assertTrue(found.getFirst().contains("Caused by: java.lang.NullPointerException"), found.getFirst());
        assertTrue(!found.getFirst().contains("next line"), found.getFirst());
    }

    @Test
    void catchesATraceWithPluginFramesAtAnyLevel() {
        assertEquals(1, errors("""
                [22:15:04 WARN]: something odd
                java.lang.IllegalStateException: boom
                \tat com.example.plugin.Worker.sort(Worker.java:41) ~[example-1.2.0.jar:?]
                """).size());
    }

    @Test
    void catchesTheServersOwnReportsOfAPluginFailure() {
        assertEquals(1, errors("[22:15:04 ERROR]: Task #3 for ExamplePlugin v1.2.0 generated an exception").size());
        assertEquals(1, errors("[22:15:04 ERROR]: Unhandled exception executing command 'sort' in plugin ExamplePlugin v1.2.0").size());
    }

    @Test
    void ignoresPaperAndHarnessNoise() {
        assertEquals(List.of(), errors("""
                [22:15:01 INFO]: Starting minecraft server version 26.3
                [00:17:31 WARN]: **** SERVER IS RUNNING IN OFFLINE/INSECURE MODE!
                [22:15:02 ERROR]: Failed to load a datapack
                [22:15:02 ERROR]: [TestUtils] fake player tu3 failed
                [22:15:03 INFO]: [ExamplePlugin] Enabling ExamplePlugin v1.2.0
                [22:15:03 WARN]: [ExamplePlugin] config.yml is missing, using defaults
                java.lang.RuntimeException: harness
                \tat lol.hub.testutils.Plugin.onCommand(Plugin.java:12) ~[testutils.jar:?]
                \tat net.minecraft.server.MinecraftServer.run(MinecraftServer.java:100) ~[paper.jar:?]
                [00:17:34 INFO]: [Not Secure] [Rcon] testutils-marker-1
                """));
    }

    @Test
    void aSiblingPackageWithTheSamePrefixIsNotThePlugin() {
        assertEquals(List.of(), errors("""
                [22:15:04 WARN]: oops
                \tat com.example.pluginish.Other.run(Other.java:1) ~[other.jar:?]
                """));
    }

    // Copied from a Paper 26.3 server log, with the middle of the server's own frames cut.
    private static final String RECORDED = """
            [00:22:08 ERROR]: Command exception: /tu boom
            org.bukkit.command.CommandException: Unhandled exception executing command 'tu' in plugin TestUtils v0.1.0
            \tat org.bukkit.command.PluginCommand.execute(PluginCommand.java:53) ~[paper-api-26.3.build.193-stable.jar:?]
            \tat com.mojang.brigadier.context.ContextChain.runExecutable(ContextChain.java:73) ~[brigadier-1.3.11.jar:?]
            \tat java.base/java.lang.Thread.run(Thread.java:1474) ~[?:?]
            Caused by: java.lang.IllegalStateException: probe
            \tat testutils-0.1.0+26.3-paper.jar//lol.hub.testutils.Plugin.onCommand(Plugin.java:56) ~[?:?]
            \tat org.bukkit.command.PluginCommand.execute(PluginCommand.java:51) ~[paper-api-26.3.build.193-stable.jar:?]
            \t... 26 more
            [00:22:08 INFO]: *** Currently you are 1 build behind ***
            """;

    @Test
    void catchesARecordedCommandExceptionOfThePlugin() {
        PluginErrors testUtils = new PluginErrors("TestUtils", "lol.hub.testutils.");
        List<String> found = testUtils.in(RECORDED.lines().toList());
        assertEquals(1, found.size());
        assertTrue(found.getFirst().contains("Caused by: java.lang.IllegalStateException: probe"), found.getFirst());
        assertTrue(!found.getFirst().contains("build behind"), found.getFirst());
    }

    @Test
    void catchesARecordedFrameThatNamesItsJarEvenWhenNothingBlamesThePlugin() {
        PluginErrors testUtils = new PluginErrors("TestUtils", "lol.hub.testutils.");
        List<String> withoutBlame = RECORDED.lines().filter(line -> !line.contains("Unhandled exception")).toList();
        assertEquals(1, testUtils.in(withoutBlame).size());
    }

    @Test
    void aRecordedExceptionOfAnotherPluginIsNotThisPlugins() {
        assertEquals(List.of(), errors(RECORDED));
    }
}
