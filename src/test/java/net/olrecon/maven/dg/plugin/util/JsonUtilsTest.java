package net.olrecon.maven.dg.plugin.util;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import net.olrecon.maven.dg.plugin.model.MavenDependencyTree;
import net.olrecon.maven.dg.plugin.model.ParentInfo;
import net.olrecon.maven.dg.plugin.model.ParentVersionIssue;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class JsonUtilsTest {

    private final Gson gson = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();
    private final JsonUtils jsonUtils = new JsonUtils(gson);

    @TempDir
    File dir;

    private ParentVersionIssue issue() {
        ParentVersionIssue issue = new ParentVersionIssue("module-a");
        issue.setLibrary("com.acme", "lib", "1.0");
        issue.setDependency("com.acme", "lib", "1.0");
        issue.setParentVersion("4.0.0");
        issue.setMinExpectedVersion("5.0.0");
        issue.setError(true);
        issue.setSourceLibrary("mid");
        issue.setSourceVersion("2.0");
        issue.setFullPath(Arrays.asList("com.acme:mid:2.0", "com.acme:lib:1.0"));
        issue.setParentChain(Arrays.asList(
                new ParentInfo("com.acme", "lib-parent", "1.0"),
                new ParentInfo("org.springframework", "spring-parent", "4.0.0")));
        return issue;
    }

    private File writeErrors(List<ParentVersionIssue> errors) throws Exception {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("module", "module-a");
        data.put("errors", errors);
        File file = new File(dir, "module-a-errors.json");
        Files.write(file.toPath(), gson.toJson(data).getBytes(StandardCharsets.UTF_8));
        return file;
    }

    @Test
    void errorsRoundTripKeepsAllFieldsIncludingParentChain() throws Exception {
        File file = writeErrors(Collections.singletonList(issue()));

        List<ParentVersionIssue> read = jsonUtils.readErrorsFromFile(file);

        assertEquals(1, read.size());
        ParentVersionIssue r = read.get(0);
        assertEquals("module-a", r.getModuleName());
        assertEquals("lib", r.getLibraryArtifactId());
        assertEquals("4.0.0", r.getParentVersion());
        assertEquals("5.0.0", r.getMinExpectedVersion());
        assertTrue(r.isError());
        assertEquals("mid", r.getSourceLibrary());
        assertEquals(Arrays.asList("com.acme:mid:2.0", "com.acme:lib:1.0"), r.getFullPath());
        assertEquals(2, r.getParentChain().size());
        assertEquals("spring-parent", r.getParentChain().get(1).getArtifactId());
        assertEquals("4.0.0", r.getParentChain().get(1).getVersion());
    }

    @Test
    void fileWithoutErrorsYieldsEmptyList() throws Exception {
        File file = new File(dir, "x-errors.json");
        Files.write(file.toPath(), "{\"module\":\"x\"}".getBytes(StandardCharsets.UTF_8));

        assertTrue(jsonUtils.readErrorsFromFile(file).isEmpty());
    }

    @Test
    void emptyFileYieldsEmptyList() throws Exception {
        File file = new File(dir, "empty-errors.json");
        Files.write(file.toPath(), new byte[0]);

        assertTrue(jsonUtils.readErrorsFromFile(file).isEmpty());
    }

    @Test
    void utf8ContentIsReadCorrectly() throws Exception {
        ParentVersionIssue issue = issue();
        issue.setSourceLibrary("библиотека-ü");
        File file = writeErrors(Collections.singletonList(issue));

        assertEquals("библиотека-ü",
                jsonUtils.readErrorsFromFile(file).get(0).getSourceLibrary());
    }

    @Test
    void treeRoundTrip() throws Exception {
        String json = "{\"groupId\":\"g\",\"artifactId\":\"a\",\"version\":\"1\",\"module\":\"a\","
                + "\"children\":[{\"groupId\":\"g2\",\"artifactId\":\"b\",\"version\":\"2\",\"children\":[]}]}";
        File file = new File(dir, "a-tree.json");
        Files.write(file.toPath(), json.getBytes(StandardCharsets.UTF_8));

        MavenDependencyTree tree = jsonUtils.readTreeFromFile(file);

        assertNotNull(tree);
        assertEquals("a", tree.getArtifactId());
        assertEquals(1, tree.getChildren().size());
        assertEquals("b", tree.getChildren().get(0).getArtifactId());
    }
}
