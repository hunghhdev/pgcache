package io.github.hunghhdev.pgcache.quarkus;

import org.junit.jupiter.api.Test;

import java.io.InputStream;

import static org.junit.jupiter.api.Assertions.*;

/**
 * pgcache-quarkus is a plain library jar (not a Quarkus extension), so ArC
 * only discovers its CDI beans if the jar ships a Jandex index. Without it,
 * {@code @Inject PgQuarkusCacheManager} fails with
 * UnsatisfiedResolutionException unless the user manually configures
 * {@code quarkus.index-dependency}.
 */
class BeanDiscoveryTest {

    @Test
    void jarMustShipAJandexIndex() {
        InputStream index = BeanDiscoveryTest.class.getClassLoader()
                .getResourceAsStream("META-INF/jandex.idx");
        assertNotNull(index,
                "META-INF/jandex.idx must be generated at build time so ArC discovers the CDI producer");
    }

    @Test
    void configInterfaceMustNotCarryExtensionOnlyAnnotations() {
        // @ConfigRoot is Quarkus-extension build-time machinery; in a runtime-only
        // jar it is dead weight that can confuse config validation
        boolean hasConfigRoot = false;
        for (java.lang.annotation.Annotation annotation : PgQuarkusCacheConfig.class.getAnnotations()) {
            if (annotation.annotationType().getName().equals("io.quarkus.runtime.annotations.ConfigRoot")) {
                hasConfigRoot = true;
            }
        }
        assertFalse(hasConfigRoot, "PgQuarkusCacheConfig must rely on @ConfigMapping alone");
    }
}
