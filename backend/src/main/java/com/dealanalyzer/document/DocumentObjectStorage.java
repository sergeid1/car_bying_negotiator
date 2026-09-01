package com.dealanalyzer.document;

interface DocumentObjectStorage {
  StoredObject store(String caseId, String documentId, byte[] content);

  void delete(String objectKey);

  record StoredObject(String objectKey, String keyReference) {}
}
