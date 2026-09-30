package com.dealanalyzer.api;

import com.dealanalyzer.document.DocumentIntakeService;
import com.dealanalyzer.document.persistence.DocumentIntakePersistence.SourceDocumentView;
import java.util.List;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Read access the Buyer needs to verify extracted terms against the pages they came from. */
@RestController
@RequestMapping("/api/v1/cases/{caseId}/documents")
public class SourceDocumentController {
  private final DocumentIntakeService documents;

  public SourceDocumentController(DocumentIntakeService documents) {
    this.documents = documents;
  }

  @GetMapping
  public List<SourceDocumentView> listDocuments(
      @PathVariable String caseId,
      @RequestHeader("Case-Access-Token") String accessToken) {
    return documents.listDocuments(caseId, accessToken);
  }

  @GetMapping("/{documentId}/pages/{pageNumber}/image")
  public ResponseEntity<byte[]> pageImage(
      @PathVariable String caseId,
      @PathVariable String documentId,
      @PathVariable int pageNumber,
      @RequestHeader("Case-Access-Token") String accessToken) {
    return ResponseEntity.ok()
        .header("Content-Type", "image/png")
        .header("X-Content-Type-Options", "nosniff")
        .body(documents.pageImage(caseId, documentId, pageNumber, accessToken));
  }
}
