package com.squadpulse.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.AdditionalAnswers.delegatesTo;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.squadpulse.common.ClubContext;
import jakarta.validation.Validator;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Date;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.bson.Document;
import org.bson.json.JsonMode;
import org.bson.json.JsonWriterSettings;
import org.bson.types.ObjectId;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
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

  /** Canonical extended JSON: every BSON type spelled out, so equal strings mean equal values. */
  private static final JsonWriterSettings CANONICAL =
      JsonWriterSettings.builder().outputMode(JsonMode.EXTENDED).build();

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
  private final RunnerLogs logs = new RunnerLogs();
  private ClubBootstrapService service;
  private ClubBootstrapRunner runner;

  @BeforeEach
  void setUp() {
    service =
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
    logs.start();
  }

  @AfterEach
  void tearDown() {
    logs.stop();
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
   * The same name rule as {@code PATCH /clubs/me} (KAN-38), from {@code @Size} on {@link Club}: a
   * name the bootstrap accepts is never one the API would reject.
   */
  @Test
  void aClubNameOverTheLimitCreatesNothing() throws Exception {
    answerPrompts(OWNER_SECRET, PASSWORD, PASSWORD);

    runner.run(
        new DefaultApplicationArguments(
            "--club-name=" + "a".repeat(Club.NAME_MAX_LENGTH + 1),
            "--manager-email=manager@example.com",
            "--manager-full-name=Dana Levi"));

    assertThat(exitCodes).containsExactly(ClubBootstrapRunner.EXIT_FAILURE);
    assertThatThrownBy(
            () ->
                service.bootstrap(
                    OWNER_SECRET,
                    new ClubBootstrapService.NewClub(
                        "a".repeat(Club.NAME_MAX_LENGTH + 1),
                        "manager@example.com",
                        PASSWORD,
                        "Dana Levi",
                        null)))
        .isInstanceOf(ClubBootstrapException.class)
        .hasMessageContaining("club name size must be between 0 and 100");
    assertNothingWasCreated();
  }

  /** Trimmed before validation, so padding neither counts toward the limit nor is stored. */
  @Test
  void aPaddedClubNameOfExactlyTheLimitIsStoredTrimmed() throws Exception {
    String hundred = "א".repeat(Club.NAME_MAX_LENGTH);
    answerPrompts(OWNER_SECRET, PASSWORD, PASSWORD);

    runner.run(
        new DefaultApplicationArguments(
            "--club-name=  " + hundred + " \t",
            "--manager-email=manager@example.com",
            "--manager-full-name=Dana Levi"));

    assertThat(exitCodes).containsExactly(ClubBootstrapRunner.EXIT_SUCCESS);
    assertThat(mongoTemplate.findAll(Club.class))
        .singleElement()
        .extracting(Club::getName)
        .isEqualTo(hundred);
  }

  @Test
  void aWhitespaceOnlyClubNameCreatesNothing() throws Exception {
    answerPrompts(OWNER_SECRET, PASSWORD, PASSWORD);

    runner.run(
        new DefaultApplicationArguments(
            "--club-name=   ",
            "--manager-email=manager@example.com",
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

  // --- football.org.il mapping (KAN-65) ---------------------------------------------------------

  /**
   * Stored as one embedded document with exactly the three ids, as BSON int32, and no {@code
   * _class} of its own; read back into the record (whose final fields only its canonical
   * constructor can set).
   */
  @Test
  void createsTheClubWithItsFootballOrgIlMappingAsAnEmbeddedDocument() throws Exception {
    answerPrompts(OWNER_SECRET, PASSWORD, PASSWORD);

    runner.run(validArgs(sderotMapping()));

    assertThat(exitCodes).containsExactly(ClubBootstrapRunner.EXIT_SUCCESS);
    Document stored = mongoTemplate.getCollection("clubs").find().first();
    assertThat(stored.get("footballOrgIl"))
        .isEqualTo(new Document("teamId", 7617).append("leagueId", 62).append("seasonId", 28));
    assertThat(stored.get("footballOrgIl", Document.class).values())
        .allSatisfy(value -> assertThat(value).isInstanceOf(Integer.class));
    // The top-level _class is Spring Data's usual one for an inserted entity (every club has had
    // it); the embedded document gets none, as its type is the declared one.
    assertThat(stored.get("footballOrgIl", Document.class)).doesNotContainKey("_class");
    assertThat(mongoTemplate.findAll(Club.class))
        .singleElement()
        .extracting(Club::getFootballOrgIl)
        .isEqualTo(new FootballOrgIlMapping(7617, 62, 28));
    assertThat(mongoTemplate.count(new Query(), User.class)).isOne();
  }

  /** No mapping given: no field at all (nulls aren't written), read back as {@code null}. */
  @Test
  void aClubCreatedWithoutAMappingHasNoMappingField() throws Exception {
    answerPrompts(OWNER_SECRET, PASSWORD, PASSWORD);

    runner.run(validArgs());

    assertThat(exitCodes).containsExactly(ClubBootstrapRunner.EXIT_SUCCESS);
    assertThat(mongoTemplate.getCollection("clubs").find().first())
        .doesNotContainKey("footballOrgIl");
    assertThat(mongoTemplate.findAll(Club.class))
        .singleElement()
        .extracting(Club::getFootballOrgIl)
        .isNull();
  }

  /** Refused right after the owner secret, before the Club Manager password is asked for. */
  @Test
  void aPartialMappingOnCreateCreatesNothing() throws Exception {
    answerPrompts(OWNER_SECRET, OWNER_SECRET);

    runner.run(validArgs("--football-team-id=7617"));
    runner.run(validArgs("--football-team-id=7617", "--football-season-id=28"));

    assertThat(exitCodes)
        .containsExactly(ClubBootstrapRunner.EXIT_FAILURE, ClubBootstrapRunner.EXIT_FAILURE);
    assertThat(promptsShown)
        .containsExactly(
            ClubBootstrapRunner.OWNER_SECRET_PROMPT, ClubBootstrapRunner.OWNER_SECRET_PROMPT);
    assertThat(logs.errors())
        .allSatisfy(message -> assertThat(message).contains("needs all three", "or none of them"));
    assertNothingWasCreated();
  }

  /** Validated in full right after the owner secret: the password is never asked for. */
  @Test
  void anInvalidMappingOnCreateCreatesNothing() throws Exception {
    answerPrompts(OWNER_SECRET, PASSWORD, PASSWORD);

    runner.run(
        validArgs("--football-team-id=0", "--football-league-id=62", "--football-season-id=28"));

    assertThat(exitCodes).containsExactly(ClubBootstrapRunner.EXIT_FAILURE);
    assertThat(promptsShown).containsExactly(ClubBootstrapRunner.OWNER_SECRET_PROMPT);
    assertThat(logs.errors())
        .singleElement()
        .asString()
        .contains("club footballOrgIl.teamId must be greater than 0");
    assertNothingWasCreated();
  }

  /** A create command with {@code --club-id} is an update with create-only options: refused. */
  @Test
  void aCreateCommandWithAClubIdIsRefused() throws Exception {
    answerPrompts(OWNER_SECRET, PASSWORD, PASSWORD);

    runner.run(validArgs("--club-id=" + new ObjectId().toHexString()));

    assertThat(exitCodes).containsExactly(ClubBootstrapRunner.EXIT_FAILURE);
    assertThat(promptsShown).containsExactly(ClubBootstrapRunner.OWNER_SECRET_PROMPT);
    assertThat(logs.errors())
        .singleElement()
        .asString()
        .contains("--club-name can't be combined with --club-id");
    assertNothingWasCreated();
  }

  /**
   * Sets, then replaces, all three ids with a {@code $set} of {@code footballOrgIl} alone: every
   * other field (including one the application doesn't know) comes through unchanged, value for
   * value and in place, and nothing else is added. No password is prompted for. Each run logs the
   * previous and the new mapping.
   */
  @Test
  void updatesTheMappingOfAnExistingClubAndChangesNothingElse() throws Exception {
    ObjectId id = new ObjectId();
    Document original =
        new Document("_id", id)
            .append("name", "Sderot FC")
            .append("createdAt", Date.from(Instant.parse("2025-01-02T03:04:05.678Z")))
            .append("futureSetting", "keep")
            .append("nested", new Document("a", 1).append("b", List.of(1L, 2.5, true)));
    mongoTemplate.getCollection("clubs").insertOne(original);
    answerPrompts(OWNER_SECRET, OWNER_SECRET);

    runner.run(updateArgs(id.toHexString(), "7617", "62", "28"));
    Document afterFirst = rawClub(id);
    runner.run(updateArgs(id.toHexString(), "7617", "63", "29"));

    assertThat(exitCodes)
        .containsExactly(ClubBootstrapRunner.EXIT_SUCCESS, ClubBootstrapRunner.EXIT_SUCCESS);
    assertThat(promptsShown)
        .containsExactly(
            ClubBootstrapRunner.OWNER_SECRET_PROMPT, ClubBootstrapRunner.OWNER_SECRET_PROMPT);
    assertThat(afterFirst.toJson(CANONICAL))
        .isEqualTo(withMapping(original, 7617, 62, 28).toJson(CANONICAL));
    assertThat(rawClub(id).toJson(CANONICAL))
        .isEqualTo(withMapping(original, 7617, 63, 29).toJson(CANONICAL));
    assertThat(mongoTemplate.findById(id, Club.class).getFootballOrgIl())
        .isEqualTo(new FootballOrgIlMapping(7617, 63, 29));
    assertThat(mongoTemplate.count(new Query(), User.class)).isZero();

    assertThat(logs.infos())
        .containsExactly(
            "Updated the football.org.il mapping of club 'Sderot FC' (id "
                + id.toHexString()
                + "): none -> teamId=7617, leagueId=62, seasonId=28",
            "Updated the football.org.il mapping of club 'Sderot FC' (id "
                + id.toHexString()
                + "): teamId=7617, leagueId=62, seasonId=28 -> teamId=7617, leagueId=63,"
                + " seasonId=29");
    assertThat(logs.everything()).doesNotContain(OWNER_SECRET);
  }

  /**
   * A wrong secret is refused before the club id is read: no repository call at all, so an unknown
   * and an existing id get the same answer.
   */
  @Test
  void aWrongOwnerSecretInUpdateModeNeverLooksTheClubUp() throws Exception {
    String clubId = insertClub("Sderot FC");
    Document before = rawClub(new ObjectId(clubId));
    ClubRepository watched = mock(ClubRepository.class, delegatesTo(clubRepository));
    ClubBootstrapRunner watchedRunner =
        new ClubBootstrapRunner(
            new ClubBootstrapService(
                watched,
                userRepository,
                passwordEncoder,
                validator,
                clubContext,
                transactionManager,
                new OwnerBootstrapProperties(OWNER_SECRET)),
            label -> {
              promptsShown.add(label);
              return promptAnswers.poll();
            },
            exitCodes::add);
    answerPrompts("not-the-owner-secret-but-long-enough-anyway", "also-wrong-and-long-enough");

    watchedRunner.run(updateArgs(clubId, "7617", "62", "28"));
    watchedRunner.run(updateArgs(new ObjectId().toHexString(), "7617", "62", "28"));

    assertThat(exitCodes)
        .containsExactly(ClubBootstrapRunner.EXIT_FAILURE, ClubBootstrapRunner.EXIT_FAILURE);
    assertThat(logs.errors())
        .containsExactly(
            "Club mapping update refused, nothing was written: missing or wrong owner secret",
            "Club mapping update refused, nothing was written: missing or wrong owner secret");
    verifyNoInteractions(watched);
    assertThat(rawClub(new ObjectId(clubId))).isEqualTo(before);
  }

  /** Each refused with exit code 1, its own message, no password prompt and nothing written. */
  @Test
  void refusesAnInvalidMappingUpdate() throws Exception {
    String clubId = insertClub("Sderot FC");
    Document before = rawClub(new ObjectId(clubId));
    String unknown = new ObjectId().toHexString();
    Map<List<String>, String> cases = new LinkedHashMap<>();
    cases.put(updateArgList(unknown, "7617", "62", "28"), "no club with id " + unknown);
    cases.put(updateArgList("not-a-club-id", "7617", "62", "28"), "24 hexadecimal characters");
    cases.put(
        updateArgList(clubId.toUpperCase(Locale.ROOT) + "0", "7617", "62", "28"),
        "24 hexadecimal characters");
    cases.put(updateArgList("", "7617", "62", "28"), "--club-id needs a value");
    cases.put(
        List.of(
            "--club-id",
            "--football-team-id=1",
            "--football-league-id=1",
            "--football-season-id=1"),
        "--club-id needs a value");
    cases.put(
        List.of("--club-id=" + clubId, "--football-team-id=7617", "--football-league-id=62"),
        "needs all three of --football-team-id, --football-league-id, --football-season-id");
    cases.put(List.of("--club-id=" + clubId), "needs all three");
    cases.put(updateArgList(clubId, "7617", "", "28"), "--football-league-id needs a value");
    cases.put(updateArgList(clubId, "abc", "62", "28"), "--football-team-id must be a whole");
    cases.put(updateArgList(clubId, "7617", "62", "2.5"), "--football-season-id must be a whole");
    cases.put(
        updateArgList(clubId, "7617", "62", "99999999999"), "--football-season-id must be a whole");
    cases.put(
        updateArgList(clubId, "0", "62", "28"), "club footballOrgIl.teamId must be greater than 0");
    cases.put(
        updateArgList(clubId, "7617", "-62", "0"),
        "club footballOrgIl.leagueId must be greater than 0");
    cases.put(
        withExtra(updateArgList(clubId, "7617", "62", "28"), "--football-team-id=7618"),
        "--football-team-id was given more than once");
    cases.put(
        withExtra(updateArgList(clubId, "7617", "62", "28"), "--club-id=" + clubId),
        "--club-id was given more than once");
    cases.put(
        withExtra(updateArgList(clubId, "7617", "62", "28"), "--club-name=Sderot FC"),
        "--club-name can't be combined with --club-id");
    cases.put(
        withExtra(updateArgList(clubId, "7617", "62", "28"), "--manager-email=a@example.com"),
        "--manager-email can't be combined with --club-id");
    cases.put(
        withExtra(updateArgList(clubId, "7617", "62", "28"), "--manager-date-of-birth"),
        "--manager-date-of-birth can't be combined with --club-id");

    cases.forEach(
        (args, expected) -> {
          logs.clear();
          exitCodes.clear();
          promptsShown.clear();
          answerPrompts(OWNER_SECRET);

          runner.run(new DefaultApplicationArguments(args.toArray(String[]::new)));

          assertThat(exitCodes).as("%s", args).containsExactly(ClubBootstrapRunner.EXIT_FAILURE);
          assertThat(promptsShown)
              .as("%s", args)
              .containsExactly(ClubBootstrapRunner.OWNER_SECRET_PROMPT);
          assertThat(logs.errors())
              .as("%s", args)
              .singleElement()
              .asString()
              .startsWith("Club mapping update refused, nothing was written: ")
              .contains(expected);
          assertThat(rawClub(new ObjectId(clubId))).as("%s", args).isEqualTo(before);
        });
    assertThat(mongoTemplate.count(new Query(), Club.class)).isOne();
    assertThat(mongoTemplate.count(new Query(), User.class)).isZero();
  }

  /**
   * What the runner relies on from {@link DefaultApplicationArguments}: {@code --name} alone is an
   * option without values, {@code --name=} one with an empty value, and a repeated option collects
   * every value.
   */
  @Test
  void applicationArgumentsParseOptionsAsTheRunnerExpects() {
    DefaultApplicationArguments args =
        new DefaultApplicationArguments("--club-id", "--football-team-id=", "--x=1", "--x=2");

    assertThat(args.containsOption("club-id")).isTrue();
    assertThat(args.getOptionValues("club-id")).isEmpty();
    assertThat(args.getOptionValues("football-team-id")).containsExactly("");
    assertThat(args.getOptionValues("x")).containsExactly("1", "2");
    assertThat(args.getOptionValues("absent")).isNull();
  }

  private void answerPrompts(String... answers) {
    promptAnswers.addAll(List.of(answers));
  }

  private String insertClub(String name) {
    Club club = new Club();
    club.setName(name);
    return mongoTemplate.insert(club).getId();
  }

  private Document rawClub(ObjectId id) {
    return mongoTemplate.getCollection("clubs").find(new Document("_id", id)).first();
  }

  /**
   * A copy of {@code original} with the mapping appended, as a {@code $set} of a new field does.
   */
  private static Document withMapping(Document original, int teamId, int leagueId, int seasonId) {
    return Document.parse(original.toJson(CANONICAL))
        .append(
            "footballOrgIl",
            new Document("teamId", teamId)
                .append("leagueId", leagueId)
                .append("seasonId", seasonId));
  }

  private static String[] sderotMapping() {
    return new String[] {
      "--football-team-id=7617", "--football-league-id=62", "--football-season-id=28"
    };
  }

  private static List<String> updateArgList(
      String clubId, String teamId, String leagueId, String seasonId) {
    return List.of(
        "--club-id=" + clubId,
        "--football-team-id=" + teamId,
        "--football-league-id=" + leagueId,
        "--football-season-id=" + seasonId);
  }

  private static DefaultApplicationArguments updateArgs(
      String clubId, String teamId, String leagueId, String seasonId) {
    return new DefaultApplicationArguments(
        updateArgList(clubId, teamId, leagueId, seasonId).toArray(String[]::new));
  }

  private static List<String> withExtra(List<String> args, String extra) {
    List<String> all = new ArrayList<>(args);
    all.add(extra);
    return all;
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

  /** What {@link ClubBootstrapRunner} itself logs, as formatted messages. */
  private static final class RunnerLogs {

    private final Logger logger = (Logger) LoggerFactory.getLogger(ClubBootstrapRunner.class);
    private final ListAppender<ILoggingEvent> appender = new ListAppender<>();

    void start() {
      appender.start();
      logger.addAppender(appender);
    }

    void stop() {
      logger.detachAppender(appender);
      appender.stop();
    }

    void clear() {
      appender.list.clear();
    }

    List<String> errors() {
      return messages(Level.ERROR);
    }

    List<String> infos() {
      return messages(Level.INFO);
    }

    String everything() {
      return String.join(
          "\n", appender.list.stream().map(ILoggingEvent::getFormattedMessage).toList());
    }

    private List<String> messages(Level level) {
      return appender.list.stream()
          .filter(event -> event.getLevel() == level)
          .map(ILoggingEvent::getFormattedMessage)
          .toList();
    }
  }
}
