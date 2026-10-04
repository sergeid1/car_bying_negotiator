package com.dealanalyzer.outbound;

import java.util.Set;

/**
 * Trusted, platform-rendered paragraphs that a feature adds to the gate's grounded language for one
 * dealer, such as test-drive proposals rendered from validated Buyer availability. Implementations must
 * not depend on {@link OutboundDraftService} and must never return Buyer-supplied prose.
 */
public interface OutboundCaseLanguage {
  Set<String> paragraphs(String caseId, String dealerId);
}
