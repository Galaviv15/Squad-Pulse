package com.squadpulse.auth;

import static org.assertj.core.api.Assertions.assertThat;

import com.squadpulse.common.ClubContext;
import jakarta.validation.Validator;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.DefaultApplicationArguments;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.mongodb.MongoDBContainer;

/**
 * Proves the owner-only club bootstrap (KAN-16) against a real MongoDB replica set — the same
 * topology as the local docker-compose MongoDB, and the only one on which MongoDB supports the
 * multi-document transaction the Club + Club Manager creation relies on.
 *
 * <p>Drives the real {@link ClubBootstrapRunner} and {@link ClubBootstrapService}, with scripted
 * answers standing in for the terminal prompts and the exit code captured instead of terminating
 * the JVM. Both are built here by hand from the real beans: they only exist under the {@code
 * bootstrap} profile, which isn't active here — otherwise the runner would run (and exit) while the
 * test context starts.
 */
@SpringBootTest(
    properties = {
      "squadpulse.security.jwt-secret=test-only-jwt-secret-not-a-real-secret",
      "squadpulse.security.password-pepper=test-only-pepper-not-a-real-secret"
    })
@Testcontainers
class ClubBootstrapIntegrationTest {

  private static final String OWNER_SECRET = "test-only-owner-secret-not-a-real-one";
  private static final String PASSWORD = "correct-horse-battery";

  @Container
  static final MongoDBContainer MONGO_DB_CONTAINER =
      new MongoDBContainer("mongo:7").withReplicaSet();

  @DynamicPropertySource
  static void mongoProperties(DynamicPropertyRegistry registry) {
    registry.add("spring.mongodb.uri", MONGO_DB_CONTAINER::getReplicaSetUrl);
  }

  @Autowired private ClubRepository clubRepository;
  @Autowired private UserRepository userRepository;
  @Autowired private PasswordEncoder passwordEncoder;
  @Autowired private Validator validator;
  @Autowired private ClubContext clubContext;
  @Autowired private PlatformTransactionManager transactionManager;
  @Autowired private MongoTemplate mongoTemplate;

  private final Deque<String> promptAnswers = new ArrayDeque<>();
  private final List<String> promptsShown = new ArrayList<>();
  private final List<Integer> exitCodes = new ArrayList<>();
  private ClubBootstrapRunner runner;

  @BeforeEach
  void setUp() {
    ClubBootstrapService service =
        new ClubBootstrapService(
            clubRepository,
            userRepository,
            passwordEncoder,
            validator,
            clubContext,
            transactionManager,
            new OwnerBootstrapProperties(OWNER_SECRET));
    runner =
        new ClubBootstrapRunner(
            service,
            label -> {
              promptsShown.add(label);
              return promptAnswers.poll();
            },
            exitCodes::add);
  }

  @AfterEach
  void tearDown() {
    clubContext.clear();
    // Remove documents rather than dropping collections, which would drop the unique email index.
    mongoTemplate.remove(new Query(), Club.class);
    mongoTemplate.remove(new Query(), User.class);
  }

  @Test
  void createsTheClubAndItsClubManagerTogetherAndExits() throws Exception {
    answerPrompts(OWNER_SECRET, PASSWORD, PASSWORD);

    runner.run(validArgs());

    assertThat(exitCodes).containsExactly(ClubBootstrapRunner.EXIT_SUCCESS);
    assertThat(promptsShown)
        .containsExactly(
            ClubBootstrapRunner.OWNER_SECRET_PROMPT,
            ClubBootstrapRunner.MANAGER_PASSWORD_PROMPT,
            ClubBootstrapRunner.MANAGER_PASSWORD_REPEAT_PROMPT);

    List<Club> clubs = mongoTemplate.findAll(Club.class);
    assertThat(clubs)
        .singleElement()
        .satisfies(
            club -> {
              assertThat(club.getName()).isEqualTo("Hapoel Example");
              assertThat(club.getCreatedAt()).isNotNull();
            });
    String clubId = clubs.getFirst().getId();

    assertThat(mongoTemplate.findAll(User.class))
        .singleElement()
        .satisfies(
            manager -> {
              assertThat(manager.getClubId()).isEqualTo(clubId);
              assertThat(manager.getEmail()).isEqualTo("manager@example.com");
              assertThat(manager.getTitle()).isEqualTo(Title.CLUB_MANAGER);
              assertThat(manager.getPermissionLevel()).isEqualTo(PermissionLevel.ADMIN);
              assertThat(manager.getFullName()).isEqualTo("Dana Levi");
              assertThat(manager.isActive()).isTrue();
              assertThat(manager.getPasswordHash()).doesNotContain(PASSWORD);
              assertThat(passwordEncoder.matches(PASSWORD, manager.getPasswordHash())).isTrue();
            });

    // The manager is a normal club-scoped user of the new club.
    clubContext.setClubId(clubId);
    assertThat(userRepository.findAll()).hasSize(1);
  }

