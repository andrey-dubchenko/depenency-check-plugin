package net.olrecon.maven.dg.plugin;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.annotations.SerializedName;
import net.olrecon.maven.dg.plugin.model.ParentInfo;
import net.olrecon.maven.dg.plugin.service.ParentChainBuilder;
import org.apache.maven.artifact.Artifact;
import org.apache.maven.artifact.factory.ArtifactFactory;
import org.apache.maven.artifact.repository.ArtifactRepository;
import org.apache.maven.artifact.resolver.ArtifactResolutionRequest;
import org.apache.maven.artifact.resolver.ArtifactResolutionResult;
import org.apache.maven.artifact.resolver.ArtifactResolver;
import org.apache.maven.execution.MavenSession;
import org.apache.maven.model.Model;
import org.apache.maven.model.Parent;
import org.apache.maven.model.io.xpp3.MavenXpp3Reader;
import org.apache.maven.plugin.AbstractMojo;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugin.MojoFailureException;
import org.apache.maven.plugins.annotations.Component;
import org.apache.maven.plugins.annotations.LifecyclePhase;
import org.apache.maven.plugins.annotations.Mojo;
import org.apache.maven.plugins.annotations.Parameter;
import org.apache.maven.project.DefaultProjectBuildingRequest;
import org.apache.maven.project.MavenProject;
import org.apache.maven.project.ProjectBuildingRequest;
import org.apache.maven.shared.dependency.graph.DependencyGraphBuilder;
import org.apache.maven.shared.dependency.graph.DependencyGraphBuilderException;
import org.apache.maven.shared.dependency.graph.DependencyNode;

import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.IOException;
import java.io.Writer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

@Mojo(
        name = "dependency-tree-json",
        defaultPhase = LifecyclePhase.VALIDATE,
        requiresDependencyResolution = org.apache.maven.plugins.annotations.ResolutionScope.TEST,
        threadSafe = true,
        aggregator = true
)
public class DependencyTreeJsonMojo extends AbstractMojo {
    @Parameter( defaultValue = "${session}", readonly = true )
    private MavenSession session;

    @Parameter( defaultValue = "${project}", readonly = true )
    private MavenProject project;

    @Component(hint = "default")
    private DependencyGraphBuilder dependencyGraphBuilder;

    @Component
    private ArtifactResolver artifactResolver;

    @Component
    private ArtifactFactory artifactFactory;

    @Parameter(defaultValue = "${localRepository}", required = true, readonly = true)
    private ArtifactRepository localRepository;

    @Parameter(defaultValue = "${project.remoteArtifactRepositories}", required = true, readonly = true)
    private List remoteArtifactRepositories;

    @Parameter(property = "outputFile", defaultValue = "target/dependency-tree.json")
    private String outputFile;

    @Parameter(property = "includeScopes", defaultValue = "")
    private String includeScopes;

    @Parameter(property = "excludeScopes", defaultValue = "system")
    private String excludeScopes;

    @Parameter(property = "includeOptional", defaultValue = "false")
    private boolean includeOptional;

    @Parameter(property = "includeTestScope", defaultValue = "false")
    private boolean includeTestScope;

    private Set<String> includeScopesSet;
    private Set<String> excludeScopesSet;

    private ParentChainBuilder parentChainBuilder;

    private Map<String, ParentChainBuilder.ParentChain> parentChainCache = new HashMap<>();

    public static class MavenDependencyTree {
        @SerializedName("groupId")
        String groupId;
        @SerializedName("artifactId")
        String artifactId;
        @SerializedName("version")
        String version;
        @SerializedName("scope")
        String scope;
        @SerializedName("type")
        String type;
        @SerializedName("classifier")
        String classifier;
        @SerializedName("optional")
        boolean optional;
        @SerializedName("children")
        List<MavenDependencyTree> children = new ArrayList<>();
        @SerializedName("parent_version_info")
        ParentVersionInfo parentVersionInfo;
        @SerializedName("module")
        String module;

        MavenDependencyTree(Artifact artifact) {
            this.groupId = artifact.getGroupId();
            this.artifactId = artifact.getArtifactId();
            this.version = artifact.getVersion();
            this.scope = artifact.getScope() != null ? artifact.getScope() : "compile";
            this.type = artifact.getType() != null ? artifact.getType() : "jar";
            this.classifier = artifact.getClassifier();
            this.optional = artifact.isOptional();
        }

        void addChild(MavenDependencyTree child) {
            this.children.add(child);
        }

        void setModule(String module) {
            this.module = module;
        }
    }
    public static class ParentVersionInfo {
        @SerializedName("parent_chain")
        List<ParentInfo> parentChain = new ArrayList<>();
    }

