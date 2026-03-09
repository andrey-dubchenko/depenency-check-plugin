package net.olrecon.maven.dg.plugin.model;

import java.util.List;

public class DependencySource {
    private String sourceArtifactId;
    private String sourceVersion;
    private List<String> path;

    public DependencySource(String sourceArtifactId, String sourceVersion, List<String> path) {
        this.sourceArtifactId = sourceArtifactId;
        this.sourceVersion = sourceVersion;
        this.path = path;
    }

    public String getSourceArtifactId() {
        return sourceArtifactId;
    }

    public String getSourceVersion() {
        return sourceVersion;
    }

    public List<String> getPath() {
        return path;
    }
}