package net.olrecon.maven.dg.plugin.service;

import lombok.Getter;
import net.olrecon.maven.dg.plugin.model.ParentInfo;
import org.apache.maven.artifact.Artifact;
import org.apache.maven.artifact.factory.ArtifactFactory;
import org.apache.maven.artifact.repository.ArtifactRepository;
import org.apache.maven.artifact.resolver.ArtifactResolutionRequest;
import org.apache.maven.artifact.resolver.ArtifactResolutionResult;
import org.apache.maven.artifact.resolver.ArtifactResolver;
import org.apache.maven.execution.MavenSession;
import org.apache.maven.model.Model;
import org.apache.maven.model.Parent;
import org.apache.maven.model.io.xpp3.MavenXpp3Reader;
import org.apache.maven.plugin.logging.Log;
import org.apache.maven.project.MavenProject;

import java.io.File;
import java.io.FileReader;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Builds the parent POM chain for a given artifact.
 * Supports projects that use maven-flatten-plugin:
 * for artifacts present in the current reactor session,
 * the POM file is taken directly from the project (including .flattened-pom.xml).
 */
public class ParentChainBuilder {

    private final ArtifactResolver artifactResolver;
    private final ArtifactFactory artifactFactory;
    private final ArtifactRepository localRepository;
    private final List remoteRepositories;

    /**
     * Target groups for version tracking (e.g. "org.springframework").
     * Empty list means no version checking is performed.
     */
    private final List<String> targetGroupIds;

    /**
     * Maven session used to look up artifacts in the reactor.
     * Used to support maven-flatten-plugin:
     * if an artifact is part of the current build, its POM is taken from the project
     * rather than from the repository (which may not yet contain the up-to-date version).
     */
    private final MavenSession session;

    private final Log log;

    public ParentChainBuilder(ArtifactResolver artifactResolver,
                              ArtifactFactory artifactFactory,
                              ArtifactRepository localRepository,
                              List remoteRepositories,
                              List<String> targetGroupIds,
                              MavenSession session,
                              Log log) {
        this.artifactResolver = artifactResolver;
        this.artifactFactory = artifactFactory;
        this.localRepository = localRepository;
        this.remoteRepositories = remoteRepositories;
        this.targetGroupIds = targetGroupIds != null ? targetGroupIds : new ArrayList<>();
        this.session = session;
        this.log = log;
    }

    /**
     * Builds the parent POM chain for an artifact.
     * First checks the reactor (to support maven-flatten-plugin),
     * then falls back to the repository.
     */
    public ParentChain buildParentChain(Artifact artifact) throws Exception {
        ParentChain chain = new ParentChain(
                artifact.getGroupId(),
                artifact.getArtifactId(),
                artifact.getVersion()
        );

        File pomFile = resolvePomFile(artifact);
        if (pomFile != null && pomFile.exists()) {
            traverseParentChain(pomFile, chain, new HashSet<>());
        }

        return chain;
    }

    /**
     * Determines the POM file for an artifact.
     * Priority: reactor project (flatten-plugin support) → local/remote repository.
     */
    private File resolvePomFile(Artifact artifact) throws Exception {
        // Check whether the artifact is part of the current build (reactor)
        File reactorPom = findReactorProjectPom(artifact);
        if (reactorPom != null) {
            log.debug("Using reactor POM for: " + artifactKey(artifact));
            return reactorPom;
        }

        // Resolve POM from the repository
        return resolveFromRepository(artifact);
    }

    /**
     * Looks for the artifact's POM file among the projects of the current reactor.
     * This is necessary when using maven-flatten-plugin: sibling modules
     * may not yet be installed in the local repository
     * but are already accessible via the Maven session.
     */
    private File findReactorProjectPom(Artifact artifact) {
        if (session == null) {
            return null;
        }
        for (MavenProject reactorProject : session.getAllProjects()) {
            if (isMatchingProject(reactorProject, artifact)) {
                File pomFile = reactorProject.getFile();
                if (pomFile != null && pomFile.exists()) {
                    return pomFile;
                }
            }
        }
        return null;
    }

    /** Checks whether a reactor project matches the given artifact */
    private boolean isMatchingProject(MavenProject project, Artifact artifact) {
        return project.getGroupId().equals(artifact.getGroupId())
                && project.getArtifactId().equals(artifact.getArtifactId())
                && project.getVersion().equals(artifact.getVersion());
    }

