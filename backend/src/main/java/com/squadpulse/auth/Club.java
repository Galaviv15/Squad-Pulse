package com.squadpulse.auth;

import com.squadpulse.common.NotClubScoped;
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
 * <p>No {@code @Version}, deliberately: its only edit today, the name ({@link
 * ClubSettingsService}), is a targeted single-field update, never a save of the whole document.
 * Adding one later needs a backfill for the existing documents first, as {@link
 * UserVersionBackfill} did for users.
 */
@Document("clubs")
@NotClubScoped
public class Club {

  /**
   * The longest club name, after trimming. On {@link #name}, so the owner bootstrap, which validates
   * this entity, enforces it.
   */
  public static final int NAME_MAX_LENGTH = 100;

  @Id private String id;

  /** Stored trimmed (see {@link #setName(String)}). */
  @NotBlank
  @Size(max = NAME_MAX_LENGTH)
  private String name;

  @CreatedDate private Instant createdAt;

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
}
