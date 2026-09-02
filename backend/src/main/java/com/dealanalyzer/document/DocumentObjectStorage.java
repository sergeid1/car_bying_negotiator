package com.dealanalyzer.document;

interface DocumentObjectStorage {
  StoredObject store(String caseId, String documentId, byte[] content);

  byte[] readOriginal(String caseId, String documentId, String objectKey);

  StoredObject storeNormalized(
      String caseId, String documentId, int pageNumber, byte[] content);

  byte[] readNormalized(String caseId, String documentId, int pageNumber, String objectKey);

  void delete(String objectKey);

  record StoredObject(String objectKey, String keyReference) {}
}
