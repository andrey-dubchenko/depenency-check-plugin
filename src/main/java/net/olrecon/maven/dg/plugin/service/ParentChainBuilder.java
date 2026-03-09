package net.olrecon.maven.dg.plugin.service;

import net.olrecon.maven.dg.plugin.model.ParentInfo;
import org.apache.maven.artifact.Artifact;
import org.apache.maven.artifact.factory.ArtifactFactory;
import org.apache.maven.artifact.repository.ArtifactRepository;
import org.apache.maven.artifact.resolver.ArtifactResolutionRequest;
import org.apache.maven.artifact.resolver.ArtifactResolutionResult;
import org.apache.maven.artifact.resolver.ArtifactResolver;
import org.apache.maven.model.Model;
import org.apache.maven.model.Parent;
import org.apache.maven.model.io.xpp3.MavenXpp3Reader;

import java.io.File;
import java.io.FileReader;
import java.util.*;

public class ParentChainBuilder {

    private final ArtifactResolver artifactResolver;
    private final ArtifactFactory artifactFactory;
    private final ArtifactRepository localRepository;
    private final List remoteRepositories;
    private final String targetGroupId;

    public ParentChainBuilder(ArtifactResolver artifactResolver,
                              ArtifactFactory artifactFactory,
                              ArtifactRepository localRepository,
                              List remoteRepositories,
                              String targetGroupId) {
        this.artifactResolver = artifactResolver;
        this.artifactFactory = artifactFactory;
        this.localRepository = localRepository;
        this.remoteRepositories = remoteRepositories;
        this.targetGroupId = targetGroupId;
    }

    public class ParentChain {
        public String groupId;
        public String artifactId;
        public String version;
        public List<ParentInfo> parents = new ArrayList<>();
        public boolean hasTargetGroup = false;
        public String targetParentVersion = null;

        public ParentChain(String groupId, String artifactId, String version) {
            this.groupId = groupId;
            this.artifactId = artifactId;
            this.version = version;
        }

        public void addParent(String groupId, String artifactId, String version) {
            parents.add(new ParentInfo(groupId, artifactId, version));
            // Используем targetGroupId внешнего класса
            if (groupId.equals(targetGroupId)) {
                this.hasTargetGroup = true;
                this.targetParentVersion = version;
            }
        }
    }

    public ParentChain buildParentChain(Artifact artifact) throws Exception {
        ParentChain chain = new ParentChain(
                artifact.getGroupId(),
                artifact.getArtifactId(),
                artifact.getVersion()
        );

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
            File pomFile = pomArtifact.getFile();
            if (pomFile != null && pomFile.exists()) {
                traverseParentChain(pomFile, chain, new HashSet<>());
            }
        }

        return chain;
    }

    private void traverseParentChain(File pomFile, ParentChain chain, Set<String> visited) throws Exception {
        try (FileReader reader = new FileReader(pomFile)) {
            MavenXpp3Reader mavenReader = new MavenXpp3Reader();
            Model model = mavenReader.read(reader);

            if (model.getParent() != null) {
                Parent parent = model.getParent();

                String parentKey = parent.getGroupId() + ":" + parent.getArtifactId() + ":" + parent.getVersion();
                if (visited.contains(parentKey)) {
                    return;
                }
                visited.add(parentKey);

                chain.addParent(parent.getGroupId(), parent.getArtifactId(), parent.getVersion());

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
                    File parentPomFile = parentArtifact.getFile();
                    if (parentPomFile != null && parentPomFile.exists()) {
                        traverseParentChain(parentPomFile, chain, visited);
                    }
                }
            }
        }
    }
}