  @Test
  void aWrongOwnerSecretCreatesNothingAndPromptsForNothingElse() throws Exception {
    answerPrompts("not-the-owner-secret-but-long-enough-anyway", PASSWORD, PASSWORD);

    runner.run(validArgs());

    assertThat(exitCodes).containsExactly(ClubBootstrapRunner.EXIT_FAILURE);
    assertThat(promptsShown).containsExactly(ClubBootstrapRunner.OWNER_SECRET_PROMPT);
    assertNothingWasCreated();
  }

  @Test
  void aMissingOwnerSecretCreatesNothing() throws Exception {
    // No answers: the prompt returns null, as Console.readPassword does at end of input.
    runner.run(validArgs());

    assertThat(exitCodes).containsExactly(ClubBootstrapRunner.EXIT_FAILURE);
    assertNothingWasCreated();
  }

  @Test
  void refusesSecretsPassedAsCommandLineArgumentsWithoutPrompting() throws Exception {
    answerPrompts(OWNER_SECRET, PASSWORD, PASSWORD);

    runner.run(validArgs("--owner-secret=" + OWNER_SECRET));
    runner.run(validArgs("--manager-password=" + PASSWORD));

    assertThat(exitCodes)
        .containsExactly(ClubBootstrapRunner.EXIT_FAILURE, ClubBootstrapRunner.EXIT_FAILURE);
    assertThat(promptsShown).isEmpty();
    assertNothingWasCreated();
  }

  @Test
  void mismatchedManagerPasswordsCreateNothing() throws Exception {
    answerPrompts(OWNER_SECRET, PASSWORD, "correct-horse-battry");

    runner.run(validArgs());

    assertThat(exitCodes).containsExactly(ClubBootstrapRunner.EXIT_FAILURE);
    assertNothingWasCreated();
  }

  @Test
  void invalidManagerDetailsCreateNothing() throws Exception {
    answerPrompts(OWNER_SECRET, PASSWORD, PASSWORD);

    runner.run(
        new DefaultApplicationArguments(
            "--club-name=Hapoel Example",
            "--manager-email=not-an-email",
            "--manager-full-name=Dana Levi"));

    assertThat(exitCodes).containsExactly(ClubBootstrapRunner.EXIT_FAILURE);
    assertNothingWasCreated();
  }

  /**
   * The Club insert succeeds and the User insert then fails on the global unique email index — the
   * transaction must roll the Club back, leaving no club without a manager.
   */
  @Test
  void rollsTheClubBackWhenCreatingTheClubManagerFails() throws Exception {
    User existing = new User();
    existing.setClubId("some-other-club");
    existing.setEmail("manager@example.com");
    existing.setTitle(Title.HEAD_COACH);
    existing.setPermissionLevel(PermissionLevel.EDIT_FULL);
    existing.setFullName("Someone Else");
    mongoTemplate.insert(existing);
    answerPrompts(OWNER_SECRET, PASSWORD, PASSWORD);

    runner.run(validArgs());

    assertThat(exitCodes).containsExactly(ClubBootstrapRunner.EXIT_FAILURE);
    assertThat(mongoTemplate.count(new Query(), Club.class)).isZero();
    assertThat(mongoTemplate.findAll(User.class))
        .extracting(User::getFullName)
        .containsExactly("Someone Else");
  }

  private void answerPrompts(String... answers) {
    promptAnswers.addAll(List.of(answers));
  }

  private void assertNothingWasCreated() {
    assertThat(mongoTemplate.count(new Query(), Club.class)).isZero();
    assertThat(mongoTemplate.count(new Query(), User.class)).isZero();
  }

  private static DefaultApplicationArguments validArgs(String... extra) {
    List<String> args =
        new ArrayList<>(
            List.of(
                "--club-name=Hapoel Example",
                "--manager-email= Manager@Example.com ",
                "--manager-full-name=Dana Levi",
                "--manager-date-of-birth=1985-03-01"));
    args.addAll(List.of(extra));
    return new DefaultApplicationArguments(args.toArray(String[]::new));
  }
}
