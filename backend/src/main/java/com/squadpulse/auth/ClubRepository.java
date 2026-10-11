package com.squadpulse.auth;

import org.springframework.data.mongodb.repository.MongoRepository;
import org.springframework.data.mongodb.repository.Query;
import org.springframework.data.mongodb.repository.Update;

/**
 * Repository for {@link Club}. Not club-scoped — {@link Club} is {@link
 * com.squadpulse.common.NotClubScoped}, so this is built on plain {@code SimpleMongoRepository}.
 */
public interface ClubRepository extends MongoRepository<Club, String> {

  /**
   * Sets the name of the club {@code clubId} — a {@code $set} of {@code name} alone on {@code {_id:
   * clubId}}, so no other field of the document is ever written (see {@link ClubSettingsService}).
   * {@code name} must already be validated and trimmed: this bypasses {@link Club#setName}. The
   * {@code _id} is mapped like {@link #findById}'s, so a hex string matches an {@code ObjectId}.
   *
   * <p>{@code void}, deliberately: Spring Data answers an {@code @Update} method with the
   * <i>modified</i> count, which is also 0 when the name doesn't change, so it can't tell a missing
   * club apart. Callers re-read the club instead.
   *
   * @param clubId only ever the clubId of the caller's access token
   */
  @Query("{ '_id': ?0 }")
  @Update("{ '$set': { 'name': ?1 } }")
  void updateNameByClubId(String clubId, String name);

  /**
   * Replaces the football.org.il mapping of the club {@code clubId} (KAN-65) — a {@code $set} of
   * the whole {@code footballOrgIl} sub-document on {@code {_id: clubId}}, so all three ids change
   * together and no other field of the document is ever written. Only the system owner's bootstrap
   * ({@link ClubBootstrapService#updateFootballOrgIl}) calls it, with a {@code mapping} it has
   * already validated: nothing here re-checks it. The {@code _id} is mapped like {@link
   * #findById}'s.
   *
   * <p>{@code void} for the same reason as {@link #updateNameByClubId}: the modified count can't
   * tell a missing club from an unchanged mapping, so the caller checks the club exists first.
   */
  @Query("{ '_id': ?0 }")
  @Update("{ '$set': { 'footballOrgIl': ?1 } }")
  void updateFootballOrgIlByClubId(String clubId, FootballOrgIlMapping mapping);
}
