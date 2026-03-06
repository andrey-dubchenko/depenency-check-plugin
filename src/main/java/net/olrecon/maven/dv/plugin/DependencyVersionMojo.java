package net.olrecon.maven.dv.plugin;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.annotations.SerializedName;
import org.apache.maven.artifact.Artifact;
import org.apache.maven.artifact.factory.ArtifactFactory;
import org.apache.maven.artifact.repository.ArtifactRepository;
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
import org.apache.maven.project.MavenProject;
import org.apache.maven.shared.dependency.graph.DependencyGraphBuilder;
import org.apache.maven.shared.dependency.graph.DependencyGraphBuilderException;
import org.apache.maven.shared.dependency.graph.DependencyNode;

import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.IOException;
import java.io.Writer;
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
        name = "check-parent-versions",
        defaultPhase = LifecyclePhase.VALIDATE,
        requiresDependencyResolution = org.apache.maven.plugins.annotations.ResolutionScope.TEST
)
public class DependencyVersionMojo extends AbstractMojo {

    @Component
    private MavenProject project;

    @Component
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

    @Parameter(property = "targetGroupId", defaultValue = "net.olrecon")
    private String targetGroupId;

    @Parameter(property = "minVersion", defaultValue = "1.0.0")
    private String minVersion;

    @Parameter(property = "includeGroups", defaultValue = "")
    private String includeGroups;

    @Parameter(property = "excludeGroups", defaultValue = "")
    private String excludeGroups;

    @Parameter(property = "outputFile", defaultValue = "target/parent-version-check.json")
    private String outputFile;

    @Parameter(property = "failOnLowVersion", defaultValue = "false")
    private boolean failOnLowVersion;

    @Parameter(property = "debug", defaultValue = "false")
    private boolean debug;

    // Результаты
    private List<ParentVersionIssue> issues = new ArrayList<>();

    // Кэш parent цепочек
    private Map<String, ParentChain> parentChainCache = new HashMap<>();

    // Граф зависимостей для поиска источников
    private Map<String, List<DependencySource>> dependencySources = new HashMap<>();

    /**
     * Цепочка parent
     */
    private static class ParentChain {
        String groupId;
        String artifactId;
        String version;
        String targetGroupId;  // добавляем поле
        List<ParentInfo> parents = new ArrayList<>();
        boolean hasTargetGroup = false;
        String targetParentVersion = null;

        ParentChain(String groupId, String artifactId, String version, String targetGroupId) {
            this.groupId = groupId;
            this.artifactId = artifactId;
            this.version = version;
            this.targetGroupId = targetGroupId;  // сохраняем
        }

        void addParent(String groupId, String artifactId, String version) {
            parents.add(new ParentInfo(groupId, artifactId, version));
            // Используем сохраненный targetGroupId
            if (groupId.equals(this.targetGroupId)) {
                this.hasTargetGroup = true;
                this.targetParentVersion = version;
            }
        }
    }

    /**
     * Информация о parent
     */
    public static class ParentInfo {
        @SerializedName("group_id")
        String groupId;
        @SerializedName("artifact_id")
        String artifactId;
        @SerializedName("version")
        String version;

        ParentInfo(String groupId, String artifactId, String version) {
            this.groupId = groupId;
            this.artifactId = artifactId;
            this.version = version;
        }
    }

    /**
     * Информация об источнике зависимости
     */
    public static class DependencySource {
        @SerializedName("source_group_id")
        String sourceGroupId;
        @SerializedName("source_artifact_id")
        String sourceArtifactId;
        @SerializedName("source_version")
        String sourceVersion;
        @SerializedName("path")
        List<String> path;

        DependencySource(String groupId, String artifactId, String version, List<String> path) {
            this.sourceGroupId = groupId;
            this.sourceArtifactId = artifactId;
            this.sourceVersion = version;
            this.path = new ArrayList<>(path);
        }

        String getKey() {
            return sourceGroupId + ":" + sourceArtifactId + ":" + sourceVersion;
        }
    }

    /**
     * Проблема с версией parent
     */
    public static class ParentVersionIssue {
        @SerializedName("library")
        String library;
        @SerializedName("library_group_id")
        String libraryGroupId;
        @SerializedName("library_artifact_id")
        String libraryArtifactId;
        @SerializedName("library_version")
        String libraryVersion;

