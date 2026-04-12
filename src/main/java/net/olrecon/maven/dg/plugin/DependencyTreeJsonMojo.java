package net.olrecon.maven.dg.plugin;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import net.olrecon.maven.dg.plugin.model.MavenDependencyTree;
import net.olrecon.maven.dg.plugin.model.ParentVersionInfo;
import net.olrecon.maven.dg.plugin.service.ParentChainBuilder;
import org.apache.maven.artifact.Artifact;
import org.apache.maven.artifact.factory.ArtifactFactory;
import org.apache.maven.artifact.repository.ArtifactRepository;
import org.apache.maven.artifact.resolver.ArtifactResolver;
import org.apache.maven.execution.MavenSession;
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

import java.io.FileWriter;
import java.io.IOException;
import java.io.Writer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Maven plugin (aggregator): builds the complete dependency tree for all modules
 * with parent POM chains and saves it to a single JSON file.
 * Used for dependency auditing and visualization.
 */
@Mojo(
        name = "dependency-tree-json",
        defaultPhase = LifecyclePhase.VALIDATE,
        requiresDependencyResolution = org.apache.maven.plugins.annotations.ResolutionScope.TEST,
        threadSafe = true,
        aggregator = true
)
public class DependencyTreeJsonMojo extends AbstractMojo {

    @Parameter(defaultValue = "${session}", readonly = true)
    private MavenSession session;

    @Parameter(defaultValue = "${project}", readonly = true)
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

    /** Path to the output JSON file (relative to the project root) */
    @Parameter(property = "outputFile", defaultValue = "target/dependency-tree.json")
    private String outputFile;

    /** Scopes to include (comma-separated); empty means all */
    @Parameter(property = "includeScopes", defaultValue = "")
    private String includeScopes;

    /** Scopes to exclude (comma-separated); "system" is excluded by default */
    @Parameter(property = "excludeScopes", defaultValue = "system")
    private String excludeScopes;

    /** Whether to include optional dependencies in the tree */
    @Parameter(property = "includeOptional", defaultValue = "false")
    private boolean includeOptional;

    /** Whether to include dependencies with scope=test */
    @Parameter(property = "includeTestScope", defaultValue = "false")
    private boolean includeTestScope;

    private Set<String> includeScopesSet;
    private Set<String> excludeScopesSet;

    /**
     * Cache of parent chains: one entry per unique GAV key.
     * Shared across all modules because this Mojo is an aggregator (single instance).
     */
    private final Map<String, ParentChainBuilder.ParentChain> parentChainCache = new HashMap<>();

    @Override
    public void execute() throws MojoExecutionException, MojoFailureException {
        initScopeFilters();

        ParentChainBuilder parentChainBuilder = createParentChainBuilder();

        logStartInfo();

        List<MavenDependencyTree> moduleTrees = buildAllModuleTrees(parentChainBuilder);

        MavenProject topLevel = session.getTopLevelProject();
        String rootDir = topLevel != null
                ? topLevel.getBasedir().getAbsolutePath()
                : project.getBasedir().getAbsolutePath();

        try {
            writeResult(rootDir, moduleTrees);
        } catch (IOException e) {
            throw new MojoExecutionException("Failed to save the JSON dependency tree", e);
        }
    }

    /** Initializes the scope filter sets */
    private void initScopeFilters() {
        includeScopesSet = parseCsvToSet(includeScopes);
        excludeScopesSet = parseCsvToSet(excludeScopes);

        if (!includeTestScope) {
            excludeScopesSet.add("test");
        }
    }

    /** Creates a ParentChainBuilder for building parent chains */
    private ParentChainBuilder createParentChainBuilder() {
        // targetGroupIds = empty: tree is built without version checking
        return new ParentChainBuilder(
                artifactResolver,
                artifactFactory,
                localRepository,
                remoteArtifactRepositories,
                new ArrayList<>(),
                session,
                getLog()
        );
    }

    private void logStartInfo() {
        MavenProject topLevel = session.getTopLevelProject();
        String projectCoords = topLevel != null
                ? topLevel.getGroupId() + ":" + topLevel.getArtifactId() + ":" + topLevel.getVersion()
                : project.getGroupId() + ":" + project.getArtifactId() + ":" + project.getVersion();

        getLog().info("================================================");
        getLog().info("Dependency Tree JSON (with parent chains, aggregator)");
        getLog().info("Root project: " + projectCoords);
        getLog().info("Output file: " + outputFile);
        getLog().info("================================================");
    }

