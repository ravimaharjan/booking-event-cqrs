package com.example.eventbooking.config;

import com.zaxxer.hikari.HikariDataSource;
import java.time.Clock;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * Two pools: the primary (commands, Flyway, transaction manager) and the read replica (queries).
 * Which one a query uses is decided explicitly per request, see {@code ReadRouter}.
 */
@Configuration
class DataSourceConfig {

    @Bean
    @Primary
    @ConfigurationProperties("app.datasource.primary")
    HikariDataSource primaryDataSource() {
        var ds = new HikariDataSource();
        ds.setPoolName("primary");
        return ds;
    }

    @Bean
    @ConfigurationProperties("app.datasource.replica")
    HikariDataSource replicaDataSource() {
        var ds = new HikariDataSource();
        ds.setPoolName("replica");
        return ds;
    }

    @Bean
    JdbcClient primaryJdbcClient(@Qualifier("primaryDataSource") HikariDataSource primaryDataSource) {
        return JdbcClient.create(primaryDataSource);
    }

    @Bean
    JdbcClient replicaJdbcClient(@Qualifier("replicaDataSource") HikariDataSource replicaDataSource) {
        return JdbcClient.create(replicaDataSource);
    }

    @Bean
    Clock clock() {
        return Clock.systemUTC();
    }
}
