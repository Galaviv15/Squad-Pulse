package com.squadpulse.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.squadpulse.common.ImageProperties;
import com.squadpulse.common.ImageType;
import com.squadpulse.common.ImageValidator;
import com.squadpulse.common.NotFoundException;
import com.squadpulse.common.StoredImage;
import com.squadpulse.common.TestImages;
import com.squadpulse.common.ValidatedImage;
import java.io.ByteArrayInputStream;
import java.util.stream.Stream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.security.autoconfigure.UserDetailsServiceAutoConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.AbstractMockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.request.MockMultipartHttpServletRequestBuilder;

/**
 * The HTTP contract of {@link StaffPhotoController} behind the real security chain, with {@link
 * StaffPhotoService} mocked and the real {@link ImageValidator}: who may call what, that {@code me}
 * is always the token's user (and wins over {@code {id}}), upload validation, and the 204 / 404 /
 * 409 / 413 mappings. Club isolation and storage are proven on a real MongoDB in {@link
 * StaffPhotoIntegrationTest}.
 */
@WebMvcTest(
    controllers = StaffPhotoController.class,
    excludeAutoConfiguration = UserDetailsServiceAutoConfiguration.class,
    properties = {AuthWebMvcTestConfig.JWT_SECRET, AuthWebMvcTestConfig.PASSWORD_PEPPER})
@Import({AuthWebMvcTestConfig.class, TestAccessTokens.class, ImageValidator.class})
@EnableConfigurationProperties(ImageProperties.class)
class StaffPhotoControllerTest {

  /** The user id {@link TestAccessTokens} puts in every token. */
  private static final String CALLER_ID = "user-1";

  private static final String ME = "/users/me/photo";
  private static final String OTHER = "/users/user-2/photo";
  private static final int TWO_MIB = 2 * 1024 * 1024;

  @Autowired private MockMvc mockMvc;
  @Autowired private TestAccessTokens tokens;
  @MockitoBean private StaffPhotoService staffPhotoService;

  @BeforeEach
  void stubService() {
    when(staffPhotoService.photo(anyString()))
        .thenAnswer(
            invocation ->
                new StoredImage(
                    ImageType.PNG,
                    TestImages.png().length,
                    new ByteArrayInputStream(TestImages.png())));
  }

  // --- permission matrix -------------------------------------------------------------------------

  @ParameterizedTest(name = "{0} {1} as {2} -> {3}")
  @CsvSource({
    "PUT,    me, VIEW_ONLY,    204",
    "PUT,    me, EDIT_PARTIAL, 204",
    "PUT,    me, EDIT_FULL,    204",
    "PUT,    me, ADMIN,        204",
    "PUT,    me, NONE,         401",
    "GET,    me, VIEW_ONLY,    200",
    "GET,    me, EDIT_PARTIAL, 200",
    "GET,    me, EDIT_FULL,    200",
    "GET,    me, ADMIN,        200",
    "GET,    me, NONE,         401",
    "DELETE, me, VIEW_ONLY,    204",
    "DELETE, me, EDIT_PARTIAL, 204",
    "DELETE, me, EDIT_FULL,    204",
    "DELETE, me, ADMIN,        204",
    "DELETE, me, NONE,         401",
    "PUT,    id, VIEW_ONLY,    403",
    "PUT,    id, EDIT_PARTIAL, 403",
    "PUT,    id, EDIT_FULL,    403",
    "PUT,    id, ADMIN,        204",
    "PUT,    id, NONE,         401",
    "GET,    id, VIEW_ONLY,    200",
    "GET,    id, EDIT_PARTIAL, 200",
    "GET,    id, EDIT_FULL,    200",
    "GET,    id, ADMIN,        200",
    "GET,    id, NONE,         401",
    "DELETE, id, VIEW_ONLY,    403",
    "DELETE, id, EDIT_PARTIAL, 403",
    "DELETE, id, EDIT_FULL,    403",
    "DELETE, id, ADMIN,        204",
    "DELETE, id, NONE,         401",
  })
  void eachEndpointAdmitsExactlyTheLevelsAtOrAboveItsMinimum(
      String method, String target, String caller, int expectedStatus) throws Exception {
    String url = target.equals("me") ? ME : OTHER;
    AbstractMockHttpServletRequestBuilder<?> request =
        switch (method) {
          case "PUT" -> photoUpload(url, TestImages.png());
          case "GET" -> get(url);
          case "DELETE" -> delete(url);
          default -> throw new IllegalArgumentException(method);
        };
    if (!caller.equals("NONE")) {
      request.header("Authorization", tokens.bearer("club-a", PermissionLevel.valueOf(caller)));
    }

    mockMvc.perform(request).andExpect(status().is(expectedStatus));

    if (expectedStatus == 401 || expectedStatus == 403) {
      verifyNoInteractions(staffPhotoService);
    }
  }

