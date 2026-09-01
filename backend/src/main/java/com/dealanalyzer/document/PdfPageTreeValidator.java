package com.dealanalyzer.document;

import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Set;
import org.apache.pdfbox.cos.COSArray;
import org.apache.pdfbox.cos.COSBase;
import org.apache.pdfbox.cos.COSDictionary;
import org.apache.pdfbox.cos.COSName;
import org.apache.pdfbox.cos.COSNumber;
import org.apache.pdfbox.pdmodel.PDDocument;

final class PdfPageTreeValidator {
  private PdfPageTreeValidator() {}

  static int validate(PDDocument document, int maxPages) {
    COSBase rootValue = document.getDocumentCatalog().getCOSObject().getDictionaryObject(COSName.PAGES);
    if (!(rootValue instanceof COSDictionary root)) {
      throw malformed();
    }
    int declared = requiredCount(root);
    if (declared > maxPages) {
      throw pageLimit();
    }
    Set<COSDictionary> visiting = Collections.newSetFromMap(new IdentityHashMap<>());
    Set<COSDictionary> visited = Collections.newSetFromMap(new IdentityHashMap<>());
    int actual = count(root, visiting, visited, maxPages);
    if (actual == 0 || actual != declared || actual != document.getNumberOfPages()) {
      throw malformed();
    }
    return actual;
  }

  private static int count(
      COSDictionary node, Set<COSDictionary> visiting, Set<COSDictionary> visited, int maxPages) {
    if (!visiting.add(node) || !visited.add(node)) {
      throw malformed();
    }
    try {
      COSBase type = node.getDictionaryObject(COSName.TYPE);
      if (COSName.PAGE.equals(type)) {
        return 1;
      }
      if (!COSName.PAGES.equals(type)) {
        throw malformed();
      }
      int declared = requiredCount(node);
      if (declared > maxPages) {
        throw pageLimit();
      }
      COSBase kidsValue = node.getDictionaryObject(COSName.KIDS);
      if (!(kidsValue instanceof COSArray kids) || kids.size() == 0) {
        throw malformed();
      }
      int actual = 0;
      for (COSBase kidValue : kids) {
        COSBase resolved = kidValue instanceof org.apache.pdfbox.cos.COSObject object
            ? object.getObject() : kidValue;
        if (!(resolved instanceof COSDictionary kid)) {
          throw malformed();
        }
        actual = Math.addExact(actual, count(kid, visiting, visited, maxPages));
        if (actual > maxPages) {
          throw pageLimit();
        }
      }
      if (actual != declared) {
        throw malformed();
      }
      return actual;
    } finally {
      visiting.remove(node);
    }
  }

  private static int requiredCount(COSDictionary node) {
    COSBase count = node.getDictionaryObject(COSName.COUNT);
    if (!(count instanceof COSNumber number) || number.intValue() < 0) {
      throw malformed();
    }
    return number.intValue();
  }

  private static InvalidDocumentException pageLimit() {
    return DocumentFileInspector.invalid(
        "PAGE_LIMIT_EXCEEDED",
        "The PDF exceeds the configured page limit. Split it into smaller documents and try again.");
  }

  private static InvalidDocumentException malformed() {
    return DocumentFileInspector.invalid(
        "MALFORMED_FILE",
        "The PDF page tree is malformed or contradictory. Export it again and retry.");
  }
}
