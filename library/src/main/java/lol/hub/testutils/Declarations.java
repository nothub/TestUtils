package lol.hub.testutils;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.google.gson.JsonParser;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * The extra dimensions a run declares, each a vanilla dimension type with some top-level fields replaced, checked against the
 * patched Paper jar and turned into one datapack. The server still decides what it accepts: an override it rejects only
 * shows at boot.
 */
final class Declarations {

    private static final Pattern RESOURCE_LOCATION = Pattern.compile("[a-z0-9_.-]+:[a-z0-9_./-]+");
    private static final Set<String> ENTRY_KEYS = Set.of("id", "base", "overrides");
    private static final String PACK = "testutils";

    private final List<String> ids;
    // Path inside the pack, and its content.
    private final Map<String, String> files;

    private Declarations(List<String> ids, Map<String, String> files) {
        this.ids = ids;
        this.files = files;
    }

    /**
     * Reads a declaration, a JSON array of {@code {"id": …, "base": …, "overrides": {…}}} entries. The repo has to be patched
     * already, since base types and the pack format come from the patched jar.
     *
     * @throws IllegalArgumentException naming the entry and its problem
     */
    static Declarations load(String json, Path paperJar, Path paperRepo) throws IOException {
        JsonElement root;
        try {
            root = JsonParser.parseString(json);
        } catch (JsonParseException e) {
            throw new IllegalArgumentException("dimension declaration is no valid JSON: " + e.getMessage(), e);
        }
        if (!root.isJsonArray()) throw new IllegalArgumentException("dimension declaration is no JSON array of entries: " + json);
        JsonArray entries = root.getAsJsonArray();
        Path jar = PaperRepo.patchedJar(paperJar, paperRepo);
        Set<String> ids = new LinkedHashSet<>();
        Map<String, String> files = new LinkedHashMap<>();
        try (ZipFile zip = new ZipFile(jar.toFile())) {
            for (int i = 0; i < entries.size(); i++) {
                String name = "dimension declaration entry " + i;
                if (!entries.get(i).isJsonObject()) throw new IllegalArgumentException(name + " is no JSON object: " + entries.get(i));
                JsonObject entry = entries.get(i).getAsJsonObject();
                String id = string(entry, "id", name);
                name += " (" + id + ")";
                for (String key : entry.keySet()) {
                    if (!ENTRY_KEYS.contains(key)) throw new IllegalArgumentException(name + ": unknown key " + key + ", an entry has id, base and overrides");
                }
                String dimension = qualified(id, name, "id");
                if (dimension.startsWith("minecraft:")) {
                    throw new IllegalArgumentException(name + ": id is in the minecraft namespace, a declared dimension needs its own namespace");
                }
                if (!ids.add(dimension)) throw new IllegalArgumentException(name + ": duplicate id, an earlier entry declares it");

                String base = qualified(string(entry, "base", name), name, "base");
                ZipEntry baseFile = zip.getEntry(dataFile(base, "dimension_type"));
                if (baseFile == null) throw new IllegalArgumentException(name + ": base " + base + " is no dimension type in " + jar.getFileName());
                JsonObject type = read(zip, baseFile).getAsJsonObject();
                // Before the overrides, so an End-based dimension can still ask for the fight.
                type.addProperty("has_ender_dragon_fight", false);
                JsonElement overrides = entry.get("overrides");
                if (overrides != null) {
                    if (!overrides.isJsonObject()) throw new IllegalArgumentException(name + ": overrides is no JSON object: " + overrides);
                    overrides.getAsJsonObject().asMap().forEach(type::add);
                }

                int minY = integer(type, "min_y", name);
                int height = integer(type, "height", name);
                if (minY >= TestServer.SURFACE_Y) {
                    throw new IllegalArgumentException(name + ": min_y " + minY + " leaves no ground below the surface at y " + TestServer.SURFACE_Y);
                }
                int top = TestServer.SURFACE_Y + Area.HEIGHT;
                if (minY + height <= top) {
                    throw new IllegalArgumentException(name + ": min_y " + minY + " and height " + height + " end below y " + top
                            + ", the top of the " + Area.HEIGHT + " air blocks above the surface at y " + TestServer.SURFACE_Y);
                }

                JsonObject generator = new JsonObject();
                generator.addProperty("type", "minecraft:flat");
                generator.add("settings", TestServer.flatWorld(minY));
                JsonObject stem = new JsonObject();
                stem.addProperty("type", dimension);
                stem.add("generator", generator);
                files.put(dataFile(dimension, "dimension_type"), type.toString());
                files.put(dataFile(dimension, "dimension"), stem.toString());
            }
            if (!ids.isEmpty()) files.put("pack.mcmeta", packMeta(zip, jar).toString());
        }
        return new Declarations(List.copyOf(ids), Map.copyOf(files));
    }

