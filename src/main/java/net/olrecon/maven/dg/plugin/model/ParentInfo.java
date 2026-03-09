package net.olrecon.maven.dg.plugin.model;

import com.google.gson.annotations.SerializedName;

public class ParentInfo {
    @SerializedName("group_id")
    private String groupId;

    @SerializedName("artifact_id")
    private String artifactId;

    @SerializedName("version")
    private String version;

    public ParentInfo(String groupId, String artifactId, String version) {
        this.groupId = groupId;
        this.artifactId = artifactId;
        this.version = version;
    }

    public String getGroupId() {
        return groupId;
    }

    public String getArtifactId() {
        return artifactId;
    }

    public String getVersion() {
        return version;
    }
}