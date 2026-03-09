package net.olrecon.maven.dg.plugin;

import net.olrecon.maven.dg.plugin.model.*;
import net.olrecon.maven.dg.plugin.service.*;
import net.olrecon.maven.dg.plugin.util.JsonUtils;
import net.olrecon.maven.dg.plugin.util.VersionComparator;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
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
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.*;
import java.util.stream.Collectors;

@Mojo(
        name = "check-versions",
        defaultPhase = LifecyclePhase.PACKAGE,
        requiresDependencyResolution = org.apache.maven.plugins.annotations.ResolutionScope.TEST,
        threadSafe = true
)
public class CheckVersionsMojo extends AbstractMojo {
    @Parameter( defaultValue = "${project}", readonly = true )
    private MavenProject project;

    @Parameter( defaultValue = "${session}", readonly = true )
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

    @Parameter(property = "targetGroupId", defaultValue = "org.springframework")
    private String targetGroupId;

    @Parameter(property = "minVersion", defaultValue = "1.0.0")
    private String minVersion;

    @Parameter(property = "includeGroups", defaultValue = "")
    private String includeGroups;

    @Parameter(property = "excludeGroups", defaultValue = "")
    private String excludeGroups;

    @Parameter(property = "tempDir", defaultValue = "target/dependency-governance-temp")
    private String tempDir;

    @Parameter(property = "debug", defaultValue = "false")
    private boolean debug;

    private Gson gson;
    private JsonUtils jsonUtils;
    private ParentChainBuilder parentChainBuilder;
    private String rootTempDir;  // Директория в корне проекта для временных файлов

    @Override
    public void execute() throws MojoExecutionException, MojoFailureException {
        init();

        getLog().info("================================================");
        getLog().info("Parent Version Checker for module: " + project.getArtifactId());
        getLog().info("================================================");
        getLog().info("Target group: " + targetGroupId);
        getLog().info("Minimum version: " + minVersion);
        getLog().info("Temp directory: " + rootTempDir);

        try {
            // Анализируем модуль
            analyzeModule();

        } catch (Exception e) {
            getLog().error("Error: " + e.getMessage());
            if (debug) e.printStackTrace();
            throw new MojoExecutionException("Error checking parent versions", e);
        }
    }

    private void init() {
        gson = new GsonBuilder()
                .setPrettyPrinting()
                .disableHtmlEscaping()
                .create();

        jsonUtils = new JsonUtils(gson);

        parentChainBuilder = new ParentChainBuilder(
                artifactResolver,
                artifactFactory,
                localRepository,
                remoteArtifactRepositories,
                targetGroupId
        );

        // Определяем корневую директорию проекта
        MavenProject topProject = session.getTopLevelProject();
        String rootDir = topProject != null ?
                topProject.getBasedir().getAbsolutePath() :
                project.getBasedir().getAbsolutePath();

        // Временная директория всегда в корне проекта
        rootTempDir = rootDir + "/" + tempDir;
    }

    private void analyzeModule() throws Exception {
        String moduleName = project.getArtifactId();

        // Строим дерево зависимостей
        DependencyGraphAnalyzer analyzer = new DependencyGraphAnalyzer(
                project,
                session,
                dependencyGraphBuilder,
                parentChainBuilder,
                minVersion
        );

        MavenDependencyTree moduleTree = analyzer.buildDependencyTree(moduleName);

        // Получаем все зависимости
        Set<Artifact> artifacts = project.getArtifacts();
        List<ParentVersionIssue> moduleIssues = analyzeArtifacts(artifacts, moduleName, analyzer);

        // Сохраняем результаты
        saveModuleResults(moduleName, moduleIssues, moduleTree);

        // Отчет в лог
        reportModuleIssues(moduleName, moduleIssues);
    }

