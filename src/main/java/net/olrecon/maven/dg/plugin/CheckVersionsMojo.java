package net.olrecon.maven.dg.plugin;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import net.olrecon.maven.dg.plugin.model.DependencySource;
import net.olrecon.maven.dg.plugin.model.MavenDependencyTree;
import net.olrecon.maven.dg.plugin.model.ParentVersionIssue;
import net.olrecon.maven.dg.plugin.service.DependencyGraphAnalyzer;
import net.olrecon.maven.dg.plugin.service.ParentChainBuilder;
import net.olrecon.maven.dg.plugin.util.VersionComparator;
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
import org.apache.maven.project.MavenProject;
import org.apache.maven.shared.dependency.graph.DependencyGraphBuilder;

import java.io.File;
import java.io.FileWriter;
import java.io.Writer;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Maven plugin: checks parent POM versions for the dependencies of the current module.
 * Saves results to a temporary directory for subsequent aggregation.
 */
@Mojo(
        name = "check-versions",
        defaultPhase = LifecyclePhase.PACKAGE,
        requiresDependencyResolution = org.apache.maven.plugins.annotations.ResolutionScope.TEST,
        threadSafe = true
)
public class CheckVersionsMojo extends AbstractMojo {

    @Parameter(defaultValue = "${project}", readonly = true)
    private MavenProject project;

    @Parameter(defaultValue = "${session}", readonly = true)
    private MavenSession session;

    @Component
    private ArtifactResolver artifactResolver;

    @Component
    private ArtifactFactory artifactFactory;

    @Component(hint = "default")
    private DependencyGraphBuilder dependencyGraphBuilder;

    @Parameter(defaultValue = "${localRepository}", required = true, readonly = true)
    private ArtifactRepository localRepository;

    @Parameter(defaultValue = "${project.remoteArtifactRepositories}", required = true, readonly = true)
    private List remoteArtifactRepositories;

    /**
     * Target groups for version checking (comma-separated).
     * Example: "org.springframework,org.springframework.boot"
     */
    @Parameter(property = "targetGroupId", defaultValue = "org.springframework")
    private String targetGroupId;

    /** Parsed list of targetGroupId values (computed during initialization) */
    private List<String> targetGroupIds;

    /** Minimum allowed parent version from the target group */
    @Parameter(property = "minVersion", defaultValue = "1.0.0")
    private String minVersion;

    /** List of groupId values to include in analysis (comma-separated); empty means analyze all */
    @Parameter(property = "includeGroups", defaultValue = "")
    private String includeGroups;

    /** List of groupId values to exclude from analysis (comma-separated) */
    @Parameter(property = "excludeGroups", defaultValue = "")
    private String excludeGroups;

    /** Directory for result temporary files (relative to the project root) */
    @Parameter(property = "tempDir", defaultValue = "target/dependency-governance-temp")
    private String tempDir;

    /**
     * If true, the build will fail immediately when violations are found in a module,
     * without waiting for the aggregate phase. Use -DfailOnError=true.
     */
    @Parameter(property = "failOnError", defaultValue = "false")
    private boolean failOnError;

    @Parameter(property = "debug", defaultValue = "false")
    private boolean debug;

    private Gson gson;
    private ParentChainBuilder parentChainBuilder;

    /** Absolute path to the temporary files directory (at the project root) */
    private String rootTempDir;

    @Override
    public void execute() throws MojoExecutionException, MojoFailureException {
        initComponents();
        logStartInfo();

        List<ParentVersionIssue> issues;
        try {
            issues = analyzeModule();
        } catch (Exception e) {
            getLog().error("Error checking parent versions: " + e.getMessage());
            if (debug) {
                e.printStackTrace();
            }
            throw new MojoExecutionException("Error checking parent versions", e);
        }

        // MojoFailureException is thrown outside try/catch so it is not wrapped in MojoExecutionException
        if (failOnError) {
            long errorCount = issues.stream().filter(ParentVersionIssue::isError).count();
            if (errorCount > 0) {
                throw new MojoFailureException(
                        "Module " + project.getArtifactId() + ": found " + errorCount
                                + " dependencies with low parent POM version!"
                );
            }
        }
    }

