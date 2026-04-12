package net.olrecon.maven.dg.plugin;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import net.olrecon.maven.dg.plugin.model.MavenDependencyTree;
import net.olrecon.maven.dg.plugin.model.ParentVersionIssue;
import net.olrecon.maven.dg.plugin.util.HtmlReportGenerator;
import net.olrecon.maven.dg.plugin.util.JsonUtils;
import org.apache.maven.execution.MavenSession;
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
import java.util.Arrays;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Maven plugin: aggregates the check results from all modules and generates an HTML report.
 *
 * <p><b>Execution order:</b> the goal is NOT bound to any lifecycle phase — it must be invoked
 * explicitly AFTER all modules have completed the package/verify phase. The recommended way:
 * <pre>mvn verify dependency-check:aggregate</pre>
 * Maven guarantees that explicitly specified goals run after the lifecycle completes
 * for the entire reactor. Binding aggregate to a lifecycle phase (via &lt;execution&gt;) is unreliable:
 * depending on the Maven version and project structure the goal may run
 * before child modules have finished.
 *
 * <p>When failOnError=true, throws MojoFailureException if any errors are found.
 */
@Mojo(
        name = "aggregate",
        defaultPhase = LifecyclePhase.NONE,
        aggregator = true,
        requiresProject = true,
        threadSafe = true
)
public class AggregateResultsMojo extends AbstractMojo {

    @Parameter(defaultValue = "${project}", readonly = true, required = true)
    private MavenProject project;

    @Parameter(defaultValue = "${session}", readonly = true)
    private MavenSession session;

    /**
     * Target groups for version checking (comma-separated).
     * Example: "org.springframework.boot,org.springframework"
     */
    @Parameter(property = "targetGroupId", defaultValue = "org.springframework")
    private String targetGroupId;

    @Parameter(property = "minVersion", defaultValue = "1.0.0")
    private String minVersion;

    /** Path to the aggregated error file (relative to the project root) */
    @Parameter(property = "errorFile", defaultValue = "target/check-error.json")
    private String errorFile;

    /** Path to the aggregated dependency tree file (relative to the project root) */
    @Parameter(property = "treeFile", defaultValue = "target/deps-tree-root.json")
    private String treeFile;

    /** Path to the HTML report (relative to the project root) */
    @Parameter(property = "reportFile", defaultValue = "target/dependency-report.html")
    private String reportFile;

    /** Directory for module temporary files (relative to the project root) */
    @Parameter(property = "tempDir", defaultValue = "target/dependency-governance-temp")
    private String tempDir;

    /**
     * If true, the build will fail when version violations are found.
     * Use -DfailOnError=true on the command line or set it in the plugin configuration.
     */
    @Parameter(property = "failOnError", defaultValue = "false")
    private boolean failOnError;

    /** If true, the HTML report is not generated */
    @Parameter(property = "skipReport", defaultValue = "false")
    private boolean skipReport;

    @Parameter(property = "debug", defaultValue = "false")
    private boolean debug;

    private Gson gson;
    private JsonUtils jsonUtils;

    /** Parsed list of targetGroupId values (computed during initialization) */
    private List<String> targetGroupIds;

    @Override
    public void execute() throws MojoExecutionException, MojoFailureException {
        if (!project.isExecutionRoot()) {
            getLog().info("Not a root project, skipping aggregation");
            return;
        }

        gson = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();
        jsonUtils = new JsonUtils(gson);
        targetGroupIds = parseTargetGroupIds();

        logStartInfo();

        // First collect data and save — exceptions here indicate an infrastructure failure
        AggregationData data;
        try {
            data = collectData();
            saveAggregatedResults(data);
            generateHtmlReport(data);
        } catch (Exception e) {
            getLog().error("Aggregation failed: " + e.getMessage());
            if (debug) {
                e.printStackTrace();
            }
            throw new MojoExecutionException("Error during aggregation", e);
        }

        reportResults(data);

        // MojoFailureException is thrown outside the try/catch block
        // so it is not caught and re-wrapped as MojoExecutionException
        if (failOnError && data.getTotalErrors() > 0) {
            throw new MojoFailureException(
                    "Found " + data.getTotalErrors()
                            + " dependencies with low parent POM version! "
                            + "See " + errorFile + " for details"
            );
        }
    }

