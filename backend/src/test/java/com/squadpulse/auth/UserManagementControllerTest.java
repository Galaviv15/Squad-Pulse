package com.squadpulse.auth;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.squadpulse.common.NotFoundException;
import java.time.LocalDate;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.security.autoconfigure.UserDetailsServiceAutoConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/**
 * The HTTP contract of {@link UserManagementController} behind the real security chain, with {@link
 * UserInvitationService} and {@link UserPermissionLevelService} mocked. That both really stay
 * within the caller's club is proven end to end in {@link AuthFlowIntegrationTest}.
 */
@WebMvcTest(
    controllers = UserManagementController.class,
    excludeAutoConfiguration = UserDetailsServiceAutoConfiguration.class,
    properties = {AuthWebMvcTestConfig.JWT_SECRET, AuthWebMvcTestConfig.PASSWORD_PEPPER})
@Import(AuthWebMvcTestConfig.class)
class UserManagementControllerTest {

  private static final String VALID_BODY =
      """
      {"email": "coach@example.com", "fullName": "Dana Levi", "title": "HEAD_COACH",
       "permissionLevel": "EDIT_FULL", "dateOfBirth": "1985-03-01"}
      """;

  private static final String LEVEL_BODY = "{\"permissionLevel\": \"EDIT_PARTIAL\"}";

  @Autowired private MockMvc mockMvc;
  @Autowired private JwtService jwtService;
  @MockitoBean private UserInvitationService userInvitationService;
  @MockitoBean private UserPermissionLevelService userPermissionLevelService;
  @MockitoBean private StaffPhotoService staffPhotoService;

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
        .andExpect(jsonPath("$.hasPhoto").value(false))
        .andExpect(content().string(not(containsString("password"))));
    // A new user can't have a photo yet: no storage query.
    verifyNoInteractions(staffPhotoService);
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

  @Test
  void anAdminChangesAUsersPermissionLevelAndGets200WithoutAnyPasswordField() throws Exception {
    User updated = new User();
    updated.setId("user-2");
    updated.setEmail("analyst@example.com");
    updated.setFullName("Noa Cohen");
    updated.setTitle(Title.ANALYST);
    updated.setPermissionLevel(PermissionLevel.EDIT_PARTIAL);
    updated.setPasswordHash("hash-that-must-not-leak");
    when(userPermissionLevelService.changePermissionLevel(any(), any(), any())).thenReturn(updated);

    mockMvc
        .perform(changePermissionLevel(PermissionLevel.ADMIN, "user-2", LEVEL_BODY))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.id").value("user-2"))
        .andExpect(jsonPath("$.email").value("analyst@example.com"))
        .andExpect(jsonPath("$.title").value("ANALYST"))
        .andExpect(jsonPath("$.permissionLevel").value("EDIT_PARTIAL"))
        .andExpect(jsonPath("$.hasPhoto").value(false))
        .andExpect(content().string(not(containsString("password"))))
        .andExpect(content().string(not(containsString("hash-that-must-not-leak"))));
    verify(userPermissionLevelService)
        .changePermissionLevel(
            "user-2",
            PermissionLevel.EDIT_PARTIAL,
            new AuthenticatedUser("user-1", "club-a", PermissionLevel.ADMIN));
    verify(staffPhotoService).hasPhoto(updated);
  }

  @Test
  void aPermissionLevelChangeReportsTheTargetsPhoto() throws Exception {
    User updated = new User();
    updated.setId("user-2");
    updated.setPermissionLevel(PermissionLevel.EDIT_PARTIAL);
    when(userPermissionLevelService.changePermissionLevel(any(), any(), any())).thenReturn(updated);
    when(staffPhotoService.hasPhoto(updated)).thenReturn(true);

    mockMvc
        .perform(changePermissionLevel(PermissionLevel.ADMIN, "user-2", LEVEL_BODY))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.hasPhoto").value(true));
  }

