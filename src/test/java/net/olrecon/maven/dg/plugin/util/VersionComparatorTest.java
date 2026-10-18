package net.olrecon.maven.dg.plugin.util;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class VersionComparatorTest {

    @ParameterizedTest
    @CsvSource({
            "5.2.9, 5.3.0",
            "5.3.0, 5.10.0",
            "1.9, 1.10",
            "5.3, 5.3.1",
            "3.0.0-M1, 3.0.0",
            "6.0.0-RC1, 6.0.0",
            "5.2.9-SNAPSHOT, 5.3.0"
    })
    void lowerVersions(String version, String min) {
        assertTrue(VersionComparator.isVersionLower(version, min), version + " < " + min);
    }

    @ParameterizedTest
    @CsvSource({
            "5.3.0, 5.3.0",
            "5.3.1, 5.3.0",
            "5.10.0, 5.9.0",
            "5.3.0.RELEASE, 5.3.0",
            "2.7.18-SNAPSHOT, 2.7.2",
            "10.0.0-RC1, 9.0.0",
            "5.3, 5.3.0",
            "6, 5.3.0",
            "5.3.0-SNAPSHOT, 5.3.0",
            "5.3.0-snapshot, 5.3.0",
            "5.3.0, 5.3.0-SNAPSHOT"
    })
    void notLowerVersions(String version, String min) {
        assertFalse(VersionComparator.isVersionLower(version, min), version + " >= " + min);
    }

    @Test
    void nullsAreNeverLower() {
        assertFalse(VersionComparator.isVersionLower(null, "1.0"));
        assertFalse(VersionComparator.isVersionLower("1.0", null));
        assertFalse(VersionComparator.isVersionLower(null, null));
    }

    @Test
    void unresolvedPlaceholderIsNeverLower() {
        assertFalse(VersionComparator.isVersionLower("${revision}", "5.0.0"));
        assertFalse(VersionComparator.isVersionLower("5.0.0", "${min}"));
    }

    @Test
    void surroundingWhitespaceIsIgnored() {
        assertFalse(VersionComparator.isVersionLower(" 5.3.0 ", "5.3.0"));
        assertTrue(VersionComparator.isVersionLower("5.2.0 ", " 5.3.0"));
    }
}
