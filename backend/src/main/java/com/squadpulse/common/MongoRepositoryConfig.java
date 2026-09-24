package com.squadpulse.common;

import org.springframework.context.annotation.Configuration;
import org.springframework.data.mongodb.repository.config.EnableMongoRepositories;

/**
 * Registers {@link ClubScopedRepositoryImpl} as the base implementation for every Spring Data
 * MongoDB repository in the application, so club isolation applies by default with no
 * per-repository opt-in (see docs/spec.md section 03, CLAUDE.md standing rule 4).
 *
 * <p>An explicit {@code @EnableMongoRepositories} here replaces Spring Boot's default repository
 * auto-configuration for the whole app — it backs off automatically once a user-provided one is
 * present.
 */
@Configuration
@EnableMongoRepositories(
    basePackages = "com.squadpulse",
    repositoryBaseClass = ClubScopedRepositoryImpl.class,
    repositoryFactoryBeanClass = ClubScopedRepositoryFactoryBean.class)
public class MongoRepositoryConfig {}
