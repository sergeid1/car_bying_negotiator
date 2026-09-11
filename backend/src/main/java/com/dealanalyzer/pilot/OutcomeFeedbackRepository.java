package com.dealanalyzer.pilot;

import java.util.*;
import org.springframework.data.jpa.repository.JpaRepository;

interface OutcomeFeedbackRepository extends JpaRepository<OutcomeFeedbackEntity, String> {
  Optional<OutcomeFeedbackEntity> findByCaseIdAndStage(String caseId, String stage);
  List<OutcomeFeedbackEntity> findByCaseIdOrderByCreatedAt(String caseId);
}
