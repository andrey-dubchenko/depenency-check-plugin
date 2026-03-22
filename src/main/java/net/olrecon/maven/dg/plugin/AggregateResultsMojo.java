package net.olrecon.maven.dg.plugin;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import net.olrecon.maven.dg.plugin.model.MavenDependencyTree;
import net.olrecon.maven.dg.plugin.model.ParentVersionIssue;
import net.olrecon.maven.dg.plugin.util.JsonUtils;
import org.apache.maven.plugin.AbstractMojo;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugin.MojoFailureException;
import org.apache.maven.plugins.annotations.LifecyclePhase;
import org.apache.maven.plugins.annotations.Mojo;
import org.apache.maven.plugins.annotations.Parameter;
import org.apache.maven.project.MavenProject;

import java.io.FileWriter;
import java.io.Writer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Mojo(
        name = "aggregate",
        defaultPhase = LifecyclePhase.VALIDATE,
        requiresProject = true,
        threadSafe = true
)
public class AggregateResultsMojo extends AbstractMojo {
    @Parameter(defaultValue = "${project}", readonly = true, required = true)
    private MavenProject project;

    @Parameter(property = "targetGroupId", defaultValue = "org.springframework")
    private String targetGroupId;

    @Parameter(property = "minVersion", defaultValue = "1.0.0")
    private String minVersion;

    @Parameter(property = "errorFile", defaultValue = "target/check-error.json")
    private String errorFile;

    @Parameter(property = "treeFile", defaultValue = "target/deps-tree-root.json")
    private String treeFile;

    @Parameter(property = "tempDir", defaultValue = "target/dependency-governance-temp")
    private String tempDir;

    @Parameter(property = "failOnError", defaultValue = "false")
    private boolean failOnError;

    @Parameter(property = "debug", defaultValue = "false")
    private boolean debug;

    private Gson gson = new GsonBuilder()
            .setPrettyPrinting()
            .disableHtmlEscaping()
            .create();

    private JsonUtils jsonUtils;

    @Override
    public void execute() throws MojoExecutionException, MojoFailureException {
        jsonUtils = new JsonUtils(gson);

        if (!project.isExecutionRoot()) {
            getLog().info("Not a root project, skipping aggregation");
            return;
        }

        getLog().info("================================================");
        getLog().info("Starting dependency results aggregation");
        getLog().info("================================================");
        getLog().info("Root project: " + project.getArtifactId() + ":" + project.getVersion());
        getLog().info("Temp directory: " + getFullTempPath());
        getLog().info("Target group: " + targetGroupId);
        getLog().info("Minimum version: " + minVersion);
        getLog().info("================================================");

        try {
            AggregationData data = collectData();

            saveAggregatedResults(data);

            reportResults(data);

            if (failOnError && data.totalErrors > 0) {
                throw new MojoFailureException(
                        "Found " + data.totalErrors + " dependencies with low parent version! " +
                                "See " + errorFile + " for details."
                );
            }

        } catch (Exception e) {
            getLog().error("Aggregation failed: " + e.getMessage());
            if (debug) {
                e.printStackTrace();
            }
            throw new MojoExecutionException("Error during aggregation", e);
        }
    }

    private String getFullTempPath() {
        return project.getBasedir().getAbsolutePath() + "/" + tempDir;
    }

    private AggregationData collectData() throws Exception {
        AggregationData data = new AggregationData();
        Path tempPath = Paths.get(getFullTempPath());

        if (!Files.exists(tempPath)) {
            getLog().warn("Temporary directory does not exist: " + tempPath);
            return data;
        }

        List<Path> files = Files.list(tempPath).collect(Collectors.toList());
        getLog().info("Found " + files.size() + " files in temp directory");

        for (Path file : files) {
            String fileName = file.getFileName().toString();
            String moduleName = extractModuleName(fileName);

            try {
                if (fileName.endsWith("-tree.json")) {
                    MavenDependencyTree tree = jsonUtils.readTreeFromFile(file.toFile());
                    if (tree != null) {
                        data.trees.put(moduleName, tree);
                        getLog().debug("Loaded tree from " + fileName);
                    }
                } else if (fileName.endsWith("-errors.json")) {
                    List<ParentVersionIssue> errors = jsonUtils.readErrorsFromFile(file.toFile());
                    if (errors != null && !errors.isEmpty()) {
                        data.errors.put(moduleName, errors);
                        data.totalErrors += errors.size();
                        getLog().debug("Loaded " + errors.size() + " errors from " + fileName);
                    }
                }
            } catch (Exception e) {
                getLog().warn("Failed to read file " + fileName + ": " + e.getMessage());
            }
        }

        getLog().info("Collected data: " + data.trees.size() + " trees, " +
                data.totalErrors + " errors from " + data.errors.size() + " modules");

        return data;
    }