    /** Initializes gson, ParentChainBuilder, and the path to the temporary directory */
    private void initComponents() {
        gson = new GsonBuilder()
                .setPrettyPrinting()
                .disableHtmlEscaping()
                .create();

        targetGroupIds = Arrays.stream(targetGroupId.split(","))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .collect(Collectors.toList());

        parentChainBuilder = new ParentChainBuilder(
                artifactResolver,
                artifactFactory,
                localRepository,
                remoteArtifactRepositories,
                targetGroupIds,
                session,
                getLog()
        );

        MavenProject topProject = session.getTopLevelProject();
        String rootDir = topProject != null
                ? topProject.getBasedir().getAbsolutePath()
                : project.getBasedir().getAbsolutePath();

        rootTempDir = rootDir + "/" + tempDir;
    }

    private void logStartInfo() {
        getLog().info("================================================");
        getLog().info("Parent Version Checker for module: " + project.getArtifactId());
        getLog().info("================================================");
        getLog().info("Target groups: " + targetGroupIds);
        getLog().info("Minimum version: " + minVersion);
        getLog().info("Temp directory: " + rootTempDir);
    }

    /** Main module analysis method: builds the tree, collects errors, saves results. Returns all found issues. */
    private List<ParentVersionIssue> analyzeModule() throws Exception {
        String moduleName = project.getArtifactId();

        DependencyGraphAnalyzer analyzer = createAnalyzer();
        MavenDependencyTree moduleTree = analyzer.buildDependencyTree(moduleName);

        Set<Artifact> artifacts = project.getArtifacts();
        List<ParentVersionIssue> issues = detectVersionIssues(artifacts, moduleName, analyzer);

        saveModuleResults(moduleName, issues, moduleTree);
        reportModuleIssues(moduleName, issues);

        return issues;
    }

    /** Creates a dependency graph analyzer for the current module */
    private DependencyGraphAnalyzer createAnalyzer() {
        return new DependencyGraphAnalyzer(
                project,
                session,
                dependencyGraphBuilder,
                parentChainBuilder,
                minVersion,
                getLog()
        );
    }

    /**
     * Iterates over module artifacts, builds parent chains, and detects version violations.
     * Artifacts are filtered by includeGroups / excludeGroups.
     */
    private List<ParentVersionIssue> detectVersionIssues(Set<Artifact> artifacts,
                                                          String moduleName,
                                                          DependencyGraphAnalyzer analyzer) {
        List<ParentVersionIssue> issues = new ArrayList<>();

        for (Artifact artifact : artifacts) {
            if (!isGroupIncluded(artifact.getGroupId())) {
                continue;
            }

            try {
                ParentVersionIssue issue = analyzeArtifact(artifact, moduleName, analyzer);
                if (issue != null) {
                    issues.add(issue);
                }
            } catch (Exception e) {
                if (debug) {
                    getLog().debug("Error analyzing " + artifact + ": " + e.getMessage());
                }
            }
        }

        return issues;
    }

    /**
     * Analyzes a single artifact: builds the parent chain and creates a violation record.
     * Returns null if the target group is not found in the chain.
     */
    private ParentVersionIssue analyzeArtifact(Artifact artifact,
                                                String moduleName,
                                                DependencyGraphAnalyzer analyzer) throws Exception {
        ParentChainBuilder.ParentChain chain = parentChainBuilder.buildParentChain(artifact);

        if (!chain.isHasTargetGroup()) {
            return null;
        }

        boolean isLowVersion = VersionComparator.isVersionLower(chain.getTargetParentVersion(), minVersion);

        ParentVersionIssue issue = new ParentVersionIssue(moduleName);
        issue.setLibrary(chain.getGroupId(), chain.getArtifactId(), chain.getVersion());
        issue.setDependency(chain.getGroupId(), chain.getArtifactId(), chain.getVersion());
        issue.setParentVersion(chain.getTargetParentVersion());
        issue.setMinExpectedVersion(minVersion);
        issue.setError(isLowVersion);
        issue.setParentChain(chain.getParents());

        // Set the source — who introduced this dependency
        attachSourceInfo(issue, artifact, analyzer);

        return issue;
    }

