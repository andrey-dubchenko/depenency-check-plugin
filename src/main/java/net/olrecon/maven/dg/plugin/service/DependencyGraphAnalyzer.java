package net.olrecon.maven.dg.plugin.service;

import net.olrecon.maven.dg.plugin.model.DependencySource;
import net.olrecon.maven.dg.plugin.model.MavenDependencyTree;
import net.olrecon.maven.dg.plugin.model.ParentVersionInfo;
import net.olrecon.maven.dg.plugin.util.VersionComparator;
import org.apache.maven.artifact.Artifact;
import org.apache.maven.execution.MavenSession;
import org.apache.maven.plugin.logging.Log;
import org.apache.maven.project.DefaultProjectBuildingRequest;
import org.apache.maven.project.MavenProject;
import org.apache.maven.project.ProjectBuildingRequest;
import org.apache.maven.shared.dependency.graph.DependencyGraphBuilder;
import org.apache.maven.shared.dependency.graph.DependencyGraphBuilderException;
import org.apache.maven.shared.dependency.graph.DependencyNode;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Builds the dependency tree for a Maven module and enriches each node
 * with information about the parent POM chain.
 */
public class DependencyGraphAnalyzer {

    private final MavenProject project;
    private final MavenSession session;
    private final DependencyGraphBuilder dependencyGraphBuilder;
    private final ParentChainBuilder parentChainBuilder;

    /**
     * Minimum allowed parent version from the target group.
     * null means no version checking is performed (tree-building-only mode).
     */
    private final String minVersion;

    private final Log log;

    /** Cache of parent chains to avoid redundant repository lookups */
    private final Map<String, ParentChainBuilder.ParentChain> parentChainCache = new HashMap<>();

    /** Map of dependency sources: artifactId → list of sources */
    private final Map<String, List<DependencySource>> dependencySources = new HashMap<>();

    public DependencyGraphAnalyzer(MavenProject project,
                                   MavenSession session,
                                   DependencyGraphBuilder dependencyGraphBuilder,
                                   ParentChainBuilder parentChainBuilder,
                                   String minVersion,
                                   Log log) {
        this.project = project;
        this.session = session;
        this.dependencyGraphBuilder = dependencyGraphBuilder;
        this.parentChainBuilder = parentChainBuilder;
        this.minVersion = minVersion;
        this.log = log;
    }

    /**
     * Builds the complete dependency tree for the module.
     * IMPORTANT: a new ProjectBuildingRequest is created (the shared session object is not mutated),
     * which prevents race conditions in parallel builds.
     */
    public MavenDependencyTree buildDependencyTree(String moduleName) throws DependencyGraphBuilderException {
        // Create an isolated request for the current module — do not modify the shared session object
        ProjectBuildingRequest buildingRequest = new DefaultProjectBuildingRequest(
                session.getProjectBuildingRequest()
        );
        buildingRequest.setProject(project);

        DependencyNode rootNode = dependencyGraphBuilder.buildDependencyGraph(buildingRequest, null);

        if (rootNode == null) {
            log.warn("Dependency graph is empty for module: " + moduleName);
            return null;
        }

        MavenDependencyTree tree = createModuleRootNode(moduleName);
        buildTreeFromNode(rootNode, tree, new HashSet<>());

        return tree;
    }

    /** Creates the root tree node for the module */
    private MavenDependencyTree createModuleRootNode(String moduleName) {
        MavenDependencyTree tree = new MavenDependencyTree(project.getArtifact());
        tree.setModule(moduleName);
        return tree;
    }

    /**
     * Recursively traverses the dependency graph and builds the tree.
     * For the root node (the project itself) — its children are processed directly.
     * Uses visited to guard against cycles within a single path.
     */
    private void buildTreeFromNode(DependencyNode node, MavenDependencyTree parentNode, Set<String> visited) {
        Artifact artifact = node.getArtifact();
        if (artifact == null) {
            return;
        }

        // Root node — the module itself; its children are processed as direct dependencies
        if (isProjectArtifact(artifact)) {
            processProjectNodeChildren(node, parentNode, visited);
            return;
        }

        String versionKey = artifactKey(artifact);
        if (visited.contains(versionKey)) {
            return;
        }
        visited.add(versionKey);

        MavenDependencyTree treeNode = new MavenDependencyTree(artifact);
        treeNode.setParentVersionInfo(resolveParentVersionInfo(artifact));

        parentNode.addChild(treeNode);

        registerDependencySource(node, artifact);

        // Child nodes receive a copy of visited — this allows the same artifact
        // to appear in different branches of the tree while protecting only against cycles within one branch
        processChildren(node, treeNode, new HashSet<>(visited));
    }