    /** Builds dependency trees for all modules in the reactor build */
    private List<MavenDependencyTree> buildAllModuleTrees(ParentChainBuilder parentChainBuilder) {
        List<MavenDependencyTree> result = new ArrayList<>();

        for (MavenProject moduleProject : session.getAllProjects()) {
            String moduleName = moduleProject.getArtifactId();
            getLog().info("Building dependency tree for module: " + moduleName);

            try {
                MavenDependencyTree moduleTree = buildModuleTree(moduleProject, parentChainBuilder);
                if (moduleTree != null) {
                    result.add(moduleTree);
                }
            } catch (DependencyGraphBuilderException e) {
                getLog().warn("Cannot build dependency graph for module " + moduleName + ": " + e.getMessage());
            }
        }

        return result;
    }

    /**
     * Builds the dependency tree for a single module.
     * IMPORTANT: a new DefaultProjectBuildingRequest is created — the shared session object is not mutated.
     */
    private MavenDependencyTree buildModuleTree(MavenProject moduleProject,
                                                 ParentChainBuilder parentChainBuilder)
            throws DependencyGraphBuilderException {

        DependencyNode rootNode = buildDependencyGraph(moduleProject);
        if (rootNode == null) {
            getLog().warn("No dependency graph for module: " + moduleProject.getArtifactId());
            return null;
        }

        // Root node — the module itself
        MavenDependencyTree moduleRoot = createModuleRootNode(moduleProject);
        buildTreeFromNode(moduleProject, rootNode, moduleRoot, new HashSet<>(), parentChainBuilder);

        return moduleRoot;
    }

    /**
     * Builds the dependency graph for a module via the Maven Dependency Graph Builder.
     * Creates an isolated ProjectBuildingRequest — does not modify the shared session object.
     */
    private DependencyNode buildDependencyGraph(MavenProject moduleProject)
            throws DependencyGraphBuilderException {

        ProjectBuildingRequest buildingRequest = new DefaultProjectBuildingRequest(
                session.getProjectBuildingRequest()
        );
        buildingRequest.setProject(moduleProject);

        return dependencyGraphBuilder.buildDependencyGraph(buildingRequest, null);
    }

    /** Creates the root tree node for a module */
    private MavenDependencyTree createModuleRootNode(MavenProject moduleProject) {
        MavenDependencyTree root = new MavenDependencyTree(moduleProject.getArtifact());
        root.setModule(moduleProject.getArtifactId());
        root.setScope("project");
        root.setType(moduleProject.getPackaging());
        root.setOptional(false);
        return root;
    }

    /**
     * Recursively traverses the dependency graph and builds the model tree.
     * For the root node (the project itself) — children are treated as direct dependencies.
     * Uses visited to guard against cycles within a single path.
     */
    private void buildTreeFromNode(MavenProject moduleProject,
                                   DependencyNode node,
                                   MavenDependencyTree parentNode,
                                   Set<String> visited,
                                   ParentChainBuilder parentChainBuilder) {
        Artifact artifact = node.getArtifact();
        if (artifact == null) {
            return;
        }

        // Root node — the module itself; its children are direct dependencies
        if (isProjectArtifact(moduleProject, artifact)) {
            processProjectNodeChildren(moduleProject, node, parentNode, visited, parentChainBuilder);
            return;
        }

        String versionKey = artifactKey(artifact);
        if (visited.contains(versionKey)) {
            return;
        }
        visited.add(versionKey);

        // Check scope and optional filters
        if (!isScopeIncluded(artifact.getScope())) {
            return;
        }
        if (!includeOptional && artifact.isOptional()) {
            return;
        }

        MavenDependencyTree treeNode = new MavenDependencyTree(artifact);
        treeNode.setParentVersionInfo(resolveParentVersionInfo(artifact, parentChainBuilder));

        parentNode.addChild(treeNode);

        // Child nodes receive a copy of visited — protection against cycles within a single branch
        processNodeChildren(moduleProject, node, treeNode, new HashSet<>(visited), parentChainBuilder);
    }

