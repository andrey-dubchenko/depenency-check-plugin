package net.olrecon.maven.dg.plugin.service;

import net.olrecon.maven.dg.plugin.model.DependencySource;
import net.olrecon.maven.dg.plugin.model.MavenDependencyTree;
import net.olrecon.maven.dg.plugin.model.ParentVersionInfo;
import net.olrecon.maven.dg.plugin.util.VersionComparator;
import org.apache.maven.artifact.Artifact;
import org.apache.maven.execution.MavenSession;
import org.apache.maven.project.MavenProject;
import org.apache.maven.shared.dependency.graph.DependencyGraphBuilder;
import org.apache.maven.shared.dependency.graph.DependencyGraphBuilderException;
import org.apache.maven.shared.dependency.graph.DependencyNode;
import org.checkerframework.checker.nullness.compatqual.NonNullDecl;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

public class DependencyGraphAnalyzer {

    private final MavenProject project;
    private final MavenSession session;
    private final DependencyGraphBuilder dependencyGraphBuilder;
    private final ParentChainBuilder parentChainBuilder;
    private final String minVersion;
    private final Map<String, ParentChainBuilder.ParentChain> parentChainCache;
    private final Map<String, List<DependencySource>> dependencySources;

    public DependencyGraphAnalyzer(MavenProject project,
                                   MavenSession session,
                                   DependencyGraphBuilder dependencyGraphBuilder,
                                   ParentChainBuilder parentChainBuilder,
                                   String minVersion) {
        this.project = project;
        this.session = session;
        this.dependencyGraphBuilder = dependencyGraphBuilder;
        this.parentChainBuilder = parentChainBuilder;
        this.minVersion = minVersion;
        this.parentChainCache = new HashMap<>();
        this.dependencySources = new HashMap<>();
    }

    public MavenDependencyTree buildDependencyTree(String moduleName) throws DependencyGraphBuilderException {

        org.apache.maven.project.ProjectBuildingRequest buildingRequest = session.getProjectBuildingRequest();

        buildingRequest.setProject(project);

        DependencyNode rootNode = dependencyGraphBuilder.buildDependencyGraph(buildingRequest, null);

        if (rootNode != null) {
            Artifact projectArtifact = project.getArtifact();

            MavenDependencyTree tree = new MavenDependencyTree(projectArtifact);

            tree.setModule(moduleName);

            buildTreeFromNode(rootNode, tree, new HashSet<>());

            return tree;
        }

        return null;
    }

    private void buildTreeFromNode(DependencyNode node, MavenDependencyTree parentNode, Set<String> visited) {
        Artifact artifact = node.getArtifact();

        if (artifact == null) return;

        if (isProjectArtifact(artifact)) {
            List<DependencyNode> children = node.getChildren();

            if (children != null) {
                for (DependencyNode child : children) {
                    buildTreeFromNode(child, parentNode, visited);
                }
            }

            return;
        }

        String versionKey = artifact.getGroupId() + ":" + artifact.getArtifactId() + ":" + artifact.getVersion();

        if (visited.contains(versionKey)) {
            return;
        }

        visited.add(versionKey);

        MavenDependencyTree treeNode = new MavenDependencyTree(artifact);

        ParentVersionInfo parentInfo = getParentVersionInfo(artifact);

        if (parentInfo != null) {
            treeNode.setParentVersionInfo(parentInfo);
        }

        parentNode.addChild(treeNode);

        if (node.getParent() != null && node.getParent().getArtifact() != null) {
            DependencySource source = getDependencySource(node, artifact);

            String depKey = artifact.getArtifactId();

            dependencySources.computeIfAbsent(depKey, k -> new ArrayList<>()).add(source);
        }

        List<DependencyNode> children = node.getChildren();

        if (children != null) {
            for (DependencyNode child : children) {
                buildTreeFromNode(child, treeNode, new HashSet<>(visited));
            }
        }
    }

    @NonNullDecl
    private static DependencySource getDependencySource(DependencyNode node, Artifact artifact) {
        Artifact parent = node.getParent().getArtifact();

        List<String> path = new ArrayList<>();

        path.add(parent.getGroupId() + ":" + parent.getArtifactId() + ":" + parent.getVersion());
        path.add(artifact.getGroupId() + ":" + artifact.getArtifactId() + ":" + artifact.getVersion());

        DependencySource source = new DependencySource(
                parent.getArtifactId(),
                parent.getVersion(),
                path
        );

        return source;
    }

    private ParentVersionInfo getParentVersionInfo(Artifact artifact) {
        try {
            String cacheKey = artifact.getGroupId() + ":" + artifact.getArtifactId() + ":" + artifact.getVersion();

            if (!parentChainCache.containsKey(cacheKey)) {
                ParentChainBuilder.ParentChain chain = parentChainBuilder.buildParentChain(artifact);
                parentChainCache.put(cacheKey, chain);
            }

            ParentChainBuilder.ParentChain chain = parentChainCache.get(cacheKey);

            if (chain.hasTargetGroup) {
                ParentVersionInfo info = new ParentVersionInfo();

                info.setHasTargetGroup(true);
                info.setTargetParentVersion(chain.targetParentVersion);
                info.setMinimumExpected(minVersion);
                info.setLowVersion(VersionComparator.isVersionLower(chain.targetParentVersion, minVersion));
                info.setParentChain(chain.parents);

                return info;
            }
        } catch (Exception e) {
        }

        return null;
    }

    private boolean isProjectArtifact(Artifact artifact) {
        return artifact.getGroupId().equals(project.getGroupId()) &&
                artifact.getArtifactId().equals(project.getArtifactId());
    }

    public Map<String, List<DependencySource>> getDependencySources() {
        return dependencySources;
    }
}