package net.olrecon.maven.dg.plugin.model;

import lombok.Value;

import java.util.List;

/**
 * Source (origin) of a dependency — the artifact that pulled it in, and its path in the graph
 */
@Value
public class DependencySource {

    /** ArtifactId of the source artifact */
    String sourceArtifactId;

    /** Version of the source artifact */
    String sourceVersion;

    /** Path from the root to this dependency */
    List<String> path;
}
