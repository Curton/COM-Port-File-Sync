package com.filesync.plugin;

import com.filesync.Main;
import java.io.IOException;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.CodeSource;
import java.util.ArrayList;
import java.util.List;
import java.util.ServiceConfigurationError;
import java.util.ServiceLoader;

/**
 * Discovers {@link LinkPlugin} implementations: every {@code *.jar} in the {@code plugins}
 * directory next to the running jar, plus any provider registered on the application class path
 * itself.
 *
 * <p>Each jar gets its own {@link URLClassLoader} whose parent is the application loader, so a
 * plugin can use the host's shaded jSerialComm (and the app's own plugin interface) while keeping
 * its private copies of everything else to itself. No version check is performed: the plugin jar
 * and the app are a matched pair maintained by hand, exactly like the firmware generation the
 * bridge plugin talks to.
 *
 * <p>A jar that fails to load (corrupt, missing provider, throwing provider) is reported on stderr
 * and skipped; it never prevents the app from starting.
 */
public final class LinkPluginLoader {

    /** Directory scanned next to the running jar. */
    public static final String PLUGIN_DIR_NAME = "plugins";

    private static final String SERVICES_RESOURCE =
            "META-INF/services/" + LinkPlugin.class.getName();

    private LinkPluginLoader() {}

    /** Loads plugins from the class path and from the {@code plugins} directory of this run. */
    public static List<LinkPlugin> loadDefault() {
        ClassLoader appLoader = LinkPluginLoader.class.getClassLoader();
        List<LinkPlugin> plugins = new ArrayList<>(loadFromClasspath(appLoader));
        plugins.addAll(loadFromDirectory(defaultPluginDirectory(), appLoader));
        return plugins;
    }

    /** Service-provider lookup on an existing class loader (test seam; no directory scan). */
    static List<LinkPlugin> loadFromClasspath(ClassLoader loader) {
        List<LinkPlugin> plugins = new ArrayList<>();
        try {
            for (LinkPlugin plugin : ServiceLoader.load(LinkPlugin.class, loader)) {
                plugins.add(plugin);
            }
        } catch (ServiceConfigurationError e) {
            report("classpath provider failed", e);
        }
        return plugins;
    }

    /**
     * Loads every {@code *.jar} in {@code directory}. A missing directory is not an error: a
     * deployment without plugins is the normal case.
     */
    static List<LinkPlugin> loadFromDirectory(Path directory, ClassLoader parent) {
        if (directory == null || !Files.isDirectory(directory)) {
            return List.of();
        }
        List<LinkPlugin> plugins = new ArrayList<>();
        try (DirectoryStream<Path> jars = Files.newDirectoryStream(directory, "*.jar")) {
            for (Path jar : jars) {
                plugins.addAll(loadJar(jar, parent));
            }
        } catch (IOException e) {
            report("cannot list " + directory, e);
        }
        return plugins;
    }

    private static List<LinkPlugin> loadJar(Path jar, ClassLoader parent) {
        URLClassLoader loader = null;
        try {
            URL url = jar.toUri().toURL();
            loader = new URLClassLoader(new URL[] {url}, parent);
            if (loader.findResource(SERVICES_RESOURCE) == null) {
                System.err.println(
                        "[plugins] " + jar.getFileName() + " declares no LinkPlugin provider");
                closeQuietly(loader);
                return List.of();
            }
            List<LinkPlugin> plugins = new ArrayList<>();
            for (LinkPlugin plugin : ServiceLoader.load(LinkPlugin.class, loader)) {
                plugins.add(plugin);
            }
            if (plugins.isEmpty()) {
                closeQuietly(loader);
            }
            // A loader that yielded plugins stays open: its classes are live in the app.
            return plugins;
        } catch (Exception | ServiceConfigurationError e) {
            report("cannot load " + jar.getFileName(), e);
            if (loader != null) {
                closeQuietly(loader);
            }
            return List.of();
        }
    }

    /**
     * The {@code plugins} directory next to the running jar. Started from a jar, that is the jar's
     * own directory; started from a classes directory (development), the working directory is used
     * instead, because {@code target/classes/plugins} is not where anything is deployed.
     */
    static Path defaultPluginDirectory() {
        try {
            CodeSource source = Main.class.getProtectionDomain().getCodeSource();
            if (source != null && source.getLocation() != null) {
                Path location = Paths.get(source.getLocation().toURI());
                if (Files.isRegularFile(location) && location.getParent() != null) {
                    return location.getParent().resolve(PLUGIN_DIR_NAME);
                }
            }
        } catch (Exception e) {
            report("cannot locate the application jar", e);
        }
        return Paths.get(PLUGIN_DIR_NAME);
    }

    private static void closeQuietly(URLClassLoader loader) {
        try {
            loader.close();
        } catch (IOException e) {
            report("cannot close plugin loader", e);
        }
    }

    private static void report(String what, Throwable e) {
        System.err.println("[plugins] " + what + ": " + e);
    }
}
