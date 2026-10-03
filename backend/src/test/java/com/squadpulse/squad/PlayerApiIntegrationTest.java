package com.squadpulse.squad;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.mongodb.ErrorCategory;
import com.mongodb.MongoWriteException;
import com.squadpulse.auth.PermissionLevel;
import com.squadpulse.auth.TestAccessTokens;
import com.squadpulse.common.ClubContext;
import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.bson.Document;
import org.bson.types.ObjectId;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.ResultActions;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.mongodb.MongoDBContainer;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * The squad API end to end on a real MongoDB (see docs/spec.md section 11): club isolation over
 * HTTP, the jersey-number index and how its violations are told apart from other duplicate keys,
 * version checks and write races, released players, release / re-activation / permanent deletion,
 * full replacement, and the list filters.
 *
 * <p>Races are deterministic: {@link PlayerLoadHook} performs the "concurrent" write right after
 * the request under test has loaded the player, and {@link PlayerInsertBarrier} releases two writes
 * together at the last moment before they reach the database.
 */
@SpringBootTest(
    properties = {
      "squadpulse.security.jwt-secret=test-only-jwt-secret-not-a-real-secret",
      "squadpulse.security.password-pepper=test-only-pepper-not-a-real-secret"
    })
@AutoConfigureMockMvc
@Testcontainers
@Import({TestAccessTokens.class, PlayerLoadHook.class, PlayerInsertBarrier.class})
class PlayerApiIntegrationTest {

  private static final String CLUB_A = "club-a";
  private static final String CLUB_B = "club-b";
  private static final String STALE_MESSAGE =
      "This player was changed by someone else since you loaded it; reload it and apply your"
          + " changes again";
  private static final String SEVEN_TAKEN =
      "Jersey number 7 is already taken by another active player";
  private static final JsonMapper JSON = JsonMapper.builder().build();

  @Container
  static final MongoDBContainer MONGO_DB_CONTAINER =
      new MongoDBContainer("mongo:7").withReplicaSet();

  @DynamicPropertySource
  static void mongoProperties(DynamicPropertyRegistry registry) {
    registry.add("spring.mongodb.uri", MONGO_DB_CONTAINER::getReplicaSetUrl);
  }

  @Autowired private MockMvc mockMvc;
  @Autowired private MongoTemplate mongoTemplate;
  @Autowired private PlayerRepository playerRepository;
  @Autowired private ClubContext clubContext;
  @Autowired private TestAccessTokens tokens;
  @Autowired private PlayerLoadHook loadHook;
  @Autowired private PlayerInsertBarrier insertBarrier;

  @AfterEach
  void tearDown() {
    loadHook.disarm();
    insertBarrier.disarm();
    clubContext.clear();
    // Remove documents rather than dropping the collection, which would drop the jersey index too.
    mongoTemplate.remove(new Query(), Player.class);
  }

  // --- club isolation ----------------------------------------------------------------------------

  @Test
  void anotherClubCannotSeeAPlayerInAnyListOrById() throws Exception {
    String active = createdId(create(CLUB_A, fields("Active A", Position.ST, 9)));
    Player released = newPlayer("Released A", Position.CB, 4);
    released.setActive(false);
    saveAs(CLUB_A, released);

    for (String status : List.of("active", "released", "all")) {
      list(CLUB_B, "status=" + status)
          .andExpect(status().isOk())
          .andExpect(jsonPath("$").isEmpty());
    }
    for (String id : List.of(active, released.getId())) {
      mockMvc
          .perform(get("/squad/players/" + id).header("Authorization", bearer(CLUB_B)))
          .andExpect(status().isNotFound())
          .andExpect(jsonPath("$.message").value("Player not found"));
    }
  }

  /** A 404 — not the 403 of the club check inside save — and the document is untouched. */
  @Test
  void anotherClubCannotUpdateAPlayer() throws Exception {
    String id = createdId(create(CLUB_A, fields("Eran Zahavi", Position.ST, 7)));
    Document before = rawPlayer(id);
    Map<String, Object> body = fields("Hijacked", Position.GK, 1);
    body.put("medicalStatus", "INJURED");
    body.put("version", 0);

    update(CLUB_B, id, body)
        .andExpect(status().isNotFound())
        .andExpect(jsonPath("$.message").value("Player not found"));

    assertThat(rawPlayer(id)).isEqualTo(before);
  }

  @Test
  void aClubIdOrActiveFlagInTheBodyIsIgnoredOnCreateAndUpdate() throws Exception {
    Map<String, Object> body = fields("Eran Zahavi", Position.ST, 7);
    body.put("clubId", CLUB_B);
    body.put("active", false);
    body.put("id", new ObjectId().toHexString());
    String id = createdId(create(CLUB_A, body).andExpect(jsonPath("$.active").value(true)));

    assertThat(rawPlayer(id).get("clubId")).isEqualTo(CLUB_A);
    assertThat(rawPlayer(id).get("active")).isEqualTo(true);

    body.put("medicalStatus", "FIT");
    body.put("version", 0);
    update(CLUB_A, id, body).andExpect(status().isOk()).andExpect(jsonPath("$.active").value(true));

    assertThat(rawPlayer(id).get("clubId")).isEqualTo(CLUB_A);
    assertThat(rawPlayer(id).get("active")).isEqualTo(true);
    assertThat(mongoTemplate.count(new Query(), Player.class)).isEqualTo(1);
  }