    /**
     * Processes the direct dependencies of the root node.
     * Each direct dependency receives an independent copy of visited.
     */
    private void processProjectNodeChildren(MavenProject moduleProject,
                                             DependencyNode node,
                                             MavenDependencyTree parentNode,
                                             Set<String> visited,
                                             ParentChainBuilder parentChainBuilder) {
        List<DependencyNode> children = node.getChildren();
        if (children == null) {
            return;
        }
        for (DependencyNode child : children) {
            buildTreeFromNode(moduleProject, child, parentNode, new HashSet<>(visited), parentChainBuilder);
        }
    }

    /** Recursively processes child nodes */
    private void processNodeChildren(MavenProject moduleProject,
                                      DependencyNode node,
                                      MavenDependencyTree parentNode,
                                      Set<String> visited,
                                      ParentChainBuilder parentChainBuilder) {
        List<DependencyNode> children = node.getChildren();
        if (children == null) {
            return;
        }
        for (DependencyNode child : children) {
            buildTreeFromNode(moduleProject, child, parentNode, new HashSet<>(visited), parentChainBuilder);
        }
    }

    /**
     * Retrieves the parent POM chain for an artifact.
     * The result is cached. No version checking is performed (targetGroupId = null).
     */
    private ParentVersionInfo resolveParentVersionInfo(Artifact artifact,
                                                        ParentChainBuilder parentChainBuilder) {
        String cacheKey = artifactKey(artifact);
        try {
            if (!parentChainCache.containsKey(cacheKey)) {
                ParentChainBuilder.ParentChain chain = parentChainBuilder.buildParentChain(artifact);
                parentChainCache.put(cacheKey, chain);
            }

            ParentChainBuilder.ParentChain chain = parentChainCache.get(cacheKey);
            if (chain.getParents().isEmpty()) {
                return null;
            }

            ParentVersionInfo info = new ParentVersionInfo();
            info.setParentChain(chain.getParents());
            return info;
        } catch (Exception e) {
            getLog().debug("Could not resolve parent chain for " + cacheKey + ": " + e.getMessage());
            return null;
        }
    }

    /**
     * Determines whether a scope belongs to the include/exclude sets.
     * A null or empty scope is treated as "compile".
     */
    private boolean isScopeIncluded(String scope) {
        if (scope == null || scope.isEmpty()) {
            scope = "compile";
        }
        if (!includeScopesSet.isEmpty() && !includeScopesSet.contains(scope)) {
            return false;
        }
        return !excludeScopesSet.contains(scope);
    }

    private boolean isProjectArtifact(MavenProject moduleProject, Artifact artifact) {
        return artifact.getGroupId().equals(moduleProject.getGroupId())
                && artifact.getArtifactId().equals(moduleProject.getArtifactId());
    }

    private String artifactKey(Artifact artifact) {
        return artifact.getGroupId() + ":" + artifact.getArtifactId() + ":" + artifact.getVersion();
    }

    private Set<String> parseCsvToSet(String value) {
        if (value == null || value.trim().isEmpty()) {
            return new HashSet<>();
        }
        return Arrays.stream(value.split(","))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .collect(Collectors.toSet());
    }

    /** Saves the final JSON file containing the trees for all modules */
    private void writeResult(String rootDir, List<MavenDependencyTree> modules) throws IOException {
        Path outputPath = Paths.get(rootDir + "/" + outputFile);
        Files.createDirectories(outputPath.getParent());

        Map<String, Object> result = buildOutputResult(modules);

        Gson gson = new GsonBuilder()
                .setPrettyPrinting()
                .disableHtmlEscaping()
                .create();

        try (Writer writer = new FileWriter(outputPath.toFile())) {
            writer.write(gson.toJson(result));
        }

        getLog().info("JSON dependency tree saved: " + outputPath.toAbsolutePath());
        getLog().info("Modules in tree: " + modules.size());
    }

    private Map<String, Object> buildOutputResult(List<MavenDependencyTree> modules) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("timestamp", new Date().toString());
        result.put("project", project.getGroupId() + ":" + project.getArtifactId() + ":" + project.getVersion());
        result.put("modules", modules);
        return result;
    }
}
