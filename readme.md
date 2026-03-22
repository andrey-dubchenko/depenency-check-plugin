# Dependency Governance Maven Plugin
A comprehensive Maven plugin for analyzing and managing dependency versions across multi-module projects. This plugin helps identify potential issues with transitive dependencies, parent version conflicts, and ensures consistency across your project's dependency tree.

# Features
📦 Collect Dependencies - Gather all direct and transitive dependencies from your project

🔍 Check Parent Versions - Validate parent POM versions against defined minimum requirements

📊 Aggregate Results - Combine reports from all modules into a single comprehensive report

🎯 Multiple Target Groups - Support different version requirements for different parent groups

📁 Multi-module Support - Works seamlessly with multi-module Maven projects

📄 JSON Output - Structured output for easy integration with other tools

🚦 Build Failure - Optionally fail the build when version conflicts are detected

# Installation
Add the plugin to your parent pom.xml:

```xml
<build>
    <plugins>
        <plugin>
            <groupId>net.olrecon</groupId>
            <artifactId>dependency-governance-plugin</artifactId>
            <version>2.1.8</version>
        </plugin>
    </plugins>
</build>
```

# Plugin Goals
|Goal|   Phase   |                      Description                       |
| :--- |:---------:|:------------------------------------------------------:|
|dependency-tree-json|  compile  |  Collects all dependencies and builds dependency tree |
|check-versions|  package  | Validates parent versions against minimum requirements |
|aggregate| 	validate |	Aggregates results from all modules into final reports

# Configuration

```xml
    <configuration>
        <targetGroupId>org.springframework</targetGroupId>
        <minVersion>5.1.0</minVersion>
    </configuration>
    
    <executions>
        <execution>
            <id>check</id>
            <phase>package</phase>
            <goals>
                <goal>check-versions</goal>
            </goals>
        </execution>
    </executions>

<profiles>
    <profile>
        <id>aggregate</id>
        <build>
            <plugins>
                <plugin>
                    <groupId>net.olrecon</groupId>
                    <artifactId>dependency-governance-plugin</artifactId>
                    <executions>
                        <execution>
                            <id>aggregate-results</id>
                            <phase>validate</phase>
                            <goals>
                                <goal>aggregate</goal>
                            </goals>
                        </execution>
                    </executions>
                </plugin>
            </plugins>
        </build>
    </profile>
</profiles>
```

# Configuration parameters

| Parameter            |	Default|                                                               	Description                                                                |
|:---------------------|:---------:|:-----------------------------------------------------------------------------------------------------------------------------------------:|
| targetGroups         |	org.srpingframework=5.1.0|                           Comma-separated list of target groups with minimum versions (format: groupId=version)                           |
| failOnLowVersion     |	false|                                          	Whether to fail the build when low versions are found                                           | 
| includeTestScope     |	false|                                               	Include test scope dependencies in analysis                                                |
| includeProvidedScope |	false|                                                   	Include provided scope dependencies                                                    |
| includeSystemScope   |	false|                                                    	Include system scope dependencies                                                     |
| includeOptional      |	true|                                                      	Include optional dependencies                                                       |
| debug                |	false|                                                           	Enable debug logging                                                           |

# Examples

```bash
mvn net.olrecon:dependency-governance-plugin:2.1.8:dependency-tree-json \
  -DoutputFile=dependency-tree.json \
  -DincludeTestScope=false

mvn net.olrecon:dependency-governance-plugin:2.1.8:check-versions \
  -DtargetGroupId=org.springframework.boot \
  -DminVersion=3.1.0 \
  -DincludeGroups=com.mycompany,org.springframework.boot \
  -DexcludeGroups=org.junit,org.testcontainers
```
