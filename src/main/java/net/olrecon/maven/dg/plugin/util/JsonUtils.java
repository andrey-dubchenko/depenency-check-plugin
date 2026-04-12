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

/**
 * Utilities for reading JSON files containing dependency trees and version errors
 */
public class JsonUtils {

    private final Gson gson;

    public JsonUtils(Gson gson) {
        this.gson = gson;
    }

    /** Reads a dependency tree from a JSON file */
    public MavenDependencyTree readTreeFromFile(File file) throws Exception {
        try (FileReader reader = new FileReader(file)) {
            Type type = new TypeToken<MavenDependencyTree>() {}.getType();
            return gson.fromJson(reader, type);
        }
    }

    /**
     * Reads a list of version errors from a module's JSON file.
     * The file has the structure: { "module": "...", "errors": [...] }
     */
    public List<ParentVersionIssue> readErrorsFromFile(File file) throws Exception {
        try (FileReader reader = new FileReader(file)) {
            Type mapType = new TypeToken<Map<String, Object>>() {}.getType();
            Map<String, Object> data = gson.fromJson(reader, mapType);

            List<ParentVersionIssue> errors = new ArrayList<>();

            if (data.containsKey("errors")) {
                List<Map<String, Object>> errorMaps = (List<Map<String, Object>>) data.get("errors");
                for (Map<String, Object> map : errorMaps) {
                    errors.add(convertMapToIssue(map));
                }
            }

            return errors;
        }
    }

    /**
     * Converts a list of Maps (from Gson) into a list of ParentVersionIssue objects.
     * Used when reading the aggregated JSON in GenerateReportMojo.
     */
    public List<ParentVersionIssue> convertMapsToIssues(List<Map<String, Object>> maps) {
        List<ParentVersionIssue> result = new ArrayList<>();
        if (maps == null) {
            return result;
        }
        for (Map<String, Object> map : maps) {
            result.add(convertMapToIssue(map));
        }
        return result;
    }

    /** Converts a Map (from Gson) into a ParentVersionIssue object */
    private ParentVersionIssue convertMapToIssue(Map<String, Object> map) {
        ParentVersionIssue issue = new ParentVersionIssue((String) map.get("module_name"));

        setStringField(map, "library_group_id", issue::setLibraryGroupId);
        setStringField(map, "library_artifact_id", issue::setLibraryArtifactId);
        setStringField(map, "library_version", issue::setLibraryVersion);
        setStringField(map, "source_library", issue::setSourceLibrary);
        setStringField(map, "source_version", issue::setSourceVersion);
        setStringField(map, "dependency_group_id", issue::setDependencyGroupId);
        setStringField(map, "dependency_artifact_id", issue::setDependencyArtifactId);
        setStringField(map, "dependency_version", issue::setDependencyVersion);
        setStringField(map, "parent_version", issue::setParentVersion);
        setStringField(map, "minimum_expected_version", issue::setMinExpectedVersion);

        if (map.containsKey("is_error")) {
            issue.setError((Boolean) map.get("is_error"));
        }
        if (map.containsKey("full_path")) {
            issue.setFullPath((List<String>) map.get("full_path"));
        }

        return issue;
    }

    /** Helper method: sets a string field if it is present in the map */
    private void setStringField(Map<String, Object> map, String key, java.util.function.Consumer<String> setter) {
        if (map.containsKey(key)) {
            setter.accept((String) map.get(key));
        }
    }
}
