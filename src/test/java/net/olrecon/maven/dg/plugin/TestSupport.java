package net.olrecon.maven.dg.plugin;

import org.apache.maven.artifact.Artifact;
import org.apache.maven.artifact.DefaultArtifact;
import org.apache.maven.artifact.handler.DefaultArtifactHandler;

import java.io.File;
import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

/** Shared helpers for tests */
public final class TestSupport {

    private TestSupport() {
    }

    /** Sets a (possibly private) field, searching the class hierarchy. Mojo @Parameter defaults are not applied outside Maven. */
    public static void setField(Object target, String name, Object value) {
        Class<?> c = target.getClass();
        while (c != null) {
            try {
                Field f = c.getDeclaredField(name);
                f.setAccessible(true);
                f.set(target, value);
                return;
            } catch (NoSuchFieldException e) {
                c = c.getSuperclass();
            } catch (IllegalAccessException e) {
                throw new IllegalStateException(e);
            }
        }
        throw new IllegalArgumentException("No field " + name + " in " + target.getClass());
    }

    public static void write(File file, String content) throws Exception {
        Files.write(file.toPath(), content.getBytes(StandardCharsets.UTF_8));
    }

    public static String read(File file) throws Exception {
        return new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8);
    }

    public static Artifact artifact(String g, String a, String v, String scope) {
        return new DefaultArtifact(g, a, v, scope, "jar", null, new DefaultArtifactHandler("jar"));
    }
}
