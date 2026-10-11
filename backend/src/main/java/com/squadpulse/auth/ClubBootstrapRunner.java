package com.squadpulse.auth;

import com.squadpulse.auth.ClubBootstrapService.CreatedClub;
import com.squadpulse.auth.ClubBootstrapService.NewClub;
import com.squadpulse.auth.ClubBootstrapService.UpdatedMapping;
import java.io.Console;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.List;
import java.util.function.IntConsumer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.SpringApplication;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Profile;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Component;

/**
 * One-off command-line task for the system owner (see docs/spec.md section 09, README
 * "Bootstrapping and mapping a club"), in one of two modes, then exits the process:
 *
 * <ul>
 *   <li><b>Create</b> (no {@code --club-id}): creates a new club and its initial Club Manager,
 *       optionally with the club's football.org.il mapping — all three of {@code
 *       --football-team-id}, {@code --football-league-id} and {@code --football-season-id}, or
 *       none.
 *   <li><b>Update the mapping</b> ({@code --club-id}, KAN-65): replaces an existing club's
 *       football.org.il mapping, with all three of those options required. It prompts for no
 *       password, and refuses the create-only options ({@code --club-name}, {@code --manager-*}),
 *       so the two modes can't be mixed by mistake. There's no way to clear a mapping.
 * </ul>
 *
 * <p>Either way the order is the same: secrets passed as options are refused, then the owner secret
 * is prompted for and checked, and only then is anything else read, validated or looked up — so a
 * caller without the secret learns nothing, not even whether a club id exists.
 *
 * <p>Only exists under the {@value #PROFILE} profile, which also switches the web server off (see
 * application-bootstrap.yml) — so it can run alongside the real server, and nothing about it is
 * reachable over the network. It runs in the normal Spring context, reusing the real repositories,
 * {@link PepperedPasswordEncoder} and Bean Validation rules via {@link ClubBootstrapService}.
 *
 * <p>The owner secret and the manager's password are read from an interactive prompt with echo off,
 * never from command-line arguments — those are visible to other local users (e.g. via {@code ps})
 * and end up in shell history. Neither is ever logged.
 */
@Component
@Profile(ClubBootstrapRunner.PROFILE)
class ClubBootstrapRunner implements ApplicationRunner {

  static final String PROFILE = "bootstrap";

  static final String CLUB_NAME = "club-name";
  static final String MANAGER_EMAIL = "manager-email";
  static final String MANAGER_FULL_NAME = "manager-full-name";
  static final String MANAGER_DATE_OF_BIRTH = "manager-date-of-birth";
  static final String CLUB_ID = "club-id";
  static final String FOOTBALL_TEAM_ID = "football-team-id";
  static final String FOOTBALL_LEAGUE_ID = "football-league-id";
  static final String FOOTBALL_SEASON_ID = "football-season-id";

  /** Refused in update mode, so a create command with a stray {@code --club-id} never runs. */
  private static final List<String> CREATE_ONLY_OPTIONS =
      List.of(CLUB_NAME, MANAGER_EMAIL, MANAGER_FULL_NAME, MANAGER_DATE_OF_BIRTH);

  private static final List<String> MAPPING_OPTIONS =
      List.of(FOOTBALL_TEAM_ID, FOOTBALL_LEAGUE_ID, FOOTBALL_SEASON_ID);

  /** Rejected outright, so nobody believes a secret passed this way was used (or safe). */
  private static final List<String> FORBIDDEN_SECRET_OPTIONS =
      List.of("owner-secret", "manager-password");

  static final String OWNER_SECRET_PROMPT = "Owner bootstrap secret";
  static final String MANAGER_PASSWORD_PROMPT = "Initial Club Manager password";
  static final String MANAGER_PASSWORD_REPEAT_PROMPT = "Repeat the Club Manager password";

  static final int EXIT_SUCCESS = 0;
  static final int EXIT_FAILURE = 1;

  private static final Logger log = LoggerFactory.getLogger(ClubBootstrapRunner.class);

