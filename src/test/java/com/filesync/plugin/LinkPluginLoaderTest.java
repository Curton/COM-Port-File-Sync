package com.filesync.plugin;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.CleanupMode;
import org.junit.jupiter.api.io.TempDir;

/**
 * Plugin discovery along both paths: a provider declared on the class path itself, and one shipped
 * inside a jar in the {@code plugins} directory. Broken jars must never take the app down.
 */
class LinkPluginLoaderTest {

    private static final String SERVICE_FILE = "META-INF/services/" + LinkPlugin.class.getName();

    // The plugin class loader keeps the jar open for the lifetime of the process (that is the
    // point — its classes stay live), so Windows refuses the delete and JUnit must not try.
    @TempDir(cleanup = CleanupMode.NEVER)
    Path pluginsDir;

    @Test
    void classpathProviderIsDiscovered() {
        List<LinkPlugin> plugins =
                LinkPluginLoader.loadFromClasspath(LinkPluginLoaderTest.class.getClassLoader());

        assertEquals(1, plugins.size());
        assertEquals("classpath-test", plugins.get(0).name());
        assertEquals(
                ClasspathTestPlugin.ENDPOINT_ID, plugins.get(0).discoverEndpoints().get(0).id());
    }

    @Test
    void jarInThePluginsDirectoryIsDiscovered() throws IOException {
        writePluginJar(pluginsDir.resolve("bridge-plugin.jar"));

        List<LinkPlugin> plugins =
                LinkPluginLoader.loadFromDirectory(pluginsDir, getClass().getClassLoader());

        assertEquals(1, plugins.size());
        assertTrue(plugins.get(0).owns(ClasspathTestPlugin.ENDPOINT_ID));
        assertEquals(1, plugins.get(0).discoverEndpoints().size());
    }

    @Test
    void brokenJarIsSkippedWithoutLosingTheGoodOne() throws IOException {
        Files.write(pluginsDir.resolve("a-broken.jar"), new byte[] {1, 2, 3, 4, 5});
        writePluginJar(pluginsDir.resolve("z-good.jar"));

        List<LinkPlugin> plugins =
                LinkPluginLoader.loadFromDirectory(pluginsDir, getClass().getClassLoader());

        assertEquals(1, plugins.size(), "the corrupt jar must be skipped, not fatal");
        assertEquals("classpath-test", plugins.get(0).name());
    }

    @Test
    void jarWithoutAServiceDeclarationIsSkipped() throws IOException {
        Path jar = pluginsDir.resolve("plain-library.jar");
        try (JarOutputStream out = new JarOutputStream(Files.newOutputStream(jar))) {
            out.putNextEntry(new JarEntry("some/Other.class"));
            out.write(new byte[] {0});
            out.closeEntry();
        }

        assertTrue(
                LinkPluginLoader.loadFromDirectory(pluginsDir, getClass().getClassLoader())
                        .isEmpty());
    }

    @Test
    void missingDirectoryIsNotAnError() {
        assertTrue(
                LinkPluginLoader.loadFromDirectory(
                                pluginsDir.resolve("nope"), getClass().getClassLoader())
                        .isEmpty());
        assertTrue(LinkPluginLoader.loadFromDirectory(null, getClass().getClassLoader()).isEmpty());
    }

    @Test
    void defaultDirectoryIsNamedPlugins() {
        assertEquals("plugins", LinkPluginLoader.defaultPluginDirectory().getFileName().toString());
    }

    /** Writes a jar holding the provider class and its service declaration. */
    private static void writePluginJar(Path jar) throws IOException {
        try (OutputStream file = Files.newOutputStream(jar);
                JarOutputStream out = new JarOutputStream(file)) {
            out.putNextEntry(new JarEntry(SERVICE_FILE));
            out.write(
                    (ClasspathTestPlugin.class.getName() + "\n").getBytes(StandardCharsets.UTF_8));
            out.closeEntry();

            String classResource = "com/filesync/plugin/ClasspathTestPlugin.class";
            try (InputStream in =
                    LinkPluginLoaderTest.class
                            .getClassLoader()
                            .getResourceAsStream(classResource)) {
                assertTrue(
                        in != null, "the provider class must be compiled before the jar is built");
                out.putNextEntry(new JarEntry(classResource));
                out.write(in.readAllBytes());
                out.closeEntry();
            }
        }
    }
}