    /**
     * Processes the direct dependencies of the root project.
     * Each direct dependency is processed with an independent copy of visited
     * so that sibling branches do not block each other.
     */
    private void processProjectNodeChildren(DependencyNode node, MavenDependencyTree parentNode, Set<String> visited) {
        List<DependencyNode> children = node.getChildren();
        if (children == null) {
            return;
        }
        for (DependencyNode child : children) {
            // Each direct dependency is processed with its own copy of visited
            buildTreeFromNode(child, parentNode, new HashSet<>(visited));
        }
    }

    /** Recursively processes child nodes */
    private void processChildren(DependencyNode node, MavenDependencyTree parentNode, Set<String> visited) {
        List<DependencyNode> children = node.getChildren();
        if (children == null) {
            return;
        }
        for (DependencyNode child : children) {
            buildTreeFromNode(child, parentNode, new HashSet<>(visited));
        }
    }

    /**
     * Registers the dependency source (who introduced it) for subsequent reporting.
     * Only recorded when the node has a parent (i.e. it is not a direct dependency).
     */
    private void registerDependencySource(DependencyNode node, Artifact artifact) {
        if (node.getParent() == null || node.getParent().getArtifact() == null) {
            return;
        }

        DependencySource source = buildDependencySource(node, artifact);
        dependencySources
                .computeIfAbsent(artifact.getArtifactId(), k -> new ArrayList<>())
                .add(source);
    }

    /** Creates a dependency source object */
    private DependencySource buildDependencySource(DependencyNode node, Artifact artifact) {
        Artifact parentArtifact = node.getParent().getArtifact();

        List<String> path = new ArrayList<>();
        path.add(artifactKey(parentArtifact));
        path.add(artifactKey(artifact));

        return new DependencySource(
                parentArtifact.getArtifactId(),
                parentArtifact.getVersion(),
                path
        );
    }

    /**
     * Retrieves parent POM chain information for an artifact.
     * The result is cached for reuse.
     * Returns null if the chain is empty or an error occurred.
     * Version data is populated only when minVersion is set (check mode).
     */
    private ParentVersionInfo resolveParentVersionInfo(Artifact artifact) {
        try {
            String cacheKey = artifactKey(artifact);
            if (!parentChainCache.containsKey(cacheKey)) {
                ParentChainBuilder.ParentChain chain = parentChainBuilder.buildParentChain(artifact);
                parentChainCache.put(cacheKey, chain);
            }

            ParentChainBuilder.ParentChain chain = parentChainCache.get(cacheKey);
            if (chain.getParents().isEmpty()) {
                return null;
            }

            return buildParentVersionInfo(chain);
        } catch (Exception e) {
            log.debug("Could not resolve parent chain for " + artifactKey(artifact) + ": " + e.getMessage());
            return null;
        }
    }

    /**
     * Builds ParentVersionInfo from the chain traversal result.
     * If minVersion is set, checks whether the minimum version is violated.
     */
    private ParentVersionInfo buildParentVersionInfo(ParentChainBuilder.ParentChain chain) {
        ParentVersionInfo info = new ParentVersionInfo();
        info.setParentChain(chain.getParents());

        if (chain.isHasTargetGroup()) {
            info.setHasTargetGroup(true);
            info.setTargetParentVersion(chain.getTargetParentVersion());

            if (minVersion != null) {
                boolean isLow = VersionComparator.isVersionLower(chain.getTargetParentVersion(), minVersion);
                info.setLowVersion(isLow);
                info.setMinimumExpected(minVersion);
            }
        }

        return info;
    }

    private boolean isProjectArtifact(Artifact artifact) {
        return artifact.getGroupId().equals(project.getGroupId())
                && artifact.getArtifactId().equals(project.getArtifactId());
    }

    private String artifactKey(Artifact artifact) {
        return artifact.getGroupId() + ":" + artifact.getArtifactId() + ":" + artifact.getVersion();
    }

    /** Returns the dependency source map collected during tree traversal */
    public Map<String, List<DependencySource>> getDependencySources() {
        return dependencySources;
    }
}
