package net.olrecon.maven.dg.plugin.model;

import com.google.gson.annotations.SerializedName;
import lombok.Data;

import java.util.ArrayList;
import java.util.List;

/**
 * Record of a detected violation: a dependency uses a parent POM
 * with a version below the minimum allowed
 */
@Data
public class ParentVersionIssue {

    @SerializedName("module_name")
    private String moduleName;

    @SerializedName("library_group_id")
    private String libraryGroupId;

    @SerializedName("library_artifact_id")
    private String libraryArtifactId;

    @SerializedName("library_version")
    private String libraryVersion;

    /** ArtifactId of the source — who introduced this dependency */
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

    /** Error flag: parent version is below the minimum */
    @SerializedName("is_error")
    private boolean error;

    @SerializedName("parent_chain")
    private List<ParentInfo> parentChain = new ArrayList<>();

    /** Full dependency path from the root */
    @SerializedName("full_path")
    private List<String> fullPath = new ArrayList<>();

    public ParentVersionIssue(String moduleName) {
        this.moduleName = moduleName;
    }

    /** Sets the data for the violating library */
    public void setLibrary(String groupId, String artifactId, String version) {
        this.libraryGroupId = groupId;
        this.libraryArtifactId = artifactId;
        this.libraryVersion = version;
    }

    /** Sets the data for the direct dependency */
    public void setDependency(String groupId, String artifactId, String version) {
        this.dependencyGroupId = groupId;
        this.dependencyArtifactId = artifactId;
        this.dependencyVersion = version;
    }

    /** Sets the data for the parent POM */
    public void setParent(String groupId, String artifactId, String version) {
        this.parentGroupId = groupId;
        this.parentArtifactId = artifactId;
        this.parentVersion = version;
    }

    /** Populates the dependency source information */
    public void setSource(DependencySource source) {
        if (source != null) {
            this.sourceLibrary = source.getSourceArtifactId();
            this.sourceVersion = source.getSourceVersion();
            this.fullPath = source.getPath();
        }
    }
}
