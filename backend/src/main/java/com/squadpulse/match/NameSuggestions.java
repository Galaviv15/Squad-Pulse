package com.squadpulse.match;

import java.text.Normalizer;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * Which of the club's players a scraped player probably is, by name (KAN-72): a suggestion for the
 * user to confirm, never applied by itself. Pure; computed on every read, nothing stored.
 *
 * <p><b>Normalising</b> a name, for matching only (never stored): Unicode NFC; every geresh-like
 * character ({@code ׳ ' ’ ‘ ` ´}) becomes one, every gershayim-like one ({@code ״ " “ ”}) another;
 * hyphen and maqaf ({@code - ־}) become a space; any run of whitespace, no-break spaces included,
 * becomes one space; trimmed; lower-cased (only Latin letters have case here). NFC alone doesn't
 * unify these look-alikes: they're distinct characters, not canonical equivalents. The name is then
 * a <b>set of words</b>, so word order doesn't matter (the site's table puts the surname first, the
 * squad usually doesn't).
 *
 * <p><b>Matching</b> a scraped player's words A against a candidate's words B: <b>exact</b> if A =
 * B; <b>partial</b> if one contains the other and they share at least two words (a middle name on
 * one side only). A scraped player's candidates are its exact matches if it has any, otherwise its
 * partial ones. A candidate is suggested only if it's the scraped player's <b>only</b> candidate
 * <b>and</b> that scraped player is the only one whose candidates include it. Anything ambiguous →
 * no suggestion. An empty name matches nothing.
 */
final class NameSuggestions {

  private static final Pattern GERESH = Pattern.compile("[׳'’‘`´]");
  private static final Pattern GERSHAYIM = Pattern.compile("[״\"“”]");
  private static final Pattern HYPHEN = Pattern.compile("[-־]");

  /** Whitespace and space separators (no-break spaces included). */
  private static final Pattern SPACES = Pattern.compile("[\\s\\p{Z}]+");

  private NameSuggestions() {}

  /**
   * @param scrapedNames by the scraped player's key
   * @param candidateNames by the candidate's key: the players that may be suggested (not linked)
   * @return the suggested candidate's key by scraped player's key, only for those with one
   */
  static <S, C> Map<S, C> suggest(Map<S, String> scrapedNames, Map<C, String> candidateNames) {
    Map<C, Set<String>> candidateWords = new LinkedHashMap<>();
    candidateNames.forEach((key, name) -> candidateWords.put(key, words(name)));

    Map<S, List<C>> candidatesOf = new LinkedHashMap<>();
    Map<C, Integer> timesACandidate = new HashMap<>();
    scrapedNames.forEach(
        (key, name) -> {
          List<C> candidates = candidates(words(name), candidateWords);
          candidatesOf.put(key, candidates);
          candidates.forEach(candidate -> timesACandidate.merge(candidate, 1, Integer::sum));
        });

    Map<S, C> suggestions = new LinkedHashMap<>();
    candidatesOf.forEach(
        (key, candidates) -> {
          if (candidates.size() == 1 && timesACandidate.get(candidates.getFirst()) == 1) {
            suggestions.put(key, candidates.getFirst());
          }
        });
    return suggestions;
  }

  /** The exact matches if there are any, otherwise the partial ones. */
  private static <C> List<C> candidates(Set<String> words, Map<C, Set<String>> candidateWords) {
    if (words.isEmpty()) {
      return List.of();
    }
    List<C> exact =
        candidateWords.entrySet().stream()
            .filter(entry -> entry.getValue().equals(words))
            .map(Map.Entry::getKey)
            .toList();
    if (!exact.isEmpty()) {
      return exact;
    }
    return candidateWords.entrySet().stream()
        .filter(entry -> isPartial(words, entry.getValue()))
        .map(Map.Entry::getKey)
        .toList();
  }

  /** One contains the other, and they share at least two words. */
  private static boolean isPartial(Set<String> a, Set<String> b) {
    Set<String> smaller = a.size() <= b.size() ? a : b;
    Set<String> larger = smaller == a ? b : a;
    return smaller.size() >= 2 && larger.containsAll(smaller);
  }

  /** The normalised name's words; empty for a blank name. */
  static Set<String> words(String name) {
    String normalized = normalize(name);
    if (normalized.isEmpty()) {
      return Set.of();
    }
    return Arrays.stream(normalized.split(" ")).collect(Collectors.toCollection(HashSet::new));
  }

  /** See the class comment. */
  static String normalize(String name) {
    if (name == null) {
      return "";
    }
    String normalized = Normalizer.normalize(name, Normalizer.Form.NFC);
    normalized = GERESH.matcher(normalized).replaceAll("'");
    normalized = GERSHAYIM.matcher(normalized).replaceAll("\"");
    normalized = HYPHEN.matcher(normalized).replaceAll(" ");
    normalized = SPACES.matcher(normalized).replaceAll(" ").strip();
    return normalized.toLowerCase(Locale.ROOT);
  }
}
