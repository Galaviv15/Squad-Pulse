package com.squadpulse.common;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.methods;

import com.tngtech.archunit.base.DescribedPredicate;
import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.domain.JavaMethod;
import com.tngtech.archunit.lang.ArchCondition;
import com.tngtech.archunit.lang.ArchRule;
import com.tngtech.archunit.lang.ConditionEvents;
import com.tngtech.archunit.lang.SimpleConditionEvent;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import org.springframework.data.mongodb.repository.MongoRepository;

/**
 * Shared between {@link ClubScopedRepositoryMethodNamingTest} (runs the rule against the real
 * codebase) and {@code ClubScopedRepositoryMethodNamingRuleTest} (proves the rule itself actually
 * catches a violation and allows a compliant method), so both exercise the exact same {@link
 * ArchRule} rather than two hand-maintained copies of it.
 *
 * <p>See {@link ClubScopedRepositoryImpl}'s class Javadoc for why this rule exists: custom/derived
 * query methods bypass that class entirely and are the one gap it can't close on its own.
 *
 * <p>The check is structural, not name-based: it flags every method declared directly on a
 * club-scoped repository interface (as opposed to one inherited from {@code MongoRepository} and
 * friends). {@link com.tngtech.archunit.core.domain.JavaClass#getMethods()} — what the {@code
 * methods()} DSL below iterates — only returns methods actually declared in that class's own
 * bytecode, never inherited ones, so this alone already means "custom method added by this
 * repository interface," whether it's a derived query (e.g. {@code findByEmail}) or a hand-written
 * {@code @Query}-annotated method with an arbitrary name. An earlier version of this rule matched
 * method names against a {@code findBy/countBy/existsBy/deleteBy} prefix regex, which missed both
 * other derivation prefixes (e.g. {@code findFirstBy}, {@code readBy}) and any {@code @Query}
 * method, since those have no naming convention to match at all.
 *
 * <p>A method satisfies the rule if <b>either</b> its name includes {@code ClubId} <b>or</b> it's
 * annotated with {@link GloballyScoped} — the narrow, explicit escape hatch for a lookup by a
 * globally unique value that must run before any club context exists (e.g. {@code
 * UserRepository.findByEmail} during login). The annotation is checked per method, so it only ever
 * exempts the one method it's on, never its siblings or the repository as a whole. See {@link
 * GloballyScoped} for when it's (rarely) acceptable.
 */
final class ClubScopedRepositoryRules {

  static final ArchRule DERIVED_FINDERS_ON_CLUB_SCOPED_REPOSITORIES_MUST_INCLUDE_CLUB_ID =
      methods()
          .that(
              DescribedPredicate.describe(
                  "are declared directly on a club-scoped repository interface",
                  method -> isClubScopedRepository(method.getOwner())))
          .should(
              new ArchCondition<JavaMethod>(
                  "include \"ClubId\" in the method name or be annotated with @GloballyScoped") {
                @Override
                public void check(JavaMethod method, ConditionEvents events) {
                  boolean satisfied =
                      method.getName().contains("ClubId")
                          || method.isAnnotatedWith(GloballyScoped.class);
                  String message =
                      satisfied
                          ? method.getFullName() + " is club-scoped or explicitly @GloballyScoped"
                          : ("%s is a custom method declared directly on a club-scoped repository"
                                  + " interface but its name doesn't include \"ClubId\" — whether"
                                  + " it's a derived query or an @Query method, it would run"
                                  + " unscoped against MongoDB and could leak another club's data."
                                  + " Rename it to include ClubId, e.g. findByNameAndClubId(...)."
                                  + " Only if it's a lookup by a globally unique value that must"
                                  + " run without a club context, annotate it @GloballyScoped"
                                  + " instead.")
                              .formatted(method.getFullName());
                  events.add(new SimpleConditionEvent(method, satisfied, message));
                }
              })
          .because(
              "a custom method on a club-scoped repository that omits ClubId from its name"
                  + " bypasses club isolation entirely, regardless of whether it's a derived query"
                  + " or an @Query method, unless it's deliberately marked @GloballyScoped");

  private ClubScopedRepositoryRules() {}

  private static boolean isClubScopedRepository(JavaClass javaClass) {
    if (!javaClass.isInterface()) {
      return false;
    }
    Class<?> reflected;
    try {
      reflected = javaClass.reflect();
    } catch (RuntimeException e) {
      // Not resolvable via reflection (e.g. not on this classpath) -> can't be one of ours.
      return false;
    }
    for (Type genericInterface : reflected.getGenericInterfaces()) {
      if (genericInterface instanceof ParameterizedType parameterizedType
          && parameterizedType.getRawType() instanceof Class<?> rawInterface
          && MongoRepository.class.isAssignableFrom(rawInterface)) {
        Type entityType = parameterizedType.getActualTypeArguments()[0];
        if (entityType instanceof Class<?> entityClass
            && ClubScopedEntity.class.isAssignableFrom(entityClass)) {
          return true;
        }
      }
    }
    return false;
  }
}
