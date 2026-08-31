package com.dealanalyzer.buyingcase.persistence;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;
import java.io.Serializable;
import java.util.Objects;

@Embeddable
class AnalysisReportKey implements Serializable {
  @Column(name = "case_id", length = 36)
  String caseId;

  @Column(name = "report_version")
  int reportVersion;

  protected AnalysisReportKey() {}

  AnalysisReportKey(String caseId, int reportVersion) {
    this.caseId = caseId;
    this.reportVersion = reportVersion;
  }

  @Override
  public boolean equals(Object other) {
    return this == other || other instanceof AnalysisReportKey that
        && reportVersion == that.reportVersion
        && Objects.equals(caseId, that.caseId);
  }

  @Override
  public int hashCode() {
    return Objects.hash(caseId, reportVersion);
  }
}
