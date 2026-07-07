package io.github.hunghhdev.pgcache.spring;

import org.junit.jupiter.api.Test;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Auto-configurations must be registered for BOTH registration mechanisms:
 * {@code spring.factories} (Spring Boot 2.x) and
 * {@code META-INF/spring/....AutoConfiguration.imports} (Spring Boot 3.x,
 * which no longer reads auto-configurations from spring.factories).
 */
class AutoConfigurationRegistrationTest {

    private static final String IMPORTS_RESOURCE =
            "META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports";
    private static final String FACTORIES_RESOURCE = "META-INF/spring.factories";

    @Test
    void boot3ImportsFileListsAllAutoConfigurations() throws IOException {
        List<String> imports = readLines(IMPORTS_RESOURCE);

        assertTrue(imports.contains(PgCacheAutoConfiguration.class.getName()));
        assertTrue(imports.contains(PgCacheHealthAutoConfiguration.class.getName()));
        assertTrue(imports.contains(PgCacheMetricsAutoConfiguration.class.getName()));
    }

    @Test
    void boot2FactoriesAndBoot3ImportsStayInSync() throws IOException {
        List<String> imports = readLines(IMPORTS_RESOURCE);

        List<String> factoriesEntries = new ArrayList<>();
        for (String line : readLines(FACTORIES_RESOURCE)) {
            String content = line.replace("org.springframework.boot.autoconfigure.EnableAutoConfiguration=", "");
            factoriesEntries.addAll(Arrays.stream(content.split(","))
                    .map(s -> s.replace("\\", "").trim())
                    .filter(s -> !s.isEmpty())
                    .collect(Collectors.toList()));
        }

        assertEquals(factoriesEntries.stream().sorted().collect(Collectors.toList()),
                imports.stream().sorted().collect(Collectors.toList()),
                "spring.factories and AutoConfiguration.imports must list the same auto-configurations");
    }

    @Test
    void allRegisteredAutoConfigurationsAreResolvable() throws Exception {
        for (String className : readLines(IMPORTS_RESOURCE)) {
            assertDoesNotThrow(() -> Class.forName(className),
                    "class listed in AutoConfiguration.imports must exist: " + className);
        }
    }

    private static List<String> readLines(String resource) throws IOException {
        InputStream in = AutoConfigurationRegistrationTest.class.getClassLoader().getResourceAsStream(resource);
        assertNotNull(in, "resource must exist on the classpath: " + resource);
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
            return reader.lines()
                    .map(String::trim)
                    .filter(line -> !line.isEmpty() && !line.startsWith("#"))
                    .collect(Collectors.toList());
        }
    }
}
