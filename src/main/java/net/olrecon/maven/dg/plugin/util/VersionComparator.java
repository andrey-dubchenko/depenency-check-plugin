package net.olrecon.maven.dg.plugin.util;

public final class VersionComparator {
    private VersionComparator() {}

    public static boolean isVersionLower(String version, String minVersion) {
        if (version == null || minVersion == null) return false;

        try {
            String[] vParts = version.split("\\.");
            String[] mParts = minVersion.split("\\.");

            int maxLength = Math.max(vParts.length, mParts.length);
            for (int i = 0; i < maxLength; i++) {
                int vNum = i < vParts.length ? Integer.parseInt(vParts[i]) : 0;
                int mNum = i < mParts.length ? Integer.parseInt(mParts[i]) : 0;

                if (vNum < mNum) return true;
                if (vNum > mNum) return false;
            }
            return false;
        } catch (NumberFormatException e) {
            return version.compareTo(minVersion) < 0;
        }
    }
}