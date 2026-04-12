package net.olrecon.maven.dg.plugin.model;

import com.google.gson.annotations.SerializedName;
import lombok.Value;

/**
 * Information about a single parent POM in the inheritance chain
 */
@Value
public class ParentInfo {

    @SerializedName("group_id")
    String groupId;

    @SerializedName("artifact_id")
    String artifactId;

    @SerializedName("version")
    String version;
}
