/*
 * Copyright Kroxylicious Authors.
 *
 * Licensed under the Apache Software License version 2.0, available at http://www.apache.org/licenses/LICENSE-2.0
 */
package io.kroxylicious.krpccodegen.main;

import java.io.File;
import java.io.IOException;
import java.net.URISyntaxException;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import javax.tools.DiagnosticCollector;
import javax.tools.JavaFileObject;
import javax.tools.StandardJavaFileManager;
import javax.tools.ToolProvider;

import org.apache.kafka.common.protocol.Message;
import org.junit.jupiter.api.Assumptions;

/**
 * Dynamically generates and compiles a {@code *Data} codec class using the kroxylicious
 * template, then loads it in a child {@link URLClassLoader} that delegates to the current
 * classloader for Kafka protocol interfaces.
 * <p>
 * Because the generated class implements {@link Message} (and {@code ApiMessage}) from the
 * same {@code kafka-clients} jar visible to the current classloader, callers can cast the
 * loaded class instance directly to {@link Message} — no reflection is required for
 * {@code read}, {@code write}, or {@code addSize}.
 */
class GeneratedCodecHarness {

    private static final String TEST_PACKAGE = "io.kroxylicious.test.message";
    private static final String TEMPLATE = "Data/example.ftl";

    private GeneratedCodecHarness() {
    }

    /**
     * Generates, compiles and loads the {@code *Data} class for the given Kafka message spec.
     *
     * <p>The test is skipped ({@code Assumptions.assumeTrue}) if a JDK compiler is not available
     * (e.g. running on a JRE-only environment).
     *
     * @param specFilter glob filter matching the JSON spec file (e.g. {@code "FetchRequest.json"})
     * @param dataClassName simple class name of the generated class (e.g. {@code "FetchRequestData"})
     * @return the loaded {@link Class}, castable to {@link Message}
     */
    @SuppressWarnings("java:S2095") // URLClassLoader intentionally kept open for the test's lifetime
    static Class<?> generateAndLoad(String specFilter, String dataClassName) throws Exception {
        Assumptions.assumeTrue(ToolProvider.getSystemJavaCompiler() != null,
                "JDK compiler not available — skipping fidelity test");

        Path tempDir = Files.createTempDirectory("krpc-fidelity-");
        generate(specFilter, tempDir.toFile());
        compile(tempDir);

        URLClassLoader loader = new URLClassLoader(
                new URL[]{ tempDir.toUri().toURL() },
                Thread.currentThread().getContextClassLoader());
        return loader.loadClass(TEST_PACKAGE + "." + dataClassName);
    }

    /**
     * Generates, compiles and loads all {@code *Data} classes for all *Request.json
     * and *Response.json message specs.
     *
     * @return unmodifiable map from simple class name (e.g. {@code "FetchRequestData"})
     *         to loaded {@link Class}, or an empty map if no JDK compiler is available
     */
    @SuppressWarnings("java:S2095") // URLClassLoader intentionally kept open
    static Map<String, Class<?>> generateAndLoadAll() throws Exception {
        if (ToolProvider.getSystemJavaCompiler() == null) {
            return Collections.emptyMap();
        }

        Path tempDir = Files.createTempDirectory("krpc-all-");
        generate("*{Request,Response}.json", tempDir.toFile());
        compile(tempDir);

        URLClassLoader loader = new URLClassLoader(
                new URL[]{ tempDir.toUri().toURL() },
                Thread.currentThread().getContextClassLoader());

        Map<String, Class<?>> result = new HashMap<>();
        try (Stream<Path> stream = Files.walk(tempDir)) {
            stream.filter(p -> p.toString().endsWith(".class") && !p.toString().contains("$"))
                    .forEach(p -> {
                        String relative = tempDir.relativize(p).toString()
                                .replace(File.separatorChar, '.')
                                .replace(".class", "");
                        String simpleName = relative.substring(relative.lastIndexOf('.') + 1);
                        try {
                            result.put(simpleName, loader.loadClass(relative));
                        }
                        catch (ClassNotFoundException e) {
                            throw new IllegalStateException("Cannot load " + relative, e);
                        }
                    });
        }
        return Collections.unmodifiableMap(result);
    }

    /**
     * Returns the names of all *Request.json and *Response.json message specs in the
     * test message spec directory, without the ".json" suffix.
     */
    static List<String> allRequestResponseSpecNames() throws URISyntaxException {
        Path specDir = buildDir().resolve("message-specs/common/message");
        File[] files = specDir.toFile().listFiles(
                f -> f.getName().endsWith("Request.json") || f.getName().endsWith("Response.json"));
        if (files == null) {
            return Collections.emptyList();
        }
        return Arrays.stream(files)
                .map(f -> f.getName().replace(".json", ""))
                .sorted()
                .collect(Collectors.toList());
    }

    /** Creates a new instance of the loaded generated class, cast to {@link Message}. */
    static Message newInstance(Class<?> generatedClass) throws Exception {
        return (Message) generatedClass.getDeclaredConstructor().newInstance();
    }

    private static void generate(String specFilter, File outputDir) throws Exception {
        Path buildDir = buildDir();
        KrpcGenerator gen = KrpcGenerator.single()
                .withMessageSpecDir(buildDir.resolve("message-specs/common/message").toFile())
                .withMessageSpecFilter(specFilter)
                .withTemplateDir(buildDir.resolve("test-classes").toFile())
                .withTemplateNames(List.of(TEMPLATE))
                .withOutputPackage(TEST_PACKAGE)
                .withOutputDir(outputDir)
                .withOutputFilePattern("${messageSpecName}Data.java")
                .build();
        gen.generate();
    }

    private static void compile(Path outputDir) throws IOException {
        var compiler = ToolProvider.getSystemJavaCompiler();
        DiagnosticCollector<JavaFileObject> diagnostics = new DiagnosticCollector<>();
        try (StandardJavaFileManager fm = compiler.getStandardFileManager(diagnostics, null, null)) {
            List<File> javaFiles;
            try (Stream<Path> stream = Files.walk(outputDir)) {
                javaFiles = stream
                        .filter(p -> p.toString().endsWith(".java"))
                        .map(Path::toFile)
                        .collect(Collectors.toList());
            }

            List<String> options = List.of(
                    "-classpath", System.getProperty("java.class.path"),
                    "-d", outputDir.toString());

            boolean success = compiler.getTask(
                    null, fm, diagnostics, options, null,
                    fm.getJavaFileObjectsFromFiles(javaFiles)).call();

            if (!success) {
                String errors = diagnostics.getDiagnostics().stream()
                        .map(Object::toString)
                        .collect(Collectors.joining("\n"));
                throw new IllegalStateException("Compilation of generated codec failed:\n" + errors);
            }
        }
    }

    private static Path buildDir() throws URISyntaxException {
        return Paths.get(GeneratedCodecHarness.class
                .getProtectionDomain().getCodeSource().getLocation().toURI()).getParent();
    }
}
