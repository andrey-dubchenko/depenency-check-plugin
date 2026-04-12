package net.olrecon.maven.dg.plugin.model;

import com.google.gson.annotations.SerializedName;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * Basic information about a Maven dependency
 */
@Data
@AllArgsConstructor
@EqualsAndHashCode(onlyExplicitlyIncluded = true)
public class DependencyInfo {

    @EqualsAndHashCode.Include
    @SerializedName("groupId")
    private String groupId;

    @EqualsAndHashCode.Include
    @SerializedName("artifactId")
    private String artifactId;

    @EqualsAndHashCode.Include
    @SerializedName("version")
    private String version;

    @SerializedName("scope")
    private String scope;

    @SerializedName("type")
    private String type;

    /** Returns the unique dependency key in groupId:artifactId:version format */
    public String getKey() {
        return groupId + ":" + artifactId + ":" + version;
    }

    @Override
    public String toString() {
        return String.format("%s:%s:%s [%s]", groupId, artifactId, version, scope);
    }
}