  @Test
  void withoutAToken401CarriesTheGenericMessage() throws Exception {
    mockMvc
        .perform(get(ME))
        .andExpect(status().isUnauthorized())
        .andExpect(jsonPath("$.message").value("Authentication required"));
  }

  // --- "me" is the token's user, and wins over {id} ----------------------------------------------

  /**
   * The literal {@code me} segment takes precedence over {@code {id}}: each operation reaches the
   * service with the caller's id from the token, never with {@code "me"} — and a {@code VIEW_ONLY}
   * upload isn't caught by the {@code ADMIN} rule on {@code PUT /users/{id}/photo}.
   */
  @Test
  void meIsAlwaysTheCallerFromTheToken() throws Exception {
    mockMvc.perform(asViewer(photoUpload(ME, TestImages.png()))).andExpect(status().isNoContent());
    mockMvc.perform(asViewer(get(ME))).andExpect(status().isOk());
    mockMvc.perform(asViewer(delete(ME))).andExpect(status().isNoContent());

    verify(staffPhotoService).upload(eq(CALLER_ID), any());
    verify(staffPhotoService).photo(CALLER_ID);
    verify(staffPhotoService).delete(CALLER_ID);
    verify(staffPhotoService, never()).upload(eq("me"), any());
    verify(staffPhotoService, never()).photo("me");
    verify(staffPhotoService, never()).delete("me");
  }

  @Test
  void theIdFormPassesThePathIdToTheService() throws Exception {
    mockMvc
        .perform(asAdmin(photoUpload(OTHER, TestImages.png())))
        .andExpect(status().isNoContent());
    mockMvc.perform(asViewer(get(OTHER))).andExpect(status().isOk());
    mockMvc.perform(asAdmin(delete(OTHER))).andExpect(status().isNoContent());

    verify(staffPhotoService).upload(eq("user-2"), any());
    verify(staffPhotoService).photo("user-2");
    verify(staffPhotoService).delete("user-2");
  }

  // --- upload ------------------------------------------------------------------------------------

  /** The type comes from the bytes alone: a PNG declared as {@code image/jpeg} is a PNG. */
  @Test
  void uploadIs204AndPassesTheImageWithItsDetectedTypeToTheService() throws Exception {
    mockMvc
        .perform(
            asViewer(
                multipart(HttpMethod.PUT, ME)
                    .file(new MockMultipartFile("file", "x.jpg", "image/jpeg", TestImages.png()))))
        .andExpect(status().isNoContent())
        .andExpect(content().string(""));

    ArgumentCaptor<ValidatedImage> image = ArgumentCaptor.forClass(ValidatedImage.class);
    verify(staffPhotoService).upload(eq(CALLER_ID), image.capture());
    assertThat(image.getValue().type()).isEqualTo(ImageType.PNG);
    assertThat(image.getValue().content()).isEqualTo(TestImages.png());
  }

  @Test
  void anImageOfExactlyTheLimitIsAccepted() throws Exception {
    mockMvc
        .perform(asViewer(photoUpload(ME, TestImages.jpegOfSize(TWO_MIB))))
        .andExpect(status().isNoContent());
  }

  /** The application's own limit — the same one as for player photos and the club logo. */
  @ParameterizedTest
  @ValueSource(strings = {ME, OTHER})
  void anImageOneByteOverTheLimitIs413(String url) throws Exception {
    mockMvc
        .perform(asAdmin(photoUpload(url, TestImages.jpegOfSize(TWO_MIB + 1))))
        .andExpect(status().is(413))
        .andExpect(jsonPath("$.error").value("Content Too Large"))
        .andExpect(jsonPath("$.message").value("Image must be at most 2 MB"));

    verify(staffPhotoService, never()).upload(any(), any());
  }

  static Stream<Arguments> invalidPhotos() {
    return Stream.of(
        Arguments.of("empty", new byte[0], "file: must not be empty"),
        Arguments.of("SVG", TestImages.svg(), "file: must be a JPEG, PNG or WebP image"));
  }

