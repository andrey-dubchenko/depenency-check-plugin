package net.olrecon.maven.dg.plugin.model;

import com.google.gson.annotations.SerializedName;
import lombok.Getter;
import lombok.Setter;
import org.apache.maven.artifact.Artifact;

import java.util.ArrayList;
import java.util.List;

/**
 * Node of the Maven module dependency tree.
 * Recursive structure: each node contains a list of child nodes.
 */
@Getter
@Setter
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

    @SerializedName("classifier")
    private String classifier;

    @SerializedName("optional")
    private boolean optional;

    @SerializedName("module")
    private String module;

    @SerializedName("parent_version_info")
    private ParentVersionInfo parentVersionInfo;

    @SerializedName("children")
    private List<MavenDependencyTree> children = new ArrayList<>();

    /** Creates a tree node from a Maven artifact */
    public MavenDependencyTree(Artifact artifact) {
        this.groupId = artifact.getGroupId();
        this.artifactId = artifact.getArtifactId();
        this.version = artifact.getVersion();
        this.scope = artifact.getScope() != null ? artifact.getScope() : "compile";
        this.type = artifact.getType() != null ? artifact.getType() : "jar";
        this.classifier = artifact.getClassifier();
        this.optional = artifact.isOptional();
    }

    /** Adds a child node to the tree */
    public void addChild(MavenDependencyTree child) {
        this.children.add(child);
    }
}