    @Override
    public void execute() throws MojoExecutionException, MojoFailureException {
        initScopeFilters();

        parentChainBuilder = new ParentChainBuilder(
                artifactResolver,
                artifactFactory,
                localRepository,
                remoteArtifactRepositories,
                null
        );

        MavenProject topLevel = session.getTopLevelProject();
        String rootDir = topLevel != null
                ? topLevel.getBasedir().getAbsolutePath()
                : project.getBasedir().getAbsolutePath();

        getLog().info("================================================");
        getLog().info("Dependency Tree JSON (with parent chains, aggregator)");
        getLog().info("Root project: " + (topLevel != null
                ? topLevel.getGroupId() + ":" + topLevel.getArtifactId() + ":" + topLevel.getVersion()
                : project.getGroupId() + ":" + project.getArtifactId() + ":" + project.getVersion()));
        getLog().info("Output file: " + outputFile);
        getLog().info("Root directory: " + rootDir);
        getLog().info("================================================");

        List<MavenDependencyTree> modules = new ArrayList<>();

        for (MavenProject moduleProject : session.getAllProjects()) {
            String moduleName = moduleProject.getArtifactId();
            getLog().info("Building dependency tree for module: " + moduleName);

            try {
                DependencyNode rootNode = buildDependencyGraphForProject(moduleProject);
                if (rootNode == null) {
                    getLog().warn("No dependency graph for module: " + moduleName);
                    continue;
                }

                Artifact projectArtifact = moduleProject.getArtifact();
                MavenDependencyTree moduleRoot = new MavenDependencyTree(projectArtifact);
                moduleRoot.setModule(moduleName);
                moduleRoot.scope = "project";
                moduleRoot.type = moduleProject.getPackaging();
                moduleRoot.optional = false;

                buildTreeFromNode(moduleProject, rootNode, moduleRoot, new HashSet<String>());

                modules.add(moduleRoot);
            } catch (DependencyGraphBuilderException e) {
                getLog().warn("Cannot build dependency graph for module "
                        + moduleName + ": " + e.getMessage());
            }
        }

        try {
            writeResult(rootDir, modules);
        } catch (IOException e) {
            throw new MojoExecutionException("Failed to write dependency tree JSON", e);
        }
    }

    private void initScopeFilters() {
        includeScopesSet = parseCsvToSet(includeScopes);
        excludeScopesSet = parseCsvToSet(excludeScopes);

        if (!includeTestScope) {
            excludeScopesSet.add("test");
        }
    }

    private Set<String> parseCsvToSet(String value) {
        Set<String> result = new HashSet<>();
        if (value == null || value.trim().isEmpty()) {
            return result;
        }
        String[] parts = value.split(",");
        for (String part : parts) {
            String p = part.trim();
            if (!p.isEmpty()) {
                result.add(p);
            }
        }
        return result;
    }

    private DependencyNode buildDependencyGraphForProject(MavenProject moduleProject)
            throws DependencyGraphBuilderException {

        ProjectBuildingRequest buildingRequest =
                new DefaultProjectBuildingRequest(session.getProjectBuildingRequest());
        buildingRequest.setProject(moduleProject);

        return dependencyGraphBuilder.buildDependencyGraph(buildingRequest, null);
    }

    private boolean isScopeIncluded(String scope) {
        if (scope == null || scope.isEmpty()) {
            scope = "compile";
        }

        if (!includeScopesSet.isEmpty() && !includeScopesSet.contains(scope)) {
            return false;
        }

        if (excludeScopesSet.contains(scope)) {
            return false;
        }

        return true;
    }

    private void buildTreeFromNode(MavenProject moduleProject,
                                   DependencyNode node,
                                   MavenDependencyTree parentNode,
                                   Set<String> visited) {
        Artifact artifact = node.getArtifact();
        if (artifact == null) {
            return;
        }

        if (isProjectArtifact(moduleProject, artifact)) {
            List<DependencyNode> children = node.getChildren();
            if (children != null) {
                for (DependencyNode child : children) {
                    buildTreeFromNode(moduleProject, child, parentNode, visited);
                }
            }
            return;
        }

        String versionKey = artifact.getGroupId() + ":" + artifact.getArtifactId() + ":" + artifact.getVersion();
        if (visited.contains(versionKey)) {
            return;
        }
        visited.add(versionKey);

        String scope = artifact.getScope();
        if (!isScopeIncluded(scope)) {
            return;
        }

        if (!includeOptional && artifact.isOptional()) {
            return;
        }

        MavenDependencyTree treeNode = new MavenDependencyTree(artifact);

        try {
            ParentVersionInfo parentInfo = getParentVersionInfo(artifact);
            if (parentInfo != null) {
                treeNode.parentVersionInfo = parentInfo;
            }
        } catch (Exception e) {
            getLog().debug("Could not resolve parent chain for " + artifact + ": " + e.getMessage());
        }

        parentNode.addChild(treeNode);

        List<DependencyNode> children = node.getChildren();
        if (children != null) {
            for (DependencyNode child : children) {
                buildTreeFromNode(moduleProject, child, treeNode, new HashSet<>(visited));
            }
        }
    }

    private boolean isProjectArtifact(MavenProject moduleProject, Artifact artifact) {
        return artifact.getGroupId().equals(moduleProject.getGroupId()) &&
                artifact.getArtifactId().equals(moduleProject.getArtifactId());
    }

    private ParentVersionInfo getParentVersionInfo(Artifact artifact) throws Exception {
        String cacheKey = artifact.getGroupId() + ":" + artifact.getArtifactId() + ":" + artifact.getVersion();

        if (!parentChainCache.containsKey(cacheKey)) {
            ParentChainBuilder.ParentChain chain = parentChainBuilder.buildParentChain(artifact);
            parentChainCache.put(cacheKey, chain);
        }

        ParentChainBuilder.ParentChain chain = parentChainCache.get(cacheKey);

        ParentVersionInfo info = new ParentVersionInfo();
        info.parentChain.addAll(chain.parents);
        return info;
    }

    private void writeResult(String rootDir, List<MavenDependencyTree> modules) throws IOException {
        String outputPathStr = rootDir + "/" + outputFile;
        Path outputPath = Paths.get(outputPathStr);
        Files.createDirectories(outputPath.getParent());

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("timestamp", new Date().toString());
        result.put("project", project.getGroupId() + ":" +
                project.getArtifactId() + ":" +
                project.getVersion());
        result.put("modules", modules);

        Gson gson = new GsonBuilder()
                .setPrettyPrinting()
                .disableHtmlEscaping()
                .create();

        try (Writer writer = new FileWriter(outputPath.toFile())) {
            writer.write(gson.toJson(result));
        }

        getLog().info("Dependency tree JSON saved to: " + outputPath.toAbsolutePath());
    }
}

