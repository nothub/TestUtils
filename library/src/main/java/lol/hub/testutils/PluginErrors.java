package lol.hub.testutils;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.jar.JarFile;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Decides which server log lines are errors of one plugin. A log entry is a header line
 * ({@code [22:15:04 ERROR]: ...}) plus the lines that follow it, such as a stack trace. An entry counts when
 * <ul>
 * <li>it is an ERROR logged under the plugin's name ({@code [ExamplePlugin] ...}),</li>
 * <li>it is an ERROR in which the server blames the plugin, on any of its lines ("Could not pass event X to
 * ExamplePlugin v1"), or</li>
 * <li>any of its lines is a stack frame in the plugin's main package, at any level.</li>
 * </ul>
 * Everything else is Paper's or another plugin's business.
 *
 * @param packagePrefix the package of the plugin's main class, with the trailing dot
 */
record PluginErrors(String name, String packagePrefix) {

    private static final Pattern HEADER = Pattern.compile("^\\[\\d{2}:\\d{2}:\\d{2} (\\w+)]: (.*)$");
    private static final Pattern NAME = Pattern.compile("(?m)^name:\\s*['\"]?([^'\"\\s]+)['\"]?\\s*$");
    private static final Pattern MAIN = Pattern.compile("(?m)^main:\\s*['\"]?([^'\"\\s]+)['\"]?\\s*$");

    static PluginErrors of(String pluginYml) {
        Matcher name = NAME.matcher(pluginYml);
        Matcher main = MAIN.matcher(pluginYml);
        if (!name.find() || !main.find()) throw new IllegalArgumentException("plugin.yml has no name or no main");
        String mainClass = main.group(1);
        int dot = mainClass.lastIndexOf('.');
        // A main class in the default package has no package to match, so its own frames stand in.
        return new PluginErrors(name.group(1), dot < 0 ? mainClass + "." : mainClass.substring(0, dot + 1));
    }

    static PluginErrors forJar(Path jar) throws IOException {
        try (JarFile file = new JarFile(jar.toFile())) {
            var entry = file.getEntry("plugin.yml");
            if (entry == null) throw new IllegalArgumentException(jar + " has no plugin.yml");
            try (InputStream in = file.getInputStream(entry)) {
                return of(new String(in.readAllBytes(), StandardCharsets.UTF_8));
            }
        }
    }

    /** The entries of these lines that are errors of this plugin, each as its lines joined with newlines. */
    List<String> in(List<String> lines) {
        Pattern blamed = Pattern.compile("\\b(?:to|for|in plugin|enabling|disabling) " + Pattern.quote(name) + " v\\S*");
        // Paper names the jar in front of a plugin's frames: "at example-1.0.jar//com.example.Foo.bar(Foo.java:1)".
        Pattern frame = Pattern.compile("^at (?:\\S+//)?" + Pattern.quote(packagePrefix));
        List<String> found = new ArrayList<>();
        List<String> entry = new ArrayList<>();
        for (String line : lines) {
            if (HEADER.matcher(line).matches() && !entry.isEmpty()) {
                if (isError(entry, blamed, frame)) found.add(String.join("\n", entry));
                entry.clear();
            }
            entry.add(line);
        }
        if (!entry.isEmpty() && isError(entry, blamed, frame)) found.add(String.join("\n", entry));
        return found;
    }

    private boolean isError(List<String> entry, Pattern blamed, Pattern frame) {
        Matcher header = HEADER.matcher(entry.getFirst());
        if (header.matches() && header.group(1).equals("ERROR")) {
            if (header.group(2).startsWith("[" + name + "] ")) return true;
            if (entry.stream().anyMatch(line -> blamed.matcher(line).find())) return true;
        }
        return entry.stream().anyMatch(line -> frame.matcher(line.strip()).find());
    }
}
