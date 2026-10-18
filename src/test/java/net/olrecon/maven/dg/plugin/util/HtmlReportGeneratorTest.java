package net.olrecon.maven.dg.plugin.util;

import net.olrecon.maven.dg.plugin.model.ParentInfo;
import net.olrecon.maven.dg.plugin.model.ParentVersionIssue;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class HtmlReportGeneratorTest {

    private ParentVersionIssue issue(String source) {
        ParentVersionIssue issue = new ParentVersionIssue("mod");
        issue.setLibrary("com.acme", "lib", "1.0");
        issue.setParentVersion("4.0.0");
        issue.setMinExpectedVersion("5.0.0");
        issue.setError(true);
        if (source != null) {
            issue.setSourceLibrary(source);
            issue.setSourceVersion("2.0");
        }
        issue.setParentChain(Collections.singletonList(new ParentInfo("org.springframework", "spring-parent", "4.0.0")));
        return issue;
    }

    private String html(List<String> groups, Map<String, List<ParentVersionIssue>> errors, int total) {
        return new HtmlReportGenerator("g:p:1", groups, "5.0.0", "now", total, errors).generate();
    }

    @Test
    void multipleTargetGroupsAreSeparatedByRealLineBreak() {
        String html = html(Arrays.asList("org.springframework", "org.springframework.boot"),
                new LinkedHashMap<>(), 0);

        assertTrue(html.contains("org.springframework<br>org.springframework.boot"));
        assertFalse(html.contains("&lt;br&gt;"));
    }

    @Test
    void targetGroupsAreEscaped() {
        String html = html(Collections.singletonList("<script>x</script>"), new LinkedHashMap<>(), 0);

        assertFalse(html.contains("<script>x</script>"));
        assertTrue(html.contains("&lt;script&gt;x&lt;/script&gt;"));
    }

    @Test
    void successBannerWhenNoErrors() {
        String html = html(Collections.singletonList("g"), new LinkedHashMap<>(), 0);

        assertTrue(html.contains("PASSED"));
        assertTrue(html.contains("All dependencies are compliant"));
        assertFalse(html.contains("Violations by Module"));
    }

    @Test
    void violationsTableContainsChainAndSource() {
        Map<String, List<ParentVersionIssue>> errors = new LinkedHashMap<>();
        errors.put("mod", Collections.singletonList(issue("mid")));

        String html = html(Collections.singletonList("org.springframework"), errors, 1);

        assertTrue(html.contains("FAILED"));
        assertTrue(html.contains("Violations by Module"));
        assertTrue(html.contains("com.acme:lib"));
        assertTrue(html.contains("mid:2.0"));
        assertTrue(html.contains("org.springframework:spring-parent:4.0.0"));
        assertTrue(html.contains("1 violation<"));
    }

    @Test
    void issueWithoutSourceIsMarkedDirect() {
        Map<String, List<ParentVersionIssue>> errors = new LinkedHashMap<>();
        errors.put("mod", Collections.singletonList(issue(null)));

        assertTrue(html(Collections.singletonList("g"), errors, 1).contains(">direct<"));
    }

    @Test
    void valuesAreHtmlEscaped() {
        Map<String, List<ParentVersionIssue>> errors = new LinkedHashMap<>();
        errors.put("m<od>", Collections.singletonList(issue("<b>evil</b>")));

        String html = html(Collections.singletonList("g"), errors, 1);

        assertFalse(html.contains("<b>evil</b>"));
        assertFalse(html.contains("m<od>"));
        assertTrue(html.contains("&lt;b&gt;evil&lt;/b&gt;"));
    }

    @Test
    void nullChainAndPluralisation() {
        ParentVersionIssue a = issue(null);
        a.setParentChain(null);
        Map<String, List<ParentVersionIssue>> errors = new LinkedHashMap<>();
        errors.put("mod", Arrays.asList(a, issue(null)));

        assertTrue(html(Collections.singletonList("g"), errors, 2).contains("2 violations"));
    }
}