    private List<String> parseTargetGroupIds() {
        if (targetGroupId == null || targetGroupId.trim().isEmpty()) {
            return java.util.Collections.emptyList();
        }
        return Arrays.stream(targetGroupId.split(","))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .collect(Collectors.toList());
    }

    private void logStartInfo() {
        getLog().info("================================================");
        getLog().info("Starting dependency results aggregation");
        getLog().info("================================================");
        getLog().info("Root project:   " + project.getArtifactId() + ":" + project.getVersion());
        getLog().info("Temp directory: " + getAbsoluteTempPath());
        getLog().info("Target groups:  " + targetGroupIds);
        getLog().info("Minimum version:" + minVersion);
        if (session != null) {
            long moduleCount = session.getAllProjects().stream()
                    .filter(p -> !p.equals(project))
                    .count();
            getLog().info("Reactor modules:" + moduleCount + " (expected temp files: " + moduleCount + ")");
        }
        getLog().info("================================================");
    }

    private String getAbsoluteTempPath() {
        return project.getBasedir().getAbsolutePath() + "/" + tempDir;
    }

    /**
     * Collects data from all module temporary files.
     * Files matching *-tree.json are dependency trees; *-errors.json files contain version errors.
     */
    private AggregationData collectData() throws Exception {
        AggregationData data = new AggregationData();
        Path tempPath = Paths.get(getAbsoluteTempPath());

        if (!Files.exists(tempPath)) {
            getLog().warn("Temporary directory does not exist: " + tempPath);
            getLog().warn("This usually means 'check-versions' has not run yet.");
            getLog().warn("Use the correct invocation order:");
            getLog().warn("  mvn verify dependency-check:aggregate");
            return data;
        }

        List<Path> files = Files.list(tempPath).collect(Collectors.toList());

        if (files.isEmpty()) {
            getLog().warn("Temp directory is empty — no module results found.");
            getLog().warn("Make sure 'check-versions' ran for all modules before calling 'aggregate'.");
            getLog().warn("Correct invocation: mvn verify dependency-check:aggregate");
            return data;
        }

        getLog().info("Found " + files.size() + " files in temp directory");

        for (Path file : files) {
            processTemporaryFile(file, data);
        }

        getLog().info("Collected: trees=" + data.getTrees().size()
                + ", errors=" + data.getTotalErrors()
                + " from " + data.getErrors().size() + " modules");

        return data;
    }

    /** Processes a single temporary file and adds its contents to data */
    private void processTemporaryFile(Path file, AggregationData data) {
        String fileName = file.getFileName().toString();
        String moduleName = extractModuleName(fileName);

        try {
            if (fileName.endsWith("-tree.json")) {
                MavenDependencyTree tree = jsonUtils.readTreeFromFile(file.toFile());
                if (tree != null) {
                    data.getTrees().put(moduleName, tree);
                    getLog().debug("Loaded tree from " + fileName);
                }
            } else if (fileName.endsWith("-errors.json")) {
                List<ParentVersionIssue> errors = jsonUtils.readErrorsFromFile(file.toFile());
                if (errors != null && !errors.isEmpty()) {
                    data.getErrors().put(moduleName, errors);
                    data.addErrors(errors.size());
                    getLog().debug("Loaded " + errors.size() + " errors from " + fileName);
                }
            }
        } catch (Exception e) {
            getLog().warn("Failed to read file " + fileName + ": " + e.getMessage());
        }
    }

    /** Extracts the module name from a temporary file name */
    private String extractModuleName(String fileName) {
        if (fileName.endsWith("-tree.json")) {
            return fileName.substring(0, fileName.length() - "-tree.json".length());
        }
        if (fileName.endsWith("-errors.json")) {
            return fileName.substring(0, fileName.length() - "-errors.json".length());
        }
        return fileName;
    }

    /** Saves aggregated results (tree + errors) */
    private void saveAggregatedResults(AggregationData data) throws Exception {
        saveAggregatedTree(data.getTrees());
        saveAggregatedErrors(data.getErrors(), data.getTotalErrors());
    }

