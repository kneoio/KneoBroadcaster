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

    @Produces
    @ApplicationScoped
    public ConfigurationStats scanConfigurations() {
        ConfigurationStats stats = new ConfigurationStats();
        String packageName = "io.kneo.broadcaster.config";

        try {
            ClassLoader classLoader = Thread.currentThread().getContextClassLoader();
            Index index = new IndexReader(classLoader.getResourceAsStream("META-INF/jandex.idx")).read();

            List<AnnotationInstance> configMappings = index.getAnnotations(CONFIG_MAPPING_DOT_NAME);

            for (AnnotationInstance configMapping : configMappings) {
                AnnotationTarget target = configMapping.target();
                if (target instanceof ClassInfo classInfo) {
                    if (classInfo.name().toString().startsWith(packageName)) {
                        String className = classInfo.simpleName().toLowerCase(); // Use simpleName() and toLowerCase()
                        Map<String, String> configDetails = new HashMap<>();
                        for (MethodInfo methodInfo : classInfo.methods()) {
                            String fieldName = methodInfo.name().startsWith("get") && methodInfo.name().length() > 3
                                    ? methodInfo.name().substring(3, 4).toLowerCase() + methodInfo.name().substring(4)
                                    : methodInfo.name();

                            AnnotationInstance withNameAnnotation = methodInfo.annotation(WITH_NAME_DOT_NAME);
                            if (withNameAnnotation != null) {
                                fieldName = withNameAnnotation.value().asString();
                            }

                            AnnotationInstance withDefaultAnnotation = methodInfo.annotation(WITH_DEFAULT_DOT_NAME);
                            String defaultValue = withDefaultAnnotation != null ? withDefaultAnnotation.value().asString() : "N/A";

                            configDetails.put(fieldName, defaultValue);
                        }
                        stats.addConfig(className, configDetails);
                        LOGGER.info("Found ConfigMapping: {}", className);
                    }
                }
            }

        } catch (IOException e) {
            LOGGER.error("Error scanning configurations: {}", e.getMessage(), e);
        }
        return stats;
    }
}