    /** The declared dimension ids, in declaration order. */
    List<String> ids() {
        return ids;
    }

    /** Puts the datapack into the world directory. An empty declaration writes nothing. */
    void write(Path world) throws IOException {
        Path pack = world.resolve("datapacks").resolve(PACK);
        for (Map.Entry<String, String> file : files.entrySet()) {
            Path path = pack.resolve(file.getKey());
            Files.createDirectories(path.getParent());
            Files.writeString(path, file.getValue());
        }
    }

    // A pack has to name the formats it supports, and the server's own is the one in its version data.
    private static JsonObject packMeta(ZipFile zip, Path jar) throws IOException {
        ZipEntry versionFile = zip.getEntry("version.json");
        if (versionFile == null) throw new IllegalStateException(jar + " has no version.json");
        int format = read(zip, versionFile).getAsJsonObject().getAsJsonObject("pack_version").get("data_major").getAsInt();
        JsonObject pack = new JsonObject();
        pack.addProperty("description", "Dimensions declared for TestUtils");
        pack.addProperty("min_format", format);
        pack.addProperty("max_format", format);
        JsonObject meta = new JsonObject();
        meta.add("pack", pack);
        return meta;
    }

    private static String string(JsonObject entry, String key, String name) {
        JsonElement value = entry.get(key);
        if (value == null || !value.isJsonPrimitive() || !value.getAsJsonPrimitive().isString()) {
            throw new IllegalArgumentException(name + ": " + key + " is missing or no string");
        }
        return value.getAsString();
    }

    private static int integer(JsonObject type, String key, String name) {
        JsonElement value = type.get(key);
        try {
            if (value != null && value.isJsonPrimitive() && value.getAsJsonPrimitive().isNumber()) return value.getAsBigDecimal().intValueExact();
        } catch (ArithmeticException e) {
            // A fraction or an overflow, reported below.
        }
        throw new IllegalArgumentException(name + ": " + key + " is " + value + ", expected an integer");
    }

    /** The id with its namespace, which is {@code minecraft} when it names none, as everywhere in the game. */
    private static String qualified(String id, String name, String key) {
        String qualified = id.contains(":") ? id : "minecraft:" + id;
        // The game allows . and .. as path segments, but here they would write outside the pack.
        boolean leaves = Arrays.stream(qualified.split("[:/]")).anyMatch(segment -> segment.equals(".") || segment.equals(".."));
        if (leaves || !RESOURCE_LOCATION.matcher(qualified).matches()) throw new IllegalArgumentException(name + ": " + key + " " + id + " is no valid resource location");
        return qualified;
    }

    private static String dataFile(String id, String registry) {
        int colon = id.indexOf(':');
        return "data/" + id.substring(0, colon) + "/" + registry + "/" + id.substring(colon + 1) + ".json";
    }

    private static JsonElement read(ZipFile zip, ZipEntry entry) throws IOException {
        try (InputStream in = zip.getInputStream(entry)) {
            return JsonParser.parseReader(new InputStreamReader(in, StandardCharsets.UTF_8));
        }
    }
}
