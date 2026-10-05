package com.dealanalyzer.appointment;

import com.dealanalyzer.outbound.OutboundCaseLanguage;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/** Grounds only the platform-rendered test-drive messages awaiting approval, sending or a retry, for their own dealer. */
@Component
class TestDriveLanguage implements OutboundCaseLanguage {
  private final JdbcTemplate jdbc;
  TestDriveLanguage(JdbcTemplate jdbc) { this.jdbc = jdbc; }

  @Override public Set<String> paragraphs(String caseId, String dealerId) {
    var paragraphs = new HashSet<String>();
    jdbc.queryForList("""
        select t.message_text from test_drive_messages t
        join quote_requests q on q.id = t.quote_request_id and q.case_id = t.case_id
        join outbound_drafts d on d.id = q.draft_id
        where t.case_id = ? and d.dealer_id = ? and t.state in ('DRAFT', 'SENDING', 'FAILED', 'DELIVERY_UNKNOWN')
        """, String.class, caseId, dealerId).forEach(text -> paragraphs.addAll(Arrays.asList(text.split("\\n\\n", -1))));
    return paragraphs;
  }
}
