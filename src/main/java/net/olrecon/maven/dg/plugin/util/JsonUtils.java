package net.olrecon.maven.dg.plugin.util;

import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;
import net.olrecon.maven.dg.plugin.model.MavenDependencyTree;
import net.olrecon.maven.dg.plugin.model.ParentVersionIssue;

import java.io.File;
import java.io.FileReader;
import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

public class JsonUtils {
    private final Gson gson;

    public JsonUtils(Gson gson) {
        this.gson = gson;
    }

    public MavenDependencyTree readTreeFromFile(File file) throws Exception {
        try (FileReader reader = new FileReader(file)) {

            Type type = new TypeToken<MavenDependencyTree>() {
            }.getType();

            return gson.fromJson(reader, type);
        }
    }

    public List<ParentVersionIssue> readErrorsFromFile(File file) throws Exception {
        try (FileReader reader = new FileReader(file)) {
            Type type = new TypeToken<Map<String, Object>>() {
            }.getType();

            Map<String, Object> data = gson.fromJson(reader, type);

            List<ParentVersionIssue> errors = new ArrayList<>();

            if (data.containsKey("errors")) {
                List<Map<String, Object>> errorMaps = (List<Map<String, Object>>) data.get("errors");

                for (Map<String, Object> map : errorMaps) {
                    ParentVersionIssue issue = convertMapToIssue(map);
                    errors.add(issue);
                }
            }

            return errors;
        }
    }

    private ParentVersionIssue convertMapToIssue(Map<String, Object> map) {
        ParentVersionIssue issue = new ParentVersionIssue((String) map.get("module_name"));

        if (map.containsKey("library_group_id"))
            issue.setLibraryGroupId((String) map.get("library_group_id"));
        if (map.containsKey("library_artifact_id"))
            issue.setLibraryArtifactId((String) map.get("library_artifact_id"));
        if (map.containsKey("library_version"))
            issue.setLibraryVersion((String) map.get("library_version"));
        if (map.containsKey("source_library"))
            issue.setSourceLibrary((String) map.get("source_library"));
        if (map.containsKey("source_version"))
            issue.setSourceVersion((String) map.get("source_version"));
        if (map.containsKey("dependency_group_id"))
            issue.setDependencyGroupId((String) map.get("dependency_group_id"));
        if (map.containsKey("dependency_artifact_id"))
            issue.setDependencyArtifactId((String) map.get("dependency_artifact_id"));
        if (map.containsKey("dependency_version"))
            issue.setDependencyVersion((String) map.get("dependency_version"));
        if (map.containsKey("parent_version"))
            issue.setParentVersion((String) map.get("parent_version"));
        if (map.containsKey("minimum_expected_version"))
            issue.setMinExpectedVersion((String) map.get("minimum_expected_version"));
        if (map.containsKey("is_error"))
            issue.setError((Boolean) map.get("is_error"));
        if (map.containsKey("full_path"))
            issue.setFullPath((List<String>) map.get("full_path"));

        return issue;
    }
}