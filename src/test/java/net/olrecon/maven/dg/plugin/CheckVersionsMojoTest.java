package net.olrecon.maven.dg.plugin;

import org.apache.maven.artifact.Artifact;
import org.apache.maven.artifact.resolver.ArtifactResolutionRequest;
import org.apache.maven.artifact.resolver.ArtifactResolutionResult;
import org.apache.maven.artifact.resolver.ArtifactResolver;
import org.apache.maven.execution.MavenSession;
import org.apache.maven.plugin.MojoFailureException;
import org.apache.maven.project.DefaultProjectBuildingRequest;
import org.apache.maven.project.MavenProject;
import org.apache.maven.shared.dependency.graph.DependencyGraphBuilder;
import org.apache.maven.shared.dependency.graph.internal.DefaultDependencyNode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class CheckVersionsMojoTest {

    @TempDir
    File dir;

    private MavenProject project;
    private final List<MavenProject> reactor = new ArrayList<>();
    private DependencyGraphBuilder graphBuilder;
    private CheckVersionsMojo mojo;
    private File temp;

    @BeforeEach
    void setUp() throws Exception {
        project = new MavenProject();
        project.setGroupId("com.acme");
        project.setArtifactId("app");
        project.setVersion("1.0");
        project.setFile(new File(dir, "pom.xml"));
        project.setArtifact(TestSupport.artifact("com.acme", "app", "1.0", null));
        reactor.add(project);

        MavenSession session = mock(MavenSession.class);
        when(session.getTopLevelProject()).thenReturn(project);
        when(session.getAllProjects()).thenReturn(reactor);
        when(session.getProjectBuildingRequest()).thenReturn(new DefaultProjectBuildingRequest());

        ArtifactResolver resolver = mock(ArtifactResolver.class);
        ArtifactResolutionResult failed = mock(ArtifactResolutionResult.class);
        when(failed.isSuccess()).thenReturn(false);
        when(resolver.resolve(any(ArtifactResolutionRequest.class))).thenReturn(failed);

        graphBuilder = mock(DependencyGraphBuilder.class);
        setGraph(Collections.emptyList());

        mojo = new CheckVersionsMojo();
        TestSupport.setField(mojo, "project", project);
        TestSupport.setField(mojo, "session", session);
        TestSupport.setField(mojo, "artifactResolver", resolver);
        TestSupport.setField(mojo, "dependencyGraphBuilder", graphBuilder);
        TestSupport.setField(mojo, "remoteArtifactRepositories", Collections.emptyList());
        TestSupport.setField(mojo, "targetGroupId", "org.springframework");
        TestSupport.setField(mojo, "minVersion", "5.0.0");
        TestSupport.setField(mojo, "includeGroups", "");
        TestSupport.setField(mojo, "excludeGroups", "");
        TestSupport.setField(mojo, "tempDir", "target/dependency-governance-temp");
        TestSupport.setField(mojo, "failOnError", false);
        TestSupport.setField(mojo, "debug", false);

        temp = new File(dir, "target/dependency-governance-temp");
    }

    /** Graph: app -> mid -> each of the given library artifactIds */
    private void setGraph(List<String> libs) throws Exception {
        DefaultDependencyNode root = new DefaultDependencyNode(null, project.getArtifact(), null, null, null);
        DefaultDependencyNode mid = new DefaultDependencyNode(root, TestSupport.artifact("com.acme", "mid", "2.0", "compile"),
                null, null, null);
        List<DefaultDependencyNode> children = new ArrayList<>();
        for (String lib : libs) {
            DefaultDependencyNode n = new DefaultDependencyNode(mid, TestSupport.artifact("com.acme", lib, "1.0", "compile"),
                    null, null, null);
            n.setChildren(Collections.emptyList());
            children.add(n);
        }
        mid.setChildren(new ArrayList<>(children));
        root.setChildren(Collections.singletonList(mid));
        when(graphBuilder.buildDependencyGraph(any(), any())).thenReturn(root);
    }

    /** Registers a library (reactor project with a given parent) and makes it a resolved artifact of the module */
    private void addLibrary(String artifactId, String parentVersion) throws Exception {
        File pom = new File(dir, artifactId + ".xml");
        TestSupport.write(pom, "<project><modelVersion>4.0.0</modelVersion>"
                + "<parent><groupId>org.springframework</groupId><artifactId>spring-parent</artifactId>"
                + "<version>" + parentVersion + "</version></parent>"
                + "<groupId>com.acme</groupId><artifactId>" + artifactId + "</artifactId><version>1.0</version></project>");
        MavenProject lib = new MavenProject();
        lib.setGroupId("com.acme");
        lib.setArtifactId(artifactId);
        lib.setVersion("1.0");
        lib.setFile(pom);
        reactor.add(lib);

        LinkedHashSet<Artifact> artifacts = new LinkedHashSet<>(project.getArtifacts());
        artifacts.add(TestSupport.artifact("com.acme", artifactId, "1.0", "compile"));
        project.setArtifacts(artifacts);
    }

    @Test
    void writesErrorsFileWithSourceAndParentChain() throws Exception {
        addLibrary("old-lib", "4.0.0");
        setGraph(Collections.singletonList("old-lib"));

        mojo.execute();

        String errors = TestSupport.read(new File(temp, "com.acme.app-errors.json"));
        assertTrue(errors.contains("old-lib"));
        assertTrue(errors.contains("\"source_library\": \"mid\""));
        assertTrue(errors.contains("spring-parent"));
        assertTrue(new File(temp, "com.acme.app-tree.json").exists());
    }

    @Test
    void errorsFileContainsTargetParentCoordinates() throws Exception {
        addLibrary("old-lib", "4.0.0");
        setGraph(Collections.singletonList("old-lib"));

        mojo.execute();

        String errors = TestSupport.read(new File(temp, "com.acme.app-errors.json"));
        assertTrue(errors.contains("\"parent_group_id\": \"org.springframework\""));
        assertTrue(errors.contains("\"parent_artifact_id\": \"spring-parent\""));
        assertTrue(errors.contains("\"parent_version\": \"4.0.0\""));
    }

    @Test
    void staleFilesAreRemovedEvenWhenTheRunFails() throws Exception {
        assertTrue(temp.mkdirs());
        File stale = new File(temp, "com.acme.app-errors.json");
        TestSupport.write(stale, "{\"module\":\"app\",\"errors\":[]}");
        when(graphBuilder.buildDependencyGraph(any(), any())).thenThrow(new RuntimeException("boom"));

        assertThrows(Exception.class, () -> mojo.execute());

        assertFalse(stale.exists());
    }

    @Test
    void absoluteTempDirIsHonoured() throws Exception {
        File abs = new File(dir, "custom-temp");
        TestSupport.setField(mojo, "tempDir", abs.getAbsolutePath());

        mojo.execute();

        assertTrue(new File(abs, "com.acme.app-tree.json").exists());
    }

    @Test
    void compliantLibraryProducesNoErrorsFile() throws Exception {
        addLibrary("new-lib", "5.3.0");
        setGraph(Collections.singletonList("new-lib"));

        mojo.execute();

        assertFalse(new File(temp, "com.acme.app-errors.json").exists());
    }

    @Test
    void staleErrorsFromPreviousRunAreRemoved() throws Exception {
        assertTrue(temp.mkdirs());
        File stale = new File(temp, "com.acme.app-errors.json");
        TestSupport.write(stale, "{\"module\":\"app\",\"errors\":[]}");

        mojo.execute();

        assertFalse(stale.exists());
        assertTrue(new File(temp, "com.acme.app-tree.json").exists());
    }

    @Test
    void failOnErrorThrowsFailureForLowVersion() throws Exception {
        addLibrary("old-lib", "4.0.0");
        setGraph(Collections.singletonList("old-lib"));
        TestSupport.setField(mojo, "failOnError", true);

        assertThrows(MojoFailureException.class, () -> mojo.execute());
        assertTrue(new File(temp, "com.acme.app-errors.json").exists());
    }

    @Test
    void excludedGroupsAreNotAnalyzed() throws Exception {
        addLibrary("old-lib", "4.0.0");
        setGraph(Collections.singletonList("old-lib"));
        TestSupport.setField(mojo, "excludeGroups", "com.acme");

        mojo.execute();

        assertFalse(new File(temp, "com.acme.app-errors.json").exists());
    }

    @Test
    void includeGroupsRestrictsAnalysis() throws Exception {
        addLibrary("old-lib", "4.0.0");
        setGraph(Collections.singletonList("old-lib"));
        TestSupport.setField(mojo, "includeGroups", "org.other, net.foo");

        mojo.execute();

        assertFalse(new File(temp, "com.acme.app-errors.json").exists());
    }

    @Test
    void multipleTargetGroupsAreParsed() throws Exception {
        addLibrary("old-lib", "4.0.0");
        setGraph(Collections.singletonList("old-lib"));
        TestSupport.setField(mojo, "targetGroupId", " org.unrelated , org.springframework ");

        mojo.execute();

        assertTrue(new File(temp, "com.acme.app-errors.json").exists());
    }
}
