package com.dealanalyzer.pilot;

import java.util.List;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.Repository;

interface PilotWorkflowEventRepository extends Repository<PilotWorkflowEventEntity, String> {
  interface Count { String getEventType(); String getCategory(); long getEvents(); Double getMeanMillis(); }
  @Query("select e.eventType as eventType, e.category as category, count(e) as events, avg(e.durationMillis) as meanMillis from PilotWorkflowEventEntity e group by e.eventType, e.category order by e.eventType, e.category")
  List<Count> aggregate();
}