  // --- jersey numbers ----------------------------------------------------------------------------

  @Test
  void aJerseyClashOnCreateIs409AndNothingIsWritten() throws Exception {
    create(CLUB_A, fields("First Seven", Position.ST, 7)).andExpect(status().isCreated());

    create(CLUB_A, fields("Second Seven", Position.CM, 7))
        .andExpect(status().isConflict())
        .andExpect(
            jsonPath("$.message")
                .value("Jersey number 7 is already taken by another active player"));

    assertThat(mongoTemplate.count(new Query(), Player.class)).isEqualTo(1);
  }

  @Test
  void aJerseyClashOnUpdateIs409AndNothingIsWritten() throws Exception {
    create(CLUB_A, fields("Seven", Position.ST, 7));
    String nine = createdId(create(CLUB_A, fields("Nine", Position.ST, 9)));
    Document before = rawPlayer(nine);
    Map<String, Object> body = fields("Nine", Position.ST, 7);
    body.put("medicalStatus", "FIT");
    body.put("version", 0);

    update(CLUB_A, nine, body)
        .andExpect(status().isConflict())
        .andExpect(
            jsonPath("$.message")
                .value("Jersey number 7 is already taken by another active player"));

    assertThat(rawPlayer(nine)).isEqualTo(before);
  }

  @Test
  void aReleasedPlayersNumberIsFreeForANewPlayer() throws Exception {
    Player released = newPlayer("Old Seven", Position.ST, 7);
    released.setActive(false);
    saveAs(CLUB_A, released);

    create(CLUB_A, fields("New Seven", Position.ST, 7)).andExpect(status().isCreated());
  }

  @Test
  void playersWithoutANumberDoNotCollide() throws Exception {
    create(CLUB_A, fields("No Number One", Position.ST, null)).andExpect(status().isCreated());
    create(CLUB_A, fields("No Number Two", Position.ST, null)).andExpect(status().isCreated());
  }

  /**
   * Two creates with the same number, both held just before the insert until both are there — so
   * neither could have seen the other — then released together: exactly one wins.
   */
  @Test
  void twoConcurrentCreatesWithTheSameNumberExactlyOneWins() throws Exception {
    insertBarrier.arm(2);
    ExecutorService executor = Executors.newFixedThreadPool(2);
    try {
      List<Future<Integer>> results = new ArrayList<>();
      for (String name : List.of("Racer One", "Racer Two")) {
        results.add(
            executor.submit(
                () ->
                    create(CLUB_A, fields(name, Position.ST, 10))
                        .andReturn()
                        .getResponse()
                        .getStatus()));
      }
      List<Integer> statuses = new ArrayList<>();
      for (Future<Integer> result : results) {
        statuses.add(result.get());
      }

      assertThat(statuses).containsExactlyInAnyOrder(201, 409);
      assertThat(mongoTemplate.count(new Query(), Player.class)).isEqualTo(1);
    } finally {
      executor.shutdownNow();
    }
  }

  /**
   * Verify 1: a clash surfaces as {@link DuplicateKeyException} on both writes — an insert, and a
   * versioned save of an existing player (a {@code replaceOne} filtered on {@code _id} + {@code
   * version}) — never as {@link OptimisticLockingFailureException}. Its cause is the driver's
   * {@link MongoWriteException}, whose write error carries the duplicate-key category and names the
   * index.
   */
  @Test
  void aJerseyClashIsADuplicateKeyNamingTheIndexOnInsertAndOnVersionedSave() {
    saveAs(CLUB_A, newPlayer("Seven", Position.ST, 7));

    DuplicateKeyException onInsert =
        catchDuplicateKey(() -> playerRepository.insert(newPlayer("Other Seven", Position.ST, 7)));
    Player nine = playerRepository.save(newPlayer("Nine", Position.ST, 9));
    Player loaded = playerRepository.findById(nine.getId()).orElseThrow();
    loaded.setJerseyNumber(7);
    DuplicateKeyException onSave = catchDuplicateKey(() -> playerRepository.save(loaded));

    for (DuplicateKeyException e : List.of(onInsert, onSave)) {
      assertThat(e).isNotInstanceOf(OptimisticLockingFailureException.class);
      assertThat(e.getCause()).isInstanceOf(MongoWriteException.class);
      MongoWriteException cause = (MongoWriteException) e.getCause();
      assertThat(cause.getError().getCategory()).isEqualTo(ErrorCategory.DUPLICATE_KEY);
      assertThat(cause.getError().getMessage())
          .contains("index: " + Player.JERSEY_NUMBER_INDEX + " dup key");
      assertThat(PlayerService.violatedIndex(e)).contains(Player.JERSEY_NUMBER_INDEX);
    }
  }

  /**
   * A duplicate key on another index — here {@code _id}, by inserting a second document with an
   * existing id through the repository — is not taken for a jersey clash, and the service rethrows
   * it unchanged (so it stays a 500, not a misleading 409).
   */
  @Test
  void aDuplicateKeyOnAnotherIndexIsNotAJerseyClash() {
    Player seven = saveAs(CLUB_A, newPlayer("Seven", Position.ST, 7));
    Player sameId = newPlayer("Same Id", Position.CB, 8);
    sameId.setId(seven.getId());

    DuplicateKeyException idClash = catchDuplicateKey(() -> playerRepository.insert(sameId));

    assertThat(PlayerService.violatedIndex(idClash)).contains("_id_");
    PlayerRepository failingRepository = mock(PlayerRepository.class);
    when(failingRepository.insert(any(Player.class))).thenThrow(idClash);
    PlayerService service = new PlayerService(failingRepository, clubContext);
    assertThatThrownBy(() -> service.create(createRequest("Same Id", Position.CB, 8)))
        .isSameAs(idClash);
  }

