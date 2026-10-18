package net.olrecon.maven.dg.plugin.util;

import com.google.gson.Gson;
import com.google.gson.annotations.SerializedName;
import net.olrecon.maven.dg.plugin.model.MavenDependencyTree;
import net.olrecon.maven.dg.plugin.model.ParentVersionIssue;

import java.io.File;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;

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
        try (Reader reader = Files.newBufferedReader(file.toPath(), StandardCharsets.UTF_8)) {
            return gson.fromJson(reader, MavenDependencyTree.class);
        }
    }

    /**
     * Reads a list of version errors from a module's JSON file.
     * The file has the structure: { "module": "...", "errors": [...] }
     * Issues are deserialized directly, so all fields (including parent_chain) are preserved.
     */
    public List<ParentVersionIssue> readErrorsFromFile(File file) throws Exception {
        try (Reader reader = Files.newBufferedReader(file.toPath(), StandardCharsets.UTF_8)) {
            ErrorsFile data = gson.fromJson(reader, ErrorsFile.class);
            if (data == null || data.errors == null) {
                return new ArrayList<>();
            }
            return data.errors;
        }
    }

    /** Structure of the per-module errors file */
    private static class ErrorsFile {
        @SerializedName("module")
        private String module;

        @SerializedName("errors")
        private List<ParentVersionIssue> errors;
    }
}
