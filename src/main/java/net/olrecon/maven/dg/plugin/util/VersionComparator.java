package net.olrecon.maven.dg.plugin.util;

import org.apache.maven.artifact.versioning.ComparableVersion;

public final class VersionComparator {

    private static final String SNAPSHOT = "-SNAPSHOT";

    private VersionComparator() {
    }

    /**
     * Returns true if {@code version} is strictly lower than {@code minVersion}.
     * Uses Maven's version ordering, so qualifiers are handled correctly
     * (e.g. "2.7.18-SNAPSHOT" &gt; "2.7.2", "5.3.0.RELEASE" == "5.3.0").
     * A "-SNAPSHOT" suffix is ignored: a snapshot of 5.3.0 is a build of 5.3.0, not a lower version.
     * Unresolved property placeholders (e.g. "${revision}") cannot be compared and are never reported as lower.
     */
    public static boolean isVersionLower(String version, String minVersion) {
        if (version == null || minVersion == null) return false;
        if (version.contains("${") || minVersion.contains("${")) return false;

        return new ComparableVersion(stripSnapshot(version)).compareTo(new ComparableVersion(stripSnapshot(minVersion))) < 0;
    }

    private static String stripSnapshot(String version) {
        String v = version.trim();
        if (v.regionMatches(true, v.length() - SNAPSHOT.length(), SNAPSHOT, 0, SNAPSHOT.length())) {
            return v.substring(0, v.length() - SNAPSHOT.length());
        }
        return v;
    }
}
