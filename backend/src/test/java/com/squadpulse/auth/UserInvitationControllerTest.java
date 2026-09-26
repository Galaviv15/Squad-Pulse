package com.squadpulse.auth;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.LocalDate;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.security.autoconfigure.UserDetailsServiceAutoConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/**
 * The HTTP contract of {@link UserInvitationController} behind the real security chain, with {@link
 * UserInvitationService} mocked. That the user really lands in the caller's club is proven end to
 * end in {@link AuthFlowIntegrationTest}.
 */
@WebMvcTest(
    controllers = UserInvitationController.class,
    excludeAutoConfiguration = UserDetailsServiceAutoConfiguration.class,
    properties = {AuthWebMvcTestConfig.JWT_SECRET, AuthWebMvcTestConfig.PASSWORD_PEPPER})
@Import(AuthWebMvcTestConfig.class)
class UserInvitationControllerTest {

  private static final String VALID_BODY =
      """
      {"email": "coach@example.com", "fullName": "Dana Levi", "title": "HEAD_COACH",
       "permissionLevel": "EDIT_FULL", "dateOfBirth": "1985-03-01"}
      """;

  @Autowired private MockMvc mockMvc;
  @Autowired private JwtService jwtService;
  @MockitoBean private UserInvitationService userInvitationService;

  @Test
  void anAdminInvitesAUserAndGets201WithoutAnyPasswordField() throws Exception {
    User created = new User();
    created.setId("user-2");
    created.setEmail("coach@example.com");
    created.setFullName("Dana Levi");
    created.setTitle(Title.HEAD_COACH);
    created.setPermissionLevel(PermissionLevel.EDIT_FULL);
    created.setDateOfBirth(LocalDate.of(1985, 3, 1));
    when(userInvitationService.invite(any())).thenReturn(created);

    mockMvc
        .perform(invite(PermissionLevel.ADMIN, VALID_BODY))
        .andExpect(status().isCreated())
        .andExpect(jsonPath("$.id").value("user-2"))
        .andExpect(jsonPath("$.email").value("coach@example.com"))
        .andExpect(jsonPath("$.title").value("HEAD_COACH"))
        .andExpect(jsonPath("$.permissionLevel").value("EDIT_FULL"))
        .andExpect(jsonPath("$.dateOfBirth").value("1985-03-01"))
        .andExpect(jsonPath("$.active").value(true))
        .andExpect(content().string(not(containsString("password"))));
  }

  @Test
  void aNonAdminGets403AndNothingIsCreated() throws Exception {
    for (PermissionLevel level :
        new PermissionLevel[] {
          PermissionLevel.EDIT_FULL, PermissionLevel.EDIT_PARTIAL, PermissionLevel.VIEW_ONLY
        }) {
      mockMvc
          .perform(invite(level, VALID_BODY))
          .andExpect(status().isForbidden())
          .andExpect(jsonPath("$.message").value("Access denied"));
    }
    verify(userInvitationService, never()).invite(any());
  }

  @Test
  void withoutAnAccessTokenIs401() throws Exception {
    mockMvc
        .perform(
            post("/auth/users/invite").contentType(MediaType.APPLICATION_JSON).content(VALID_BODY))
        .andExpect(status().isUnauthorized());
    verify(userInvitationService, never()).invite(any());
  }

  @Test
  void rejectsAnInvalidBodyWith400() throws Exception {
    String[] invalidBodies = {
      // Not an email.
      VALID_BODY.replace("coach@example.com", "not-an-email"),
      // Missing title.
      VALID_BODY.replace("\"title\": \"HEAD_COACH\",", ""),
      // Unknown permission level.
      VALID_BODY.replace("EDIT_FULL", "SUPERUSER"),
      // Under 18.
      VALID_BODY.replace("1985-03-01", LocalDate.now().minusYears(10).toString()),
      // Not a date.
      VALID_BODY.replace("1985-03-01", "01/03/1985"),
    };
    for (String body : invalidBodies) {
      mockMvc.perform(invite(PermissionLevel.ADMIN, body)).andExpect(status().isBadRequest());
    }
    verify(userInvitationService, never()).invite(any());
  }

  @Test
  void aTakenEmailIs409() throws Exception {
    when(userInvitationService.invite(any())).thenThrow(new EmailAlreadyRegisteredException());

    mockMvc
        .perform(invite(PermissionLevel.ADMIN, VALID_BODY))
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.message").value("A user with this email already exists"));
  }

  private MockHttpServletRequestBuilder invite(PermissionLevel callerLevel, String body) {
    User caller = new User();
    caller.setId("user-1");
    caller.setClubId("club-a");
    caller.setPermissionLevel(callerLevel);
    return post("/auth/users/invite")
        .header("Authorization", "Bearer " + jwtService.issue(caller).value())
        .contentType(MediaType.APPLICATION_JSON)
        .content(body);
  }
}