  @Test
  void aNonAdminGets403WhenChangingAPermissionLevel() throws Exception {
    for (PermissionLevel level :
        new PermissionLevel[] {
          PermissionLevel.EDIT_FULL, PermissionLevel.EDIT_PARTIAL, PermissionLevel.VIEW_ONLY
        }) {
      mockMvc
          .perform(changePermissionLevel(level, "user-2", LEVEL_BODY))
          .andExpect(status().isForbidden())
          .andExpect(jsonPath("$.message").value("Access denied"));
    }
    verify(userPermissionLevelService, never()).changePermissionLevel(any(), any(), any());
  }

  @Test
  void changingAPermissionLevelWithoutAnAccessTokenIs401() throws Exception {
    mockMvc
        .perform(
            patch("/auth/users/user-2/permission-level")
                .contentType(MediaType.APPLICATION_JSON)
                .content(LEVEL_BODY))
        .andExpect(status().isUnauthorized());
    verify(userPermissionLevelService, never()).changePermissionLevel(any(), any(), any());
  }

  @Test
  void rejectsAMissingNullOrUnknownPermissionLevelWith400() throws Exception {
    String[] invalidBodies = {
      "", "{}", "{\"permissionLevel\": null}", "{\"permissionLevel\": \"SUPERUSER\"}",
    };
    for (String body : invalidBodies) {
      mockMvc
          .perform(changePermissionLevel(PermissionLevel.ADMIN, "user-2", body))
          .andExpect(status().isBadRequest());
    }
    verify(userPermissionLevelService, never()).changePermissionLevel(any(), any(), any());
  }

  @Test
  void changingYourOwnPermissionLevelIs409() throws Exception {
    when(userPermissionLevelService.changePermissionLevel(eq("user-1"), any(), any()))
        .thenThrow(new CannotChangeOwnPermissionLevelException());

    mockMvc
        .perform(changePermissionLevel(PermissionLevel.ADMIN, "user-1", LEVEL_BODY))
        .andExpect(status().isConflict())
        .andExpect(
            jsonPath("$.message")
                .value("You can't change your own permission level; another ADMIN must do it"));
  }

  @Test
  void anUnknownUserIs404() throws Exception {
    when(userPermissionLevelService.changePermissionLevel(anyString(), any(), any()))
        .thenThrow(new NotFoundException("User not found"));

    mockMvc
        .perform(changePermissionLevel(PermissionLevel.ADMIN, "no-such-user", LEVEL_BODY))
        .andExpect(status().isNotFound())
        .andExpect(jsonPath("$.message").value("User not found"));
  }

  /** Every retry lost a race (KAN-24): a generic 409, not a 500. */
  @Test
  void aPermissionChangeWhoseRetriesAllConflictedIs409() throws Exception {
    when(userPermissionLevelService.changePermissionLevel(anyString(), any(), any()))
        .thenThrow(
            new OptimisticLockingFailureException("Cannot save entity user-2 with version 3"));

    mockMvc
        .perform(changePermissionLevel(PermissionLevel.ADMIN, "user-2", LEVEL_BODY))
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.error").value("Conflict"))
        .andExpect(
            jsonPath("$.message").value("The resource was modified concurrently, please retry"));
  }

  private MockHttpServletRequestBuilder invite(PermissionLevel callerLevel, String body) {
    return post("/auth/users/invite")
        .header("Authorization", "Bearer " + jwtService.issue(caller(callerLevel)).value())
        .contentType(MediaType.APPLICATION_JSON)
        .content(body);
  }

  private MockHttpServletRequestBuilder changePermissionLevel(
      PermissionLevel callerLevel, String targetId, String body) {
    return patch("/auth/users/" + targetId + "/permission-level")
        .header("Authorization", "Bearer " + jwtService.issue(caller(callerLevel)).value())
        .contentType(MediaType.APPLICATION_JSON)
        .content(body);
  }

  private static User caller(PermissionLevel callerLevel) {
    User caller = new User();
    caller.setId("user-1");
    caller.setClubId("club-a");
    caller.setPermissionLevel(callerLevel);
    return caller;
  }
}
