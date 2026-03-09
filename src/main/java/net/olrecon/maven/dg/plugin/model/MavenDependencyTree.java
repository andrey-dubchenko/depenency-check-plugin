package net.olrecon.maven.dg.plugin.model;

import com.google.gson.annotations.SerializedName;
import org.apache.maven.artifact.Artifact;

import java.util.ArrayList;
import java.util.List;

public class MavenDependencyTree {
    @SerializedName("groupId")
    private String groupId;

    @SerializedName("artifactId")
    private String artifactId;

    @SerializedName("version")
    private String version;

    @SerializedName("scope")
    private String scope;

    @SerializedName("type")
    private String type;

    @SerializedName("children")
    private List<MavenDependencyTree> children;

    @SerializedName("module")
    private String module;

    @SerializedName("parent_version_info")
    private ParentVersionInfo parentVersionInfo;

    public MavenDependencyTree(Artifact artifact) {
        this.groupId = artifact.getGroupId();
        this.artifactId = artifact.getArtifactId();
        this.version = artifact.getVersion();
        this.scope = artifact.getScope() != null ? artifact.getScope() : "compile";
        this.type = artifact.getType() != null ? artifact.getType() : "jar";
        this.children = new ArrayList<>();
    }

    public String getVersion() {
        return version;
    }

    public void setVersion(String version) {
        this.version = version;
    }

    public void setModule(String module) {
        this.module = module;
    }

    public void setParentVersionInfo(ParentVersionInfo parentVersionInfo) {
        this.parentVersionInfo = parentVersionInfo;
    }

    public void addChild(MavenDependencyTree child) {
        this.children.add(child);
    }
}