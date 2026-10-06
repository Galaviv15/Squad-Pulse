package com.squadpulse.common;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.lang.reflect.Method;
import java.time.LocalDate;
import java.util.Arrays;
import java.util.List;
import org.apache.catalina.connector.ClientAbortException;
import org.junit.jupiter.api.Test;
import org.springframework.context.support.DefaultMessageSourceResolvable;
import org.springframework.core.DefaultParameterNameDiscoverer;
import org.springframework.core.MethodParameter;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.http.converter.HttpMessageNotWritableException;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.validation.BeanPropertyBindingResult;
import org.springframework.validation.FieldError;
import org.springframework.validation.method.MethodValidationResult;
import org.springframework.validation.method.ParameterValidationResult;
import org.springframework.web.ErrorResponseException;
import org.springframework.web.HttpMediaTypeNotAcceptableException;
import org.springframework.web.HttpMediaTypeNotSupportedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingPathVariableException;
import org.springframework.web.bind.MissingRequestCookieException;
import org.springframework.web.bind.MissingRequestHeaderException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.context.request.async.AsyncRequestNotUsableException;
import org.springframework.web.method.annotation.ExceptionHandlerMethodResolver;
import org.springframework.web.method.annotation.HandlerMethodValidationException;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.multipart.MaxUploadSizeExceededException;
import org.springframework.web.multipart.MultipartException;
import org.springframework.web.multipart.support.MissingServletRequestPartException;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.json.JsonMapper;

class GlobalExceptionHandlerTest {

  private static final JsonMapper JSON = JsonMapper.builder().build();

  private final GlobalExceptionHandler handler = new GlobalExceptionHandler();

  /** The exception's own message names the entity id and collection — none of it may leak. */
  @Test
  void anOptimisticLockingFailureIsAGeneric409() {
    ResponseEntity<ApiErrorResponse> response =
        handler.handleOptimisticLockingFailure(
            new OptimisticLockingFailureException(
                "Cannot save entity 6650f1 with version 3 to collection users"));

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
    ApiErrorResponse body = response.getBody();
    assertThat(body.status()).isEqualTo(409);
    assertThat(body.error()).isEqualTo("Conflict");
    assertThat(body.code()).isEqualTo("CONCURRENT_MODIFICATION");
    assertThat(body.message()).isEqualTo("The resource was modified concurrently, please retry");
    assertThat(body.details()).isEmpty();
    assertThat(body.timestamp()).isNotNull();
  }

  /**
   * Resolved the way Spring MVC resolves it: the new handler catches exactly the optimistic-locking
   * branch, while a duplicate key still falls through to the catch-all, as before.
   */
  @Test
  void onlyOptimisticLockingFailuresReachTheNewHandler() throws Exception {
    ExceptionHandlerMethodResolver resolver =
        new ExceptionHandlerMethodResolver(GlobalExceptionHandler.class);

    assertThat(resolver.resolveMethod(new OptimisticLockingFailureException("conflict")))
        .isEqualTo(handlerMethod("handleOptimisticLockingFailure"));
    assertThat(resolver.resolveMethod(new DuplicateKeyException("duplicate")))
        .isEqualTo(handlerMethod("handleUnexpected"));
  }

  /** A body field error names the field, like the query-parameter details below. */
  @Test
  void aBodyValidationErrorNamesTheField() throws Exception {
    BeanPropertyBindingResult bindingResult = new BeanPropertyBindingResult(new Object(), "body");
    bindingResult.addError(
        new FieldError("body", "fullName", null, false, null, null, "must not be blank"));
    MethodArgumentNotValidException ex =
        new MethodArgumentNotValidException(probeParameter(0), bindingResult);

    ApiErrorResponse body = handler.handleValidation(ex).getBody();

    assertThat(body.status()).isEqualTo(400);
    assertThat(body.error()).isEqualTo("Validation Failed");
    assertThat(body.details()).containsExactly("fullName: must not be blank");
  }