    private String extractModuleName(String fileName) {
        if (fileName.endsWith("-tree.json")) {
            return fileName.substring(0, fileName.length() - 10); // -tree.json = 10 chars
        } else if (fileName.endsWith("-errors.json")) {
            return fileName.substring(0, fileName.length() - 12); // -errors.json = 12 chars
        }
        return fileName;
    }

    private void saveAggregatedResults(AggregationData data) throws Exception {
        saveAggregatedTree(data.trees);

        saveAggregatedErrors(data.errors, data.totalErrors);
    }

    private void saveAggregatedTree(Map<String, MavenDependencyTree> trees) throws Exception {
        String fullPath = project.getBasedir().getAbsolutePath() + "/" + treeFile;
        Path treePath = Paths.get(fullPath);
        Files.createDirectories(treePath.getParent());

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("timestamp", new Date().toString());
        result.put("project", project.getGroupId() + ":" + project.getArtifactId() + ":" + project.getVersion());
        result.put("target_group", targetGroupId);
        result.put("minimum_version", minVersion);
        result.put("modules", trees);

        try (Writer writer = new FileWriter(treePath.toFile())) {
            gson.toJson(result, writer);
        }

        getLog().info("Aggregated tree saved to: " + treePath);
        getLog().info("  Contains " + trees.size() + " modules");
    }

    private void saveAggregatedErrors(Map<String, List<ParentVersionIssue>> errors, int totalErrors) throws Exception {
        String fullPath = project.getBasedir().getAbsolutePath() + "/" + errorFile;
        Path errorPath = Paths.get(fullPath);
        Files.createDirectories(errorPath.getParent());

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("timestamp", new Date().toString());
        result.put("project", project.getGroupId() + ":" + project.getArtifactId() + ":" + project.getVersion());
        result.put("target_group", targetGroupId);
        result.put("minimum_version", minVersion);
        result.put("total_errors", totalErrors);
        result.put("modules_with_errors", errors.size());

        if (!errors.isEmpty()) {
            result.put("errors_by_module", errors);
        }

        try (Writer writer = new FileWriter(errorPath.toFile())) {
            gson.toJson(result, writer);
        }

        getLog().info("Aggregated errors saved to: " + errorPath);
        if (totalErrors > 0) {
            getLog().warn("Total errors found: " + totalErrors);
        } else {
            getLog().info("No errors found");
        }
    }

    private void reportResults(AggregationData data) {
        getLog().info("================================================");
        getLog().info("Aggregation completed");
        getLog().info("================================================");
        getLog().info("Modules processed: " + data.trees.size());

        if (data.totalErrors > 0) {
            getLog().warn("⚠️ ERRORS FOUND: " + data.totalErrors);
            for (Map.Entry<String, List<ParentVersionIssue>> entry : data.errors.entrySet()) {
                getLog().warn("  Module " + entry.getKey() + ": " + entry.getValue().size() + " errors");
                if (debug) {
                    for (ParentVersionIssue error : entry.getValue()) {
                        getLog().warn("    - " + error.getLibraryArtifactId() + ":" +
                                error.getLibraryVersion() + " uses " +
                                targetGroupId + ":" + error.getParentVersion());
                    }
                }
            }
        } else {
            getLog().info("✅ No errors found in any module");
        }
        getLog().info("================================================");
    }

    private static class AggregationData {
        Map<String, MavenDependencyTree> trees = new LinkedHashMap<>();
        Map<String, List<ParentVersionIssue>> errors = new LinkedHashMap<>();
        int totalErrors = 0;
    }
}