    /** Populates the dependency source information */
    private void attachSourceInfo(ParentVersionIssue issue,
                                   Artifact artifact,
                                   DependencyGraphAnalyzer analyzer) {
        Map<String, List<DependencySource>> sources = analyzer.getDependencySources();
        List<DependencySource> artifactSources = sources.get(artifact.getArtifactId());
        if (artifactSources != null && !artifactSources.isEmpty()) {
            issue.setSource(artifactSources.get(0));
        }
    }

    /**
     * Determines whether a dependency with the given groupId should be analyzed.
     * Applies the includeGroups and excludeGroups filters.
     */
    private boolean isGroupIncluded(String groupId) {
        if (groupId == null) {
            return false;
        }

        Set<String> includes = parseCsvToSet(includeGroups);
        Set<String> excludes = parseCsvToSet(excludeGroups);

        if (!includes.isEmpty()) {
            boolean matched = includes.stream().anyMatch(groupId::startsWith);
            if (!matched) {
                return false;
            }
        }

        return excludes.stream().noneMatch(groupId::startsWith);
    }

    /** Parses a comma-separated string into a set of strings */
    private Set<String> parseCsvToSet(String value) {
        if (value == null || value.trim().isEmpty()) {
            return new HashSet<>();
        }
        return Arrays.stream(value.split(","))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .collect(Collectors.toSet());
    }

    /** Saves module results to the temporary directory for later aggregation */
    private void saveModuleResults(String moduleName,
                                   List<ParentVersionIssue> issues,
                                   MavenDependencyTree moduleTree) throws Exception {
        Files.createDirectories(Paths.get(rootTempDir));

        saveTreeFile(moduleName, moduleTree);
        saveErrorsFile(moduleName, issues);
    }

    /** Saves the module dependency tree */
    private void saveTreeFile(String moduleName, MavenDependencyTree moduleTree) throws Exception {
        if (moduleTree == null) {
            return;
        }
        File treeFile = new File(rootTempDir, moduleName + "-tree.json");
        try (Writer writer = new FileWriter(treeFile)) {
            gson.toJson(moduleTree, writer);
        }
        getLog().debug("Tree saved: " + treeFile.getAbsolutePath());
    }

    /** Saves the module error file (only when errors are present) */
    private void saveErrorsFile(String moduleName, List<ParentVersionIssue> issues) throws Exception {
        List<ParentVersionIssue> errors = issues.stream()
                .filter(ParentVersionIssue::isError)
                .collect(Collectors.toList());

        if (errors.isEmpty()) {
            return;
        }

        File errorFile = new File(rootTempDir, moduleName + "-errors.json");
        Map<String, Object> errorData = new LinkedHashMap<>();
        errorData.put("module", moduleName);
        errorData.put("errors", errors);

        try (Writer writer = new FileWriter(errorFile)) {
            gson.toJson(errorData, writer);
        }
        getLog().info("Saved " + errors.size() + " errors for aggregation");
    }

    /** Logs the final summary for the module */
    private void reportModuleIssues(String moduleName, List<ParentVersionIssue> issues) {
        long errorCount = issues.stream().filter(ParentVersionIssue::isError).count();

        if (errorCount == 0) {
            getLog().info("OK: " + moduleName);
            return;
        }

        getLog().warn("ERRORS in module " + moduleName + ": " + errorCount);
        if (debug) {
            issues.stream()
                    .filter(ParentVersionIssue::isError)
                    .forEach(issue -> getLog().warn(
                            "  - " + issue.getLibraryArtifactId() + ":" + issue.getLibraryVersion()
                                    + " uses " + targetGroupIds + ":" + issue.getParentVersion()
                    ));
        }
    }
}