  /** Named by the {@code @RequestParam} name, which may differ from the Java parameter name. */
  @Test
  void aQueryParameterConstraintViolationIs400WithOneDetailPerViolation() throws Exception {
    MethodParameter minAge = probeParameter(0);
    MethodParameter maxAge = probeParameter(1);
    HandlerMethodValidationException ex =
        new HandlerMethodValidationException(
            MethodValidationResult.create(
                new Probe(),
                Probe.class.getDeclaredMethod("list", Integer.class, Integer.class),
                List.of(
                    parameterResult(minAge, "must be greater than or equal to 18"),
                    parameterResult(maxAge, "must be less than or equal to 99"))));

    ResponseEntity<ApiErrorResponse> response =
        handler.handleMethodValidation(ex, new MockHttpServletRequest());

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    assertThat(response.getBody().error()).isEqualTo("Validation Failed");
    assertThat(response.getBody().message()).isEqualTo("Request validation failed");
    assertThat(response.getBody().details())
        .containsExactly(
            "min-age: must be greater than or equal to 18",
            "maxAge: must be less than or equal to 99");
  }

  @Test
  void aFailedReturnValueConstraintIsTheServersFaultAndStaysA500() throws Exception {
    HandlerMethodValidationException ex =
        new HandlerMethodValidationException(
            MethodValidationResult.create(
                new Probe(),
                Probe.class.getDeclaredMethod("list", Integer.class, Integer.class),
                List.of(
                    parameterResult(
                        new MethodParameter(
                            Probe.class.getDeclaredMethod("list", Integer.class, Integer.class),
                            -1),
                        "must not be null"))));

    assertThat(handler.handleMethodValidation(ex, new MockHttpServletRequest()).getStatusCode())
        .isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
  }

  /** Lists an enum's accepted values; never echoes the rejected one. */
  @Test
  void anUnconvertibleEnumParameterIs400ListingTheAcceptedValues() throws Exception {
    MethodArgumentTypeMismatchException ex =
        new MethodArgumentTypeMismatchException(
            "<script>", HttpStatus.class, "status", probeParameter(0), null);

    ApiErrorResponse body = handler.handleArgumentTypeMismatch(ex).getBody();

    assertThat(body.status()).isEqualTo(400);
    assertThat(body.details()).hasSize(1);
    assertThat(body.details().get(0)).startsWith("status: must be one of [").contains("OK");
    assertThat(body.toString()).doesNotContain("<script>");
  }

  @Test
  void anUnconvertibleNonEnumParameterIs400WithoutEchoingTheValue() throws Exception {
    MethodArgumentTypeMismatchException ex =
        new MethodArgumentTypeMismatchException(
            "abc", Integer.class, "minAge", probeParameter(0), null);

    ApiErrorResponse body = handler.handleArgumentTypeMismatch(ex).getBody();

    assertThat(body.status()).isEqualTo(400);
    assertThat(body.details()).containsExactly("minAge: has an invalid format");
  }

  @Test
  void aBadRequestExceptionWithoutAFieldIs400WithItsMessage() {
    ResponseEntity<ApiErrorResponse> response =
        handler.handleBadRequest(new BadRequestException("These filters can't be combined"));

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    assertThat(response.getBody().error()).isEqualTo("Bad Request");
    assertThat(response.getBody().message()).isEqualTo("These filters can't be combined");
    assertThat(response.getBody().details()).isEmpty();
  }

  /** Blamed on a field, it looks exactly like a validation error, so clients handle both alike. */
  @Test
  void aBadRequestExceptionWithAFieldIsReportedLikeAValidationError() {
    ResponseEntity<ApiErrorResponse> response =
        handler.handleBadRequest(
            new BadRequestException("minAge", "must be less than or equal to maxAge"));

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    assertThat(response.getBody().error()).isEqualTo("Validation Failed");
    assertThat(response.getBody().message()).isEqualTo("Request validation failed");
    assertThat(response.getBody().details())
        .containsExactly("minAge: must be less than or equal to maxAge");
  }