  // --- versions ----------------------------------------------------------------------------------

  @Test
  void anUpdateWithTheCurrentVersionSucceedsAndIncrementsIt() throws Exception {
    String id = createdId(create(CLUB_A, fields("Eran Zahavi", Position.ST, 7)));
    Map<String, Object> body = fields("Eran Zahavi", Position.AM, 10);
    body.put("medicalStatus", "INJURED");
    body.put("version", 0);

    update(CLUB_A, id, body)
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.version").value(1))
        .andExpect(jsonPath("$.primaryPosition").value("AM"))
        .andExpect(jsonPath("$.jerseyNumber").value(10))
        .andExpect(jsonPath("$.medicalStatus").value("INJURED"));

    assertThat(rawPlayer(id).get("version")).isEqualTo(1L);
  }

  @Test
  void aStaleVersionIs409AndNothingIsWritten() throws Exception {
    String id = createdId(create(CLUB_A, fields("Eran Zahavi", Position.ST, 7)));
    Map<String, Object> coachB = fields("Eran Zahavi", Position.ST, 7);
    coachB.put("medicalStatus", "INJURED");
    coachB.put("version", 0);
    update(CLUB_A, id, coachB).andExpect(status().isOk());
    Document afterCoachB = rawPlayer(id);

    Map<String, Object> coachA = fields("Stale Edit", Position.GK, 1);
    coachA.put("medicalStatus", "FIT");
    coachA.put("version", 0);
    update(CLUB_A, id, coachA)
        .andExpect(status().isConflict())
        .andExpect(
            jsonPath("$.message")
                .value(
                    "This player was changed by someone else since you loaded it; reload it and"
                        + " apply your changes again"));

    assertThat(rawPlayer(id)).isEqualTo(afterCoachB);
  }

  /**
   * Someone else saves between this update's version check and its save: the save's own version
   * condition catches it — the same "reload" 409 as a stale version, no retry, and the other write
   * is kept.
   */
  @Test
  void anUpdateThatLosesASaveRaceIs409AndKeepsTheOtherWrite() throws Exception {
    String id = createdId(create(CLUB_A, fields("Eran Zahavi", Position.ST, 7)));
    loadHook.onNextLoad(
        () ->
            mongoTemplate.updateFirst(
                Query.query(Criteria.where("_id").is(id)),
                new Update().set("fullName", "Written Meanwhile").inc("version", 1),
                Player.class));
    Map<String, Object> body = fields("Lost Edit", Position.GK, 1);
    body.put("medicalStatus", "FIT");
    body.put("version", 0);

    update(CLUB_A, id, body)
        .andExpect(status().isConflict())
        .andExpect(
            jsonPath("$.message")
                .value(
                    "This player was changed by someone else since you loaded it; reload it and"
                        + " apply your changes again"));

    Document stored = rawPlayer(id);
    assertThat(stored.get("fullName")).isEqualTo("Written Meanwhile");
    assertThat(stored.get("version")).isEqualTo(1L);
  }

  // --- released players --------------------------------------------------------------------------

  @Test
  void aReleasedPlayerCanBeReadButNotEdited() throws Exception {
    Player released = newPlayer("Released", Position.CB, 4);
    released.setActive(false);
    String id = saveAs(CLUB_A, released).getId();
    clubContext.clear();
    Document before = rawPlayer(id);

    mockMvc
        .perform(get("/squad/players/" + id).header("Authorization", bearer(CLUB_A)))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.active").value(false));

    Map<String, Object> body = fields("Released", Position.CB, 4);
    body.put("medicalStatus", "FIT");
    body.put("version", 0);
    update(CLUB_A, id, body)
        .andExpect(status().isConflict())
        .andExpect(
            jsonPath("$.message")
                .value("This player has been released; re-activate them before editing"));

    assertThat(rawPlayer(id)).isEqualTo(before);
  }

  // --- release / re-activate: isolation ---------------------------------------------------------

  /**
   * 404 — never the 403 of the club check inside save — and the other club's document is intact.
   */
  @Test
  void anotherClubCannotReleaseReactivateOrDeleteAPlayer() throws Exception {
    String active = createdId(create(CLUB_A, fields("Active A", Position.ST, 9)));
    Player released = newPlayer("Released A", Position.CB, 4);
    released.setActive(false);
    String releasedId = saveAs(CLUB_A, released).getId();
    clubContext.clear();
    Document activeBefore = rawPlayer(active);
    Document releasedBefore = rawPlayer(releasedId);

    release(CLUB_B, active, 0)
        .andExpect(status().isNotFound())
        .andExpect(jsonPath("$.message").value("Player not found"));
    reactivate(CLUB_B, releasedId, 0, null)
        .andExpect(status().isNotFound())
        .andExpect(jsonPath("$.message").value("Player not found"));
    for (String id : List.of(active, releasedId)) {
      remove(CLUB_B, id)
          .andExpect(status().isNotFound())
          .andExpect(jsonPath("$.message").value("Player not found"));
    }

    assertThat(rawPlayer(active)).isEqualTo(activeBefore);
    assertThat(rawPlayer(releasedId)).isEqualTo(releasedBefore);
  }

