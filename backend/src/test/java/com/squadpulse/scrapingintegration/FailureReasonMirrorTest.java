package com.squadpulse.scrapingintegration;

import static org.assertj.core.api.Assertions.assertThat;

import com.squadpulse.match.ScrapedGameDetails;
import java.util.Arrays;
import org.junit.jupiter.api.Test;

/**
 * {@code match} mirrors the contract's failure reasons (it can't import {@code
 * scrapingintegration}). {@code ScrapeResultController.toMatchReason} is an exhaustive switch, so a
 * reason added to the contract doesn't compile until it's mapped; this checks the other direction:
 * no reason exists only in {@code match}.
 */
class FailureReasonMirrorTest {

  @Test
  void bothEnumsHaveTheSameConstants() {
    assertThat(Arrays.stream(ScrapedGameDetails.FailureReason.values()).map(Enum::name))
        .containsExactlyElementsOf(
            Arrays.stream(ScrapeResultRequest.FailureReason.values()).map(Enum::name).toList());
  }
}
