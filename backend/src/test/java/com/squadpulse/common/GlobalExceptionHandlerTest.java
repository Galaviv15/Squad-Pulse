package com.squadpulse.common;

import static org.assertj.core.api.Assertions.assertThat;

import java.lang.reflect.Method;
import java.time.LocalDate;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.context.support.DefaultMessageSourceResolvable;
import org.springframework.core.DefaultParameterNameDiscoverer;
import org.springframework.core.MethodParameter;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.validation.BeanPropertyBindingResult;
import org.springframework.validation.FieldError;
import org.springframework.validation.method.MethodValidationResult;
import org.springframework.validation.method.ParameterValidationResult;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.method.annotation.ExceptionHandlerMethodResolver;
import org.springframework.web.method.annotation.HandlerMethodValidationException;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
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

    ResponseEntity<ApiErrorResponse> response = handler.handleMethodValidation(ex);

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

    assertThat(handler.handleMethodValidation(ex).getStatusCode())
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