    /** Saves the aggregated dependency tree for all modules */
    private void saveAggregatedTree(Map<String, MavenDependencyTree> trees) throws Exception {
        Path treePath = resolveAbsolutePath(treeFile);
        Files.createDirectories(treePath.getParent());

        Map<String, Object> result = buildTreeResult(trees);

        try (Writer writer = new FileWriter(treePath.toFile())) {
            gson.toJson(result, writer);
        }

        getLog().info("Aggregated tree saved to: " + treePath);
        getLog().info("  Contains " + trees.size() + " modules");
    }

    private Map<String, Object> buildTreeResult(Map<String, MavenDependencyTree> trees) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("timestamp", new Date().toString());
        result.put("project", projectCoordinates());
        result.put("target_groups", targetGroupIds);
        result.put("minimum_version", minVersion);
        result.put("modules", trees);
        return result;
    }

    /** Saves the aggregated error file */
    private void saveAggregatedErrors(Map<String, List<ParentVersionIssue>> errors,
                                      int totalErrors) throws Exception {
        Path errorPath = resolveAbsolutePath(errorFile);
        Files.createDirectories(errorPath.getParent());

        Map<String, Object> result = buildErrorResult(errors, totalErrors);

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

    private Map<String, Object> buildErrorResult(Map<String, List<ParentVersionIssue>> errors,
                                                  int totalErrors) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("timestamp", new Date().toString());
        result.put("project", projectCoordinates());
        result.put("target_groups", targetGroupIds);
        result.put("minimum_version", minVersion);
        result.put("total_errors", totalErrors);
        result.put("modules_with_errors", errors.size());
        if (!errors.isEmpty()) {
            result.put("errors_by_module", errors);
        }
        return result;
    }

    /** Generates an HTML report from the collected data */
    private void generateHtmlReport(AggregationData data) throws Exception {
        if (skipReport) {
            getLog().info("HTML report generation skipped (skipReport=true)");
            return;
        }

        Path outputPath = resolveAbsolutePath(reportFile);
        Files.createDirectories(outputPath.getParent());

        HtmlReportGenerator generator = new HtmlReportGenerator(
                projectCoordinates(),
                targetGroupIds,
                minVersion,
                new Date().toString(),
                data.getTotalErrors(),
                data.getErrors()
        );

        String html = generator.generate();

        try (Writer writer = new FileWriter(outputPath.toFile())) {
            writer.write(html);
        }

        getLog().info("HTML report saved to: " + outputPath.toAbsolutePath());
    }

    /** Logs the final aggregation report */
    private void reportResults(AggregationData data) {
        getLog().info("================================================");
        getLog().info("Aggregation completed");
        getLog().info("================================================");
        getLog().info("Modules processed: " + data.getTrees().size());

        if (data.getTotalErrors() > 0) {
            getLog().warn("ERRORS FOUND: " + data.getTotalErrors());
            data.getErrors().forEach((module, moduleErrors) -> {
                getLog().warn("  Module " + module + ": " + moduleErrors.size() + " errors");
                if (debug) {
                    moduleErrors.forEach(error -> getLog().warn(
                            "    - " + error.getLibraryArtifactId() + ":" + error.getLibraryVersion()
                                    + " uses " + targetGroupIds + ":" + error.getParentVersion()
                    ));
                }
            });
        } else {
            getLog().info("No errors found in any module");
        }
        getLog().info("================================================");
    }

    private Path resolveAbsolutePath(String relativePath) {
        return Paths.get(project.getBasedir().getAbsolutePath() + "/" + relativePath);
    }

    private String projectCoordinates() {
        return project.getGroupId() + ":" + project.getArtifactId() + ":" + project.getVersion();
    }

    // -------------------------------------------------------------------------
    // Inner class — container for collected aggregation data
    // -------------------------------------------------------------------------

    /** Container for data collected from all module temporary files */
    private static class AggregationData {

        private final Map<String, MavenDependencyTree> trees = new LinkedHashMap<>();
        private final Map<String, List<ParentVersionIssue>> errors = new LinkedHashMap<>();
        private int totalErrors = 0;

        public Map<String, MavenDependencyTree> getTrees() {
            return trees;
        }

        public Map<String, List<ParentVersionIssue>> getErrors() {
            return errors;
        }

        public int getTotalErrors() {
            return totalErrors;
        }

        public void addErrors(int count) {
            totalErrors += count;
        }
    }
}
