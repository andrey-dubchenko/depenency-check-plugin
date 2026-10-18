package net.olrecon.maven.dg.plugin.service;

import net.olrecon.maven.dg.plugin.TestSupport;
import net.olrecon.maven.dg.plugin.model.DependencySource;
import net.olrecon.maven.dg.plugin.model.MavenDependencyTree;
import net.olrecon.maven.dg.plugin.model.ParentInfo;
import org.apache.maven.artifact.Artifact;
import org.apache.maven.execution.MavenSession;
import org.apache.maven.plugin.logging.SystemStreamLog;
import org.apache.maven.project.DefaultProjectBuildingRequest;
import org.apache.maven.project.MavenProject;
import org.apache.maven.shared.dependency.graph.DependencyGraphBuilder;
import org.apache.maven.shared.dependency.graph.DependencyNode;
import org.apache.maven.shared.dependency.graph.internal.DefaultDependencyNode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class DependencyGraphAnalyzerTest {

    private MavenProject project;
    private MavenSession session;
    private DependencyGraphBuilder graphBuilder;
    private ParentChainBuilder chainBuilder;

    @BeforeEach
    void setUp() {
        project = new MavenProject();
        project.setGroupId("com.acme");
        project.setArtifactId("app");
        project.setVersion("1.0");
        project.setArtifact(TestSupport.artifact("com.acme", "app", "1.0", null));

        session = mock(MavenSession.class);
        when(session.getProjectBuildingRequest()).thenReturn(new DefaultProjectBuildingRequest());
        graphBuilder = mock(DependencyGraphBuilder.class);
        chainBuilder = mock(ParentChainBuilder.class);
    }

    private DefaultDependencyNode node(DependencyNode parent, String a, String v) {
        Artifact artifact = TestSupport.artifact("com.acme", a, v, "compile");
        return new DefaultDependencyNode(parent, artifact, null, null, null);
    }

    private DependencyGraphAnalyzer analyzer(String minVersion) {
        return new DependencyGraphAnalyzer(project, session, graphBuilder, chainBuilder, minVersion,
                new SystemStreamLog());
    }

    /** app -> direct -> transitive -> deep */
    private DefaultDependencyNode graph() {
        DefaultDependencyNode root = node(null, "app", "1.0");
        DefaultDependencyNode direct = node(root, "direct", "1.0");
        DefaultDependencyNode transitive = node(direct, "transitive", "2.0");
        DefaultDependencyNode deep = node(transitive, "deep", "3.0");
        root.setChildren(Collections.singletonList(direct));
        direct.setChildren(Collections.singletonList(transitive));
        transitive.setChildren(Collections.singletonList(deep));
        deep.setChildren(Collections.emptyList());
        return root;
    }

    private ParentChainBuilder.ParentChain chain(String g, String a, String v) {
        return new ParentChainBuilder.ParentChain(g, a, v);
    }

    @Test
    void buildsTreeStartingFromDirectDependencies() throws Exception {
        when(graphBuilder.buildDependencyGraph(any(), any())).thenReturn(graph());
        when(chainBuilder.buildParentChain(any())).thenReturn(chain("g", "a", "1"));

        MavenDependencyTree tree = analyzer("5.0.0").buildDependencyTree("app");

        assertEquals("app", tree.getModule());
        assertEquals(1, tree.getChildren().size());
        MavenDependencyTree direct = tree.getChildren().get(0);
        assertEquals("direct", direct.getArtifactId());
        assertEquals("transitive", direct.getChildren().get(0).getArtifactId());
        assertEquals("deep", direct.getChildren().get(0).getChildren().get(0).getArtifactId());
    }

    @Test
    void emptyGraphGivesNullTree() throws Exception {
        when(graphBuilder.buildDependencyGraph(any(), any())).thenReturn(null);

        assertNull(analyzer("5.0.0").buildDependencyTree("app"));
    }

    @Test
    void directDependencyHasNoSource() throws Exception {
        when(graphBuilder.buildDependencyGraph(any(), any())).thenReturn(graph());
        when(chainBuilder.buildParentChain(any())).thenReturn(chain("g", "a", "1"));

        DependencyGraphAnalyzer analyzer = analyzer("5.0.0");
        analyzer.buildDependencyTree("app");

        assertFalse(analyzer.getDependencySources().containsKey("com.acme:direct:1.0"));
    }

    @Test
    void transitiveDependencySourceIsKeyedByGavAndHasFullPath() throws Exception {
        when(graphBuilder.buildDependencyGraph(any(), any())).thenReturn(graph());
        when(chainBuilder.buildParentChain(any())).thenReturn(chain("g", "a", "1"));

        DependencyGraphAnalyzer analyzer = analyzer("5.0.0");
        analyzer.buildDependencyTree("app");

        List<DependencySource> sources = analyzer.getDependencySources().get("com.acme:deep:3.0");
        assertNotNull(sources);
        DependencySource source = sources.get(0);
        assertEquals("transitive", source.getSourceArtifactId());
        assertEquals("2.0", source.getSourceVersion());
        assertEquals(Arrays.asList("com.acme:direct:1.0", "com.acme:transitive:2.0", "com.acme:deep:3.0"),
                source.getPath());

        DependencySource second = analyzer.getDependencySources().get("com.acme:transitive:2.0").get(0);
        assertEquals("direct", second.getSourceArtifactId());
        assertEquals(Arrays.asList("com.acme:direct:1.0", "com.acme:transitive:2.0"), second.getPath());
    }

    @Test
    void lowParentVersionIsFlagged() throws Exception {
        when(graphBuilder.buildDependencyGraph(any(), any())).thenReturn(graph());
        ParentChainBuilder.ParentChain low = chain("com.acme", "direct", "1.0");
        low.addParent("org.springframework", "spring-parent", "4.0.0", Collections.singletonList("org.springframework"));
        when(chainBuilder.buildParentChain(any())).thenReturn(low);

        MavenDependencyTree tree = analyzer("5.0.0").buildDependencyTree("app");

        net.olrecon.maven.dg.plugin.model.ParentVersionInfo info = tree.getChildren().get(0).getParentVersionInfo();
        assertNotNull(info);
        assertTrue(info.isHasTargetGroup());
        assertTrue(info.isLowVersion());
        assertEquals("4.0.0", info.getTargetParentVersion());
        assertEquals("5.0.0", info.getMinimumExpected());
        ParentInfo p = info.getParentChain().get(0);
        assertEquals("spring-parent", p.getArtifactId());
    }

    @Test
    void sufficientParentVersionIsNotFlagged() throws Exception {
        when(graphBuilder.buildDependencyGraph(any(), any())).thenReturn(graph());
        ParentChainBuilder.ParentChain ok = chain("com.acme", "direct", "1.0");
        ok.addParent("org.springframework", "spring-parent", "5.3.0", Collections.singletonList("org.springframework"));
        when(chainBuilder.buildParentChain(any())).thenReturn(ok);

        MavenDependencyTree tree = analyzer("5.0.0").buildDependencyTree("app");

        assertFalse(tree.getChildren().get(0).getParentVersionInfo().isLowVersion());
    }

    @Test
    void emptyChainOrFailureGivesNoParentInfo() throws Exception {
        when(graphBuilder.buildDependencyGraph(any(), any())).thenReturn(graph());
        when(chainBuilder.buildParentChain(any())).thenThrow(new RuntimeException("boom"));

        MavenDependencyTree tree = analyzer("5.0.0").buildDependencyTree("app");

        assertNull(tree.getChildren().get(0).getParentVersionInfo());
    }

    @Test
    void cyclesWithinABranchAreCut() throws Exception {
        DefaultDependencyNode root = node(null, "app", "1.0");
        DefaultDependencyNode a = node(root, "a", "1.0");
        DefaultDependencyNode b = node(a, "b", "1.0");
        DefaultDependencyNode aAgain = node(b, "a", "1.0");
        root.setChildren(Collections.singletonList(a));
        a.setChildren(Collections.singletonList(b));
        b.setChildren(Collections.singletonList(aAgain));
        aAgain.setChildren(Collections.singletonList(b));
        when(graphBuilder.buildDependencyGraph(any(), any())).thenReturn(root);
        when(chainBuilder.buildParentChain(any())).thenReturn(chain("g", "a", "1"));

        MavenDependencyTree tree = analyzer("5.0.0").buildDependencyTree("app");

        MavenDependencyTree bNode = tree.getChildren().get(0).getChildren().get(0);
        assertEquals("b", bNode.getArtifactId());
        assertTrue(bNode.getChildren().isEmpty());
    }
}
