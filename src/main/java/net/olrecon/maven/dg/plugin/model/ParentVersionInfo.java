package net.olrecon.maven.dg.plugin.model;

import com.google.gson.annotations.SerializedName;

import java.util.ArrayList;
import java.util.List;

public class ParentVersionInfo {
    @SerializedName("has_target_group")
    private boolean hasTargetGroup;

    @SerializedName("target_parent_version")
    private String targetParentVersion;

    @SerializedName("is_low_version")
    private boolean isLowVersion;

    @SerializedName("minimum_expected")
    private String minimumExpected;

    @SerializedName("parent_chain")
    private List<ParentInfo> parentChain;

    public ParentVersionInfo() {
        this.parentChain = new ArrayList<>();
    }

    public void setHasTargetGroup(boolean hasTargetGroup) {
        this.hasTargetGroup = hasTargetGroup;
    }

    public void setTargetParentVersion(String targetParentVersion) {
        this.targetParentVersion = targetParentVersion;
    }

    public void setLowVersion(boolean lowVersion) {
        isLowVersion = lowVersion;
    }

    public void setMinimumExpected(String minimumExpected) {
        this.minimumExpected = minimumExpected;
    }

    public void setParentChain(List<ParentInfo> parentChain) {
        this.parentChain = parentChain;
    }
}
