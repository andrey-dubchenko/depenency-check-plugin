package net.olrecon.maven.dg.plugin.model;

import com.google.gson.annotations.SerializedName;

public class DependencyInfo {

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

    public DependencyInfo(String groupId, String artifactId, String version, String scope, String type) {
        this.groupId = groupId;
        this.artifactId = artifactId;
        this.version = version;
        this.scope = scope;
        this.type = type;
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

    public void setVersion(String version) {
        this.version = version;
    }

    public String getType() {
        return type;
    }

    /**
     * Возвращает уникальный ключ зависимости
     */
    public String getKey() {
        return groupId + ":" + artifactId + ":" + version;
    }

    @Override
    public String toString() {
        return String.format("%s:%s:%s [%s]", groupId, artifactId, version, scope);
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;

        DependencyInfo that = (DependencyInfo) o;

        if (!groupId.equals(that.groupId)) return false;
        if (!artifactId.equals(that.artifactId)) return false;
        return version.equals(that.version);
    }

    @Override
    public int hashCode() {
        int result = groupId.hashCode();
        result = 31 * result + artifactId.hashCode();
        result = 31 * result + version.hashCode();
        return result;
    }
}