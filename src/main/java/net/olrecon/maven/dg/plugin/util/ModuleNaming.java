package net.olrecon.maven.dg.plugin.util;

import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * Naming rules for per-module result files and for resolving configured paths.
 */
public final class ModuleNaming {

    public static final String TREE_SUFFIX = "-tree.json";
    public static final String ERRORS_SUFFIX = "-errors.json";

    private ModuleNaming() {
    }

    /**
     * File id of a module: groupId.artifactId (unsafe characters replaced).
     * Includes the groupId so modules with the same artifactId in different groups never collide.
     */
    public static String fileId(String groupId, String artifactId) {
        return sanitize(groupId + "." + artifactId);
    }

    /** Display name of a module: artifactId, or groupId:artifactId when the artifactId is ambiguous */
    public static String displayName(String groupId, String artifactId, boolean ambiguous) {
        return ambiguous ? groupId + ":" + artifactId : artifactId;
    }

    /** Resolves a configured path against the root directory; absolute paths are kept as they are */
    public static Path resolve(String rootDir, String configuredPath) {
        return Paths.get(rootDir).resolve(configuredPath).normalize();
    }

    private static String sanitize(String value) {
        return value.replaceAll("[^A-Za-z0-9._-]", "_");
    }
}
