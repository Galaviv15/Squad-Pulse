package com.squadpulse.common;

import static org.assertj.core.api.Assertions.assertThat;

import java.lang.reflect.Method;
import java.util.Arrays;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.method.annotation.ExceptionHandlerMethodResolver;

class GlobalExceptionHandlerTest {

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

  private static Method handlerMethod(String name) {
    return Arrays.stream(GlobalExceptionHandler.class.getMethods())
        .filter(method -> method.getName().equals(name))
        .filter(method -> method.isAnnotationPresent(ExceptionHandler.class))
        .findFirst()
        .orElseThrow();
  }
}
