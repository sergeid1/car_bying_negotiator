package com.dealanalyzer.pilot;

import java.util.*;
import org.springframework.data.jpa.repository.JpaRepository;

interface FindingFeedbackRepository extends JpaRepository<FindingFeedbackEntity, String> {
  Optional<FindingFeedbackEntity> findByCaseIdAndReportVersionAndFindingPointer(String caseId, int version, String pointer);
  List<FindingFeedbackEntity> findByCaseIdOrderByCreatedAt(String caseId);
}