  @ParameterizedTest(name = "{0}")
  @MethodSource("invalidPhotos")
  void anInvalidPhotoIs400NamingTheFileField(String description, byte[] content, String detail)
      throws Exception {
    mockMvc
        .perform(
            asViewer(
                multipart(HttpMethod.PUT, ME)
                    .file(new MockMultipartFile("file", "secret-name.svg", "image/png", content))))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.error").value("Validation Failed"))
        .andExpect(jsonPath("$.details").value(contains(detail)))
        .andExpect(content().string(not(containsString("secret-name"))));

    verify(staffPhotoService, never()).upload(any(), any());
  }

  @Test
  void aMissingFilePartIs400() throws Exception {
    mockMvc
        .perform(
            asViewer(
                multipart(HttpMethod.PUT, ME)
                    .file(new MockMultipartFile("photo", "x.png", "image/png", TestImages.png()))))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.details").value(contains("file: is required")));

    verify(staffPhotoService, never()).upload(any(), any());
  }

  /** No {@code consumes} on the mapping: a non-multipart request is a 400, not a 415. */
  @ParameterizedTest(name = "{0}")
  @CsvSource({"application/json, {}", "image/png, not-really-a-png"})
  void aNonMultipartUploadIs400(String contentType, String body) throws Exception {
    mockMvc
        .perform(asViewer(put(ME)).contentType(MediaType.parseMediaType(contentType)).content(body))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.message").value("Malformed multipart request"));

    verify(staffPhotoService, never()).upload(any(), any());
  }

  // --- serve / delete / errors -------------------------------------------------------------------

  @ParameterizedTest
  @ValueSource(strings = {ME, OTHER})
  void thePhotoIsServedWithItsTypeLengthNosniffAndNoStore(String url) throws Exception {
    mockMvc
        .perform(asViewer(get(url)))
        .andExpect(status().isOk())
        .andExpect(header().string("Content-Type", "image/png"))
        .andExpect(header().longValue("Content-Length", TestImages.png().length))
        .andExpect(header().string("X-Content-Type-Options", "nosniff"))
        .andExpect(header().string("Cache-Control", containsString("no-store")))
        .andExpect(content().bytes(TestImages.png()));
  }

  @Test
  void noPhotoIs404() throws Exception {
    when(staffPhotoService.photo(CALLER_ID))
        .thenThrow(new NotFoundException("This user has no photo"));

    mockMvc
        .perform(asViewer(get(ME)))
        .andExpect(status().isNotFound())
        .andExpect(jsonPath("$.message").value("This user has no photo"));
  }

  @Test
  void anUnknownUserIs404() throws Exception {
    doThrow(new NotFoundException("User not found")).when(staffPhotoService).delete("user-2");

    mockMvc
        .perform(asAdmin(delete(OTHER)))
        .andExpect(status().isNotFound())
        .andExpect(jsonPath("$.message").value("User not found"));
  }

  @Test
  void aDeactivatedUserIs409WithoutIds() throws Exception {
    doThrow(new DeactivatedUserException()).when(staffPhotoService).upload(anyString(), any());

    mockMvc
        .perform(asAdmin(photoUpload(OTHER, TestImages.png())))
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.error").value("Conflict"))
        .andExpect(
            jsonPath("$.message")
                .value("This user has been deactivated; their profile can't be changed"))
        .andExpect(content().string(not(containsString("user-2"))));
  }

  @Test
  void deletingThePhotoIs204() throws Exception {
    mockMvc
        .perform(asViewer(delete(ME)))
        .andExpect(status().isNoContent())
        .andExpect(content().string(""));

    verify(staffPhotoService).delete(CALLER_ID);
  }

  // --- helpers -----------------------------------------------------------------------------------

  private static MockMultipartHttpServletRequestBuilder photoUpload(String url, byte[] content) {
    return multipart(HttpMethod.PUT, url)
        .file(new MockMultipartFile("file", "photo", "application/octet-stream", content));
  }

  private <B extends AbstractMockHttpServletRequestBuilder<B>> B asAdmin(B request) {
    return request.header("Authorization", tokens.bearer("club-a", PermissionLevel.ADMIN));
  }

  private <B extends AbstractMockHttpServletRequestBuilder<B>> B asViewer(B request) {
    return request.header("Authorization", tokens.bearer("club-a", PermissionLevel.VIEW_ONLY));
  }
}
