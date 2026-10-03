package com.squadpulse.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.mockito.ArgumentMatchers.any;
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
 * The HTTP contract of {@link ClubLogoController} behind the real security chain, with {@link
 * ClubLogoService} mocked and the real {@link ImageValidator}: who may call what, upload
 * validation, and the 204 / 404 / 413 mappings. That the logo really stays within the caller's club
 * is proven on a real MongoDB in {@link ClubLogoIntegrationTest}.
 */
@WebMvcTest(
    controllers = ClubLogoController.class,
    excludeAutoConfiguration = UserDetailsServiceAutoConfiguration.class,
    properties = {AuthWebMvcTestConfig.JWT_SECRET, AuthWebMvcTestConfig.PASSWORD_PEPPER})
@Import({AuthWebMvcTestConfig.class, TestAccessTokens.class, ImageValidator.class})
@EnableConfigurationProperties(ImageProperties.class)
class ClubLogoControllerTest {

  private static final String URL = "/clubs/me/logo";
  private static final int TWO_MIB = 2 * 1024 * 1024;

  @Autowired private MockMvc mockMvc;
  @Autowired private TestAccessTokens tokens;
  @MockitoBean private ClubLogoService clubLogoService;

  @BeforeEach
  void stubService() {
    when(clubLogoService.logo())
        .thenAnswer(
            invocation ->
                new StoredImage(
                    ImageType.PNG,
                    TestImages.png().length,
                    new ByteArrayInputStream(TestImages.png())));
  }

  // --- permission matrix -------------------------------------------------------------------------

  @ParameterizedTest(name = "{0} as {1} -> {2}")
  @CsvSource({
    "PUT,    VIEW_ONLY,    403",
    "PUT,    EDIT_PARTIAL, 403",
    "PUT,    EDIT_FULL,    403",
    "PUT,    ADMIN,        204",
    "PUT,    NONE,         401",
    "GET,    VIEW_ONLY,    200",
    "GET,    EDIT_PARTIAL, 200",
    "GET,    EDIT_FULL,    200",
    "GET,    ADMIN,        200",
    "GET,    NONE,         401",
    "DELETE, VIEW_ONLY,    403",
    "DELETE, EDIT_PARTIAL, 403",
    "DELETE, EDIT_FULL,    403",
    "DELETE, ADMIN,        204",
    "DELETE, NONE,         401",
  })
  void eachEndpointAdmitsExactlyTheLevelsAtOrAboveItsMinimum(
      String method, String caller, int expectedStatus) throws Exception {
    AbstractMockHttpServletRequestBuilder<?> request =
        switch (method) {
          case "PUT" -> logoUpload(TestImages.png());
          case "GET" -> get(URL);
          case "DELETE" -> delete(URL);
          default -> throw new IllegalArgumentException(method);
        };
    if (!caller.equals("NONE")) {
      request.header("Authorization", tokens.bearer("club-a", PermissionLevel.valueOf(caller)));
    }

    mockMvc.perform(request).andExpect(status().is(expectedStatus));

    if (expectedStatus == 401 || expectedStatus == 403) {
      verifyNoInteractions(clubLogoService);
    }
  }

  @Test
  void withoutAToken401CarriesTheGenericMessage() throws Exception {
    mockMvc
        .perform(get(URL))
        .andExpect(status().isUnauthorized())
        .andExpect(jsonPath("$.message").value("Authentication required"));
  }

  // --- upload ------------------------------------------------------------------------------------

  /** The type comes from the bytes alone: a PNG declared as {@code image/jpeg} is a PNG. */
  @Test
  void uploadIs204AndPassesTheImageWithItsDetectedTypeToTheService() throws Exception {
    mockMvc
        .perform(
            asAdmin(
                multipart(HttpMethod.PUT, URL)
                    .file(new MockMultipartFile("file", "x.jpg", "image/jpeg", TestImages.png()))))
        .andExpect(status().isNoContent())
        .andExpect(content().string(""));

    ArgumentCaptor<ValidatedImage> image = ArgumentCaptor.forClass(ValidatedImage.class);
    verify(clubLogoService).upload(image.capture());
    assertThat(image.getValue().type()).isEqualTo(ImageType.PNG);
    assertThat(image.getValue().content()).isEqualTo(TestImages.png());
  }

  @Test
  void anImageOfExactlyTheLimitIsAccepted() throws Exception {
    mockMvc
        .perform(asAdmin(logoUpload(TestImages.jpegOfSize(TWO_MIB))))
        .andExpect(status().isNoContent());
  }

