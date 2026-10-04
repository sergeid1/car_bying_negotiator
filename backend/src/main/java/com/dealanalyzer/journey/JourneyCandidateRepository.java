package com.dealanalyzer.journey;

import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

interface JourneyCandidateRepository extends JpaRepository<JourneyCandidateEntity, String> {
  List<JourneyCandidateEntity> findByJourneyIdAndShortlistPositionIsNotNullOrderByShortlistPosition(String journeyId);
  List<JourneyCandidateEntity> findByJourneyIdOrderByCreatedAtAscIdAsc(String journeyId);
  Optional<JourneyCandidateEntity> findByIdAndJourneyId(String id, String journeyId);
}