  // --- unreadable bodies, with the exceptions Jackson 3 really throws
  // -----------------------------

  record BodyProbe(LocalDate dateOfBirth, HttpStatus status, List<Item> items) {}

  record Item(Integer number) {}

  @Test
  void anUnparseableDateNamesTheFieldButNotTheValue() {
    ApiErrorResponse body = unreadable("{\"dateOfBirth\": \"20/05/1995\"}");

    assertThat(body.status()).isEqualTo(400);
    assertThat(body.error()).isEqualTo("Bad Request");
    assertThat(body.message()).isEqualTo("Malformed request body");
    assertThat(body.details()).containsExactly("dateOfBirth: invalid value");
    assertThat(body.toString()).doesNotContain("20/05/1995");
  }

  @Test
  void anUnknownEnumConstantNamesTheField() {
    assertThat(unreadable("{\"status\": \"<script>\"}").details())
        .containsExactly("status: invalid value");
  }

  @Test
  void aNestedPathIncludesIndexes() {
    assertThat(unreadable("{\"items\": [{\"number\": 1}, {\"number\": \"x\"}]}").details())
        .containsExactly("items[1].number: invalid value");
  }

  @Test
  void brokenJsonHasNoPathAndNoDetails() {
    ApiErrorResponse body = unreadable("{\"dateOfBirth\": ");

    assertThat(body.message()).isEqualTo("Malformed request body");
    assertThat(body.details()).isEmpty();
  }

  @Test
  void aBodyThatIsntJsonAtAllHasNoDetails() {
    HttpMessageNotReadableException ex =
        new HttpMessageNotReadableException("Required request body is missing", null, null);

    assertThat(handler.handleUnreadableBody(ex).getBody().details()).isEmpty();
  }

  /** Reads {@code json} with a real Jackson 3 mapper and wraps the failure as Spring MVC does. */
  private ApiErrorResponse unreadable(String json) {
    try {
      JSON.readValue(json, BodyProbe.class);
    } catch (JacksonException e) {
      return handler
          .handleUnreadableBody(new HttpMessageNotReadableException("JSON parse error", e, null))
          .getBody();
    }
    throw new AssertionError("Expected Jackson to reject " + json);
  }

  /** Before KAN-26 both of these fell through to the catch-all as a 500. */
  @Test
  void theQueryParameterExceptionsReachTheirOwnHandlers() throws Exception {
    ExceptionHandlerMethodResolver resolver =
        new ExceptionHandlerMethodResolver(GlobalExceptionHandler.class);

    assertThat(
            resolver.resolveMethod(
                new MethodArgumentTypeMismatchException(
                    "x", Integer.class, "minAge", probeParameter(0), null)))
        .isEqualTo(handlerMethod("handleArgumentTypeMismatch"));
    assertThat(
            resolver.resolveMethod(
                new HandlerMethodValidationException(
                    MethodValidationResult.create(
                        new Probe(),
                        Probe.class.getDeclaredMethod("list", Integer.class, Integer.class),
                        List.of(parameterResult(probeParameter(0), "invalid"))))))
        .isEqualTo(handlerMethod("handleMethodValidation"));
    assertThat(resolver.resolveMethod(new BadRequestException("bad")))
        .isEqualTo(handlerMethod("handleBadRequest"));
  }