  /** The application's own limit — the same one as for player photos. */
  @Test
  void anImageOneByteOverTheLimitIs413() throws Exception {
    mockMvc
        .perform(asAdmin(logoUpload(TestImages.jpegOfSize(TWO_MIB + 1))))
        .andExpect(status().is(413))
        .andExpect(jsonPath("$.error").value("Content Too Large"))
        .andExpect(jsonPath("$.message").value("Image must be at most 2 MB"));

    verify(clubLogoService, never()).upload(any());
  }

  static Stream<Arguments> invalidLogos() {
    return Stream.of(
        Arguments.of("empty", new byte[0], "file: must not be empty"),
        Arguments.of("SVG", TestImages.svg(), "file: must be a JPEG, PNG or WebP image"));
  }

  @ParameterizedTest(name = "{0}")
  @MethodSource("invalidLogos")
  void anInvalidLogoIs400NamingTheFileField(String description, byte[] content, String detail)
      throws Exception {
    mockMvc
        .perform(
            asAdmin(
                multipart(HttpMethod.PUT, URL)
                    .file(new MockMultipartFile("file", "secret-name.svg", "image/png", content))))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.error").value("Validation Failed"))
        .andExpect(jsonPath("$.details").value(contains(detail)))
        .andExpect(content().string(not(containsString("secret-name"))));

    verify(clubLogoService, never()).upload(any());
  }

  @Test
  void aMissingFilePartIs400() throws Exception {
    mockMvc
        .perform(
            asAdmin(
                multipart(HttpMethod.PUT, URL)
                    .file(new MockMultipartFile("logo", "x.png", "image/png", TestImages.png()))))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.details").value(contains("file: is required")));

    verify(clubLogoService, never()).upload(any());
  }

  /** No {@code consumes} on the mapping: a non-multipart request is a 400, not a 415. */
  @ParameterizedTest(name = "{0}")
  @CsvSource({"application/json, {}", "image/png, not-really-a-png"})
  void aNonMultipartUploadIs400(String contentType, String body) throws Exception {
    mockMvc
        .perform(asAdmin(put(URL)).contentType(MediaType.parseMediaType(contentType)).content(body))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.message").value("Malformed multipart request"));

    verify(clubLogoService, never()).upload(any());
  }

  /** A missing club is a data-integrity bug: the generic 500, never the exception's message. */
  @Test
  void aMissingClubIsAGeneric500() throws Exception {
    doThrow(new IllegalStateException("Club club-a not found")).when(clubLogoService).upload(any());

    mockMvc
        .perform(asAdmin(logoUpload(TestImages.png())))
        .andExpect(status().isInternalServerError())
        .andExpect(jsonPath("$.message").value("An unexpected error occurred"))
        .andExpect(content().string(not(containsString("club-a"))));
  }

  // --- serve / delete ----------------------------------------------------------------------------

  @Test
  void theLogoIsServedWithItsTypeLengthNosniffAndNoStore() throws Exception {
    mockMvc
        .perform(asViewer(get(URL)))
        .andExpect(status().isOk())
        .andExpect(header().string("Content-Type", "image/png"))
        .andExpect(header().longValue("Content-Length", TestImages.png().length))
        .andExpect(header().string("X-Content-Type-Options", "nosniff"))
        .andExpect(header().string("Cache-Control", containsString("no-store")))
        .andExpect(content().bytes(TestImages.png()));
  }

  @Test
  void noLogoIs404() throws Exception {
    when(clubLogoService.logo()).thenThrow(new NotFoundException("This club has no logo"));

    mockMvc
        .perform(asViewer(get(URL)))
        .andExpect(status().isNotFound())
        .andExpect(jsonPath("$.message").value("This club has no logo"));
  }

  @Test
  void deletingTheLogoIs204() throws Exception {
    mockMvc
        .perform(asAdmin(delete(URL)))
        .andExpect(status().isNoContent())
        .andExpect(content().string(""));

    verify(clubLogoService).delete();
  }

  // --- helpers -----------------------------------------------------------------------------------

  private static MockMultipartHttpServletRequestBuilder logoUpload(byte[] content) {
    return multipart(HttpMethod.PUT, URL)
        .file(new MockMultipartFile("file", "logo", "application/octet-stream", content));
  }

  private <B extends AbstractMockHttpServletRequestBuilder<B>> B asAdmin(B request) {
    return request.header("Authorization", tokens.bearer("club-a", PermissionLevel.ADMIN));
  }

  private <B extends AbstractMockHttpServletRequestBuilder<B>> B asViewer(B request) {
    return request.header("Authorization", tokens.bearer("club-a", PermissionLevel.VIEW_ONLY));
  }
}
