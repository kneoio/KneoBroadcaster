package io.kneo.broadcaster.service.scheduler.quartz;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Produces;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Properties;

@ApplicationScoped
public class QuartzConfig {
    private static final Logger LOGGER = LoggerFactory.getLogger(QuartzConfig.class);

    @ConfigProperty(name = "quarkus.datasource.reactive.url")
    String databaseUrl;

    @ConfigProperty(name = "quarkus.datasource.username")
    String databaseUsername;

    @ConfigProperty(name = "quarkus.datasource.password")
    String databasePassword;

    @Produces
    @ApplicationScoped
    public Properties quartzProperties() {
        Properties props = new Properties();
        
        // Scheduler Configuration
        props.setProperty("org.quartz.scheduler.instanceName", "KneoBroadcasterScheduler");
        props.setProperty("org.quartz.scheduler.instanceId", "AUTO");
        props.setProperty("org.quartz.scheduler.skipUpdateCheck", "true");
        
        // Thread Pool Configuration
        props.setProperty("org.quartz.threadPool.class", "org.quartz.simpl.SimpleThreadPool");
        props.setProperty("org.quartz.threadPool.threadCount", "10");
        props.setProperty("org.quartz.threadPool.threadPriority", "5");
        
        // Job Store Configuration - Database
        props.setProperty("org.quartz.jobStore.class", "org.quartz.impl.jdbcjobstore.JobStoreTX");
        props.setProperty("org.quartz.jobStore.driverDelegateClass", "org.quartz.impl.jdbcjobstore.PostgreSQLDelegate");
        props.setProperty("org.quartz.jobStore.useProperties", "false");
        props.setProperty("org.quartz.jobStore.dataSource", "myDS");
        props.setProperty("org.quartz.jobStore.tablePrefix", "QRTZ_");
        props.setProperty("org.quartz.jobStore.isClustered", "false");
        
        // DataSource Configuration
        String jdbcUrl = convertReactiveUrlToJdbc(databaseUrl);
        props.setProperty("org.quartz.dataSource.myDS.driver", "org.postgresql.Driver");
        props.setProperty("org.quartz.dataSource.myDS.URL", jdbcUrl);
        props.setProperty("org.quartz.dataSource.myDS.user", databaseUsername);
        props.setProperty("org.quartz.dataSource.myDS.password", databasePassword);
        props.setProperty("org.quartz.dataSource.myDS.maxConnections", "5");
        props.setProperty("org.quartz.dataSource.myDS.validationQuery", "SELECT 1");
        
        LOGGER.info("Configured Quartz with database persistence: {}", jdbcUrl);
        return props;
    }



    private String convertReactiveUrlToJdbc(String reactiveUrl) {
        // Convert postgresql://host:port/db to jdbc:postgresql://host:port/db
        if (reactiveUrl.startsWith("postgresql://")) {
            return "jdbc:" + reactiveUrl;
        }
        return reactiveUrl;
    }
}
