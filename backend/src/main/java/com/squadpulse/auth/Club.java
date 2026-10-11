package com.squadpulse.auth;

import com.squadpulse.common.NotClubScoped;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;

/**
 * A club — the tenant itself (see docs/spec.md sections 03 and 09). Every {@link
 * com.squadpulse.common.ClubScopedEntity}'s {@code clubId} is the {@link #id} of one of these.
 *
 * <p>Deliberately {@link NotClubScoped}: it's the tenant root, not data owned by a tenant. Clubs
 * are only ever created by the system owner, via {@link ClubBootstrapRunner}.
 *
 * <p>No {@code @Version}, deliberately: each of its edits is a targeted update of its own field,
 * never a save of the whole document — the name by an {@code ADMIN} ({@link ClubSettingsService},
 * {@link ClubRepository#updateNameByClubId}) and the football.org.il mapping by the system owner
 * ({@link ClubBootstrapService}, {@link ClubRepository#updateFootballOrgIlByClubId}). Adding one
 * later needs a backfill for the existing documents first, as {@link UserVersionBackfill} did for
 * users.
 */
@Document("clubs")
@NotClubScoped
public class Club {

  /**
   * The longest club name, after trimming — shared by {@link #name} (so the owner bootstrap, which
   * validates this entity, enforces it) and {@link UpdateClubRequest}, so a name one accepts is
   * never one the other rejects.
   */
  public static final int NAME_MAX_LENGTH = 100;

  @Id private String id;

  /** Stored trimmed (see {@link #setName(String)}). */
  @NotBlank
  @Size(max = NAME_MAX_LENGTH)
  private String name;

  @CreatedDate private Instant createdAt;

  /**
   * Which team, league and season this club is on football.org.il (KAN-65), or {@code null} if it
   * isn't mapped — then scraping is unavailable for it. A document stored before KAN-65 has no such
   * field and reads as {@code null}. Set only by the system owner through the {@code bootstrap}
   * profile ({@link ClubBootstrapRunner}): on creation, or later by a targeted update of this field
   * alone. Never exposed by the API ({@link ClubResponse}).
   */
  @Valid private FootballOrgIlMapping footballOrgIl;

  public String getId() {
    return id;
  }

  public void setId(String id) {
    this.id = id;
  }

  public String getName() {
    return name;
  }

  /** Trimmed, so a whitespace-only name fails {@code @NotBlank} and padding doesn't count. */
  public void setName(String name) {
    this.name = name == null ? null : name.trim();
  }

  public Instant getCreatedAt() {
    return createdAt;
  }

  public FootballOrgIlMapping getFootballOrgIl() {
    return footballOrgIl;
  }

  /** For a new club only: an existing club's mapping is changed by a targeted update. */
  public void setFootballOrgIl(FootballOrgIlMapping footballOrgIl) {
    this.footballOrgIl = footballOrgIl;
  }
}
