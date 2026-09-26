package com.squadpulse.auth;

import com.squadpulse.common.AdultAge;
import com.squadpulse.common.ClubScopedEntity;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Locale;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.annotation.Id;
import org.springframework.data.annotation.LastModifiedDate;
import org.springframework.data.mongodb.core.index.Indexed;
import org.springframework.data.mongodb.core.mapping.Document;

/**
 * A staff member's login account. Every user belongs to exactly one club (see docs/spec.md section
 * 04), so this is a {@link ClubScopedEntity} like any other domain document.
 *
 * <p>The one deliberate exception is {@link #email}: it's unique across the <b>whole system</b>,
 * not per club, because login looks a user up by email alone before any club context exists (see
 * {@link UserRepository#findByEmail(String)}). The unique index is created at startup via {@code
 * spring.data.mongodb.auto-index-creation} (see application.yml).
 */
@Document("users")
public class User extends ClubScopedEntity {

  @Id private String id;

  /** Always stored normalized (see {@link #normalizeEmail(String)}). */
  @NotBlank
  @Email
  @Indexed(unique = true)
  private String email;

  /**
   * Argon2id hash of the peppered password (see {@link PepperedPasswordEncoder}). {@code null} for
   * an invited user who hasn't set a password yet (see {@link UserInvitationService}) — login
   * rejects such a user like any other failed login.
   */
  private String passwordHash;

  @NotNull private Title title;

  @NotNull private PermissionLevel permissionLevel;

  /** A single field rather than first/last — Hebrew names don't split cleanly that way. */
  @NotBlank private String fullName;

  @AdultAge private LocalDate dateOfBirth;

  /**
   * Lets a Club Manager cut a departed staff member's access without deleting the account, which
   * would orphan references to it from things they created.
   */
  private boolean active = true;

  @CreatedDate private Instant createdAt;

  @LastModifiedDate private Instant updatedAt;

  /**
   * Trims and lower-cases an email address, so "Gal@Example.com" and "gal@example.com" can't end up
   * as two different accounts under the global unique index. Anything that looks a user up by email
   * (e.g. login) must pass its input through this first — {@link
   * UserRepository#findByEmail(String)} matches the stored value exactly.
   */
  public static String normalizeEmail(String email) {
    return email == null ? null : email.trim().toLowerCase(Locale.ROOT);
  }

  public String getId() {
    return id;
  }

  public void setId(String id) {
    this.id = id;
  }

  public String getEmail() {
    return email;
  }

  public void setEmail(String email) {
    this.email = normalizeEmail(email);
  }

  public String getPasswordHash() {
    return passwordHash;
  }

  public void setPasswordHash(String passwordHash) {
    this.passwordHash = passwordHash;
  }

  public Title getTitle() {
    return title;
  }

  public void setTitle(Title title) {
    this.title = title;
  }

  public PermissionLevel getPermissionLevel() {
    return permissionLevel;
  }

  public void setPermissionLevel(PermissionLevel permissionLevel) {
    this.permissionLevel = permissionLevel;
  }

  public String getFullName() {
    return fullName;
  }

  public void setFullName(String fullName) {
    this.fullName = fullName;
  }

  public LocalDate getDateOfBirth() {
    return dateOfBirth;
  }

  public void setDateOfBirth(LocalDate dateOfBirth) {
    this.dateOfBirth = dateOfBirth;
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
}
