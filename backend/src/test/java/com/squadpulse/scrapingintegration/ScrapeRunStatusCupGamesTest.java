package com.squadpulse.scrapingintegration;

import static org.assertj.core.api.Assertions.assertThat;

import com.squadpulse.match.CupCompetition;
import com.squadpulse.match.IngestResult.RejectionReason;
import com.squadpulse.scrapingintegration.RefreshStatusResponse.FailureReason;
import com.squadpulse.scrapingintegration.ScrapeResultRequest.CupCompetitionPart;
import com.squadpulse.scrapingintegration.ScrapeResultRequest.CupGamesPart;
import com.squadpulse.scrapingintegration.ScrapeResultRequest.PartStatus;
import com.squadpulse.scrapingintegration.ScrapeResultResponse.CompetitionOutcome;
import com.squadpulse.scrapingintegration.ScrapeResultResponse.CupGamesOutcome;
import com.squadpulse.scrapingintegration.ScrapeResultResponse.Outcome;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * {@link ScrapeRunStatusService#cupGamesFailure} (KAN-84) on its own. The "an expected competition
 * is missing" rule can't be reached through the endpoint while {@link CupCompetition} has a single
 * value (a processed part always lists it), so it's exercised here with the constant and with the
 * expected set as a parameter.
 */
class ScrapeRunStatusCupGamesTest {

  private static final CupGamesPart SENT_OK =
      new CupGamesPart(
          PartStatus.OK,
          Instant.parse("2026-10-11T09:00:00Z"),
          List.of(
              new CupCompetitionPart(
                  CupCompetition.STATE_CUP, PartStatus.OK, List.of(), null, null)),
          null,
          null);

  @Test
  void theExpectedCompetitionsAreTheStateCup() {
    assertThat(ScrapeRunStatusService.EXPECTED_CUP_COMPETITIONS)
        .containsExactly(CupCompetition.STATE_CUP);
  }

  @Test
  void aProcessedPartWithoutAnExpectedCompetitionIsNoResult() {
    CupGamesOutcome withoutAny = new CupGamesOutcome(Outcome.STORED, null, List.of());

    assertThat(
            ScrapeRunStatusService.cupGamesFailure(
                withoutAny, SENT_OK, ScrapeRunStatusService.EXPECTED_CUP_COMPETITIONS))
        .isEqualTo(FailureReason.NO_RESULT);
    // Nothing expected, nothing missing.
    assertThat(ScrapeRunStatusService.cupGamesFailure(withoutAny, SENT_OK, Set.of())).isNull();
  }

  @Test
  void storedAndStaleCompetitionsAreFine() {
    for (Outcome fine : List.of(Outcome.STORED, Outcome.STALE)) {
      assertThat(
              ScrapeRunStatusService.cupGamesFailure(
                  stored(new CompetitionOutcome(CupCompetition.STATE_CUP, fine, null)),
                  SENT_OK,
                  ScrapeRunStatusService.EXPECTED_CUP_COMPETITIONS))
          .isNull();
    }
  }

  @Test
  void aRejectedCompetitionIsRejectedWhateverTheReason() {
    for (RejectionReason reason : RejectionReason.values()) {
      assertThat(
              ScrapeRunStatusService.cupGamesFailure(
                  stored(
                      new CompetitionOutcome(CupCompetition.STATE_CUP, Outcome.REJECTED, reason)),
                  SENT_OK,
                  ScrapeRunStatusService.EXPECTED_CUP_COMPETITIONS))
          .isEqualTo(FailureReason.REJECTED);
    }
  }

  @Test
  void aFailedCompetitionCarriesTheScrapersReason() {
    CupGamesPart sent =
        new CupGamesPart(
            PartStatus.OK,
            Instant.parse("2026-10-11T09:00:00Z"),
            List.of(
                new CupCompetitionPart(
                    CupCompetition.STATE_CUP,
                    PartStatus.FAILED,
                    null,
                    ScrapeResultRequest.FailureReason.TIMEOUT,
                    null)),
            null,
            null);

    assertThat(
            ScrapeRunStatusService.cupGamesFailure(
                stored(
                    new CompetitionOutcome(
                        CupCompetition.STATE_CUP, Outcome.FAILURE_RECORDED, null)),
                sent,
                ScrapeRunStatusService.EXPECTED_CUP_COMPETITIONS))
        .isEqualTo(FailureReason.SOURCE_TIMEOUT);
  }

  @Test
  void thePartsOwnOutcomeComesFirst() {
    assertThat(
            ScrapeRunStatusService.cupGamesFailure(
                CupGamesOutcome.NOT_SENT, null, ScrapeRunStatusService.EXPECTED_CUP_COMPETITIONS))
        .isEqualTo(FailureReason.NO_RESULT);
    assertThat(
            ScrapeRunStatusService.cupGamesFailure(
                new CupGamesOutcome(Outcome.REJECTED, RejectionReason.NOT_MAPPED, List.of()),
                SENT_OK,
                ScrapeRunStatusService.EXPECTED_CUP_COMPETITIONS))
        .isEqualTo(FailureReason.REJECTED);
  }

  private static CupGamesOutcome stored(CompetitionOutcome competition) {
    return new CupGamesOutcome(Outcome.STORED, null, List.of(competition));
  }
}
