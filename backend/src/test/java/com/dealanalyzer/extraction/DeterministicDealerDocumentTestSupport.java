package com.dealanalyzer.extraction;

import com.dealanalyzer.extraction.DealTermsExtractionProvider.ExtractionDocument;
import com.dealanalyzer.extraction.DealTermsExtractionProvider.ProviderExtraction;
import com.fasterxml.jackson.databind.JsonNode;
import java.util.ArrayList;
import java.util.List;

/** Test bridge that runs the normal supported-layout provider against corpus page text. */
public final class DeterministicDealerDocumentTestSupport {
  private DeterministicDealerDocumentTestSupport() {}

  public static ProviderExtraction extract(JsonNode pageTexts) {
    List<NormalizedDocumentPage> pages = new ArrayList<>();
    int pageNumber = 1;
    for (JsonNode text : pageTexts) {
      pages.add(new NormalizedDocumentPage(
          pageNumber++, new byte[] {0}, text.asText(), false, false, false, false));
    }
    return new DeterministicDealerDocumentProvider()
        .extract(new ExtractionDocument("corpus-document", pages));
  }
}