  /** Reads one secret without echoing it; returns {@code null} if nothing could be read. */
  @FunctionalInterface
  interface SecretPrompt {
    String read(String label);
  }

  private final ClubBootstrapService clubBootstrapService;
  private final SecretPrompt secretPrompt;
  private final IntConsumer exit;

  @Autowired
  ClubBootstrapRunner(
      ClubBootstrapService clubBootstrapService, ConfigurableApplicationContext context) {
    this(
        clubBootstrapService,
        ClubBootstrapRunner::readFromConsole,
        exitCode -> System.exit(SpringApplication.exit(context, () -> exitCode)));
  }

  /**
   * For tests: {@code secretPrompt} stands in for the terminal, and {@code exit} receives the exit
   * code instead of terminating the JVM.
   */
  ClubBootstrapRunner(
      ClubBootstrapService clubBootstrapService, SecretPrompt secretPrompt, IntConsumer exit) {
    this.clubBootstrapService = clubBootstrapService;
    this.secretPrompt = secretPrompt;
    this.exit = exit;
  }

  @Override
  public void run(ApplicationArguments args) {
    exit.accept(bootstrap(args));
  }

  private int bootstrap(ApplicationArguments args) {
    // Decided from the option's presence alone, before the secret: it only picks the wording of
    // the messages, and reveals nothing.
    boolean updateMapping = args.containsOption(CLUB_ID);
    try {
      for (String option : FORBIDDEN_SECRET_OPTIONS) {
        if (args.containsOption(option)) {
          throw new ClubBootstrapException(
              "--"
                  + option
                  + " is not accepted (command-line arguments aren't secret) — you'll"
                  + " be prompted for it instead");
        }
      }

      String ownerSecret = secretPrompt.read(OWNER_SECRET_PROMPT);
      // Checked before anything else is read or prompted for, so a caller without the secret
      // learns nothing further.
      clubBootstrapService.verifyOwnerSecret(ownerSecret);

      if (updateMapping) {
        return updateMapping(ownerSecret, args);
      }
      // Read and fully validated before the password prompt, so a mistyped mapping doesn't cost
      // a password typed twice.
      FootballOrgIlMapping mapping = mappingOption(args, false);
      if (mapping != null) {
        clubBootstrapService.validateFootballOrgIl(mapping);
      }
      CreatedClub created =
          clubBootstrapService.bootstrap(
              ownerSecret, newClub(args, readManagerPassword(), mapping));
      log.info(
          "Created club '{}' (id {}) with Club Manager {} (user id {}), football.org.il mapping:"
              + " {}",
          created.club().getName(),
          created.club().getId(),
          created.manager().getEmail(),
          created.manager().getId(),
          describe(created.club().getFootballOrgIl()));
      return EXIT_SUCCESS;
    } catch (ClubBootstrapException e) {
      log.error(
          updateMapping
              ? "Club mapping update refused, nothing was written: {}"
              : "Club bootstrap refused, nothing was created: {}",
          e.getMessage());
    } catch (DuplicateKeyException e) {
      log.error(
          "Club bootstrap failed, nothing was created: a user with that email already exists");
    } catch (RuntimeException e) {
      log.error(updateMapping ? "Club mapping update failed" : "Club bootstrap failed", e);
    }
    return EXIT_FAILURE;
  }

  private int updateMapping(String ownerSecret, ApplicationArguments args) {
    for (String option : CREATE_ONLY_OPTIONS) {
      if (args.containsOption(option)) {
        throw new ClubBootstrapException(
            "--"
                + option
                + " can't be combined with --"
                + CLUB_ID
                + ": --"
                + CLUB_ID
                + " only updates an existing club's football.org.il mapping");
      }
    }
    String clubId = requiredOption(args, CLUB_ID);
    UpdatedMapping updated =
        clubBootstrapService.updateFootballOrgIl(ownerSecret, clubId, mappingOption(args, true));
    log.info(
        "Updated the football.org.il mapping of club '{}' (id {}): {} -> {}",
        updated.club().getName(),
        updated.club().getId(),
        describe(updated.previous()),
        describe(updated.club().getFootballOrgIl()));
    return EXIT_SUCCESS;
  }

