package hudson.plugins.gradle.injection;

import com.google.common.base.Supplier;
import com.google.common.base.Suppliers;
import hudson.FilePath;
import org.apache.commons.io.IOUtils;

import javax.annotation.Nullable;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;
import java.util.stream.Collectors;

import static hudson.plugins.gradle.injection.CopyUtil.copyResourceToNode;
import static hudson.plugins.gradle.injection.CopyUtil.unsafeResourceDigest;

public class MavenExtensionsHandler {

    static final String LIB_DIR_PATH = "jenkins-gradle-plugin/lib";

    private final Map<MavenExtension, MavenExtensionFileHandler> fileHandlers =
        Arrays.stream(MavenExtension.values())
            .map(MavenExtensionFileHandler::new)
            .collect(Collectors.toMap(h -> h.extension, Function.identity()));

    public FilePath copyExtensionToAgent(MavenExtension extension, FilePath rootPath) throws IOException, InterruptedException {
        return fileHandlers.get(extension).copyExtensionToAgent(rootPath);
    }

    public void deleteExtensionFromAgent(MavenExtension extension, FilePath rootPath) throws IOException, InterruptedException {
        fileHandlers.get(extension).deleteExtensionFromAgent(rootPath);
    }

    public void deleteAllExtensionsFromAgent(FilePath rootPath) throws IOException, InterruptedException {
        rootPath.child(LIB_DIR_PATH).deleteContents();
    }

    private static final class MavenExtensionFileHandler {

        private final MavenExtension extension;
        private final Supplier<String> extensionDigest;

        MavenExtensionFileHandler(MavenExtension extension) {
            this.extension = extension;
            this.extensionDigest =
                Suppliers.memoize(() -> unsafeResourceDigest(extension.getEmbeddedJarName()));
        }

        /**
         * Copies the extension to the agent, if it is not already present, and returns a path to the extension
         * on the agent.
         */
        public FilePath copyExtensionToAgent(FilePath rootPath) throws IOException, InterruptedException {
            FilePath extensionLocation = getExtensionLocation(rootPath);
            if (extensionChanged(extensionLocation)) {
                copyResourceToNode(extensionLocation, extension.getEmbeddedJarName());
            }
            return extensionLocation;
        }

        public void deleteExtensionFromAgent(FilePath rootPath) throws IOException, InterruptedException {
            FilePath extensionLocation = getExtensionLocation(rootPath);
            if (extensionLocation.exists()) {
                extensionLocation.delete();
            }
        }

        private FilePath getExtensionLocation(FilePath rootPath) {
            return rootPath.child(LIB_DIR_PATH).child(extension.getTargetJarName());
        }

        private boolean extensionChanged(FilePath nodePath) throws IOException, InterruptedException {
            if (!nodePath.exists()) {
                return true;
            }
            String existingFileDigest = nodePath.digest();
            return !Objects.equals(existingFileDigest, extensionDigest.get());
        }
    }

    public enum MavenExtension {
        GRADLE_ENTERPRISE("gradle-enterprise-maven-extension"),
        CCUD("common-custom-user-data-maven-extension"),
        CONFIGURATION("configuration-maven-extension", "1.0.0");

        private static final String JAR_EXTENSION = ".jar";

        private final String name;
        private final Supplier<String> version;

        MavenExtension(String name) {
            this(name, null);
        }

        MavenExtension(String name, @Nullable String fixedVersion) {
            this.name = name;
            this.version =
                fixedVersion != null
                    ? Suppliers.ofInstance(fixedVersion)
                    : Suppliers.memoize(this::getExtensionVersion);
        }

        public String getTargetJarName() {
            return name + JAR_EXTENSION;
        }

        public String getEmbeddedJarName() {
            return name + "-" + version.get() + JAR_EXTENSION;
        }

        private String getExtensionVersion() {
            try {
                String resourceName = name + "-version.txt";
                try (InputStream is = MavenBuildScanInjection.class.getResourceAsStream("/versions/" + resourceName)) {
                    if (is == null) {
                        throw new IllegalStateException("Could not find resource: " + resourceName);
                    }
                    return IOUtils.toString(is, StandardCharsets.UTF_8);
                }
            } catch (IOException e) {
                throw new IllegalStateException(e);
            }
        }
    }
}
