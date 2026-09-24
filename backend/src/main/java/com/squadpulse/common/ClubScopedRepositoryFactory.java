package com.squadpulse.common;

import org.springframework.data.mongodb.core.MongoOperations;
import org.springframework.data.mongodb.repository.query.MongoEntityInformation;
import org.springframework.data.mongodb.repository.support.MongoRepositoryFactory;
import org.springframework.data.repository.core.RepositoryInformation;
import org.springframework.data.repository.core.RepositoryMetadata;

/**
 * Repository factory that passes {@link ClubContext} into every repository it builds, in addition
 * to the standard entity-information/{@link MongoOperations} pair Spring Data normally supplies to
 * {@link org.springframework.data.mongodb.repository.support.SimpleMongoRepository} subclasses.
 *
 * <p>This is the extension point {@link ClubScopedRepositoryImpl} needs: the default {@link
 * MongoRepositoryFactory} has no way to inject an extra collaborator into the repository base
 * class, so {@code repositoryBaseClass} alone isn't enough — see {@link
 * ClubScopedRepositoryFactoryBean}, which wires this factory in.
 */
class ClubScopedRepositoryFactory extends MongoRepositoryFactory {

  private final MongoOperations mongoOperations;
  private final ClubContext clubContext;

  ClubScopedRepositoryFactory(MongoOperations mongoOperations, ClubContext clubContext) {
    super(mongoOperations);
    this.mongoOperations = mongoOperations;
    this.clubContext = clubContext;
  }

  @Override
  protected Object getTargetRepository(RepositoryInformation information) {
    MongoEntityInformation<?, ?> entityInformation = getEntityInformation(information);
    return getTargetRepositoryViaReflection(
        information, entityInformation, mongoOperations, clubContext);
  }

  @Override
  protected Class<?> getRepositoryBaseClass(RepositoryMetadata metadata) {
    return ClubScopedRepositoryImpl.class;
  }
}