  @Test
  void anUnknownIdIs404ForEveryLifecycleAction() throws Exception {
    String unknown = new ObjectId().toHexString();

    release(CLUB_A, unknown, 0).andExpect(status().isNotFound());
    reactivate(CLUB_A, unknown, 0, null).andExpect(status().isNotFound());
    remove(CLUB_A, unknown).andExpect(status().isNotFound());
  }

  // --- release / re-activate: state transitions --------------------------------------------------

  /**
   * Active → released → active again, each step incrementing the version. The number stays on the
   * released record, and the lists follow the {@code active} flag.
   */
  @Test
  void aPlayerCanBeReleasedAndReactivated() throws Exception {
    String id = createdId(create(CLUB_A, fields("Eran Zahavi", Position.ST, 7)));

    release(CLUB_A, id, 0)
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.active").value(false))
        .andExpect(jsonPath("$.jerseyNumber").value(7))
        .andExpect(jsonPath("$.version").value(1));

    Document stored = rawPlayer(id);
    assertThat(stored.get("active")).isEqualTo(false);
    assertThat(stored.get("jerseyNumber")).isEqualTo(7);
    assertThat(stored.get("version")).isEqualTo(1L);
    assertThat(names(list(CLUB_A, "status=active"))).isEmpty();
    assertThat(names(list(CLUB_A, "status=released"))).containsExactly("Eran Zahavi");
    assertThat(names(list(CLUB_A, "status=all"))).containsExactly("Eran Zahavi");

    reactivate(CLUB_A, id, 1, 7)
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.active").value(true))
        .andExpect(jsonPath("$.jerseyNumber").value(7))
        .andExpect(jsonPath("$.version").value(2));

    stored = rawPlayer(id);
    assertThat(stored.get("active")).isEqualTo(true);
    assertThat(stored.get("version")).isEqualTo(2L);
    assertThat(names(list(CLUB_A, "status=active"))).containsExactly("Eran Zahavi");
    assertThat(names(list(CLUB_A, "status=released"))).isEmpty();
  }

  @Test
  void releasingAReleasedPlayerIs409AndNothingIsWritten() throws Exception {
    String id = createdId(create(CLUB_A, fields("Eran Zahavi", Position.ST, 7)));
    release(CLUB_A, id, 0).andExpect(status().isOk());
    Document before = rawPlayer(id);

    // The current version, so the state check — not the version check — is what refuses it.
    release(CLUB_A, id, 1)
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.message").value("This player has already been released"));

    assertThat(rawPlayer(id)).isEqualTo(before);
  }

  @Test
  void reactivatingAnActivePlayerIs409AndNothingIsWritten() throws Exception {
    String id = createdId(create(CLUB_A, fields("Eran Zahavi", Position.ST, 7)));
    Document before = rawPlayer(id);

    reactivate(CLUB_A, id, 0, 8)
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.message").value("This player is already active"));

    assertThat(rawPlayer(id)).isEqualTo(before);
  }

  /** Regression (KAN-26): released through the API, the player still can't be edited. */
  @Test
  void aPlayerReleasedThroughTheApiCannotBeEdited() throws Exception {
    String id = createdId(create(CLUB_A, fields("Eran Zahavi", Position.ST, 7)));
    release(CLUB_A, id, 0).andExpect(status().isOk());
    Document before = rawPlayer(id);
    Map<String, Object> body = fields("Eran Zahavi", Position.ST, 8);
    body.put("medicalStatus", "FIT");
    body.put("version", 1);

    update(CLUB_A, id, body)
        .andExpect(status().isConflict())
        .andExpect(
            jsonPath("$.message")
                .value("This player has been released; re-activate them before editing"));

    assertThat(rawPlayer(id)).isEqualTo(before);
  }

  // --- release / re-activate: jersey numbers -----------------------------------------------------

  @Test
  void afterAReleaseTheNumberCanBeTakenByACreateOrAnUpdate() throws Exception {
    String first = createdId(create(CLUB_A, fields("First Seven", Position.ST, 7)));
    release(CLUB_A, first, 0).andExpect(status().isOk());

    String second = createdId(create(CLUB_A, fields("Second Seven", Position.ST, 7)));
    release(CLUB_A, second, 0).andExpect(status().isOk());

    String nine = createdId(create(CLUB_A, fields("Nine", Position.ST, 9)));
    Map<String, Object> body = fields("Nine", Position.ST, 7);
    body.put("medicalStatus", "FIT");
    body.put("version", 0);
    update(CLUB_A, nine, body)
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.jerseyNumber").value(7));
  }

  /** No silent fallback to "no number": a 409, and the player stays released, untouched. */
  @Test
  void reactivatingWithATakenNumberIs409AndThePlayerStaysReleased() throws Exception {
    String id = createdId(create(CLUB_A, fields("Old Seven", Position.ST, 7)));
    release(CLUB_A, id, 0).andExpect(status().isOk());
    create(CLUB_A, fields("New Seven", Position.ST, 7)).andExpect(status().isCreated());
    Document before = rawPlayer(id);

    reactivate(CLUB_A, id, 1, 7)
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.message").value(SEVEN_TAKEN));

    assertThat(rawPlayer(id)).isEqualTo(before);
    assertThat(rawPlayer(id).get("active")).isEqualTo(false);
  }

