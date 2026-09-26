package com.dealanalyzer.document;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.ArrayList;
import org.junit.jupiter.api.Test;

class FrozenDocumentMediaTest {
  @Test void parserMediaTypesMatchTheFrozenPilotSet() throws Exception {
    try (var input = getClass().getResourceAsStream("/pilot/pilot-parameters.v1.json")) {
      var types = new ArrayList<String>();
      new ObjectMapper().readTree(input).at("/uploadLimits/supportedMediaTypes")
          .forEach(type -> types.add(type.asText()));
      assertThat(types).doesNotHaveDuplicates().containsExactlyInAnyOrderElementsOf(DocumentParserProcess.SUPPORTED_MEDIA_TYPES);
    }
  }
}
