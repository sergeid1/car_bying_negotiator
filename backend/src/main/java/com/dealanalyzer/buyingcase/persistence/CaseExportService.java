package com.dealanalyzer.buyingcase.persistence;
import com.dealanalyzer.buyingcase.*;
import com.dealanalyzer.document.RetainedDocumentExport;
import com.dealanalyzer.report.StoredReportRenderer;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.JsonNode;
import java.time.Instant;
import java.util.*;
import java.io.*;
import java.util.zip.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.http.HttpStatus;
@Service
public class CaseExportService {
  private final AnalysisCaseRepository cases;
  private final AnalysisReportRepository reports;
  private final ExportJobRepository jobs;
  private final CaseAccessService access;
  private final CaseExportProjection projection;
  private final RetainedDocumentExport documents;
  private final StoredReportRenderer renderer;
  private final ObjectMapper json;
  private final ExportContentProtection protection;
  private final java.time.Duration lifetime;
  private final com.dealanalyzer.purchase.PurchaseService purchases;
  public CaseExportService(AnalysisCaseRepository cases, AnalysisReportRepository reports, ExportJobRepository jobs,
      CaseAccessService access, CaseExportProjection projection, RetainedDocumentExport documents,
      StoredReportRenderer renderer, ObjectMapper json, ExportContentProtection protection,
      com.dealanalyzer.purchase.PurchaseService purchases,
      @org.springframework.beans.factory.annotation.Value("${case.export.lifetime:PT24H}") java.time.Duration lifetime) {
    this.cases=cases; this.reports=reports; this.jobs=jobs; this.access=access; this.projection=projection;
    this.documents=documents; this.renderer=renderer; this.json=json; this.protection=protection; this.lifetime=lifetime;
    this.purchases=purchases;
  }
  public enum Format { ZIP, HTML, PDF }
  public record Job(String id, Format format, int reportVersion, Instant createdAt, Instant expiresAt) {}
  public record Download(byte[] bytes, Format format) {}
  @Transactional
  public Job create(String caseId, String token, String key, Format format, int version) {
    var buyingCase=access.requireAuthorized(cases.findLockedById(caseId), token, c->c.accessTokenHash, c->c.accessExpiresAt);
    if (format != Format.ZIP || buyingCase.currentReportVersion > 0) purchases.requireReport(caseId, token);
    var previous=jobs.findByCaseIdAndRequestKey(caseId,key).orElse(null);
    if(previous!=null) {
      if(!previous.format.equals(format.name()) || previous.reportVersion!=version) throw new IdempotencyConflictException();
      if(!previous.expiresAt.isAfter(Instant.now())) throw new ResponseStatusException(HttpStatus.GONE, "Export expired; start a new export.");
      return view(previous);
    }
    if (format==Format.ZIP && version!=0 || format!=Format.ZIP && version<1)
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Select a saved report version, or version zero for a case archive.");
    byte[] content;
    try {
      if(format==Format.ZIP) content=archive(caseId);
      else {
        var payload=reports.findProjectedById(new AnalysisReportKey(caseId,version)).orElseThrow(()->new ResourceNotFoundException("Report not found"));
        var report=json.readTree(payload.getPayloadJson());
        content=format==Format.HTML ? renderer.html(report) : renderer.pdf(report);
      }
    } catch(IOException failure) { throw new IllegalStateException("Export unavailable; retry safely",failure); }
    var job=new ExportJobEntity(); job.id=UUID.randomUUID().toString(); job.caseId=caseId; job.requestKey=key;
    job.format=format.name(); job.reportVersion=version; job.contentBase64=protection.encrypt(caseId + ":" + job.id,content);
    job.createdAt=Instant.now(); job.expiresAt=job.createdAt.plus(lifetime);
    if(job.expiresAt.isAfter(buyingCase.accessExpiresAt)) job.expiresAt=buyingCase.accessExpiresAt;
    if(format==Format.ZIP) job.expiresAt=documents.earliestRetentionDeadline(caseId,job.expiresAt);
    jobs.saveAndFlush(job); return view(job);
  }
  @Transactional(readOnly=true)
  public Download download(String caseId, String token, String id) {
    access.requireAuthorized(cases.findById(caseId), token, c->c.accessTokenHash, c->c.accessExpiresAt);
    var job=jobs.findById(id).filter(j->j.caseId.equals(caseId) && j.expiresAt.isAfter(Instant.now()))
        .orElseThrow(()->new ResourceNotFoundException("Export not found"));
    // A pre-offer archive may already contain reports, so ZIP downloads are gated if the case has reports.
    if (!job.format.equals("ZIP") || cases.findById(caseId).orElseThrow().currentReportVersion > 0)
      purchases.requireReport(caseId, token);
    return new Download(protection.decrypt(caseId + ":" + job.id,job.contentBase64), Format.valueOf(job.format));
  }
  public enum ArchiveStatus { INCLUDED, PURCHASE_REQUIRED, UNAVAILABLE }
  public record AccountArchive(ArchiveStatus status, byte[] bytes) {}
  /** The case archive for an attached Buyer Account export, under the same report gate as a case export. */
  @Transactional(readOnly=true)
  public AccountArchive accountArchive(String caseId) {
    var buyingCase=cases.findById(caseId).orElse(null);
    if(buyingCase==null || !"ACTIVE".equals(buyingCase.lifecycle) || !buyingCase.accessExpiresAt.isAfter(Instant.now()))
      return new AccountArchive(ArchiveStatus.UNAVAILABLE,null);
    if(buyingCase.currentReportVersion>0 && purchases.reportPurchaseRequired(caseId))
      return new AccountArchive(ArchiveStatus.PURCHASE_REQUIRED,null);
    try { return new AccountArchive(ArchiveStatus.INCLUDED,archive(caseId)); }
    catch(IOException failure) { throw new IllegalStateException("Export unavailable; retry safely",failure); }
  }
  private Job view(ExportJobEntity job) { return new Job(job.id,Format.valueOf(job.format),job.reportVersion,job.createdAt,job.expiresAt); }
  private byte[] archive(String caseId) throws IOException {
    var data=projection.read(caseId); data.put("schemaVersion","buying-case-export.v1");
    try(var output=new ByteArrayOutputStream(); var zip=new ZipOutputStream(output)) {
      entry(zip,"README.txt",("Buying Case retained data export\ncase-data.json contains named records and their full retained version history. "
          + "IDs connect evidence, documents, corrections, offers, approvals and reports. Amounts ending in minor are cents for USD. "
          + "Reports are immutable snapshots; HTML contains every saved field, label, limitation and evidence reference. "
          + "Original documents are named by document ID; original filenames and media types are in source_documents. "
          + "Normalized pages are included as PNG files. Expired, deleted or quarantined originals are not included. Credentials, recovery links and storage keys are excluded. "
          + "The archive is frozen at export creation; request a new export to include later changes.\n").getBytes(java.nio.charset.StandardCharsets.UTF_8));
      entry(zip,"case-data.json",json.writerWithDefaultPrettyPrinter().writeValueAsBytes(data));
      for(JsonNode report:data.path("analysis_reports")) entry(zip,"reports/report-" + report.path("report_version").asInt() + ".html", renderer.html(report.path("payload_json")));
      for(var original:documents.read(caseId)) entry(zip,"documents/" + original.path(), original.content());
      zip.finish(); return output.toByteArray();
    }
  }
  private void entry(ZipOutputStream zip,String name,byte[] bytes) throws IOException {
    var entry=new ZipEntry(name); entry.setTime(0); zip.putNextEntry(entry); zip.write(bytes); zip.closeEntry();
  }
}