    private List<ParentVersionIssue> analyzeArtifacts(Set<Artifact> artifacts,
                                                      String moduleName,
                                                      DependencyGraphAnalyzer analyzer) {
        List<ParentVersionIssue> issues = new ArrayList<>();

        for (Artifact artifact : artifacts) {
            if (!shouldAnalyze(artifact.getGroupId())) continue;

            try {
                ParentChainBuilder.ParentChain chain = parentChainBuilder.buildParentChain(artifact);

                if (chain.hasTargetGroup) {
                    boolean isLowVersion = VersionComparator.isVersionLower(
                            chain.targetParentVersion, minVersion
                    );

                    ParentVersionIssue issue = new ParentVersionIssue(moduleName);
                    issue.setLibrary(chain.groupId, chain.artifactId, chain.version);
                    issue.setDependency(chain.groupId, chain.artifactId, chain.version);
                    issue.setParentVersion(chain.targetParentVersion);
                    issue.setMinExpectedVersion(minVersion);
                    issue.setError(isLowVersion);

                    Map<String, List<DependencySource>> sources = analyzer.getDependencySources();
                    String depKey = artifact.getArtifactId();
                    if (sources.containsKey(depKey) && !sources.get(depKey).isEmpty()) {
                        issue.setSource(sources.get(depKey).get(0));
                    }

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

    private boolean shouldAnalyze(String groupId) {
        if (groupId == null) return false;

        Set<String> includes = parseSet(includeGroups);
        Set<String> excludes = parseSet(excludeGroups);

        if (!includes.isEmpty()) {
            boolean included = false;
            for (String include : includes) {
                if (groupId.startsWith(include)) {
                    included = true;
                    break;
                }
            }
            if (!included) return false;
        }

        for (String exclude : excludes) {
            if (groupId.startsWith(exclude)) return false;
        }

        return true;
    }

    private Set<String> parseSet(String value) {
        Set<String> result = new HashSet<>();
        if (value != null && !value.isEmpty()) {
            for (String s : value.split(",")) {
                result.add(s.trim());
            }
        }
        return result;
    }

    private void saveModuleResults(String moduleName,
                                   List<ParentVersionIssue> issues,
                                   MavenDependencyTree moduleTree) throws Exception {
        // Создаем временную директорию в корне проекта
        Files.createDirectories(Paths.get(rootTempDir));

        // Сохраняем дерево для агрегации в корневой temp
        if (moduleTree != null) {
            File treeFile = new File(rootTempDir, moduleName + "-tree.json");
            try (Writer writer = new FileWriter(treeFile)) {
                gson.toJson(moduleTree, writer);
            }
            getLog().debug("Tree saved for aggregation: " + treeFile.getAbsolutePath());
        }

        // Сохраняем ошибки для агрегации
        List<ParentVersionIssue> errors = issues.stream()
                .filter(ParentVersionIssue::isError)
                .collect(Collectors.toList());

        if (!errors.isEmpty()) {
            File errorFile = new File(rootTempDir, moduleName + "-errors.json");
            Map<String, Object> errorData = new LinkedHashMap<>();
            errorData.put("module", moduleName);
            errorData.put("errors", errors);

            try (Writer writer = new FileWriter(errorFile)) {
                gson.toJson(errorData, writer);
            }
            getLog().info("Saved " + errors.size() + " errors for aggregation");
        }
    }

    private void reportModuleIssues(String moduleName, List<ParentVersionIssue> issues) {
        long errorCount = issues.stream().filter(ParentVersionIssue::isError).count();

        if (errorCount == 0) {
            getLog().info("✅ Module " + moduleName + ": OK");
        } else {
            getLog().warn("⚠️ Module " + moduleName + ": found " + errorCount + " errors");
            if (debug) {
                for (ParentVersionIssue issue : issues) {
                    if (issue.isError()) {
                        getLog().warn("  - " + issue.getLibraryArtifactId() + ":" +
                                issue.getLibraryVersion() + " uses " +
                                targetGroupId + ":" + issue.getParentVersion());
                    }
                }
            }
        }
    }
}