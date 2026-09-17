package com.dealanalyzer.api;

import com.dealanalyzer.time.PersistedInstant;
import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.databind.JsonSerializer;
import com.fasterxml.jackson.databind.SerializerProvider;
import java.io.IOException;
import java.time.Instant;
import org.springframework.boot.autoconfigure.jackson.Jackson2ObjectMapperBuilderCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Match the microsecond precision of persisted PostgreSQL timestamps at the API boundary. */
@Configuration(proxyBeanMethods = false)
class InstantJsonConfiguration {
  @Bean
  Jackson2ObjectMapperBuilderCustomizer persistedInstantPrecision() {
    return builder -> builder.serializerByType(Instant.class, new JsonSerializer<Instant>() {
      @Override
      public void serialize(Instant value, JsonGenerator output, SerializerProvider provider)
          throws IOException {
        output.writeString(PersistedInstant.format(value));
      }
    });
  }
}
