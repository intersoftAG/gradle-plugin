package hudson.plugins.gradle.injection.maven;

import com.google.common.base.Splitter;
import hudson.EnvVars;
import hudson.Extension;
import hudson.FilePath;
import hudson.maven.PlexusModuleContributor;
import hudson.maven.PlexusModuleContributorFactory;
import hudson.model.AbstractBuild;
import hudson.model.Computer;
import hudson.model.Node;
import hudson.plugins.gradle.injection.MavenBuildScanInjection;
import hudson.util.LogTaskListener;
import org.apache.commons.lang3.StringUtils;

import java.io.File;
import java.util.List;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.stream.Collectors;

@Extension(optional = true)
public class GradleEnterpriseExtensionsContributorFactory extends PlexusModuleContributorFactory {

    private static final Logger LOGGER = Logger.getLogger(GradleEnterpriseExtensionsContributorFactory.class.getName());

    private static final PlexusModuleContributor EMPTY_CONTRIBUTOR = PlexusModuleContributor.of();
    private static final Splitter UNIX_CLASSPATH_SPLITTER = Splitter.on(':').omitEmptyStrings();
    private static final Splitter WINDOWS_CLASSPATH_SPLITTER = Splitter.on(';').omitEmptyStrings();

    @Override
    public PlexusModuleContributor createFor(AbstractBuild<?, ?> build) {
        try {
            Node node = build.getBuiltOn();
            if (node == null) {
                LOGGER.log(Level.WARNING, "Node must not be null");

                return EMPTY_CONTRIBUTOR;
            }

            EnvVars environment = build.getEnvironment(new LogTaskListener(LOGGER, Level.INFO));
            String classpath = environment.get(MavenBuildScanInjection.JENKINSGRADLEPLUGIN_MAVEN_PLUGIN_CONFIG_EXT_CLASSPATH);
            if (StringUtils.isBlank(classpath)) {
                return EMPTY_CONTRIBUTOR;
            }

            List<FilePath> jars =
                classpathFiles(node, classpath)
                    .stream()
                    .map(File::new)
                    .map(FilePath::new)
                    .filter(this::filePathExists)
                    .collect(Collectors.toList());

            // files were not found
            if (jars.isEmpty()) {
                return EMPTY_CONTRIBUTOR;
            }

            LOGGER.log(
                Level.FINE,
                "Maven extensions to add: {0}",
                jars.stream().map(FilePath::getRemote).collect(Collectors.joining(", ")));

            return PlexusModuleContributor.of(jars);
        } catch (Exception e) {
            LOGGER.log(Level.WARNING, "Unexpected exception while checking for maven extension", e);

            return EMPTY_CONTRIBUTOR;
        }
    }

    private static List<String> classpathFiles(Node node, String classpath) {
        Computer computer = node.toComputer();
        Splitter classpathSplitter =
            (computer == null || Boolean.TRUE.equals(computer.isUnix()))
                ? UNIX_CLASSPATH_SPLITTER
                : WINDOWS_CLASSPATH_SPLITTER;

        return classpathSplitter.splitToList(classpath);
    }

    private boolean filePathExists(FilePath filePath) {
        try {
            boolean exists = filePath.exists();
            if (!exists) {
                LOGGER.log(Level.WARNING, "File not found: {0}", filePath.getRemote());
            }
            return exists;
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }
}