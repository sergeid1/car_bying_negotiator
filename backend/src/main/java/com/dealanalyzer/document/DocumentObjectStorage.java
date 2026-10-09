package com.dealanalyzer.document;

interface DocumentObjectStorage {
  StoredObject store(String caseId, String documentId, byte[] content);

  byte[] readOriginal(String caseId, String documentId, String objectKey);

  StoredObject storeNormalized(
      String caseId, String documentId, int pageNumber, byte[] content);

  byte[] readNormalized(String caseId, String documentId, int pageNumber, String objectKey);

  void delete(String objectKey);

  /** Complete prefix inventory, including orphaned bytes and retained versions; failures throw. */
  java.util.List<String> caseObjectKeys(String caseId);

  /** False only for a conclusively absent local root; inaccessible remote storage throws. */
  boolean available();

  record StoredObject(String objectKey, String keyReference) {}
}
