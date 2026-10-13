package com.squadpulse.match;

import static org.assertj.core.api.Assertions.assertThat;

import java.text.Normalizer;
import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** The name-suggestion rule (KAN-72), on made-up names. */
class NameSuggestionsTest {

  // --- what NFC does and doesn't do (java.text.Normalizer, proven rather than assumed) --------

  @Test
  void nfcLeavesGereshGershayimAndTheirAsciiLookAlikesDistinct() {
    for (String c : new String[] {"׳", "'", "’", "״", "\"", "”", "־"}) {
      assertThat(Normalizer.normalize(c, Normalizer.Form.NFC)).as(c).isEqualTo(c);
    }
    assertThat(Normalizer.normalize("׳", Normalizer.Form.NFC)).isNotEqualTo("'");
    assertThat(Normalizer.normalize("״", Normalizer.Form.NFC)).isNotEqualTo("\"");
    // So the explicit mapping is what unifies them.
    assertThat(NameSuggestions.normalize("ג׳ורג׳")).isEqualTo(NameSuggestions.normalize("ג'ורג'"));
  }

  @Test
  void nfcComposesADecomposedLetter() {
    String decomposed = "José";
    assertThat(decomposed).isNotEqualTo("José");
    assertThat(NameSuggestions.normalize(decomposed)).isEqualTo(NameSuggestions.normalize("José"));
  }

  // --- normalising ------------------------------------------------------------------------------

  @Test
  void gereshLookAlikesAreOneCharacter() {
    String expected = NameSuggestions.normalize("ז'אן");
    for (String geresh : new String[] {"׳", "’", "‘", "`", "´"}) {
      assertThat(NameSuggestions.normalize("ז" + geresh + "אן")).as(geresh).isEqualTo(expected);
    }
  }

  @Test
  void gershayimLookAlikesAreOneCharacter() {
    String expected = NameSuggestions.normalize("רמה\"ש");
    for (String gershayim : new String[] {"״", "“", "”"}) {
      assertThat(NameSuggestions.normalize("רמה" + gershayim + "ש"))
          .as(gershayim)
          .isEqualTo(expected);
    }
  }

  @Test
  void hyphenMaqafAndSpaceAreTheSame() {
    assertThat(NameSuggestions.words("בן-חיים אורי"))
        .isEqualTo(NameSuggestions.words("בן־חיים אורי"))
        .isEqualTo(NameSuggestions.words("בן חיים אורי"))
        .containsExactlyInAnyOrder("בן", "חיים", "אורי");
  }

  @Test
  void doubleAndNoBreakSpacesAreOneSpaceAndTrimmed() {
    assertThat(NameSuggestions.normalize("  כהן   דניאל \t"))
        .isEqualTo(NameSuggestions.normalize("כהן דניאל"))
        .isEqualTo("כהן דניאל");
  }

  @Test
  void latinLettersAreLowerCased() {
    assertThat(NameSuggestions.normalize("Daniel COHEN")).isEqualTo("daniel cohen");
  }

  @Test
  void aBlankNameHasNoWords() {
    assertThat(NameSuggestions.words("   ")).isEmpty();
    assertThat(NameSuggestions.words(null)).isEmpty();
  }

  // --- matching ---------------------------------------------------------------------------------

  @Test
  void surnameFirstAndFirstNameFirstAreAnExactMatch() {
    assertThat(suggest(Map.of("s1", "כהן דניאל"), Map.of("p1", "דניאל כהן")))
        .containsExactly(Map.entry("s1", "p1"));
  }

  @Test
  void threeWordsAgainstTwoOfThemIsAPartialMatch() {
    assertThat(suggest(Map.of("s1", "לוי יוסף משה"), Map.of("p1", "יוסף לוי")))
        .containsExactly(Map.entry("s1", "p1"));
    assertThat(suggest(Map.of("s1", "לוי יוסף"), Map.of("p1", "יוסף משה לוי")))
        .containsExactly(Map.entry("s1", "p1"));
  }

  @Test
  void onlyOneSharedWordIsNoMatch() {
    assertThat(suggest(Map.of("s1", "כהן דניאל"), Map.of("p1", "כהן אבירם"))).isEmpty();
    // One contains the other, but they share only one word.
    assertThat(suggest(Map.of("s1", "כהן דניאל"), Map.of("p1", "כהן"))).isEmpty();
  }

  @Test
  void twoExactCandidatesAreNoSuggestion() {
    assertThat(suggest(Map.of("s1", "כהן דניאל"), ordered("p1", "דניאל כהן", "p2", "כהן דניאל")))
        .isEmpty();
  }

  @Test
  void anExactCandidateWinsOverAPartialOne() {
    assertThat(suggest(Map.of("s1", "לוי יוסף"), ordered("p1", "יוסף לוי", "p2", "יוסף משה לוי")))
        .containsExactly(Map.entry("s1", "p1"));
  }

  @Test
  void twoPartialCandidatesAreNoSuggestion() {
    assertThat(suggest(Map.of("s1", "לוי יוסף משה"), ordered("p1", "יוסף לוי", "p2", "משה לוי")))
        .isEmpty();
  }

  @Test
  void onePlayerTheOnlyCandidateOfTwoScrapedPlayersIsSuggestedForNeither() {
    assertThat(suggest(ordered("s1", "לוי יוסף משה", "s2", "לוי יוסף"), Map.of("p1", "יוסף לוי")))
        .isEmpty();
  }

  @Test
  void onePlayerInAnotherScrapedPlayersAmbiguousListIsNotSuggested() {
    // p1 is s1's only (exact) candidate, but also one of s2's two partial ones: ambiguous, so
    // neither gets a suggestion.
    assertThat(
            suggest(
                ordered("s1", "לוי יוסף", "s2", "לוי יוסף משה"),
                ordered("p1", "יוסף לוי", "p2", "משה לוי")))
        .isEmpty();
  }

  @Test
  void punctuationAndSpacingVariantsStillMatchExactly() {
    assertThat(
            suggest(
                ordered("s1", "ג׳אבר בן־חיים", "s2", "אבו  רמה״ש"),
                ordered("p1", "בן-חיים ג'אבר", "p2", "רמה\"ש אבו")))
        .containsExactly(Map.entry("s1", "p1"), Map.entry("s2", "p2"));
  }

  @Test
  void eachScrapedPlayerGetsItsOwnUnambiguousSuggestion() {
    assertThat(
            suggest(
                ordered("s1", "כהן דניאל", "s2", "לוי יוסף משה", "s3", "מזרחי אבירם"),
                ordered("p1", "דניאל כהן", "p2", "יוסף לוי", "p3", "עומר פרץ")))
        .containsExactly(Map.entry("s1", "p1"), Map.entry("s2", "p2"));
  }

  // --- helpers ----------------------------------------------------------------------------------

  private static Map<String, String> suggest(
      Map<String, String> scraped, Map<String, String> candidates) {
    return NameSuggestions.suggest(scraped, candidates);
  }

  private static Map<String, String> ordered(String... keysAndNames) {
    Map<String, String> map = new LinkedHashMap<>();
    for (int i = 0; i < keysAndNames.length; i += 2) {
      map.put(keysAndNames[i], keysAndNames[i + 1]);
    }
    return map;
  }
}
