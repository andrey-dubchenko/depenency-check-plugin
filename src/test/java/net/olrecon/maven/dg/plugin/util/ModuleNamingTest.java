package net.olrecon.maven.dg.plugin.util;

import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.nio.file.Paths;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ModuleNamingTest {

    @Test
    void fileIdIncludesGroupSoSameArtifactIdDoesNotCollide() {
        assertNotEquals(ModuleNaming.fileId("com.a", "core"), ModuleNaming.fileId("com.b", "core"));
        assertEquals("com.a.core", ModuleNaming.fileId("com.a", "core"));
    }

    @Test
    void fileIdIsFilesystemSafe() {
        assertEquals("com.a_b.c_d", ModuleNaming.fileId("com.a/b", "c:d"));
    }

    @Test
    void displayNameIsQualifiedOnlyWhenAmbiguous() {
        assertEquals("core", ModuleNaming.displayName("com.a", "core", false));
        assertEquals("com.a:core", ModuleNaming.displayName("com.a", "core", true));
    }

    @Test
    void relativePathIsResolvedAgainstRoot() {
        Path root = Paths.get("root").toAbsolutePath();
        assertEquals(root.resolve("target").resolve("x"), ModuleNaming.resolve(root.toString(), "target/x"));
    }

    @Test
    void absolutePathIsKept() {
        Path abs = Paths.get("somewhere").toAbsolutePath().resolve("out");
        Path root = Paths.get("root").toAbsolutePath();
        assertTrue(ModuleNaming.resolve(root.toString(), abs.toString()).equals(abs.normalize()));
    }
}