  @Test
  void reactivatingWithAnotherFreeNumberOrNoneSetsThatValue() throws Exception {
    String withNumber = createdId(create(CLUB_A, fields("Old Seven", Position.ST, 7)));
    String withoutNumber = createdId(create(CLUB_A, fields("Old Eight", Position.CM, 8)));
    release(CLUB_A, withNumber, 0).andExpect(status().isOk());
    release(CLUB_A, withoutNumber, 0).andExpect(status().isOk());
    create(CLUB_A, fields("New Seven", Position.ST, 7)).andExpect(status().isCreated());

    reactivate(CLUB_A, withNumber, 1, 17)
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.jerseyNumber").value(17));
    reactivate(CLUB_A, withoutNumber, 1, null)
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.jerseyNumber").isEmpty());

    assertThat(rawPlayer(withNumber).get("jerseyNumber")).isEqualTo(17);
    assertThat(rawPlayer(withNumber).get("active")).isEqualTo(true);
    assertThat(rawPlayer(withoutNumber)).doesNotContainKey("jerseyNumber");
    assertThat(rawPlayer(withoutNumber).get("active")).isEqualTo(true);
  }

  /**
   * Coming back without a number never clashes — not with a player who took the old number, nor
   * with other players without one. An absent field means "no number" too.
   */
  @Test
  void reactivatingWithoutANumberNeverConflicts() throws Exception {
    String id = createdId(create(CLUB_A, fields("Old Seven", Position.ST, 7)));
    release(CLUB_A, id, 0).andExpect(status().isOk());
    create(CLUB_A, fields("New Seven", Position.ST, 7)).andExpect(status().isCreated());
    create(CLUB_A, fields("No Number", Position.CB, null)).andExpect(status().isCreated());

    mockMvc
        .perform(
            post("/squad/players/" + id + "/reactivate")
                .header("Authorization", bearer(CLUB_A))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"version\": 1}"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.active").value(true))
        .andExpect(jsonPath("$.jerseyNumber").isEmpty());
  }

  /**
   * The number is taken right after the re-activation has loaded the player — after any check a
   * service could have made — so only the index can catch it: a jersey 409, not a 500, and the
   * player stays released.
   */
  @Test
  void aNumberTakenBetweenLoadAndSaveIs409AndThePlayerStaysReleased() throws Exception {
    String id = createdId(create(CLUB_A, fields("Old Seven", Position.ST, 7)));
    release(CLUB_A, id, 0).andExpect(status().isOk());
    Document before = rawPlayer(id);
    Player newSeven = newPlayer("New Seven", Position.ST, 7);
    newSeven.setClubId(CLUB_A);
    loadHook.onNextLoad(() -> mongoTemplate.insert(newSeven));

    reactivate(CLUB_A, id, 1, 7)
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.message").value(SEVEN_TAKEN));

    assertThat(rawPlayer(id)).isEqualTo(before);
    assertThat(mongoTemplate.count(Query.query(Criteria.where("active").is(true)), Player.class))
        .isEqualTo(1);
  }

  /**
   * A re-activation and a create with the same number, both held just before their write until both
   * are there, then released together: exactly one gets the number, and the database ends up with
   * exactly one active number 7.
   */
  @Test
  void aConcurrentReactivationAndCreateWithTheSameNumberExactlyOneWins() throws Exception {
    String id = createdId(create(CLUB_A, fields("Old Seven", Position.ST, 7)));
    release(CLUB_A, id, 0).andExpect(status().isOk());
    insertBarrier.arm(2);
    ExecutorService executor = Executors.newFixedThreadPool(2);
    try {
      Future<Integer> reactivated =
          executor.submit(() -> reactivate(CLUB_A, id, 1, 7).andReturn().getResponse().getStatus());
      Future<Integer> created =
          executor.submit(
              () ->
                  create(CLUB_A, fields("New Seven", Position.ST, 7))
                      .andReturn()
                      .getResponse()
                      .getStatus());
      int reactivateStatus = reactivated.get();
      int createStatus = created.get();

      // [reactivate, create]: one winner, one jersey 409.
      assertThat(List.of(reactivateStatus, createStatus))
          .isIn(List.of(200, 409), List.of(409, 201));
      assertThat(rawPlayer(id).get("active")).isEqualTo(reactivateStatus == 200);
      assertThat(
              mongoTemplate.count(
                  Query.query(Criteria.where("active").is(true).and("jerseyNumber").is(7)),
                  Player.class))
          .isEqualTo(1);
    } finally {
      executor.shutdownNow();
    }
  }

  // --- release / re-activate: versions -----------------------------------------------------------

  @Test
  void aReleaseOrReactivationWithAStaleVersionIs409AndNothingIsWritten() throws Exception {
    String active = createdId(create(CLUB_A, fields("Active", Position.ST, 9)));
    String released = createdId(create(CLUB_A, fields("Released", Position.ST, 10)));
    release(CLUB_A, released, 0).andExpect(status().isOk());
    Document activeBefore = rawPlayer(active);
    Document releasedBefore = rawPlayer(released);

    release(CLUB_A, active, 5)
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.message").value(STALE_MESSAGE));
    reactivate(CLUB_A, released, 0, 10)
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.message").value(STALE_MESSAGE));

    assertThat(rawPlayer(active)).isEqualTo(activeBefore);
    assertThat(rawPlayer(released)).isEqualTo(releasedBefore);
  }

  /**
   * Someone else saves between the version check and the save: the save's own version condition
   * catches it (verify 1) — the same 409 as a stale version, and the other write is kept.
   */
  @Test
  void aReleaseThatLosesASaveRaceIs409AndKeepsTheOtherWrite() throws Exception {
    String id = createdId(create(CLUB_A, fields("Eran Zahavi", Position.ST, 7)));
    loadHook.onNextLoad(() -> writeMeanwhile(id));

    release(CLUB_A, id, 0)
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.message").value(STALE_MESSAGE));

    Document stored = rawPlayer(id);
    assertThat(stored.get("active")).isEqualTo(true);
    assertThat(stored.get("fullName")).isEqualTo("Written Meanwhile");
    assertThat(stored.get("version")).isEqualTo(1L);
  }

  @Test
  void aReactivationThatLosesASaveRaceIs409AndKeepsTheOtherWrite() throws Exception {
    String id = createdId(create(CLUB_A, fields("Eran Zahavi", Position.ST, 7)));
    release(CLUB_A, id, 0).andExpect(status().isOk());
    loadHook.onNextLoad(() -> writeMeanwhile(id));

    reactivate(CLUB_A, id, 1, 7)
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.message").value(STALE_MESSAGE));

    Document stored = rawPlayer(id);
    assertThat(stored.get("active")).isEqualTo(false);
    assertThat(stored.get("fullName")).isEqualTo("Written Meanwhile");
    assertThat(stored.get("version")).isEqualTo(2L);
  }

  // --- permanent delete --------------------------------------------------------------------------

  @Test
  void anActiveOrReleasedPlayerCanBeDeletedForGood() throws Exception {
    String active = createdId(create(CLUB_A, fields("Active", Position.ST, 9)));
    String released = createdId(create(CLUB_A, fields("Released", Position.ST, 10)));
    String kept = createdId(create(CLUB_A, fields("Kept", Position.ST, 11)));
    release(CLUB_A, released, 0).andExpect(status().isOk());

    for (String id : List.of(active, released)) {
      remove(CLUB_A, id).andExpect(status().isNoContent()).andExpect(content().string(""));

      assertThat(rawPlayer(id)).isNull();
      mockMvc
          .perform(get("/squad/players/" + id).header("Authorization", bearer(CLUB_A)))
          .andExpect(status().isNotFound());
      remove(CLUB_A, id)
          .andExpect(status().isNotFound())
          .andExpect(jsonPath("$.message").value("Player not found"));
    }
    assertThat(names(list(CLUB_A, "status=all"))).containsExactly("Kept");
    assertThat(rawPlayer(kept)).isNotNull();
  }

  /** Not version-checked: a delete wins over an edit saved after the admin loaded the player. */
  @Test
  void aDeleteIsNotVersionChecked() throws Exception {
    String id = createdId(create(CLUB_A, fields("Eran Zahavi", Position.ST, 7)));
    loadHook.onNextLoad(() -> writeMeanwhile(id));

    remove(CLUB_A, id).andExpect(status().isNoContent());

    assertThat(rawPlayer(id)).isNull();
  }

  /**
   * Verify 3: the club-scoped {@code delete} removes nothing and raises nothing if the document is
   * already gone — so a delete racing another delete is a 204 for both, not a 404 or a 500.
   */
  @Test
  void aPlayerDeletedBetweenLoadAndDeleteIsStill204() throws Exception {
    String id = createdId(create(CLUB_A, fields("Eran Zahavi", Position.ST, 7)));
    loadHook.onNextLoad(
        () -> mongoTemplate.remove(Query.query(Criteria.where("_id").is(id)), Player.class));

    remove(CLUB_A, id).andExpect(status().isNoContent());

    assertThat(rawPlayer(id)).isNull();
  }

  // --- full replacement --------------------------------------------------------------------------

  @Test
  void omittingAnOptionalFieldOnUpdateClearsIt() throws Exception {
    Map<String, Object> full = fields("Eran Zahavi", Position.ST, 7);
    full.put("secondaryPosition", "AM");
    full.put("heightCm", 180);
    full.put("weightKg", 75);
    full.put("preferredFoot", "RIGHT");
    String id = createdId(create(CLUB_A, full));

    Map<String, Object> minimal = new LinkedHashMap<>();
    minimal.put("fullName", "Eran Zahavi");
    minimal.put("primaryPosition", "ST");
    minimal.put("dateOfBirth", "1995-05-20");
    minimal.put("medicalStatus", "FIT");
    minimal.put("version", 0);
    update(CLUB_A, id, minimal)
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.secondaryPosition").isEmpty())
        .andExpect(jsonPath("$.jerseyNumber").isEmpty())
        .andExpect(jsonPath("$.heightCm").isEmpty())
        .andExpect(jsonPath("$.weightKg").isEmpty())
        .andExpect(jsonPath("$.preferredFoot").isEmpty());

    assertThat(rawPlayer(id))
        .doesNotContainKeys(
            "secondaryPosition", "jerseyNumber", "heightCm", "weightKg", "preferredFoot");
  }

  @Test
  void createDefaultsMedicalStatusToFitAndStoresTheNameTrimmed() throws Exception {
    Map<String, Object> body = fields("  Eran Zahavi  ", Position.ST, 7);
    String id =
        createdId(
            create(CLUB_A, body)
                .andExpect(jsonPath("$.medicalStatus").value("FIT"))
                .andExpect(jsonPath("$.version").value(0))
                .andExpect(jsonPath("$.createdAt").isNotEmpty()));

    assertThat(rawPlayer(id).get("fullName")).isEqualTo("Eran Zahavi");
    assertThat(rawPlayer(id).get("medicalStatus")).isEqualTo("FIT");
  }

  // --- list filters ------------------------------------------------------------------------------

  @Test
  void eachStatusValueListsTheMatchingPlayers() throws Exception {
    saveAs(CLUB_A, newPlayer("Active", Position.ST, 9));
    Player released = newPlayer("Released", Position.ST, 10);
    released.setActive(false);
    saveAs(CLUB_A, released);
    clubContext.clear();

    assertThat(names(list(CLUB_A, ""))).containsExactly("Active");
    assertThat(names(list(CLUB_A, "status=active"))).containsExactly("Active");
    assertThat(names(list(CLUB_A, "status=released"))).containsExactly("Released");
    assertThat(names(list(CLUB_A, "status=all"))).containsExactly("Active", "Released");
  }

  @Test
  void eachFilterOnItsOwnAndCombined() throws Exception {
    Player striker = newPlayer("Striker", Position.ST, 9);
    striker.setPreferredFoot(PreferredFoot.LEFT);
    Player injuredStriker = newPlayer("Injured Striker", Position.ST, 19);
    injuredStriker.setMedicalStatus(MedicalStatus.INJURED);
    injuredStriker.setPreferredFoot(PreferredFoot.LEFT);
    Player keeper = newPlayer("Keeper", Position.GK, 1);
    keeper.setPreferredFoot(PreferredFoot.RIGHT);
    keeper.setDateOfBirth(LocalDate.now().minusYears(40));
    for (Player player : List.of(striker, injuredStriker, keeper)) {
      saveAs(CLUB_A, player);
    }
    clubContext.clear();

    assertThat(names(list(CLUB_A, "position=ST"))).containsExactly("Striker", "Injured Striker");
    assertThat(names(list(CLUB_A, "medicalStatus=INJURED"))).containsExactly("Injured Striker");
    assertThat(names(list(CLUB_A, "preferredFoot=RIGHT"))).containsExactly("Keeper");
    assertThat(names(list(CLUB_A, "minAge=35"))).containsExactly("Keeper");
    assertThat(names(list(CLUB_A, "maxAge=35"))).containsExactly("Striker", "Injured Striker");
    assertThat(names(list(CLUB_A, "position=ST&medicalStatus=FIT&preferredFoot=LEFT&maxAge=35")))
        .containsExactly("Striker");
    list(CLUB_A, "minAge=40&maxAge=30").andExpect(status().isBadRequest());
  }

  @Test
  void thePositionFilterIgnoresTheSecondaryPosition() throws Exception {
    Player defender = newPlayer("Defender", Position.CB, 5);
    defender.setSecondaryPosition(Position.DM);
    saveAs(CLUB_A, defender);
    saveAs(CLUB_A, newPlayer("Holding Mid", Position.DM, 6));
    clubContext.clear();

    assertThat(names(list(CLUB_A, "position=DM"))).containsExactly("Holding Mid");
  }

  /**
   * Age boundaries with a fixed clock: on "today" 2026-06-15, a player born 2000-06-15 has just
   * turned 26, and one born 2000-06-16 is still 25 (their birthday is tomorrow). Both bounds are
   * inclusive.
   */
  @Test
  void theAgeFiltersAreInclusiveAndTurnOnTheBirthdayItself() {
    Player birthdayToday = newPlayer("Birthday Today", Position.ST, 9);
    birthdayToday.setDateOfBirth(LocalDate.of(2000, 6, 15));
    Player birthdayTomorrow = newPlayer("Birthday Tomorrow", Position.ST, 10);
    birthdayTomorrow.setDateOfBirth(LocalDate.of(2000, 6, 16));
    saveAs(CLUB_A, birthdayToday);
    saveAs(CLUB_A, birthdayTomorrow);
    PlayerService onJune15 = serviceOn(LocalDate.of(2026, 6, 15));
    PlayerService onJune16 = serviceOn(LocalDate.of(2026, 6, 16));

    assertThat(ages(onJune15, 26, null)).containsExactly("Birthday Today");
    assertThat(ages(onJune15, null, 25)).containsExactly("Birthday Tomorrow");
    assertThat(ages(onJune15, 26, 26)).containsExactly("Birthday Today");
    assertThat(ages(onJune15, 25, 25)).containsExactly("Birthday Tomorrow");
    assertThat(ages(onJune16, 26, 26)).containsExactly("Birthday Today", "Birthday Tomorrow");
    assertThat(ages(onJune16, null, 25)).isEmpty();
  }

  @Test
  void theListIsInSquadOrderWithPlayersWithoutANumberLast() throws Exception {
    saveAs(CLUB_A, newPlayer("Striker Nine", Position.ST, 9));
    saveAs(CLUB_A, newPlayer("Keeper No Number", Position.GK, null));
    saveAs(CLUB_A, newPlayer("Keeper Twelve", Position.GK, 12));
    saveAs(CLUB_A, newPlayer("Keeper One", Position.GK, 1));
    saveAs(CLUB_A, newPlayer("Back B", Position.CB, null));
    saveAs(CLUB_A, newPlayer("Back A", Position.CB, null));
    saveAs(CLUB_A, newPlayer("Back Four", Position.CB, 4));
    clubContext.clear();

    assertThat(names(list(CLUB_A, "")))
        .containsExactly(
            "Keeper One",
            "Keeper Twelve",
            "Keeper No Number",
            "Back Four",
            "Back A",
            "Back B",
            "Striker Nine");
  }

  // --- helpers -----------------------------------------------------------------------------------

  private ResultActions create(String clubId, Map<String, Object> body) throws Exception {
    return mockMvc.perform(
        post("/squad/players")
            .header("Authorization", bearer(clubId))
            .contentType(MediaType.APPLICATION_JSON)
            .content(JSON.writeValueAsString(body)));
  }

  private ResultActions update(String clubId, String id, Map<String, Object> body)
      throws Exception {
    return mockMvc.perform(
        put("/squad/players/" + id)
            .header("Authorization", bearer(clubId))
            .contentType(MediaType.APPLICATION_JSON)
            .content(JSON.writeValueAsString(body)));
  }

  private ResultActions release(String clubId, String id, long version) throws Exception {
    return mockMvc.perform(
        post("/squad/players/" + id + "/release")
            .header("Authorization", bearer(clubId))
            .contentType(MediaType.APPLICATION_JSON)
            .content(JSON.writeValueAsString(Map.of("version", version))));
  }

  private ResultActions reactivate(String clubId, String id, long version, Integer jerseyNumber)
      throws Exception {
    Map<String, Object> body = new LinkedHashMap<>();
    body.put("version", version);
    body.put("jerseyNumber", jerseyNumber);
    return mockMvc.perform(
        post("/squad/players/" + id + "/reactivate")
            .header("Authorization", bearer(clubId))
            .contentType(MediaType.APPLICATION_JSON)
            .content(JSON.writeValueAsString(body)));
  }

  /** Permanent delete, as an {@code ADMIN} — the only level allowed to. */
  private ResultActions remove(String clubId, String id) throws Exception {
    return mockMvc.perform(
        delete("/squad/players/" + id)
            .header("Authorization", tokens.bearer(clubId, PermissionLevel.ADMIN)));
  }

  /** A concurrent edit: changes the name and bumps the version, as a save would. */
  private void writeMeanwhile(String id) {
    mongoTemplate.updateFirst(
        Query.query(Criteria.where("_id").is(id)),
        new Update().set("fullName", "Written Meanwhile").inc("version", 1),
        Player.class);
  }

  private ResultActions list(String clubId, String query) throws Exception {
    return mockMvc.perform(
        get("/squad/players" + (query.isEmpty() ? "" : "?" + query))
            .header("Authorization", tokens.bearer(clubId, PermissionLevel.VIEW_ONLY)));
  }

  private String bearer(String clubId) {
    return tokens.bearer(clubId, PermissionLevel.EDIT_FULL);
  }

  private static String createdId(ResultActions created) throws Exception {
    MvcResult result = created.andExpect(status().isCreated()).andReturn();
    return JSON.readTree(result.getResponse().getContentAsString()).get("id").asString();
  }

  private static List<String> names(ResultActions listed) throws Exception {
    JsonNode players =
        JSON.readTree(
            listed.andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
    List<String> names = new ArrayList<>();
    players.forEach(player -> names.add(player.get("fullName").asString()));
    return names;
  }

  private List<String> ages(PlayerService service, Integer minAge, Integer maxAge) {
    return clubContext.callAs(
        CLUB_A,
        () ->
            service
                .list(new PlayerFilter(PlayerStatus.ACTIVE, null, minAge, maxAge, null, null))
                .stream()
                .map(Player::getFullName)
                .toList());
  }

  private PlayerService serviceOn(LocalDate today) {
    Clock clock = Clock.fixed(today.atTime(12, 0).toInstant(ZoneOffset.UTC), ZoneOffset.UTC);
    return new PlayerService(playerRepository, clubContext, clock);
  }

  private Player saveAs(String clubId, Player player) {
    clubContext.setClubId(clubId);
    return playerRepository.save(player);
  }

  private static DuplicateKeyException catchDuplicateKey(Runnable write) {
    try {
      write.run();
    } catch (DuplicateKeyException e) {
      return e;
    }
    throw new AssertionError("Expected a DuplicateKeyException");
  }

  private static Map<String, Object> fields(String fullName, Position position, Integer number) {
    Map<String, Object> body = new LinkedHashMap<>();
    body.put("fullName", fullName);
    body.put("primaryPosition", position.name());
    body.put("jerseyNumber", number);
    body.put("dateOfBirth", "1995-05-20");
    return body;
  }

  private static CreatePlayerRequest createRequest(String name, Position position, int number) {
    return new CreatePlayerRequest(
        name, position, null, number, LocalDate.of(1995, 5, 20), null, null, null, null);
  }

  private static Player newPlayer(String fullName, Position position, Integer jerseyNumber) {
    Player player = new Player();
    player.setFullName(fullName);
    player.setPrimaryPosition(position);
    player.setJerseyNumber(jerseyNumber);
    player.setDateOfBirth(LocalDate.of(1995, 5, 20));
    return player;
  }

  private Document rawPlayer(String id) {
    return mongoTemplate
        .getCollection("players")
        .find(new Document("_id", new ObjectId(id)))
        .first();
  }
}
