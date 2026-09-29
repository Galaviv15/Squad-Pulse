package com.squadpulse.squad;

import com.squadpulse.common.AdultAge;
import com.squadpulse.common.ClubScopedEntity;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.time.LocalDate;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.annotation.Id;
import org.springframework.data.annotation.LastModifiedDate;
import org.springframework.data.annotation.Version;
import org.springframework.data.mongodb.core.index.CompoundIndex;
import org.springframework.data.mongodb.core.mapping.Document;

/**
 * A player on a club's roster (see docs/spec.md section 05).
 *
 * <p><b>A player is a club-scoped roster record, not a global person.</b> If the same real person
 * moves from club A to club B, A's record is released and stays in A as history, and B creates its
 * own, fully independent record. The system doesn't know the two records are the same person, and
 * neither club can see or edit the other's — so this is a {@link ClubScopedEntity} like any other,
 * with no cross-club field and no globally scoped lookup. If players ever get logins, the intended
 * extension point is an optional link to the global {@code auth.User} (e.g. a {@code userId}
 * field); each club would still see only its own record.
 *
 * <p><b>Leaving the club is a soft delete</b> ({@link #active} = {@code false}), so references to
 * the player from training sessions and lineups stay valid. Permanently deleting the document is
 * only for records created by mistake.
 *
 * <p>Uses optimistic locking ({@link #version}) from day one, like {@code auth.User} (KAN-24).
 *
 * <p>Bean Validation constraints here are <b>not</b> enforced on save — nothing registers a
 * validating entity callback — so they must be checked on the request before a player is written.
 */
@Document("players")
@CompoundIndex(
    name = Player.JERSEY_NUMBER_INDEX,
    def = "{'clubId': 1, 'jerseyNumber': 1}",
    unique = true,
    partialFilter = "{'jerseyNumber': {'$type': 'number'}, 'active': true}")
@DistinctPositions
public class Player extends ClubScopedEntity implements HasPositions {

  /**
   * Name of the unique index that keeps jersey numbers unique among a club's active players (see
   * {@link #jerseyNumber}). A write that violates it fails with {@link
   * org.springframework.dao.DuplicateKeyException} naming this index.
   */
  public static final String JERSEY_NUMBER_INDEX = "clubId_jerseyNumber_active_unique";

  @Id private String id;

  /**
   * A single field rather than first/last — Hebrew names don't split cleanly that way. Always
   * stored trimmed (see {@link #setFullName(String)}).
   */
  @NotBlank
  @Size(max = 100)
  private String fullName;

  @NotNull private Position primaryPosition;

  /**
   * Optional; if set, must differ from {@link #primaryPosition} (see {@link DistinctPositions}).
   */
  private Position secondaryPosition;

  /**
   * Optional. Unique among the club's <b>active</b> players, guaranteed by the {@value
   * #JERSEY_NUMBER_INDEX} index rather than a check in code, so two concurrent writes can't both
   * take the same number.
   *
   * <p>The index is unique on {@code (clubId, jerseyNumber)} but <b>partial</b>: it only covers
   * documents where {@code jerseyNumber} is a number and {@code active} is {@code true}. So players
   * without a number never collide with each other, and a released player keeps their old number on
   * record without blocking it for a new player. Re-activating a released player whose number has
   * been taken in the meantime fails. The filter tests the type rather than {@code $exists}, which
   * would also match a field explicitly stored as {@code null}; Spring Data omits {@code null}
   * fields on write, but the index shouldn't depend on that.
   */
  @Min(1)
  @Max(99)
  private Integer jerseyNumber;

  /** Age is always derived from this, never stored. */
  @NotNull @AdultAge private LocalDate dateOfBirth;

  @Min(140)
  @Max(220)
  private Integer heightCm;

  @Min(40)
  @Max(150)
  private Integer weightKg;

  private PreferredFoot preferredFoot;

  @NotNull private MedicalStatus medicalStatus = MedicalStatus.FIT;

  /** {@code false} once the player has left the club — a soft delete (see the class Javadoc). */
  private boolean active = true;

  @CreatedDate private Instant createdAt;

  @LastModifiedDate private Instant updatedAt;

  /**
   * Optimistic locking against lost updates, as for {@code auth.User} (KAN-24). Every save of an
   * existing player is a conditional update on {@code _id} <b>and</b> this value, and increments
   * it; a save made from a stale copy fails with {@link
   * org.springframework.dao.OptimisticLockingFailureException} instead of silently overwriting a
   * concurrent write.
   *
   * <p>Managed entirely by Spring Data: application code never sets it. {@code null} only until the
   * player is first inserted, which sets it to 0.
   */
  @Version private Long version;

  public String getId() {
    return id;
  }

  public void setId(String id) {
    this.id = id;
  }

  public String getFullName() {
    return fullName;
  }

  public void setFullName(String fullName) {
    this.fullName = fullName == null ? null : fullName.trim();
  }

  public Position getPrimaryPosition() {
    return primaryPosition;
  }

  public void setPrimaryPosition(Position primaryPosition) {
    this.primaryPosition = primaryPosition;
  }

  public Position getSecondaryPosition() {
    return secondaryPosition;
  }

  public void setSecondaryPosition(Position secondaryPosition) {
    this.secondaryPosition = secondaryPosition;
  }

  @Override
  public Position primaryPosition() {
    return primaryPosition;
  }

  @Override
  public Position secondaryPosition() {
    return secondaryPosition;
  }

  public Integer getJerseyNumber() {
    return jerseyNumber;
  }

  public void setJerseyNumber(Integer jerseyNumber) {
    this.jerseyNumber = jerseyNumber;
  }

  public LocalDate getDateOfBirth() {
    return dateOfBirth;
  }

  public void setDateOfBirth(LocalDate dateOfBirth) {
    this.dateOfBirth = dateOfBirth;
  }

  public Integer getHeightCm() {
    return heightCm;
  }

  public void setHeightCm(Integer heightCm) {
    this.heightCm = heightCm;
  }

  public Integer getWeightKg() {
    return weightKg;
  }

  public void setWeightKg(Integer weightKg) {
    this.weightKg = weightKg;
  }

  public PreferredFoot getPreferredFoot() {
    return preferredFoot;
  }

  public void setPreferredFoot(PreferredFoot preferredFoot) {
    this.preferredFoot = preferredFoot;
  }

  public MedicalStatus getMedicalStatus() {
    return medicalStatus;
  }

  public void setMedicalStatus(MedicalStatus medicalStatus) {
    this.medicalStatus = medicalStatus;
  }

  public boolean isActive() {
    return active;
  }

  public void setActive(boolean active) {
    this.active = active;
  }

  public Instant getCreatedAt() {
    return createdAt;
  }

  public Instant getUpdatedAt() {
    return updatedAt;
  }

  public Long getVersion() {
    return version;
  }
}
