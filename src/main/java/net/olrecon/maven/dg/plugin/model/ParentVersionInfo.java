package net.olrecon.maven.dg.plugin.model;

import com.google.gson.annotations.SerializedName;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.ArrayList;
import java.util.List;

/**
 * Result of analyzing the parent POM chain for a single dependency:
 * whether the target group was found, the version, and whether the minimum version is violated
 */
@Data
@NoArgsConstructor
public class ParentVersionInfo {

    /** Whether the target group was found in the parent chain */
    @SerializedName("has_target_group")
    private boolean hasTargetGroup;

    /** Version of the found parent from the target group */
    @SerializedName("target_parent_version")
    private String targetParentVersion;

    /** Whether the version is below the minimum allowed */
    @SerializedName("is_low_version")
    private boolean lowVersion;

    /** Minimum allowed version */
    @SerializedName("minimum_expected")
    private String minimumExpected;

    /** Full parent POM chain */
    @SerializedName("parent_chain")
    private List<ParentInfo> parentChain = new ArrayList<>();
}
