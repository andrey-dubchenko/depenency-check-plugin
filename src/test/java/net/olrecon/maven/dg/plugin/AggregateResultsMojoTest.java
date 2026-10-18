package net.olrecon.maven.dg.plugin;

import org.apache.maven.execution.MavenSession;
import org.apache.maven.plugin.MojoFailureException;
import org.apache.maven.project.MavenProject;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class AggregateResultsMojoTest {

    private static final String ERRORS_JSON = "{\"module\":\"%s\",\"errors\":[{"
            + "\"module_name\":\"%s\",\"library_group_id\":\"com.acme\",\"library_artifact_id\":\"lib-%s\","
            + "\"library_version\":\"1.0\",\"parent_version\":\"4.0.0\",\"minimum_expected_version\":\"5.0.0\","
            + "\"is_error\":true,"
            + "\"parent_chain\":[{\"group_id\":\"org.springframework\",\"artifact_id\":\"spring-parent\",\"version\":\"4.0.0\"}]"
            + "}]}";

    @TempDir
    File dir;

    private File temp;
    private AggregateResultsMojo mojo;

    @BeforeEach
    void setUp() throws Exception {
        temp = new File(dir, "target/dependency-governance-temp");
        assertTrue(temp.mkdirs());

        MavenProject root = new MavenProject();
        root.setGroupId("com.acme");
        root.setArtifactId("root");
        root.setVersion("1.0");
        root.setFile(new File(dir, "pom.xml"));
        root.setExecutionRoot(true);

        MavenProject moduleA = new MavenProject();
        moduleA.setGroupId("com.acme");
        moduleA.setArtifactId("module-a");
        moduleA.setVersion("1.0");

        MavenSession session = mock(MavenSession.class);
        when(session.getAllProjects()).thenReturn(Arrays.asList(root, moduleA));

        mojo = new AggregateResultsMojo();
        TestSupport.setField(mojo, "project", root);
        TestSupport.setField(mojo, "session", session);
        TestSupport.setField(mojo, "targetGroupId", "org.springframework,org.springframework.boot");
        TestSupport.setField(mojo, "minVersion", "5.0.0");
        TestSupport.setField(mojo, "errorFile", "target/check-error.json");
        TestSupport.setField(mojo, "treeFile", "target/deps-tree-root.json");
        TestSupport.setField(mojo, "reportFile", "target/dependency-report.html");
        TestSupport.setField(mojo, "tempDir", "target/dependency-governance-temp");
        TestSupport.setField(mojo, "failOnError", false);
        TestSupport.setField(mojo, "skipReport", false);
        TestSupport.setField(mojo, "debug", false);
    }

    private void errorsFor(String module) throws Exception {
        TestSupport.write(new File(temp, "com.acme." + module + "-errors.json"), String.format(ERRORS_JSON, module, module, module));
    }

    @Test
    void aggregatesErrorsAndKeepsParentChainInReport() throws Exception {
        errorsFor("module-a");

        mojo.execute();

        String errors = TestSupport.read(new File(dir, "target/check-error.json"));
        assertTrue(errors.contains("\"total_errors\": 1"));
        assertTrue(errors.contains("lib-module-a"));

        String html = TestSupport.read(new File(dir, "target/dependency-report.html"));
        assertTrue(html.contains("org.springframework:spring-parent:4.0.0"));
        assertTrue(html.contains("org.springframework<br>org.springframework.boot"));
    }

    @Test
    void filesOfModulesOutsideTheReactorAreIgnored() throws Exception {
        errorsFor("module-a");
        errorsFor("ghost-module");

        mojo.execute();

        String errors = TestSupport.read(new File(dir, "target/check-error.json"));
        assertTrue(errors.contains("\"total_errors\": 1"));
        assertFalse(errors.contains("ghost-module"));
    }

    @Test
    void failOnErrorThrowsFailureWhenViolationsExist() throws Exception {
        errorsFor("module-a");
        TestSupport.setField(mojo, "failOnError", true);

        assertThrows(MojoFailureException.class, () -> mojo.execute());
    }

    @Test
    void failOnErrorPassesWithoutViolations() throws Exception {
        TestSupport.write(new File(temp, "com.acme.module-a-tree.json"),
                "{\"groupId\":\"com.acme\",\"artifactId\":\"module-a\",\"version\":\"1.0\",\"children\":[]}");
        TestSupport.setField(mojo, "failOnError", true);

        mojo.execute();

        assertTrue(TestSupport.read(new File(dir, "target/check-error.json")).contains("\"total_errors\": 0"));
        assertTrue(TestSupport.read(new File(dir, "target/deps-tree-root.json")).contains("module-a"));
    }

    @Test
    void skipReportDoesNotCreateHtml() throws Exception {
        errorsFor("module-a");
        TestSupport.setField(mojo, "skipReport", true);

        mojo.execute();

        assertFalse(new File(dir, "target/dependency-report.html").exists());
    }

    @Test
    void missingTempDirectoryProducesEmptyResult() throws Exception {
        TestSupport.setField(mojo, "tempDir", "target/does-not-exist");

        mojo.execute();

        assertTrue(TestSupport.read(new File(dir, "target/check-error.json")).contains("\"total_errors\": 0"));
    }

    @Test
    void modulesWithSameArtifactIdInDifferentGroupsAreKeptApart() throws Exception {
        MavenProject root = new MavenProject();
        root.setGroupId("com.acme");
        root.setArtifactId("root");
        root.setVersion("1.0");
        root.setFile(new File(dir, "pom.xml"));
        root.setExecutionRoot(true);
        MavenProject a = new MavenProject();
        a.setGroupId("com.a");
        a.setArtifactId("core");
        a.setVersion("1.0");
        MavenProject b = new MavenProject();
        b.setGroupId("com.b");
        b.setArtifactId("core");
        b.setVersion("1.0");
        MavenSession session = mock(MavenSession.class);
        when(session.getAllProjects()).thenReturn(Arrays.asList(root, a, b));
        TestSupport.setField(mojo, "project", root);
        TestSupport.setField(mojo, "session", session);

        TestSupport.write(new File(temp, "com.a.core-errors.json"), String.format(ERRORS_JSON, "core", "core", "from-a"));
        TestSupport.write(new File(temp, "com.b.core-errors.json"), String.format(ERRORS_JSON, "core", "core", "from-b"));

        mojo.execute();

        String errors = TestSupport.read(new File(dir, "target/check-error.json"));
        assertTrue(errors.contains("\"total_errors\": 2"));
        assertTrue(errors.contains("\"com.a:core\""));
        assertTrue(errors.contains("\"com.b:core\""));
    }

    @Test
    void absoluteOutputPathIsHonoured() throws Exception {
        errorsFor("module-a");
        File out = new File(dir, "elsewhere/report.html");
        TestSupport.setField(mojo, "reportFile", out.getAbsolutePath());

        mojo.execute();

        assertTrue(out.exists());
    }

    @Test
    void nonRootProjectIsSkipped() throws Exception {
        MavenProject child = new MavenProject();
        child.setFile(new File(dir, "pom.xml"));
        child.setExecutionRoot(false);
        TestSupport.setField(mojo, "project", child);

        mojo.execute();

        assertFalse(new File(dir, "target/check-error.json").exists());
    }
}