  /**
   * Upload failures (KAN-29), all 500s before: the size exception reaches its own 413 handler, not
   * the broader multipart one it extends; a missing part is a field-style 400.
   */
  @Test
  void theMultipartExceptionsReachTheirOwnHandlers() {
    ExceptionHandlerMethodResolver resolver =
        new ExceptionHandlerMethodResolver(GlobalExceptionHandler.class);

    assertThat(resolver.resolveMethod(new MaxUploadSizeExceededException(2_097_152)))
        .isEqualTo(handlerMethod("handleMaxUploadSizeExceeded"));
    assertThat(resolver.resolveMethod(new MultipartException("Failed to parse multipart")))
        .isEqualTo(handlerMethod("handleMultipart"));
    assertThat(resolver.resolveMethod(new MissingServletRequestPartException("file")))
        .isEqualTo(handlerMethod("handleMissingPart"));
    assertThat(resolver.resolveMethod(new PayloadTooLargeException("too large")))
        .isEqualTo(handlerMethod("handlePayloadTooLarge"));
  }

  /** The container's message quotes the limit and the actual size; neither is passed on. */
  @Test
  void anOversizeUploadIsAGeneric413() {
    ResponseEntity<ApiErrorResponse> response =
        handler.handleMaxUploadSizeExceeded(
            new MaxUploadSizeExceededException(
                2_097_152, new IllegalStateException("size 3145728 exceeds 2097152")));

    assertThat(response.getStatusCode().value()).isEqualTo(413);
    ApiErrorResponse body = response.getBody();
    assertThat(body.status()).isEqualTo(413);
    assertThat(body.error()).isEqualTo("Content Too Large");
    assertThat(body.message()).isEqualTo("Upload exceeds the maximum allowed size");
    assertThat(body.message()).doesNotContain("2097152").doesNotContain("3145728");
    assertThat(body.details()).isEmpty();
  }

  @Test
  void aPayloadTooLargeExceptionIs413WithItsMessage() {
    ApiErrorResponse body =
        handler
            .handlePayloadTooLarge(new PayloadTooLargeException("Image must be at most 2 MB"))
            .getBody();

    assertThat(body.status()).isEqualTo(413);
    assertThat(body.error()).isEqualTo("Content Too Large");
    assertThat(body.message()).isEqualTo("Image must be at most 2 MB");
  }

  @Test
  void aMissingPartIsReportedLikeAMissingField() {
    ApiErrorResponse body =
        handler.handleMissingPart(new MissingServletRequestPartException("file")).getBody();

    assertThat(body.status()).isEqualTo(400);
    assertThat(body.error()).isEqualTo("Validation Failed");
    assertThat(body.details()).containsExactly("file: is required");
  }

  /** The parser's message may quote the request; it isn't passed on. */
  @Test
  void aMalformedMultipartRequestIsAPlain400() {
    ApiErrorResponse body =
        handler
            .handleMultipart(new MultipartException("Stream ended unexpectedly near <script>"))
            .getBody();

    assertThat(body.status()).isEqualTo(400);
    assertThat(body.error()).isEqualTo("Bad Request");
    assertThat(body.message()).isEqualTo("Malformed multipart request");
    assertThat(body.details()).isEmpty();
  }

  // --- standard Spring MVC exceptions (KAN-31) ---------------------------------------------------

  /**
   * Each of these fell through to the catch-all as a 500 before KAN-31. The exceptions Spring knows
   * a status for, but that aren't mapped explicitly, still reach the catch-all — which now answers
   * with that status (see the safety-net tests below).
   */
  @Test
  void theStandardSpringMvcExceptionsReachTheirOwnHandlers() throws Exception {
    ExceptionHandlerMethodResolver resolver =
        new ExceptionHandlerMethodResolver(GlobalExceptionHandler.class);

    assertThat(
            resolver.resolveMethod(
                new HttpMediaTypeNotSupportedException(
                    MediaType.TEXT_PLAIN, List.of(MediaType.APPLICATION_JSON))))
        .isEqualTo(handlerMethod("handleMediaTypeNotSupported"));
    assertThat(
            resolver.resolveMethod(
                new HttpMediaTypeNotAcceptableException(List.of(MediaType.APPLICATION_JSON))))
        .isEqualTo(handlerMethod("handleMediaTypeNotAcceptable"));
    assertThat(resolver.resolveMethod(new MissingServletRequestParameterException("q", "String")))
        .isEqualTo(handlerMethod("handleMissingParameter"));
    assertThat(
            resolver.resolveMethod(new MissingRequestHeaderException("X-Club", probeParameter(0))))
        .isEqualTo(handlerMethod("handleMissingHeader"));
    assertThat(
            resolver.resolveMethod(new MissingRequestCookieException("session", probeParameter(0))))
        .isEqualTo(handlerMethod("handleMissingCookie"));
    assertThat(resolver.resolveMethod(new MissingPathVariableException("id", probeParameter(0))))
        .isEqualTo(handlerMethod("handleUnexpected"));
    assertThat(resolver.resolveMethod(new ResponseStatusException(HttpStatus.GONE)))
        .isEqualTo(handlerMethod("handleUnexpected"));
  }

