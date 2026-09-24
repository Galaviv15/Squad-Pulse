package com.squadpulse.common;

import java.io.Serializable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.mongodb.core.MongoOperations;
import org.springframework.data.mongodb.repository.support.MongoRepositoryFactory;
import org.springframework.data.mongodb.repository.support.MongoRepositoryFactoryBean;
import org.springframework.data.repository.Repository;

/**
 * {@link org.springframework.data.mongodb.repository.config.EnableMongoRepositories
 * repositoryFactoryBeanClass} that builds a {@link ClubScopedRepositoryFactory} instead of the
 * default {@link MongoRepositoryFactory}, so {@link ClubContext} reaches every repository's base
 * implementation. See {@link MongoRepositoryConfig}.
 */
class ClubScopedRepositoryFactoryBean<T extends Repository<S, ID>, S, ID extends Serializable>
    extends MongoRepositoryFactoryBean<T, S, ID> {

  // Field injection: RepositoryFactoryBeanSupport instances only expose a single-arg
  // (repositoryInterface) constructor to the Spring Data infrastructure that creates them, so
  // there's no constructor slot for this. They're still ordinary Spring beans under the hood,
  // so @Autowired runs before afterPropertiesSet() calls getFactoryInstance() below.
  @Autowired private ClubContext clubContext;

  ClubScopedRepositoryFactoryBean(Class<? extends T> repositoryInterface) {
    super(repositoryInterface);
  }

  @Override
  protected MongoRepositoryFactory getFactoryInstance(MongoOperations operations) {
    return new ClubScopedRepositoryFactory(operations, clubContext);
  }
}
