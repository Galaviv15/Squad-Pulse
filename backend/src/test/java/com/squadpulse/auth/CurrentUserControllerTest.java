package com.squadpulse.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.security.autoconfigure.UserDetailsServiceAutoConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/**
 * The HTTP contract of {@link CurrentUserController} behind the real security chain, with {@link
 * CurrentUserService} mocked. What the service reads from the database, and that it stays within
 * the token's club, is proven end to end in {@link CurrentUserIntegrationTest}.
 */
@WebMvcTest(
    controllers = CurrentUserController.class,
    excludeAutoConfiguration = UserDetailsServiceAutoConfiguration.class,
    properties = {AuthWebMvcTestConfig.JWT_SECRET, AuthWebMvcTestConfig.PASSWORD_PEPPER})
@Import(AuthWebMvcTestConfig.class)
class CurrentUserControllerTest {

  @Autowired private MockMvc mockMvc;
  @Autowired private JwtService jwtService;
  @MockitoBean private CurrentUserService currentUserService;

  @Test
  void everyPermissionLevelGets200WithItsTokensLevel() throws Exception {
    // The mock answers like the real service: the level comes from the caller it's handed.
    when(currentUserService.currentUser(any()))
        .thenAnswer(
            invocation ->
                CurrentUserResponse.from(
                    storedUser(),
                    invocation.<AuthenticatedUser>getArgument(0).permissionLevel(),
                    false,
                    club(),
                    false));

    for (PermissionLevel level : PermissionLevel.values()) {
      mockMvc
          .perform(me(level))
          .andExpect(status().isOk())
          .andExpect(jsonPath("$.permissionLevel").value(level.name()));
      verify(currentUserService).currentUser(new AuthenticatedUser("user-1", "club-a", level));
    }
  }

  @Test
  void mapsEveryFieldInUserResponsesOrderPlusTheClub() throws Exception {
    when(currentUserService.currentUser(any()))
        .thenReturn(
            CurrentUserResponse.from(storedUser(), PermissionLevel.EDIT_FULL, true, club(), true));

    MvcResult result =
        mockMvc
            .perform(me(PermissionLevel.EDIT_FULL))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.id").value("user-1"))
            .andExpect(jsonPath("$.email").value("coach@example.com"))
            .andExpect(jsonPath("$.fullName").value("Dana Levi"))
            .andExpect(jsonPath("$.title").value("HEAD_COACH"))
            .andExpect(jsonPath("$.permissionLevel").value("EDIT_FULL"))
            .andExpect(jsonPath("$.dateOfBirth").value("1985-03-01"))
            .andExpect(jsonPath("$.active").value(true))
            .andExpect(jsonPath("$.hasPhoto").value(true))
            .andExpect(jsonPath("$.club.id").value("club-a"))
            .andExpect(jsonPath("$.club.name").value("Hapoel Example"))
            .andExpect(jsonPath("$.club.hasLogo").value(true))
            .andReturn();

    String body = result.getResponse().getContentAsString();
    assertThat(JsonPath.<Map<String, Object>>read(body, "$").keySet())
        .containsExactly(
            "id",
            "email",
            "fullName",
            "title",
            "permissionLevel",
            "dateOfBirth",
            "active",
            "hasPhoto",
            "club");
    assertThat(JsonPath.<Map<String, Object>>read(body, "$.club").keySet())
        .containsExactly("id", "name", "hasLogo");
    assertThat(body).doesNotContain("password", "hash-that-must-not-leak");
  }

  @Test
  void withoutAnAccessTokenIs401() throws Exception {
    mockMvc
        .perform(get("/auth/users/me"))
        .andExpect(status().isUnauthorized())
        .andExpect(jsonPath("$.message").value("Authentication required"));
    verify(currentUserService, never()).currentUser(any());
  }

  @Test
  void withAnInvalidAccessTokenIs401() throws Exception {
    for (String header :
        List.of("Bearer not-a-token", "Bearer " + tamperedToken(), "Basic dXNlcjpwYXNz")) {
      mockMvc
          .perform(get("/auth/users/me").header(HttpHeaders.AUTHORIZATION, header))
          .andExpect(status().isUnauthorized())
          .andExpect(jsonPath("$.message").value("Authentication required"));
    }
    verify(currentUserService, never()).currentUser(any());
  }

  /**
   * The 401 the service raises for a deactivated or missing user goes through {@code
   * GlobalExceptionHandler}, the one for no token through the security entry point: a client must
   * not be able to tell them apart.
   */
  @Test
  void anUnavailableUsersBodyAndContentTypeEqualThoseOfNoToken() throws Exception {
    when(currentUserService.currentUser(any())).thenThrow(new CurrentUserUnavailableException());

    MvcResult unavailable =
        mockMvc.perform(me(PermissionLevel.ADMIN)).andExpect(status().isUnauthorized()).andReturn();
    MvcResult noToken =
        mockMvc.perform(get("/auth/users/me")).andExpect(status().isUnauthorized()).andReturn();

    assertThat(withoutTimestamp(unavailable)).isEqualTo(withoutTimestamp(noToken));
    assertThat(unavailable.getResponse().getContentType())
        .isEqualTo(noToken.getResponse().getContentType());
  }

  private MockHttpServletRequestBuilder me(PermissionLevel level) {
    User caller = new User();
    caller.setId("user-1");
    caller.setClubId("club-a");
    caller.setPermissionLevel(level);
    return get("/auth/users/me")
        .header(HttpHeaders.AUTHORIZATION, "Bearer " + jwtService.issue(caller).value());
  }

  /** A genuine token with its signature's last character changed. */
  private String tamperedToken() {
    User caller = new User();
    caller.setId("user-1");
    caller.setClubId("club-a");
    caller.setPermissionLevel(PermissionLevel.ADMIN);
    String token = jwtService.issue(caller).value();
    char last = token.charAt(token.length() - 1);
    return token.substring(0, token.length() - 1) + (last == 'A' ? 'B' : 'A');
  }

  private static User storedUser() {
    User user = new User();
    user.setId("user-1");
    user.setClubId("club-a");
    user.setEmail("coach@example.com");
    user.setFullName("Dana Levi");
    user.setTitle(Title.HEAD_COACH);
    user.setPermissionLevel(PermissionLevel.VIEW_ONLY);
    user.setDateOfBirth(LocalDate.of(1985, 3, 1));
    user.setPasswordHash("hash-that-must-not-leak");
    return user;
  }

  private static Club club() {
    Club club = new Club();
    club.setId("club-a");
    club.setName("Hapoel Example");
    return club;
  }

  private static Map<String, Object> withoutTimestamp(MvcResult result) throws Exception {
    Map<String, Object> fields =
        new HashMap<>(
            JsonPath.<Map<String, Object>>read(result.getResponse().getContentAsString(), "$"));
    assertThat(fields.remove("timestamp")).as("timestamp").isNotNull();
    return fields;
  }
}
