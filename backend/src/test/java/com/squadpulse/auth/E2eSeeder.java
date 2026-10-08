package com.squadpulse.auth;

import com.squadpulse.auth.ClubBootstrapService.CreatedClub;
import com.squadpulse.auth.ClubBootstrapService.NewClub;
import com.squadpulse.common.ClubContext;
import jakarta.validation.Validator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.SpringApplication;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Profile;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.redis.connection.RedisConnection;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;

/**
 * Seeds the database the Playwright end-to-end tests run against (KAN-53): wipes the E2E MongoDB
 * database and the E2E Redis logical database, then creates one club and its Club Manager (ADMIN)
 * through the real {@link ClubBootstrapService} — the production validation, Argon2id + pepper
 * hashing and transaction. Players are created later through the real API (frontend/e2e).
 *
 * <p>Test sources only, never in the jar, and only under the {@value #PROFILE} profile (which also
 * switches the web server off, see application-e2e-seed.yml), run once with {@code ./mvnw
 * spring-boot:test-run -Dspring-boot.run.profiles=e2e-seed}; it exits the JVM with 0 or 1. Not
 * under the {@code bootstrap} profile, which would also start the interactive {@link
 * ClubBootstrapRunner}: the service is built here from the same beans instead.
 *
 * <p>{@link #checkTarget} refuses anything but a {@value #DATABASE_SUFFIX} database and a non-zero
 * Redis database, so it can never wipe the development data (database {@code squadpulse}, Redis
 * database 0). The passwords come from environment variables and are never logged.
 */
@Component
@Profile(E2eSeeder.PROFILE)
class E2eSeeder implements ApplicationRunner {

  static final String PROFILE = "e2e-seed";
  static final String DATABASE_SUFFIX = "_e2e";

  private static final Logger log = LoggerFactory.getLogger(E2eSeeder.class);

  private final MongoTemplate mongoTemplate;
  private final RedisConnectionFactory redisConnectionFactory;
  private final ClubBootstrapService clubBootstrapService;
  private final ConfigurableApplicationContext context;
  private final String ownerSecret;
  private final NewClub newClub;

  E2eSeeder(
      MongoTemplate mongoTemplate,
      RedisConnectionFactory redisConnectionFactory,
      ClubRepository clubRepository,
      UserRepository userRepository,
      PasswordEncoder passwordEncoder,
      Validator validator,
      ClubContext clubContext,
      PlatformTransactionManager transactionManager,
      ConfigurableApplicationContext context,
      @Value("${E2E_OWNER_SECRET}") String ownerSecret,
      @Value("${E2E_CLUB_NAME}") String clubName,
      @Value("${E2E_ADMIN_EMAIL}") String adminEmail,
      @Value("${E2E_ADMIN_PASSWORD}") String adminPassword,
      @Value("${E2E_ADMIN_FULL_NAME}") String adminFullName) {
    this.mongoTemplate = mongoTemplate;
    this.redisConnectionFactory = redisConnectionFactory;
    this.clubBootstrapService =
        new ClubBootstrapService(
            clubRepository,
            userRepository,
            passwordEncoder,
            validator,
            clubContext,
            transactionManager,
            new OwnerBootstrapProperties(ownerSecret));
    this.context = context;
    this.ownerSecret = ownerSecret;
    this.newClub = new NewClub(clubName, adminEmail, adminPassword, adminFullName, null);
  }

  @Override
  public void run(ApplicationArguments args) {
    int exitCode = seed();
    System.exit(SpringApplication.exit(context, () -> exitCode));
  }

  private int seed() {
    try {
      String database = mongoTemplate.getDb().getName();
      int redisDatabase = redisDatabase();
      checkTarget(database, redisDatabase);

      mongoTemplate.getDb().drop();
      try (RedisConnection connection = redisConnectionFactory.getConnection()) {
        connection.serverCommands().flushDb();
      }
      CreatedClub created = clubBootstrapService.bootstrap(ownerSecret, newClub);
      log.info(
          "E2E seed done: database '{}' and Redis database {} wiped, club {} with ADMIN {}",
          database,
          redisDatabase,
          created.club().getId(),
          created.manager().getEmail());
      return 0;
    } catch (RuntimeException e) {
      log.error("E2E seed failed", e);
      return 1;
    }
  }

  private int redisDatabase() {
    if (redisConnectionFactory instanceof LettuceConnectionFactory lettuce) {
      return lettuce.getDatabase();
    }
    throw new IllegalStateException(
        "unexpected Redis connection factory " + redisConnectionFactory.getClass().getName());
  }

  /**
   * Refuses to wipe anything but a dedicated E2E target: a MongoDB database whose name ends in
   * {@value #DATABASE_SUFFIX}, and a Redis logical database other than 0 (the development one).
   */
  static void checkTarget(String database, int redisDatabase) {
    if (database == null || !database.endsWith(DATABASE_SUFFIX)) {
      throw new IllegalStateException(
          "refusing to seed: the MongoDB database must end with '"
              + DATABASE_SUFFIX
              + "', got '"
              + database
              + "' (set it in MONGODB_URI)");
    }
    if (redisDatabase == 0) {
      throw new IllegalStateException(
          "refusing to seed: the Redis database must not be 0 (set SPRING_DATA_REDIS_DATABASE)");
    }
  }
}
