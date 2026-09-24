package com.squadpulse.common;

import org.springframework.data.mongodb.core.MongoOperations;
import org.springframework.data.mongodb.repository.query.MongoEntityInformation;
import org.springframework.data.mongodb.repository.support.MongoRepositoryFactory;
import org.springframework.data.mongodb.repository.support.SimpleMongoRepository;
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
 *
 * <p>It also picks the base class per entity: {@link ClubScopedRepositoryImpl} for every {@link
 * ClubScopedEntity}, plain {@link SimpleMongoRepository} for an entity explicitly marked {@link
 * NotClubScoped} (e.g. {@code Club}, the tenant root), and a startup failure for anything else.
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
    if (isClubScoped(information.getDomainType())) {
      return getTargetRepositoryViaReflection(
          information, entityInformation, mongoOperations, clubContext);
    }
    return getTargetRepositoryViaReflection(information, entityInformation, mongoOperations);
  }

  @Override
  protected Class<?> getRepositoryBaseClass(RepositoryMetadata metadata) {
    Class<?> domainType = metadata.getDomainType();
    if (isClubScoped(domainType)) {
      return ClubScopedRepositoryImpl.class;
    }
    if (domainType.isAnnotationPresent(NotClubScoped.class)) {
      return SimpleMongoRepository.class;
    }
    throw new IllegalStateException(
        ("%s is a repository for %s, which neither extends ClubScopedEntity nor is annotated"
                + " @NotClubScoped. Tenant data must extend ClubScopedEntity; only data owned by no"
                + " club at all (e.g. Club itself) may be marked @NotClubScoped.")
            .formatted(metadata.getRepositoryInterface().getName(), domainType.getName()));
  }

  private static boolean isClubScoped(Class<?> domainType) {
    return ClubScopedEntity.class.isAssignableFrom(domainType);
  }
}
