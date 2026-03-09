package net.olrecon.maven.dg.plugin.model;

import com.google.gson.annotations.SerializedName;

import java.util.ArrayList;
import java.util.List;

public class ParentVersionIssue {
    @SerializedName("module_name")
    private String moduleName;

    @SerializedName("library_group_id")
    private String libraryGroupId;

    @SerializedName("library_artifact_id")
    private String libraryArtifactId;

    @SerializedName("library_version")
    private String libraryVersion;

    @SerializedName("source_library")
    private String sourceLibrary;

    @SerializedName("source_version")
    private String sourceVersion;

    @SerializedName("dependency_group_id")
    private String dependencyGroupId;

    @SerializedName("dependency_artifact_id")
    private String dependencyArtifactId;

    @SerializedName("dependency_version")
    private String dependencyVersion;

    @SerializedName("parent_group_id")
    private String parentGroupId;

    @SerializedName("parent_artifact_id")
    private String parentArtifactId;

    @SerializedName("parent_version")
    private String parentVersion;

    @SerializedName("minimum_expected_version")
    private String minExpectedVersion;

    @SerializedName("is_error")
    private boolean isError;

    @SerializedName("parent_chain")
    private List<ParentInfo> parentChain;

    @SerializedName("full_path")
    private List<String> fullPath;

    public ParentVersionIssue(String moduleName) {
        this.moduleName = moduleName;
        this.parentChain = new ArrayList<>();
        this.fullPath = new ArrayList<>();
    }

    public String getModuleName() {
        return moduleName;
    }

    public void setLibraryGroupId(String libraryGroupId) {
        this.libraryGroupId = libraryGroupId;
    }

    public String getLibraryArtifactId() {
        return libraryArtifactId;
    }

    public void setLibraryArtifactId(String libraryArtifactId) {
        this.libraryArtifactId = libraryArtifactId;
    }

    public String getLibraryVersion() {
        return libraryVersion;
    }

    public void setLibraryVersion(String libraryVersion) {
        this.libraryVersion = libraryVersion;
    }

    public void setSourceLibrary(String sourceLibrary) {
        this.sourceLibrary = sourceLibrary;
    }

    public void setSourceVersion(String sourceVersion) {
        this.sourceVersion = sourceVersion;
    }

    public void setDependencyGroupId(String dependencyGroupId) {
        this.dependencyGroupId = dependencyGroupId;
    }

    public void setDependencyArtifactId(String dependencyArtifactId) {
        this.dependencyArtifactId = dependencyArtifactId;
    }

    public void setDependencyVersion(String dependencyVersion) {
        this.dependencyVersion = dependencyVersion;
    }

    public String getParentVersion() {
        return parentVersion;
    }

    public void setParentVersion(String parentVersion) {
        this.parentVersion = parentVersion;
    }

    public void setMinExpectedVersion(String minExpectedVersion) {
        this.minExpectedVersion = minExpectedVersion;
    }

    public boolean isError() {
        return isError;
    }

    public void setError(boolean error) {
        isError = error;
    }

    public void setFullPath(List<String> fullPath) {
        this.fullPath = fullPath;
    }

    public void addToChain(String groupId, String artifactId, String version) {
        this.parentChain.add(new ParentInfo(groupId, artifactId, version));
    }

    public void setLibrary(String groupId, String artifactId, String version) {
        this.libraryGroupId = groupId;
        this.libraryArtifactId = artifactId;
        this.libraryVersion = version;
    }

    public void setSource(DependencySource source) {
        if (source != null) {
            this.sourceLibrary = source.getSourceArtifactId();
            this.sourceVersion = source.getSourceVersion();
            this.fullPath = source.getPath();
        }
    }

    public void setDependency(String groupId, String artifactId, String version) {
        this.dependencyGroupId = groupId;
        this.dependencyArtifactId = artifactId;
        this.dependencyVersion = version;
    }

    public void setParent(String groupId, String artifactId, String version) {
        this.parentGroupId = groupId;
        this.parentArtifactId = artifactId;
        this.parentVersion = version;
    }
}