  /** Spring's own message quotes the sent type; only the server's supported types are passed on. */
  @Test
  void anUnsupportedMediaTypeIs415ListingTheSupportedTypesInTheBodyAndAcceptHeader() {
    HttpMediaTypeNotSupportedException ex =
        new HttpMediaTypeNotSupportedException(
            MediaType.parseMediaType("text/x-marker-12345"),
            List.of(MediaType.APPLICATION_JSON, MediaType.parseMediaType("application/*+json")),
            HttpMethod.POST);

    ResponseEntity<ApiErrorResponse> response = handler.handleMediaTypeNotSupported(ex);

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNSUPPORTED_MEDIA_TYPE);
    assertThat(response.getHeaders().getAccept())
        .containsExactly(
            MediaType.APPLICATION_JSON, MediaType.parseMediaType("application/*+json"));
    assertThat(response.getHeaders().getContentType()).isEqualTo(MediaType.APPLICATION_JSON);
    ApiErrorResponse body = response.getBody();
    assertThat(body.status()).isEqualTo(415);
    assertThat(body.error()).isEqualTo("Unsupported Media Type");
    assertThat(body.message()).isEqualTo("Unsupported Content-Type");
    assertThat(body.details()).containsExactly("application/json", "application/*+json");
    assertThat(body.toString()).doesNotContain("x-marker-12345");
  }

  /** RFC 5789: a PATCH endpoint also advertises what it reads in {@code Accept-Patch}. */
  @Test
  void anUnsupportedMediaTypeOnAPatchAlsoCarriesAcceptPatch() {
    HttpMediaTypeNotSupportedException ex =
        new HttpMediaTypeNotSupportedException(
            MediaType.TEXT_PLAIN, List.of(MediaType.APPLICATION_JSON), HttpMethod.PATCH);

    assertThat(handler.handleMediaTypeNotSupported(ex).getHeaders().getAcceptPatch())
        .containsExactly(MediaType.APPLICATION_JSON);
  }

  /** An unparseable {@code Content-Type}: Spring knows no supported types, so there are none. */
  @Test
  void anUnparseableContentTypeIs415WithoutDetailsOrEcho() {
    ResponseEntity<ApiErrorResponse> response =
        handler.handleMediaTypeNotSupported(
            new HttpMediaTypeNotSupportedException("Invalid mime type \"marker-12345\""));

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNSUPPORTED_MEDIA_TYPE);
    assertThat(response.getHeaders().getAccept()).isEmpty();
    assertThat(response.getBody().details()).isEmpty();
    assertThat(response.getBody().toString()).doesNotContain("marker-12345");
  }

  @Test
  void aNotAcceptableRequestIs406AsJsonListingWhatCanBeProduced() {
    ResponseEntity<ApiErrorResponse> response =
        handler.handleMediaTypeNotAcceptable(
            new HttpMediaTypeNotAcceptableException(List.of(MediaType.APPLICATION_JSON)));

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_ACCEPTABLE);
    assertThat(response.getHeaders().getContentType()).isEqualTo(MediaType.APPLICATION_JSON);
    assertThat(response.getHeaders().getAccept()).containsExactly(MediaType.APPLICATION_JSON);
    ApiErrorResponse body = response.getBody();
    assertThat(body.status()).isEqualTo(406);
    assertThat(body.error()).isEqualTo("Not Acceptable");
    assertThat(body.message()).isEqualTo("None of the accepted media types can be produced");
    assertThat(body.details()).containsExactly("application/json");
  }

  @Test
  void anUnparseableAcceptHeaderIs406WithoutEcho() {
    ApiErrorResponse body =
        handler
            .handleMediaTypeNotAcceptable(
                new HttpMediaTypeNotAcceptableException("Could not parse 'Accept' header [<x>]"))
            .getBody();

    assertThat(body.status()).isEqualTo(406);
    assertThat(body.details()).isEmpty();
    assertThat(body.toString()).doesNotContain("<x>");
  }

  @Test
  void aMissingQueryParameterIsReportedLikeAMissingField() {
    ApiErrorResponse body =
        handler
            .handleMissingParameter(
                new MissingServletRequestParameterException("min-age", "Integer"))
            .getBody();

    assertMissing(body, "min-age: is required");
  }

  @Test
  void aMissingHeaderIsReportedLikeAMissingField() throws Exception {
    ApiErrorResponse body =
        handler
            .handleMissingHeader(new MissingRequestHeaderException("X-Club", probeParameter(0)))
            .getBody();

    assertMissing(body, "X-Club: is required");
  }

  @Test
  void aMissingCookieIsReportedLikeAMissingField() throws Exception {
    ApiErrorResponse body =
        handler
            .handleMissingCookie(
                new MissingRequestCookieException("refresh_token", probeParameter(0)))
            .getBody();

    assertMissing(body, "refresh_token: is required");
  }

  private static void assertMissing(ApiErrorResponse body, String detail) {
    assertThat(body.status()).isEqualTo(400);
    assertThat(body.error()).isEqualTo("Validation Failed");
    assertThat(body.message()).isEqualTo("Request validation failed");
    assertThat(body.details()).containsExactly(detail);
  }

  // --- the catch-all: ErrorResponse safety net, disconnects, committed responses (KAN-31)
  // ---------

  /** Its own status, reason phrase and headers, but a fixed message: the reason may quote input. */
  @Test
  void anUnmappedClientErrorResponseKeepsItsStatusAndHeadersUnderAGenericMessage() {
    ResponseStatusException ex =
        new ResponseStatusException(HttpStatus.GONE, "marker-12345") {
          @Override
          public HttpHeaders getHeaders() {
            HttpHeaders headers = new HttpHeaders();
            headers.add("X-Probe", "kept");
            return headers;
          }
        };

    ResponseEntity<ApiErrorResponse> response = unexpected(ex);

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.GONE);
    assertThat(response.getHeaders().getFirst("X-Probe")).isEqualTo("kept");
    ApiErrorResponse body = response.getBody();
    assertThat(body.status()).isEqualTo(410);
    assertThat(body.error()).isEqualTo("Gone");
    assertThat(body.message()).isEqualTo("The request could not be processed");
    assertThat(body.details()).isEmpty();
    assertThat(body.toString()).doesNotContain("marker-12345");
  }

  @Test
  void anUnmappedServerErrorResponseKeepsItsStatusUnderTheGenericMessage() {
    ApiErrorResponse body =
        unexpected(new ErrorResponseException(HttpStatus.SERVICE_UNAVAILABLE)).getBody();

    assertThat(body.status()).isEqualTo(503);
    assertThat(body.error()).isEqualTo("Service Unavailable");
    assertThat(body.message()).isEqualTo("An unexpected error occurred");
  }

  /**
   * A path variable the handler declares but its mapping lacks is a server bug — 500, as Spring has
   * it — unless the value was there but converted to null, which is the client's.
   */
  @Test
  void aMissingPathVariableIsA500UnlessItWasConvertedToNull() throws Exception {
    assertThat(unexpected(new MissingPathVariableException("id", probeParameter(0))).getBody())
        .extracting(ApiErrorResponse::status, ApiErrorResponse::message)
        .containsExactly(500, "An unexpected error occurred");
    assertThat(unexpected(new MissingPathVariableException("id", probeParameter(0), true)))
        .extracting(response -> response.getStatusCode().value())
        .isEqualTo(400);
  }

  /** Not an {@link org.springframework.web.ErrorResponse}: the generic 500, exactly as before. */
  @Test
  void anyOtherExceptionIsTheGeneric500() {
    ResponseEntity<ApiErrorResponse> response =
        unexpected(new IllegalStateException("internal detail"));

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
    ApiErrorResponse body = response.getBody();
    assertThat(body.status()).isEqualTo(500);
    assertThat(body.error()).isEqualTo("Internal Server Error");
    assertThat(body.message()).isEqualTo("An unexpected error occurred");
    assertThat(body.details()).isEmpty();
  }

  /**
   * Nothing to write to a client that's gone. Recognised as Spring's {@code
   * DisconnectedClientHelper} does: by Tomcat's exception type anywhere in the cause chain, or a
   * broken-pipe / connection-reset message.
   */
  @Test
  void aClientThatWentAwayGetsNoResponseBody() {
    assertThat(unexpected(new ClientAbortException(new IOException("Broken pipe")))).isNull();
    assertThat(unexpected(new AsyncRequestNotUsableException("Response not usable"))).isNull();
    assertThat(
            unexpected(
                new HttpMessageNotWritableException(
                    "Could not write", new ClientAbortException("closed"))))
        .isNull();
    assertThat(unexpected(new IOException("Connection reset by peer"))).isNull();
  }

  /**
   * Spring's own exclusion: a dropped connection to the database is our failure, not the client's.
   */
  @Test
  void aDroppedDatabaseConnectionIsNotAClientDisconnect() {
    assertThat(
            unexpected(
                    new DataAccessResourceFailureException(
                        "Mongo", new IOException("Connection reset by peer")))
                .getStatusCode())
        .isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
  }

  /** Once the status and part of the body are sent, an error body would only corrupt them. */
  @Test
  void nothingIsWrittenToAnAlreadyCommittedResponse() {
    MockHttpServletResponse committed = new MockHttpServletResponse();
    committed.setCommitted(true);

    assertThat(
            handler.handleUnexpected(
                new IllegalStateException("stream failed"),
                new MockHttpServletRequest(),
                committed))
        .isNull();
  }

  private ResponseEntity<ApiErrorResponse> unexpected(Exception ex) {
    return handler.handleUnexpected(
        ex, new MockHttpServletRequest("GET", "/probe"), new MockHttpServletResponse());
  }

  /** Stands in for a controller method with two query parameters. */
  static class Probe {
    @SuppressWarnings("unused")
    String list(@RequestParam(name = "min-age") Integer minAge, @RequestParam Integer maxAge) {
      return "";
    }
  }

  private static MethodParameter probeParameter(int index) throws NoSuchMethodException {
    MethodParameter parameter =
        new MethodParameter(
            Probe.class.getDeclaredMethod("list", Integer.class, Integer.class), index);
    parameter.initParameterNameDiscovery(new DefaultParameterNameDiscoverer());
    return parameter;
  }

  private static ParameterValidationResult parameterResult(
      MethodParameter parameter, String message) {
    return new ParameterValidationResult(
        parameter,
        null,
        List.of(new DefaultMessageSourceResolvable(null, null, message)),
        null,
        null,
        null,
        (error, type) -> {
          throw new IllegalArgumentException();
        });
  }

  private static Method handlerMethod(String name) {
    return Arrays.stream(GlobalExceptionHandler.class.getMethods())
        .filter(method -> method.getName().equals(name))
        .filter(method -> method.isAnnotationPresent(ExceptionHandler.class))
        .findFirst()
        .orElseThrow();
  }
}