        @SerializedName("parent_group_id")
        String parentGroupId;
        @SerializedName("parent_artifact_id")
        String parentArtifactId;
        @SerializedName("parent_version")
        String parentVersion;

        @SerializedName("minimum_expected_version")
        String minExpectedVersion;

        @SerializedName("parent_chain")
        List<ParentInfo> parentChain;

        @SerializedName("is_low_version")
        boolean isLowVersion;

        @SerializedName("sources")
        List<DependencySource> sources; // откуда эта зависимость пришла

        ParentVersionIssue(String groupId, String artifactId, String version) {
            this.libraryGroupId = groupId;
            this.libraryArtifactId = artifactId;
            this.libraryVersion = version;
            this.library = artifactId + ":" + version;
            this.parentChain = new ArrayList<>();
            this.sources = new ArrayList<>();
        }

        void setParent(String groupId, String artifactId, String version) {
            this.parentGroupId = groupId;
            this.parentArtifactId = artifactId;
            this.parentVersion = version;
        }

        void setMinExpectedVersion(String version) {
            this.minExpectedVersion = version;
        }

        void addToChain(String groupId, String artifactId, String version) {
            this.parentChain.add(new ParentInfo(groupId, artifactId, version));
        }

        void setIsLowVersion(boolean isLow) {
            this.isLowVersion = isLow;
        }

        void addSource(DependencySource source) {
            this.sources.add(source);
        }
    }

    @Override
    public void execute() throws MojoExecutionException, MojoFailureException {
        getLog().info("================================================");
        getLog().info("Parent Version Checker (with transitive tracking)");
        getLog().info("================================================");
        getLog().info("Project: " + project.getGroupId() + ":" +
                project.getArtifactId() + ":" +
                project.getVersion());
        getLog().info("Target group: " + targetGroupId);
        getLog().info("Minimum version: " + minVersion);

        try {
            // ШАГ 1: Строим граф зависимостей для поиска источников
            buildDependencyGraph();

            // ШАГ 2: Получаем все зависимости
            Set<Artifact> artifacts = project.getArtifacts();
            getLog().info("Analyzing " + artifacts.size() + " dependencies...");

            // ШАГ 3: Анализируем каждую зависимость
            int analyzed = 0;
            for (Artifact artifact : artifacts) {
                if (shouldAnalyze(artifact.getGroupId())) {
                    analyzeArtifactParentChain(artifact);
                    analyzed++;
                }
            }

            getLog().info("Analyzed " + analyzed + " artifacts");

            // ШАГ 4: Фильтруем issues
            filterIssues();

            // ШАГ 5: Отчет
            reportIssues();

            // ШАГ 6: Сохраняем
            saveResults();

            // ШАГ 7: Опционально - fail сборки
            if (failOnLowVersion && !issues.isEmpty()) {
                throw new MojoFailureException("Found " + issues.size() + " libraries with low parent version!");
            }

        } catch (Exception e) {
            getLog().error("Error: " + e.getMessage());
            e.printStackTrace();
            throw new MojoExecutionException("Error checking parent versions", e);
        }
    }

    /**
     * Строит граф зависимостей для определения источников
     */
    private void buildDependencyGraph() {
        try {
            getLog().info("Building dependency graph to track sources...");

            org.apache.maven.project.ProjectBuildingRequest buildingRequest =
                    session.getProjectBuildingRequest();
            buildingRequest.setProject(project);

            DependencyNode rootNode = dependencyGraphBuilder.buildDependencyGraph(
                    buildingRequest,
                    null
            );

            if (rootNode != null) {
                traverseGraph(rootNode, new ArrayList<>());
            }

            getLog().info("Found sources for " + dependencySources.size() + " dependencies");

        } catch (DependencyGraphBuilderException e) {
            getLog().warn("Could not build dependency graph: " + e.getMessage());
        }
    }

