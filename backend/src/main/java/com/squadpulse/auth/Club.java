package com.squadpulse.auth;

import com.squadpulse.common.NotClubScoped;
import jakarta.validation.constraints.NotBlank;
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
 */
@Document("clubs")
@NotClubScoped
public class Club {

  @Id private String id;

  @NotBlank private String name;

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

  public void setName(String name) {
    this.name = name;
  }

  public Instant getCreatedAt() {
    return createdAt;
  }
}