  private static String describe(FootballOrgIlMapping mapping) {
    return mapping == null ? "none" : mapping.toString();
  }

  /** Asked twice: with echo off, a typo would otherwise lock the new Club Manager out. */
  private String readManagerPassword() {
    String password = secretPrompt.read(MANAGER_PASSWORD_PROMPT);
    String repeated = secretPrompt.read(MANAGER_PASSWORD_REPEAT_PROMPT);
    if (password == null || !password.equals(repeated)) {
      throw new ClubBootstrapException("the two Club Manager passwords don't match");
    }
    return password;
  }

  private static NewClub newClub(
      ApplicationArguments args, String managerPassword, FootballOrgIlMapping mapping) {
    return new NewClub(
        option(args, CLUB_NAME),
        option(args, MANAGER_EMAIL),
        managerPassword,
        option(args, MANAGER_FULL_NAME),
        dateOption(args, MANAGER_DATE_OF_BIRTH),
        mapping);
  }

  /**
   * The football.org.il mapping from its three options: all three or none ({@code null}, allowed
   * only if not {@code required}) — a partial set is refused, so no partial mapping is ever stored.
   * Each must be a whole number; that it's positive is checked by {@link
   * ClubBootstrapService#validateFootballOrgIl}, with the entity's own constraints.
   */
  private static FootballOrgIlMapping mappingOption(ApplicationArguments args, boolean required) {
    List<String> given = MAPPING_OPTIONS.stream().filter(args::containsOption).toList();
    if (given.isEmpty() && !required) {
      return null;
    }
    if (given.size() != MAPPING_OPTIONS.size()) {
      throw new ClubBootstrapException(
          "the football.org.il mapping needs all three of --"
              + String.join(", --", MAPPING_OPTIONS)
              + (required ? "" : ", or none of them"));
    }
    return new FootballOrgIlMapping(
        integerOption(args, FOOTBALL_TEAM_ID),
        integerOption(args, FOOTBALL_LEAGUE_ID),
        integerOption(args, FOOTBALL_SEASON_ID));
  }

  private static Integer integerOption(ApplicationArguments args, String name) {
    String value = requiredOption(args, name);
    try {
      return Integer.valueOf(value);
    } catch (NumberFormatException e) {
      throw new ClubBootstrapException("--" + name + " must be a whole number");
    }
  }

  /**
   * The option's single value. Unlike {@link #option}, given without a value ({@code --name}) or
   * with an empty one ({@code --name=}) is refused rather than read as absent.
   */
  private static String requiredOption(ApplicationArguments args, String name) {
    String value = option(args, name);
    if (value == null || value.isEmpty()) {
      throw new ClubBootstrapException("--" + name + " needs a value");
    }
    return value;
  }

  /** The option's single value, or {@code null} if absent. */
  private static String option(ApplicationArguments args, String name) {
    List<String> values = args.getOptionValues(name);
    if (values == null || values.isEmpty()) {
      return null;
    }
    if (values.size() > 1) {
      throw new ClubBootstrapException("--" + name + " was given more than once");
    }
    return values.getFirst();
  }

  private static LocalDate dateOption(ApplicationArguments args, String name) {
    String value = option(args, name);
    if (value == null) {
      return null;
    }
    try {
      return LocalDate.parse(value);
    } catch (DateTimeParseException e) {
      throw new ClubBootstrapException("--" + name + " must be a date in yyyy-MM-dd format");
    }
  }

  private static String readFromConsole(String label) {
    Console console = System.console();
    if (console == null) {
      throw new ClubBootstrapException(
          "no interactive terminal to prompt on — run it directly in a terminal (java -jar ...),"
              + " not through a pipe, Maven or an IDE run configuration");
    }
    char[] value = console.readPassword("%s: ", label);
    return value == null ? null : new String(value);
  }
}