    /**
     * Обходит граф и собирает информацию об источниках
     */
    private void traverseGraph(DependencyNode node, List<String> path) {
        Artifact artifact = node.getArtifact();
        if (artifact == null) return;

        String artifactKey = artifact.getGroupId() + ":" + artifact.getArtifactId() + ":" + artifact.getVersion();
        List<String> newPath = new ArrayList<>(path);
        newPath.add(artifactKey);

        // Если у узла есть родитель, значит это транзитивная зависимость
        if (node.getParent() != null && node.getParent().getArtifact() != null) {
            Artifact parent = node.getParent().getArtifact();

            DependencySource source = new DependencySource(
                    parent.getGroupId(),
                    parent.getArtifactId(),
                    parent.getVersion(),
                    newPath
            );

            String depKey = artifact.getGroupId() + ":" + artifact.getArtifactId() + ":" + artifact.getVersion();
            dependencySources.computeIfAbsent(depKey, k -> new ArrayList<>()).add(source);
        }

        // Рекурсивно обходим детей
        List<DependencyNode> children = node.getChildren();
        if (children != null) {
            for (DependencyNode child : children) {
                traverseGraph(child, newPath);
            }
        }
    }

    /**
     * Проверяет, нужно ли анализировать артефакт
     */
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

    /**
     * Анализирует parent цепочку артефакта
     */
    private void analyzeArtifactParentChain(Artifact artifact) {
        try {
            String cacheKey = artifact.getGroupId() + ":" + artifact.getArtifactId() + ":" + artifact.getVersion();

            if (!parentChainCache.containsKey(cacheKey)) {
                ParentChain chain = buildParentChain(artifact);
                parentChainCache.put(cacheKey, chain);
            }

            ParentChain chain = parentChainCache.get(cacheKey);

            if (chain.hasTargetGroup) {
                // Сравниваем версии
                if (isVersionLower(chain.targetParentVersion, minVersion)) {
                    ParentVersionIssue issue = new ParentVersionIssue(
                            chain.groupId,
                            chain.artifactId,
                            chain.version
                    );

                    // Добавляем parent chain
                    for (ParentInfo parent : chain.parents) {
                        issue.addToChain(parent.groupId, parent.artifactId, parent.version);
                        if (parent.groupId.equals(targetGroupId)) {
                            issue.setParent(parent.groupId, parent.artifactId, parent.version);
                        }
                    }

                    // Добавляем источники (откуда эта зависимость пришла)
                    String depKey = artifact.getGroupId() + ":" + artifact.getArtifactId() + ":" + artifact.getVersion();
                    if (dependencySources.containsKey(depKey)) {
                        for (DependencySource source : dependencySources.get(depKey)) {
                            issue.addSource(source);
                        }
                    }

                    issue.setMinExpectedVersion(minVersion);
                    issue.setIsLowVersion(true);

                    issues.add(issue);

                    if (debug) {
                        getLog().warn("⚠️  LOW PARENT VERSION: " + artifact.getArtifactId() + ":" +
                                artifact.getVersion() + " uses " + targetGroupId + ":" +
                                chain.targetParentVersion);
                    }
                }
            }

        } catch (Exception e) {
            if (debug) {
                getLog().debug("Error analyzing " + artifact + ": " + e.getMessage());
            }
        }
    }

    /**
     * Строит parent цепочку
     */
    private ParentChain buildParentChain(Artifact artifact) throws Exception {
        ParentChain chain = new ParentChain(
                artifact.getGroupId(),
                artifact.getArtifactId(),
                artifact.getVersion(),
                this.targetGroupId  // передаем targetGroupId
        );

        // Получаем POM артефакта
        Artifact pomArtifact = artifactFactory.createArtifact(
                artifact.getGroupId(),
                artifact.getArtifactId(),
                artifact.getVersion(),
                null,
                "pom"
        );

        artifactResolver.resolve(pomArtifact, remoteArtifactRepositories, localRepository);
        File pomFile = pomArtifact.getFile();

        if (pomFile != null && pomFile.exists()) {
            traverseParentChain(pomFile, chain, new HashSet<>());
        }

        return chain;
    }

    /**
     * Рекурсивно обходит parent цепочку
     */
    private void traverseParentChain(File pomFile, ParentChain chain, Set<String> visited) throws Exception {
        try (FileReader reader = new FileReader(pomFile)) {
            MavenXpp3Reader mavenReader = new MavenXpp3Reader();
            Model model = mavenReader.read(reader);

            if (model.getParent() != null) {
                Parent parent = model.getParent();

                // Предотвращаем циклы
                String parentKey = parent.getGroupId() + ":" + parent.getArtifactId() + ":" + parent.getVersion();
                if (visited.contains(parentKey)) {
                    return;
                }
                visited.add(parentKey);

                // Добавляем в цепочку
                chain.addParent(parent.getGroupId(), parent.getArtifactId(), parent.getVersion());

                // Получаем POM родителя
                Artifact parentArtifact = artifactFactory.createArtifact(
                        parent.getGroupId(),
                        parent.getArtifactId(),
                        parent.getVersion(),
                        null,
                        "pom"
                );

                artifactResolver.resolve(parentArtifact, remoteArtifactRepositories, localRepository);
                File parentPomFile = parentArtifact.getFile();

                if (parentPomFile != null && parentPomFile.exists()) {
                    traverseParentChain(parentPomFile, chain, visited);
                }
            }
        }
    }

