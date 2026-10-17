package lol.hub.testutils;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Bad declarations fail before any server boots. That a valid one loads is proven by booting it, in the dimension tests. */
class DeclarationsTest {

    private static Path paperJar;
    private static Path paperRepo;

    @TempDir
    Path temp;

    // Base types come from the patched jar, so a cold cache has to patch first.
    @BeforeAll
    static void patch() throws IOException {
        paperJar = Path.of(System.getProperty("testutils.paperJar"));
        paperRepo = Path.of(System.getProperty("testutils.paperRepo"));
        PaperRepo.patch(paperJar, paperRepo, List.of());
    }

    private static Declarations load(String json) throws IOException {
        return Declarations.load(json, paperJar, paperRepo);
    }

    private static void assertRejected(String json, String... expected) {
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class, () -> load(json));
        for (String part : expected) assertTrue(e.getMessage().contains(part), e.getMessage());
    }

    @Test
    void aValidDeclarationNamesItsDimensions() throws IOException {
        Declarations declarations = load("""
                [{"id": "tu:dim", "base": "minecraft:overworld", "overrides": {"monster_spawn_block_light_limit": 5}},
                 {"id": "tu:end", "base": "minecraft:the_end"}]""");
        assertEquals(List.of("tu:dim", "tu:end"), declarations.ids());
        declarations.write(temp);
        assertTrue(Files.isDirectory(temp.resolve("datapacks")));
    }

    @Test
    void anEmptyDeclarationWritesNothing() throws IOException {
        Declarations declarations = load("[]");
        assertEquals(List.of(), declarations.ids());
        declarations.write(temp);
        assertFalse(Files.exists(temp.resolve("datapacks")));
    }

    @Test
    void notAnArrayIsRejected() {
        assertRejected("{\"id\": \"tu:dim\"}", "array");
    }

    @Test
    void malformedJsonIsRejected() {
        assertRejected("[{", "JSON");
    }

    @Test
    void anEntryWithoutAnIdIsRejected() {
        assertRejected("[{\"base\": \"minecraft:overworld\"}]", "entry 0", "id");
    }

    @Test
    void anUnknownEntryKeyIsRejected() {
        assertRejected("[{\"id\": \"tu:dim\", \"base\": \"minecraft:overworld\", \"override\": {}}]", "tu:dim", "override");
    }

    @Test
    void aMinecraftIdIsRejected() {
        assertRejected("[{\"id\": \"minecraft:dim\", \"base\": \"minecraft:overworld\"}]", "minecraft:dim", "namespace");
    }

    @Test
    void anIdWithoutNamespaceIsRejected() {
        assertRejected("[{\"id\": \"dim\", \"base\": \"minecraft:overworld\"}]", "entry 0 (dim)", "namespace");
    }

    @Test
    void anInvalidIdIsRejected() {
        assertRejected("[{\"id\": \"tu:Dim!\", \"base\": \"minecraft:overworld\"}]", "tu:Dim!", "resource location");
    }

    @Test
    void anIdLeavingThePackIsRejected() {
        assertRejected("[{\"id\": \"tu:../../x\", \"base\": \"minecraft:overworld\"}]", "tu:../../x", "resource location");
    }

    @Test
    void aDuplicateIdIsRejected() {
        assertRejected("""
                [{"id": "tu:dim", "base": "minecraft:overworld"},
                 {"id": "tu:dim", "base": "minecraft:the_end"}]""", "entry 1 (tu:dim)", "duplicate");
    }

    @Test
    void anUnknownBaseIsRejected() {
        assertRejected("[{\"id\": \"tu:dim\", \"base\": \"minecraft:moon\"}]", "tu:dim", "minecraft:moon", "no dimension type");
    }

    @Test
    void aRangeBelowTheSurfaceIsRejected() {
        assertRejected("[{\"id\": \"tu:dim\", \"base\": \"minecraft:overworld\", \"overrides\": {\"min_y\": 64, \"height\": 256}}]",
                "tu:dim", "min_y 64");
    }

    @Test
    void aRangeWithoutRoomAboveTheSurfaceIsRejected() {
        // The Nether's 256 blocks from y 0 hold the surface and its air; 64 do not.
        assertRejected("[{\"id\": \"tu:dim\", \"base\": \"minecraft:the_nether\", \"overrides\": {\"height\": 64}}]",
                "tu:dim", "height 64");
    }

    @Test
    void aFractionalMinYIsRejected() {
        assertRejected("[{\"id\": \"tu:dim\", \"base\": \"minecraft:overworld\", \"overrides\": {\"min_y\": 0.5}}]",
                "tu:dim", "min_y is 0.5");
    }

    @Test
    void aNonNumericHeightIsRejected() {
        assertRejected("[{\"id\": \"tu:dim\", \"base\": \"minecraft:overworld\", \"overrides\": {\"height\": \"tall\"}}]",
                "tu:dim", "height");
    }
}
