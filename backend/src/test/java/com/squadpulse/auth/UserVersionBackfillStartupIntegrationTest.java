package com.squadpulse.auth;

import static org.assertj.core.api.Assertions.assertThat;

import com.mongodb.client.MongoClient;
import com.mongodb.client.MongoClients;
import java.util.concurrent.atomic.AtomicReference;
import org.bson.Document;
import org.bson.types.ObjectId;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.web.server.context.WebServerApplicationContext;
import org.springframework.context.ApplicationContext;
import org.springframework.context.SmartLifecycle;
import org.springframework.context.annotation.Bean;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.mongodb.MongoDBContainer;

/**
 * {@link UserVersionBackfill} has finished before the embedded web server starts accepting requests
 * (KAN-24): a legacy user stored before the application starts already has its version by the time
 * the web server's start/stop lifecycle — which is what opens Tomcat's connector — begins.
 *
 * <p>A real server ({@link WebEnvironment#RANDOM_PORT}) and its own container, so the context is
 * started fresh against a database that already holds the legacy document.
 */
@SpringBootTest(
    webEnvironment = WebEnvironment.RANDOM_PORT,
    properties = {
      "squadpulse.security.jwt-secret=test-only-jwt-secret-not-a-real-secret",
      "squadpulse.security.password-pepper=test-only-pepper-not-a-real-secret"
    })
@Testcontainers
class UserVersionBackfillStartupIntegrationTest {

  private static final ObjectId LEGACY_ID = new ObjectId();

  /** The legacy document as seen just before the web server started. */
  private static final AtomicReference<Document> SEEN_BEFORE_WEB_SERVER = new AtomicReference<>();

  @Container
  static final MongoDBContainer MONGO_DB_CONTAINER =
      new MongoDBContainer("mongo:7").withReplicaSet();

  @DynamicPropertySource
  static void mongoProperties(DynamicPropertyRegistry registry) {
    registry.add("spring.mongodb.uri", MONGO_DB_CONTAINER::getReplicaSetUrl);
  }

  /** Runs before the Spring context is created. */
  @BeforeAll
  static void storeALegacyUser() {
    try (MongoClient client = MongoClients.create(MONGO_DB_CONTAINER.getReplicaSetUrl())) {
      client
          .getDatabase("test")
          .getCollection("users")
          .insertOne(
              new Document("_id", LEGACY_ID)
                  .append("clubId", "club-a")
                  .append("email", "legacy@example.com")
                  .append("title", Title.HEAD_COACH.name())
                  .append("permissionLevel", PermissionLevel.EDIT_FULL.name())
                  .append("fullName", "Dana Levi")
                  .append("active", true));
    }
  }

  @TestConfiguration
  static class Config {

    /** Starts in the lifecycle phase just before the web server's own. */
    @Bean
    SmartLifecycle justBeforeTheWebServer(MongoTemplate mongoTemplate) {
      return new SmartLifecycle() {
        private boolean running;

        @Override
        public void start() {
          SEEN_BEFORE_WEB_SERVER.set(
              mongoTemplate.getCollection("users").find(new Document("_id", LEGACY_ID)).first());
          running = true;
        }

        @Override
        public void stop() {
          running = false;
        }

        @Override
        public boolean isRunning() {
          return running;
        }

        @Override
        public int getPhase() {
          return WebServerApplicationContext.START_STOP_LIFECYCLE_PHASE - 1;
        }
      };
    }
  }

  @Autowired private MongoTemplate mongoTemplate;
  @Autowired private ApplicationContext context;

  /** Nothing here sets {@value UserVersionBackfill#ENABLED_PROPERTY}: it's on by default. */
  @Test
  void theBackfillIsEnabledWhenThePropertyIsAbsent() {
    assertThat(context.getEnvironment().getProperty(UserVersionBackfill.ENABLED_PROPERTY)).isNull();
    assertThat(context.getBeansOfType(UserVersionBackfill.class)).hasSize(1);
  }

  @Test
  void theLegacyUserHadItsVersionBeforeTheWebServerStarted() {
    assertThat(mongoTemplate.getDb().getName()).isEqualTo("test");
    assertThat(SEEN_BEFORE_WEB_SERVER.get()).isNotNull();
    assertThat(SEEN_BEFORE_WEB_SERVER.get().get("version")).isEqualTo(0L);
  }
}
