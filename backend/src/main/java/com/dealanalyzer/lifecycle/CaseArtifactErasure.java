package com.dealanalyzer.lifecycle;

/** External storage/provider boundary. Must be idempotent and fence concurrent writes.
 * Implementations throw until deletion has been verified, including asynchronous provider removal.
 * Never include document content, object keys or provider responses in lifecycle evidence.
 */
public interface CaseArtifactErasure {
  void eraseAndVerify(String caseId);
}
