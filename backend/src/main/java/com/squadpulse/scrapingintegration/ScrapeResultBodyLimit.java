package com.squadpulse.scrapingintegration;

import com.squadpulse.common.PayloadTooLargeException;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Type;
import org.springframework.core.MethodParameter;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpInputMessage;
import org.springframework.http.converter.HttpMessageConverter;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.servlet.mvc.method.annotation.RequestBodyAdviceAdapter;

/**
 * Caps the body of {@code POST /internal/scrape-results} at {@link #MAX_BYTES} (KAN-66): over it, a
 * 413 in the standard error shape ({@link PayloadTooLargeException}), before Jackson sees a byte. A
 * real scrape is about 15 KB.
 *
 * <p>Runs just before the body is converted, for a {@link ScrapeResultRequest} only, so no other
 * endpoint's limit changes. A declared {@code Content-Length} over the limit is refused at once;
 * otherwise — a chunked body has none, and a declared length can't be trusted — the body is read
 * into memory up to one byte past the limit, and refused if that byte exists. The rest of an
 * oversize body is left to Tomcat, which discards up to {@code server.tomcat.max-swallow-size}.
 */
@ControllerAdvice(assignableTypes = ScrapeResultController.class)
class ScrapeResultBodyLimit extends RequestBodyAdviceAdapter {

  static final int MAX_BYTES = 256 * 1024;

  static final String MESSAGE = "Request body exceeds the maximum allowed size of 256 KB";

  @Override
  public boolean supports(
      MethodParameter methodParameter,
      Type targetType,
      Class<? extends HttpMessageConverter<?>> converterType) {
    return targetType == ScrapeResultRequest.class;
  }

  @Override
  public HttpInputMessage beforeBodyRead(
      HttpInputMessage inputMessage,
      MethodParameter parameter,
      Type targetType,
      Class<? extends HttpMessageConverter<?>> converterType)
      throws IOException {
    HttpHeaders headers = inputMessage.getHeaders();
    if (headers.getContentLength() > MAX_BYTES) {
      throw new PayloadTooLargeException(MESSAGE);
    }
    byte[] body;
    try (InputStream in = inputMessage.getBody()) {
      body = in.readNBytes(MAX_BYTES + 1);
    }
    if (body.length > MAX_BYTES) {
      throw new PayloadTooLargeException(MESSAGE);
    }
    return new HttpInputMessage() {
      @Override
      public InputStream getBody() {
        return new ByteArrayInputStream(body);
      }

      @Override
      public HttpHeaders getHeaders() {
        return headers;
      }
    };
  }
}
