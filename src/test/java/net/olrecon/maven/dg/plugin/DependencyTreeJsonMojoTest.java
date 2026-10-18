package net.olrecon.maven.dg.plugin;

import org.apache.maven.artifact.Artifact;
import org.apache.maven.artifact.resolver.ArtifactResolver;
import org.apache.maven.execution.MavenSession;
import org.apache.maven.project.DefaultProjectBuildingRequest;
import org.apache.maven.project.MavenProject;
import org.apache.maven.shared.dependency.graph.DependencyGraphBuilder;
import org.apache.maven.shared.dependency.graph.DependencyNode;
import org.apache.maven.shared.dependency.graph.internal.DefaultDependencyNode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class DependencyTreeJsonMojoTest {

    @TempDir
    File dir;

    private DependencyTreeJsonMojo mojo;
    private MavenProject project;

    @BeforeEach
    void setUp() throws Exception {
        project = new MavenProject();
        project.setGroupId("com.acme");
        project.setArtifactId("app");
        project.setVersion("1.0");
        project.setFile(new File(dir, "pom.xml"));
        project.setArtifact(TestSupport.artifact("com.acme", "app", "1.0", null));

        MavenSession session = mock(MavenSession.class);
        when(session.getTopLevelProject()).thenReturn(project);
        when(session.getAllProjects()).thenReturn(Collections.singletonList(project));
        when(session.getProjectBuildingRequest()).thenReturn(new DefaultProjectBuildingRequest());


        DefaultDependencyNode root = new DefaultDependencyNode(null, project.getArtifact(), null, null, null);
        List<DependencyNode> children = new ArrayList<>();
        children.add(child(root, "compile-lib", "compile", false));
        children.add(child(root, "test-lib", "test", false));
        children.add(child(root, "system-lib", "system", false));
        children.add(child(root, "optional-lib", "compile", true));
        root.setChildren(children);

        DependencyGraphBuilder graphBuilder = mock(DependencyGraphBuilder.class);
        when(graphBuilder.buildDependencyGraph(any(), any())).thenReturn(root);

        mojo = new DependencyTreeJsonMojo();
        TestSupport.setField(mojo, "session", session);
        TestSupport.setField(mojo, "project", project);
        TestSupport.setField(mojo, "dependencyGraphBuilder", graphBuilder);
        TestSupport.setField(mojo, "artifactResolver", mock(ArtifactResolver.class));
        TestSupport.setField(mojo, "remoteArtifactRepositories", Collections.emptyList());
        TestSupport.setField(mojo, "outputFile", "target/dependency-tree.json");
        TestSupport.setField(mojo, "includeScopes", "");
        TestSupport.setField(mojo, "excludeScopes", "system");
        TestSupport.setField(mojo, "includeOptional", false);
        TestSupport.setField(mojo, "includeTestScope", false);
    }

    private DependencyNode child(DependencyNode parent, String artifactId, String scope, boolean optional) {
        Artifact artifact = TestSupport.artifact("com.acme", artifactId, "1.0", scope);
        artifact.setOptional(optional);
        DefaultDependencyNode node = new DefaultDependencyNode(parent, artifact, null, null, null);
        node.setChildren(Collections.emptyList());
        return node;
    }

    private String run() throws Exception {
        mojo.execute();
        return TestSupport.read(new File(dir, "target/dependency-tree.json"));
    }

    @Test
    void defaultsExcludeTestSystemAndOptional() throws Exception {
        String json = run();

        assertTrue(json.contains("compile-lib"));
        assertFalse(json.contains("test-lib"));
        assertFalse(json.contains("system-lib"));
        assertFalse(json.contains("optional-lib"));
    }

    @Test
    void testScopeAndOptionalCanBeIncluded() throws Exception {
        TestSupport.setField(mojo, "includeTestScope", true);
        TestSupport.setField(mojo, "includeOptional", true);

        String json = run();

        assertTrue(json.contains("test-lib"));
        assertTrue(json.contains("optional-lib"));
        assertFalse(json.contains("system-lib"));
    }

    @Test
    void includeScopesRestrictsOutput() throws Exception {
        TestSupport.setField(mojo, "includeScopes", "test");
        TestSupport.setField(mojo, "includeTestScope", true);

        String json = run();

        assertTrue(json.contains("test-lib"));
        assertFalse(json.contains("compile-lib"));
    }

    @Test
    void absoluteOutputFileIsHonoured() throws Exception {
        File out = new File(dir, "elsewhere/tree.json");
        TestSupport.setField(mojo, "outputFile", out.getAbsolutePath());

        mojo.execute();

        assertTrue(out.exists());
    }
}
