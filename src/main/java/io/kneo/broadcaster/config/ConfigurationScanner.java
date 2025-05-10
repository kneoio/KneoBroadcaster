package io.kneo.broadcaster.config;

import io.kneo.broadcaster.model.stats.ConfigurationStats;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Produces;
import org.jboss.jandex.AnnotationInstance;
import org.jboss.jandex.AnnotationTarget;
import org.jboss.jandex.ClassInfo;
import org.jboss.jandex.DotName;
import org.jboss.jandex.Index;
import org.jboss.jandex.IndexReader;
import org.jboss.jandex.MethodInfo;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@ApplicationScoped
public class ConfigurationScanner {
    private static final Logger LOGGER = LoggerFactory.getLogger(ConfigurationScanner.class);
    private static final String CONFIG_MAPPING_ANNOTATION = "io.smallrye.config.ConfigMapping";
    private static final String WITH_NAME_ANNOTATION = "io.smallrye.config.WithName";
    private static final String WITH_DEFAULT_ANNOTATION = "io.smallrye.config.WithDefault";

    private static final DotName CONFIG_MAPPING_DOT_NAME = DotName.createSimple(CONFIG_MAPPING_ANNOTATION);
    private static final DotName WITH_NAME_DOT_NAME = DotName.createSimple(WITH_NAME_ANNOTATION);
    private static final DotName WITH_DEFAULT_DOT_NAME = DotName.createSimple(WITH_DEFAULT_ANNOTATION);

    private volatile ConfigurationStats cachedStats;

    @Produces
    @ApplicationScoped
    public ConfigurationStats scanConfigurations() {
        if (cachedStats != null) {
            return cachedStats;
        }

        synchronized (this) {
            if (cachedStats != null) {
                return cachedStats;
            }

            ConfigurationStats stats = new ConfigurationStats();
            String packageName = "io.kneo.broadcaster.config";

            try (InputStream inputStream = Thread.currentThread()
                    .getContextClassLoader()
                    .getResourceAsStream("META-INF/jandex.idx")) {

                if (inputStream == null) {
                    LOGGER.warn("Jandex index file not found at META-INF/jandex.idx");
                    return stats;
                }

                Index index = new IndexReader(inputStream).read();
                List<AnnotationInstance> configMappings = index.getAnnotations(CONFIG_MAPPING_DOT_NAME);

                for (AnnotationInstance configMapping : configMappings) {
                    AnnotationTarget target = configMapping.target();
                    if (target instanceof ClassInfo classInfo) {
                        if (classInfo.name().toString().startsWith(packageName)) {
                            String className = classInfo.simpleName();
                            Map<String, String> configDetails = new HashMap<>();

                            for (MethodInfo methodInfo : classInfo.methods()) {
                                String fieldName = methodInfo.name();
                                if (fieldName.startsWith("get") && fieldName.length() > 3) {
                                    fieldName = fieldName.substring(3, 4).toLowerCase()
                                            + fieldName.substring(4);
                                }

                                AnnotationInstance withNameAnnotation = methodInfo.annotation(WITH_NAME_DOT_NAME);
                                if (withNameAnnotation != null) {
                                    fieldName = withNameAnnotation.value().asString();
                                }

                                AnnotationInstance withDefaultAnnotation = methodInfo.annotation(WITH_DEFAULT_DOT_NAME);
                                String defaultValue = withDefaultAnnotation != null
                                        ? withDefaultAnnotation.value().asString()
                                        : "N/A";

                                configDetails.put(fieldName, defaultValue);
                            }
                            stats.addConfig(className.toLowerCase(), configDetails);
                            LOGGER.debug("Found ConfigMapping: {}", className);
                        }
                    }
                }

                cachedStats = stats;
                return stats;

            } catch (IOException e) {
                LOGGER.error("Error scanning configurations: {}", e.getMessage(), e);
                return new ConfigurationStats(); // Return empty stats instead of null
            }
        }
    }
}