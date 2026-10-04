package com.squadpulse.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import com.squadpulse.common.NotFoundException;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.BiFunction;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.security.autoconfigure.UserDetailsServiceAutoConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultMatcher;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/**
 * The HTTP contract of {@link UserManagementController} behind the real security chain, with {@link
 * StaffListService}, {@link UserInvitationService}, {@link UserPermissionLevelService} and {@link
 * UserActivationService} mocked. That they really stay within the caller's club is proven end to
 * end in {@link StaffListIntegrationTest}, {@link AuthFlowIntegrationTest} and {@link
 * UserActivationIntegrationTest}.
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
  @MockitoBean private StaffListService staffListService;
  @MockitoBean private UserInvitationService userInvitationService;
  @MockitoBean private UserPermissionLevelService userPermissionLevelService;
  @MockitoBean private UserActivationService userActivationService;
  @MockitoBean private StaffPhotoService staffPhotoService;

  // --- GET /auth/users ---------------------------------------------------------------------------

  /**
   * Field names and order as Jackson 3 writes the {@link UserResponse} record: its component order,
   * with {@code activated} last. Nothing internal to {@link User} leaks.
   */
  @Test
  void anAdminGetsTheStaffListAsAPlainArrayWithEveryUserField() throws Exception {
    User activated = new User();
    activated.setId("user-1");
    activated.setClubId("club-a");
    activated.setEmail("manager@example.com");
    activated.setFullName("Dana Levi");
    activated.setTitle(Title.CLUB_MANAGER);
    activated.setPermissionLevel(PermissionLevel.ADMIN);
    activated.setDateOfBirth(LocalDate.of(1985, 3, 1));
    activated.setPasswordHash("hash-that-must-not-leak");
    User invited = new User();
    invited.setId("user-2");
    invited.setClubId("club-a");
    invited.setEmail("analyst@example.com");
    invited.setFullName("Noa Cohen");
    invited.setTitle(Title.ANALYST);
    invited.setPermissionLevel(PermissionLevel.VIEW_ONLY);
    invited.setActive(false);
    when(staffListService.list())
        .thenReturn(List.of(UserResponse.from(activated, true), UserResponse.from(invited, false)));

    String body =
        mockMvc
            .perform(list(PermissionLevel.ADMIN))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.length()").value(2))
            .andExpect(jsonPath("$[0].id").value("user-1"))
            .andExpect(jsonPath("$[0].email").value("manager@example.com"))
            .andExpect(jsonPath("$[0].fullName").value("Dana Levi"))
            .andExpect(jsonPath("$[0].title").value("CLUB_MANAGER"))
            .andExpect(jsonPath("$[0].permissionLevel").value("ADMIN"))
            .andExpect(jsonPath("$[0].dateOfBirth").value("1985-03-01"))
            .andExpect(jsonPath("$[0].active").value(true))
            .andExpect(jsonPath("$[0].hasPhoto").value(true))
            .andExpect(jsonPath("$[0].activated").value(true))
            .andExpect(jsonPath("$[1].id").value("user-2"))
            .andExpect(jsonPath("$[1].dateOfBirth").isEmpty())
            .andExpect(jsonPath("$[1].active").value(false))
            .andExpect(jsonPath("$[1].hasPhoto").value(false))
            .andExpect(jsonPath("$[1].activated").value(false))
            .andReturn()
            .getResponse()
            .getContentAsString();

    for (String user : List.of("$[0]", "$[1]")) {
      assertThat(JsonPath.<Map<String, Object>>read(body, user).keySet())
          .containsExactly(
              "id",
              "email",
              "fullName",
              "title",
              "permissionLevel",
              "dateOfBirth",
              "active",
              "hasPhoto",
              "activated");
    }
    assertThat(body)
        .doesNotContain(
            "password",
            "hash-that-must-not-leak",
            "version",
            "clubId",
            "club-a",
            "sessionsInvalidatedAt",
            "createdAt",
            "updatedAt");
  }

  @Test
  void anEmptyClubListIsAnEmptyArray() throws Exception {
    when(staffListService.list()).thenReturn(List.of());

    mockMvc
        .perform(list(PermissionLevel.ADMIN))
        .andExpect(status().isOk())
        .andExpect(content().json("[]", true));
  }

  @Test
  void aNonAdminGets403ForTheStaffList() throws Exception {
    for (PermissionLevel level :
        new PermissionLevel[] {
          PermissionLevel.EDIT_FULL, PermissionLevel.EDIT_PARTIAL, PermissionLevel.VIEW_ONLY
        }) {
      mockMvc
          .perform(list(level))
          .andExpect(status().isForbidden())
          .andExpect(jsonPath("$.message").value("Access denied"));
    }
    verifyNoInteractions(staffListService);
  }

  @Test
  void theStaffListWithoutAnAccessTokenIs401() throws Exception {
    mockMvc
        .perform(get("/auth/users"))
        .andExpect(status().isUnauthorized())
        .andExpect(jsonPath("$.message").value("Authentication required"));
    verifyNoInteractions(staffListService);
  }

  // --- POST /auth/users/invite
  // --------------------------------------------------------------------

  @Test
  void anAdminInvitesAUserAndGets201WithoutAnyPasswordField() throws Exception {
    User created = new User();
    created.setId("user-2");
    created.setEmail("coach@example.com");
    created.setFullName("Dana Levi");
    created.setTitle(Title.HEAD_COACH);
    created.setPermissionLevel(PermissionLevel.EDIT_FULL);
    created.setDateOfBirth(LocalDate.of(1985, 3, 1));
    when(userInvitationService.invite(any(), any())).thenReturn(created);

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
        .andExpect(jsonPath("$.activated").value(false))
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
    verify(userInvitationService, never()).invite(any(), any());
  }

  @Test
  void withoutAnAccessTokenIs401() throws Exception {
    mockMvc
        .perform(
            post("/auth/users/invite").contentType(MediaType.APPLICATION_JSON).content(VALID_BODY))
        .andExpect(status().isUnauthorized());
    verify(userInvitationService, never()).invite(any(), any());
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
    verify(userInvitationService, never()).invite(any(), any());
  }

  @Test
  void aTakenEmailIs409() throws Exception {
    when(userInvitationService.invite(any(), any()))
        .thenThrow(new EmailAlreadyRegisteredException());

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
        .andExpect(jsonPath("$.activated").value(true))
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

  /** An admin may change the level of an invited user who hasn't set a password yet. */
  @Test
  void aPermissionLevelChangeOnANotYetActivatedUserReportsActivatedFalse() throws Exception {
    User invited = new User();
    invited.setId("user-2");
    invited.setPermissionLevel(PermissionLevel.EDIT_PARTIAL);
    when(userPermissionLevelService.changePermissionLevel(any(), any(), any())).thenReturn(invited);

    mockMvc
        .perform(changePermissionLevel(PermissionLevel.ADMIN, "user-2", LEVEL_BODY))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.activated").value(false));
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

  /** The caller re-check (KAN-37): the generic 401, the same body as a request without a token. */
  @Test
  void aCallerWhoCanNoLongerActGetsTheGeneric401OnInviteAndPermissionLevel() throws Exception {
    when(userInvitationService.invite(any(), any()))
        .thenThrow(new CurrentUserUnavailableException());
    when(userPermissionLevelService.changePermissionLevel(any(), any(), any()))
        .thenThrow(new CurrentUserUnavailableException());

    for (MockHttpServletRequestBuilder request :
        List.of(
            invite(PermissionLevel.ADMIN, VALID_BODY),
            changePermissionLevel(PermissionLevel.ADMIN, "user-2", LEVEL_BODY))) {
      assertGeneric401(request);
    }
    verify(userInvitationService)
        .invite(any(), eq(new AuthenticatedUser("user-1", "club-a", PermissionLevel.ADMIN)));
  }

  // --- POST /auth/users/{id}/deactivate and /reactivate ------------------------------------------

  /** Both endpoints, as (path segment, service call) pairs, for the contract they share. */
  private static final List<String> ACTIONS = List.of("deactivate", "reactivate");

  private BiFunction<String, AuthenticatedUser, User> serviceCall(String action) {
    return action.equals("deactivate")
        ? userActivationService::deactivate
        : userActivationService::reactivate;
  }

  @Test
  void anAdminDeactivatesAUserAndGetsTheUserResponse() throws Exception {
    User deactivated = target(false);
    when(userActivationService.deactivate(any(), any())).thenReturn(deactivated);
    when(staffPhotoService.hasPhoto(deactivated)).thenReturn(true);

    String body =
        mockMvc
            .perform(activation(PermissionLevel.ADMIN, "deactivate", "user-2"))
            .andExpect(status().isOk())
            .andExpect(
                content()
                    .json(
                        """
                        {"id": "user-2", "email": "analyst@example.com", "fullName": "Noa Cohen",
                         "title": "ANALYST", "permissionLevel": "ADMIN",
                         "dateOfBirth": "1990-06-15", "active": false, "hasPhoto": true,
                         "activated": true}
                        """,
                        true))
            .andReturn()
            .getResponse()
            .getContentAsString();

    assertThat(body).doesNotContain("password", "hash-that-must-not-leak", "sessionsInvalidatedAt");
    verify(userActivationService)
        .deactivate("user-2", new AuthenticatedUser("user-1", "club-a", PermissionLevel.ADMIN));
    verifyNoMoreInteractions(userActivationService);
  }

  @Test
  void anAdminReactivatesAUserAndGetsTheUserResponse() throws Exception {
    User reactivated = target(true);
    reactivated.setPasswordHash(null);
    when(userActivationService.reactivate(any(), any())).thenReturn(reactivated);

    mockMvc
        .perform(activation(PermissionLevel.ADMIN, "reactivate", "user-2"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.id").value("user-2"))
        .andExpect(jsonPath("$.active").value(true))
        .andExpect(jsonPath("$.hasPhoto").value(false))
        .andExpect(jsonPath("$.activated").value(false));
    verify(userActivationService)
        .reactivate("user-2", new AuthenticatedUser("user-1", "club-a", PermissionLevel.ADMIN));
    verifyNoMoreInteractions(userActivationService);
    verify(staffPhotoService).hasPhoto(reactivated);
  }

  /** No body is needed, and any body sent is ignored rather than parsed. */
  @Test
  void aBodyIsIgnored() throws Exception {
    when(userActivationService.deactivate(any(), any())).thenReturn(target(false));

    mockMvc
        .perform(
            activation(PermissionLevel.ADMIN, "deactivate", "user-2")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"active\": true, \"clubId\": \"club-b\"}"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.active").value(false));
  }

  @Test
  void aNonAdminGets403AndNothingChanges() throws Exception {
    for (String action : ACTIONS) {
      for (PermissionLevel level :
          new PermissionLevel[] {
            PermissionLevel.EDIT_FULL, PermissionLevel.EDIT_PARTIAL, PermissionLevel.VIEW_ONLY
          }) {
        mockMvc
            .perform(activation(level, action, "user-2"))
            .andExpect(status().isForbidden())
            .andExpect(errorBody(403, "Forbidden", "Access denied"));
      }
    }
    verifyNoInteractions(userActivationService, staffPhotoService);
  }

  @Test
  void withoutAnAccessTokenDeactivateAndReactivateAre401() throws Exception {
    for (String action : ACTIONS) {
      mockMvc
          .perform(post("/auth/users/user-2/" + action))
          .andExpect(status().isUnauthorized())
          .andExpect(errorBody(401, "Unauthorized", "Authentication required"));
    }
    verifyNoInteractions(userActivationService, staffPhotoService);
  }

  @Test
  void anUnknownUserIs404OnDeactivateAndReactivate() throws Exception {
    when(userActivationService.deactivate(any(), any()))
        .thenThrow(new NotFoundException("User not found"));
    when(userActivationService.reactivate(any(), any()))
        .thenThrow(new NotFoundException("User not found"));

    for (String action : ACTIONS) {
      mockMvc
          .perform(activation(PermissionLevel.ADMIN, action, "no-such-user"))
          .andExpect(status().isNotFound())
          .andExpect(errorBody(404, "Not Found", "User not found"));
    }
    verifyNoInteractions(staffPhotoService);
  }

  @Test
  void deactivatingOrReactivatingYourselfIs409() throws Exception {
    when(userActivationService.deactivate(eq("user-1"), any()))
        .thenThrow(new CannotChangeOwnActiveStatusException());
    when(userActivationService.reactivate(eq("user-1"), any()))
        .thenThrow(new CannotChangeOwnActiveStatusException());

    for (String action : ACTIONS) {
      mockMvc
          .perform(activation(PermissionLevel.ADMIN, action, "user-1"))
          .andExpect(status().isConflict())
          .andExpect(
              errorBody(
                  409,
                  "Conflict",
                  "You can't deactivate or reactivate yourself; another ADMIN must do it"));
    }
  }

  @Test
  void aCallerWhoCanNoLongerActGetsTheGeneric401() throws Exception {
    when(userActivationService.deactivate(any(), any()))
        .thenThrow(new CurrentUserUnavailableException());
    when(userActivationService.reactivate(any(), any()))
        .thenThrow(new CurrentUserUnavailableException());

    for (String action : ACTIONS) {
      assertGeneric401(activation(PermissionLevel.ADMIN, action, "user-2"));
    }
    verifyNoInteractions(staffPhotoService);
  }

  /** Every retry lost a race (KAN-24): a generic 409, not a 500. */
  @Test
  void anActivationChangeWhoseRetriesAllConflictedIs409() throws Exception {
    for (String action : ACTIONS) {
      when(serviceCall(action).apply(anyString(), any()))
          .thenThrow(new OptimisticLockingFailureException("Cannot save entity user-2"));

      mockMvc
          .perform(activation(PermissionLevel.ADMIN, action, "user-2"))
          .andExpect(status().isConflict())
          .andExpect(
              errorBody(409, "Conflict", "The resource was modified concurrently, please retry"));
    }
  }

  /** Only POST is mapped. */
  @Test
  void otherMethodsAre405() throws Exception {
    for (String action : ACTIONS) {
      mockMvc
          .perform(
              get("/auth/users/user-2/" + action)
                  .header(
                      "Authorization",
                      "Bearer " + jwtService.issue(caller(PermissionLevel.ADMIN)).value()))
          .andExpect(status().isMethodNotAllowed());
    }
    verifyNoInteractions(userActivationService);
  }

  private void assertGeneric401(MockHttpServletRequestBuilder request) throws Exception {
    mockMvc
        .perform(request)
        .andExpect(status().isUnauthorized())
        .andExpect(errorBody(401, "Unauthorized", "Authentication required"));
  }

  /** The whole error body, {@code timestamp} aside: exactly these fields, nothing more. */
  private static ResultMatcher errorBody(int status, String error, String message) {
    return result -> {
      Map<String, Object> body =
          new HashMap<>(
              JsonPath.<Map<String, Object>>read(result.getResponse().getContentAsString(), "$"));
      assertThat(body.remove("timestamp")).as("timestamp").isNotNull();
      assertThat(body)
          .isEqualTo(
              Map.of("status", status, "error", error, "message", message, "details", List.of()));
    };
  }

  private MockHttpServletRequestBuilder activation(
      PermissionLevel callerLevel, String action, String targetId) {
    return post("/auth/users/" + targetId + "/" + action)
        .header("Authorization", "Bearer " + jwtService.issue(caller(callerLevel)).value());
  }

  private static User target(boolean active) {
    User user = new User();
    user.setId("user-2");
    user.setClubId("club-a");
    user.setEmail("analyst@example.com");
    user.setFullName("Noa Cohen");
    user.setTitle(Title.ANALYST);
    user.setPermissionLevel(PermissionLevel.ADMIN);
    user.setDateOfBirth(LocalDate.of(1990, 6, 15));
    user.setPasswordHash("hash-that-must-not-leak");
    user.setActive(active);
    return user;
  }

  private MockHttpServletRequestBuilder list(PermissionLevel callerLevel) {
    return get("/auth/users")
        .header("Authorization", "Bearer " + jwtService.issue(caller(callerLevel)).value());
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
