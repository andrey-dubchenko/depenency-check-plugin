package net.olrecon.maven.dg.plugin.service;

import net.olrecon.maven.dg.plugin.TestSupport;
import org.apache.maven.artifact.Artifact;
import org.apache.maven.artifact.resolver.ArtifactResolutionRequest;
import org.apache.maven.artifact.resolver.ArtifactResolutionResult;
import org.apache.maven.artifact.resolver.ArtifactResolver;
import org.apache.maven.execution.MavenSession;
import org.apache.maven.plugin.logging.SystemStreamLog;
import org.apache.maven.project.MavenProject;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.Mockito;

import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ParentChainBuilderTest {

    @TempDir
    File dir;

    private ArtifactResolver resolver;
    private MavenSession session;
    private final List<MavenProject> reactor = new ArrayList<>();

    @BeforeEach
    void setUp() {
        resolver = mock(ArtifactResolver.class);
        session = mock(MavenSession.class);
        when(session.getAllProjects()).thenReturn(reactor);

        // Anything outside the reactor is "not resolvable"
        // ArtifactResolutionResult without errors reports success; make resolution fail explicitly
        ArtifactResolutionResult failed = mock(ArtifactResolutionResult.class);
        when(failed.isSuccess()).thenReturn(false);
        when(resolver.resolve(any(ArtifactResolutionRequest.class))).thenReturn(failed);
    }

    private ParentChainBuilder builder(String... targetGroups) {
        return new ParentChainBuilder(resolver, null, Collections.emptyList(),
                Arrays.asList(targetGroups), session, new SystemStreamLog());
    }

    private MavenProject project(String name, String g, String a, String v, String parentXml) throws Exception {
        File pom = new File(dir, name + ".xml");
        String xml = "<project><modelVersion>4.0.0</modelVersion>"
                + (parentXml == null ? "" : parentXml)
                + "<groupId>" + g + "</groupId><artifactId>" + a + "</artifactId><version>" + v + "</version>"
                + "</project>";
        TestSupport.write(pom, xml);
        MavenProject p = new MavenProject();
        p.setGroupId(g);
        p.setArtifactId(a);
        p.setVersion(v);
        p.setFile(pom);
        reactor.add(p);
        return p;
    }

    private static String parent(String g, String a, String v) {
        return "<parent><groupId>" + g + "</groupId><artifactId>" + a + "</artifactId><version>" + v + "</version></parent>";
    }

    private Artifact artifact(String g, String a, String v) {
        Artifact artifact = mock(Artifact.class);
        when(artifact.getGroupId()).thenReturn(g);
        when(artifact.getArtifactId()).thenReturn(a);
        when(artifact.getVersion()).thenReturn(v);
        return artifact;
    }

    @Test
    void artifactWithoutParentHasEmptyChain() throws Exception {
        project("lib", "com.acme", "lib", "1.0", null);

        ParentChainBuilder.ParentChain chain = builder("org.springframework")
                .buildParentChain(artifact("com.acme", "lib", "1.0"));

        assertTrue(chain.getParents().isEmpty());
        assertFalse(chain.isHasTargetGroup());
    }

    @Test
    void chainIsWalkedThroughReactorParents() throws Exception {
        project("grand", "com.acme", "grand", "3.0", null);
        project("mid", "com.acme", "mid", "2.0", parent("com.acme", "grand", "3.0"));
        project("lib", "com.acme", "lib", "1.0", parent("com.acme", "mid", "2.0"));

        ParentChainBuilder.ParentChain chain = builder("org.springframework")
                .buildParentChain(artifact("com.acme", "lib", "1.0"));

        assertEquals(2, chain.getParents().size());
        assertEquals("mid", chain.getParents().get(0).getArtifactId());
        assertEquals("grand", chain.getParents().get(1).getArtifactId());
        assertFalse(chain.isHasTargetGroup());
        assertEquals("lib", chain.getArtifactId());
    }

    @Test
    void targetGroupParentIsDetectedEvenWhenNotResolvable() throws Exception {
        project("lib", "com.acme", "lib", "1.0", parent("org.springframework", "spring-parent", "4.0.0"));

        ParentChainBuilder.ParentChain chain = builder("org.springframework")
                .buildParentChain(artifact("com.acme", "lib", "1.0"));

        assertTrue(chain.isHasTargetGroup());
        assertEquals("4.0.0", chain.getTargetParentVersion());
        assertEquals(1, chain.getParents().size());
    }

    @Test
    void targetGroupMatchingIsExact() throws Exception {
        project("lib", "com.acme", "lib", "1.0", parent("org.springframework.boot", "boot-parent", "2.0.0"));

        assertFalse(builder("org.springframework").buildParentChain(artifact("com.acme", "lib", "1.0"))
                .isHasTargetGroup());
        assertTrue(builder("org.springframework", "org.springframework.boot")
                .buildParentChain(artifact("com.acme", "lib", "1.0")).isHasTargetGroup());
    }

    @Test
    void emptyTargetGroupsDisableChecking() throws Exception {
        project("lib", "com.acme", "lib", "1.0", parent("org.springframework", "spring-parent", "4.0.0"));

        ParentChainBuilder.ParentChain chain = builder().buildParentChain(artifact("com.acme", "lib", "1.0"));

        assertEquals(1, chain.getParents().size());
        assertFalse(chain.isHasTargetGroup());
        assertEquals(null, chain.getTargetParentVersion());
    }

    @Test
    void topmostTargetGroupParentWins() throws Exception {
        project("top", "org.springframework", "top", "6.0.0", null);
        project("mid", "org.springframework", "mid", "5.0.0", parent("org.springframework", "top", "6.0.0"));
        project("lib", "com.acme", "lib", "1.0", parent("org.springframework", "mid", "5.0.0"));

        ParentChainBuilder.ParentChain chain = builder("org.springframework")
                .buildParentChain(artifact("com.acme", "lib", "1.0"));

        assertEquals(2, chain.getParents().size());
        assertEquals("6.0.0", chain.getTargetParentVersion());
        assertEquals("org.springframework", chain.getTargetParentGroupId());
        assertEquals("top", chain.getTargetParentArtifactId());
    }

    @Test
    void pomResolvedFlagReflectsAvailabilityOfArtifactPom() throws Exception {
        project("lib", "com.acme", "lib", "1.0", null);

        assertTrue(builder("org.springframework").buildParentChain(artifact("com.acme", "lib", "1.0")).isPomResolved());
        assertFalse(builder("org.springframework").buildParentChain(artifact("com.other", "x", "1")).isPomResolved());
    }

    @Test
    void cyclicParentsDoNotLoopForever() throws Exception {
        project("a", "com.acme", "a", "1.0", parent("com.acme", "b", "1.0"));
        project("b", "com.acme", "b", "1.0", parent("com.acme", "a", "1.0"));

        ParentChainBuilder.ParentChain chain = builder("org.springframework")
                .buildParentChain(artifact("com.acme", "a", "1.0"));

        assertEquals(2, chain.getParents().size());
    }

    @Test
    void unknownArtifactGivesEmptyChain() throws Exception {
        ParentChainBuilder.ParentChain chain = builder("org.springframework")
                .buildParentChain(artifact("com.other", "unknown", "9.9"));

        assertTrue(chain.getParents().isEmpty());
        Mockito.verify(resolver).resolve(any(ArtifactResolutionRequest.class));
    }
}
