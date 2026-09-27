package com.squadpulse.common;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

class ClubContextTest {

  private final ClubContext clubContext = new ClubContext();

  @AfterEach
  void tearDown() {
    clubContext.clear();
  }

  @Test
  void requireClubIdThrowsWhenNothingIsSet() {
    assertThatThrownBy(clubContext::requireClubId).isInstanceOf(MissingClubContextException.class);
  }

  @Test
  void requireClubIdReturnsTheClubIdOnceSet() {
    clubContext.setClubId("club-a");

    assertThat(clubContext.requireClubId()).isEqualTo("club-a");
  }

  @Test
  void clearRemovesThePreviouslySetClubId() {
    clubContext.setClubId("club-a");

    clubContext.clear();

    assertThat(clubContext.getClubId()).isEmpty();
  }

  @Test
  void callAsRunsWithTheGivenClubAndClearsItAfterwards() {
    String seen = clubContext.callAs("club-a", clubContext::requireClubId);

    assertThat(seen).isEqualTo("club-a");
    assertThat(clubContext.getClubId()).isEmpty();
  }

  @Test
  void callAsRestoresTheClubThatWasSetBefore() {
    clubContext.setClubId("club-b");

    clubContext.callAs("club-a", clubContext::requireClubId);

    assertThat(clubContext.getClubId()).contains("club-b");
  }

  @Test
  void callAsRestoresTheClubEvenWhenTheActionThrows() {
    clubContext.setClubId("club-b");

    assertThatThrownBy(
            () ->
                clubContext.callAs(
                    "club-a",
                    () -> {
                      throw new IllegalStateException("boom");
                    }))
        .isInstanceOf(IllegalStateException.class);

    assertThat(clubContext.getClubId()).contains("club-b");
  }
}
