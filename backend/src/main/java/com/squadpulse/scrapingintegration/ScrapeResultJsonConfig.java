package com.squadpulse.scrapingintegration;

import java.util.List;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.MediaType;
import org.springframework.http.converter.HttpMessageConverter;
import org.springframework.http.converter.json.JacksonJsonHttpMessageConverter;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;
import tools.jackson.databind.DeserializationFeature;

/**
 * Rejects unknown JSON properties in a {@link ScrapeResultRequest} — and only there (KAN-66). It's
 * a contract: a typo in the scraper must fail loudly (a 400 naming the property's path), not drop
 * data silently. Jackson 3 ignores unknown properties by default ({@code
 * FAIL_ON_UNKNOWN_PROPERTIES} is off), and {@code @JsonIgnoreProperties(ignoreUnknown = false)}
 * can't turn that around, so the JSON converter is given a second mapper for this one target type
 * ({@code registerMappersForType}): a copy of its own with that feature on. It applies to the whole
 * body, nested records included; every other type keeps the global mapper unchanged.
 */
@Configuration
class ScrapeResultJsonConfig implements WebMvcConfigurer {

  @Override
  public void extendMessageConverters(List<HttpMessageConverter<?>> converters) {
    List<JacksonJsonHttpMessageConverter> jsonConverters =
        converters.stream()
            .filter(JacksonJsonHttpMessageConverter.class::isInstance)
            .map(JacksonJsonHttpMessageConverter.class::cast)
            .toList();
    if (jsonConverters.isEmpty()) {
      // Fail at startup rather than run with the contract silently lenient.
      throw new IllegalStateException(
          "No Jackson JSON converter to make strict for scrape results");
    }
    for (JacksonJsonHttpMessageConverter converter : jsonConverters) {
      converter.registerMappersForType(
          ScrapeResultRequest.class,
          mappers ->
              mappers.put(
                  MediaType.APPLICATION_JSON,
                  converter
                      .getMapper()
                      .rebuild()
                      .enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
                      .build()));
    }
  }
}