    /**
     * Resolves the artifact's POM file from the Maven repository.
     * Returns null if resolution fails.
     */
    private File resolveFromRepository(Artifact artifact) {
        Artifact pomArtifact = artifactFactory.createArtifact(
                artifact.getGroupId(),
                artifact.getArtifactId(),
                artifact.getVersion(),
                null,
                "pom"
        );

        ArtifactResolutionRequest request = new ArtifactResolutionRequest();
        request.setArtifact(pomArtifact);
        request.setLocalRepository(localRepository);
        request.setRemoteRepositories(remoteRepositories);

        ArtifactResolutionResult result = artifactResolver.resolve(request);

        if (result.isSuccess()) {
            return pomArtifact.getFile();
        }

        log.debug("Could not resolve POM from repository: " + artifactKey(artifact));
        return null;
    }

    /**
     * Recursively traverses the parent chain starting from the given POM file.
     * Protected against circular dependencies via a set of already-visited keys.
     */
    private void traverseParentChain(File pomFile, ParentChain chain, Set<String> visited) throws Exception {
        Model model = readPomModel(pomFile);
        if (model == null || model.getParent() == null) {
            return;
        }

        Parent parent = model.getParent();
        String parentKey = parentKey(parent);

        if (visited.contains(parentKey)) {
            return;
        }
        visited.add(parentKey);

        chain.addParent(parent.getGroupId(), parent.getArtifactId(), parent.getVersion(), targetGroupIds);

        File parentPomFile = resolveParentPomFile(parent);
        if (parentPomFile != null && parentPomFile.exists()) {
            traverseParentChain(parentPomFile, chain, visited);
        }
    }

    /** Reads the POM model from a file */
    private Model readPomModel(File pomFile) {
        try (FileReader reader = new FileReader(pomFile)) {
            return new MavenXpp3Reader().read(reader);
        } catch (Exception e) {
            log.debug("Failed to read POM file " + pomFile + ": " + e.getMessage());
            return null;
        }
    }

    /**
     * Resolves the POM file for the parent artifact.
     * First checks the reactor (flatten-plugin support), then falls back to the repository.
     */
    private File resolveParentPomFile(Parent parent) {
        // Look for the parent in the reactor
        if (session != null) {
            for (MavenProject reactorProject : session.getAllProjects()) {
                if (reactorProject.getGroupId().equals(parent.getGroupId())
                        && reactorProject.getArtifactId().equals(parent.getArtifactId())
                        && reactorProject.getVersion().equals(parent.getVersion())) {
                    File pomFile = reactorProject.getFile();
                    if (pomFile != null && pomFile.exists()) {
                        log.debug("Parent found in reactor: " + parentKey(parent));
                        return pomFile;
                    }
                }
            }
        }

        // Resolve the parent from the repository
        Artifact parentArtifact = artifactFactory.createArtifact(
                parent.getGroupId(),
                parent.getArtifactId(),
                parent.getVersion(),
                null,
                "pom"
        );

        ArtifactResolutionRequest request = new ArtifactResolutionRequest();
        request.setArtifact(parentArtifact);
        request.setLocalRepository(localRepository);
        request.setRemoteRepositories(remoteRepositories);

        ArtifactResolutionResult result = artifactResolver.resolve(request);
        if (result.isSuccess()) {
            return parentArtifact.getFile();
        }

        log.debug("Could not resolve parent POM: " + parentKey(parent));
        return null;
    }

    private String artifactKey(Artifact artifact) {
        return artifact.getGroupId() + ":" + artifact.getArtifactId() + ":" + artifact.getVersion();
    }

    private String parentKey(Parent parent) {
        return parent.getGroupId() + ":" + parent.getArtifactId() + ":" + parent.getVersion();
    }

    // -------------------------------------------------------------------------
    // Inner class — result of building the parent chain
    // -------------------------------------------------------------------------

    /**
     * Result of analyzing the parent POM chain for a single artifact.
     * Contains the artifact's GAV, the list of parents, and target group data.
     */
    @Getter
    public static class ParentChain {

        private final String groupId;
        private final String artifactId;
        private final String version;

        /** List of found parent POMs */
        private final List<ParentInfo> parents = new ArrayList<>();

        /** Whether the target group was found in the parent chain */
        private boolean hasTargetGroup = false;

        /** Version of the parent from the target group (if found) */
        private String targetParentVersion = null;

        ParentChain(String groupId, String artifactId, String version) {
            this.groupId = groupId;
            this.artifactId = artifactId;
            this.version = version;
        }

        /**
         * Adds a parent POM to the chain.
         * If groupId is in targetGroupIds, the version is recorded for subsequent checking.
         *
         * @param targetGroupIds target groups for version checking; empty list disables checking
         */
        void addParent(String groupId, String artifactId, String version, List<String> targetGroupIds) {
            parents.add(new ParentInfo(groupId, artifactId, version));

            if (targetGroupIds != null && !targetGroupIds.isEmpty() && targetGroupIds.contains(groupId)) {
                this.hasTargetGroup = true;
                this.targetParentVersion = version;
            }
        }
    }
}
