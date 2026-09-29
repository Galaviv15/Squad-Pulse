package com.squadpulse.squad;

import java.beans.PropertyEditorSupport;
import java.util.Arrays;

/**
 * The {@code status} filter of {@code GET /squad/players}: which players to list by {@link
 * Player#isActive()}. Written in lowercase in the query string ({@code ?status=released}).
 */
enum PlayerStatus {
  ACTIVE("active"),
  RELEASED("released"),
  ALL("all");

  private final String paramValue;

  PlayerStatus(String paramValue) {
    this.paramValue = paramValue;
  }

  boolean matches(Player player) {
    return switch (this) {
      case ACTIVE -> player.isActive();
      case RELEASED -> !player.isActive();
      case ALL -> true;
    };
  }

  /**
   * The query-string spelling. {@code GlobalExceptionHandler} lists an enum's accepted values by
   * {@code toString()}, so a rejected {@code status} names the values that actually work.
   */
  @Override
  public String toString() {
    return paramValue;
  }

  /**
   * Binds the lowercase query-string value. Spring's own enum conversion is case-sensitive on the
   * constant name, so it would reject {@code active}. An unknown value throws {@link
   * IllegalArgumentException}, which Spring turns into a {@code
   * MethodArgumentTypeMismatchException} — the same 400 as an unknown value of any other enum
   * parameter.
   */
  static final class Editor extends PropertyEditorSupport {

    @Override
    public void setAsText(String text) {
      setValue(
          Arrays.stream(values())
              .filter(status -> status.paramValue.equals(text))
              .findFirst()
              .orElseThrow(() -> new IllegalArgumentException("Unknown player status")));
    }
  }
}