    /**
     * Сравнивает версии
     */
    private boolean isVersionLower(String version, String minVersion) {
        if (version == null || minVersion == null) return false;

        try {
            String[] vParts = version.split("\\.");
            String[] mParts = minVersion.split("\\.");

            int maxLength = Math.max(vParts.length, mParts.length);
            for (int i = 0; i < maxLength; i++) {
                int vNum = i < vParts.length ? Integer.parseInt(vParts[i]) : 0;
                int mNum = i < mParts.length ? Integer.parseInt(mParts[i]) : 0;

                if (vNum < mNum) return true;
                if (vNum > mNum) return false;
            }
            return false; // равны
        } catch (NumberFormatException e) {
            // Если не можем сравнить как числа, используем строковое сравнение
            return version.compareTo(minVersion) < 0;
        }
    }

    /**
     * Фильтрует issues
     */
    private void filterIssues() {
        List<ParentVersionIssue> filtered = new ArrayList<>();
        for (ParentVersionIssue issue : issues) {
            if (shouldAnalyze(issue.parentGroupId)) {
                filtered.add(issue);
            }
        }
        issues = filtered;
    }

    /**
     * Отчет
     */
    private void reportIssues() {
        if (issues.isEmpty()) {
            getLog().info("✅ All dependencies use " + targetGroupId + " version >= " + minVersion);
            return;
        }

        getLog().warn("================================================");
        getLog().warn("⚠️  LOW PARENT VERSIONS FOUND: " + issues.size());
        getLog().warn("================================================");

        for (ParentVersionIssue issue : issues) {
            getLog().warn("");
            getLog().warn("📦 Library: " + issue.libraryGroupId + ":" +
                    issue.libraryArtifactId + ":" + issue.libraryVersion);

            getLog().warn("   Parent chain:");
            for (ParentInfo parent : issue.parentChain) {
                getLog().warn("   └─ " + parent.groupId + ":" + parent.artifactId + ":" + parent.version);
            }

            getLog().warn("   ⚠️  Uses " + targetGroupId + " version: " + issue.parentVersion);
            getLog().warn("   ✅ Minimum expected: " + minVersion);

            if (!issue.sources.isEmpty()) {
                getLog().warn("   📍 Brought by:");
                for (DependencySource source : issue.sources) {
                    getLog().warn("      └─ " + source.sourceGroupId + ":" +
                            source.sourceArtifactId + ":" + source.sourceVersion);
                    if (debug && source.path.size() > 1) {
                        getLog().warn("         Path: " + String.join(" -> ", source.path));
                    }
                }
            }

            getLog().warn("   💡 Solution: Exclude this dependency or upgrade the source library");
        }
    }

    /**
     * Сохраняет результаты
     */
    private void saveResults() throws MojoExecutionException {
        try {
            Path outputPath = Paths.get(outputFile);
            if (outputPath.getParent() != null) {
                outputPath.getParent().toFile().mkdirs();
            }

            Map<String, Object> result = new LinkedHashMap<>();
            result.put("timestamp", new Date().toString());
            result.put("project", project.getGroupId() + ":" +
                    project.getArtifactId() + ":" +
                    project.getVersion());
            result.put("target_group", targetGroupId);
            result.put("minimum_version", minVersion);
            result.put("total_issues", issues.size());
            result.put("issues", issues);

            Gson gson = new GsonBuilder()
                    .setPrettyPrinting()
                    .disableHtmlEscaping()
                    .create();

            try (Writer writer = new FileWriter(outputFile)) {
                writer.write(gson.toJson(result));
            }

            getLog().info("Report saved to: " + outputPath.toAbsolutePath());

        } catch (IOException e) {
            throw new MojoExecutionException("Error saving results", e);
        }
    }
}