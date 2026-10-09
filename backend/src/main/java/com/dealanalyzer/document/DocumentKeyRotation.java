package com.dealanalyzer.document;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.ArrayList;
import org.springframework.core.env.StandardEnvironment;

/** Offline maintenance command. No Spring application, web server or workers are started. */
public final class DocumentKeyRotation {
  private DocumentKeyRotation() {}

  public static void main(String[] args) {
    if (args.length != 2 || !args[0].equals("--service-stopped")) {
      System.err.println("Usage: DocumentKeyRotation --service-stopped <batch-size 1..1000>");
      System.exit(2);
    }
    try {
      int batch = Integer.parseInt(args[1]);
      var environment = new StandardEnvironment();
      environment.getSystemProperties().put("document.storage.active-key-id", required("DOCUMENT_STORAGE_ACTIVE_KEY_ID"));
      environment.getSystemProperties().put("document.storage.keys", required("DOCUMENT_STORAGE_KEYS"));
      String provider = System.getenv().getOrDefault("DOCUMENT_STORAGE_PROVIDER", "file");
      if (!provider.equals("file") && !provider.equals("s3")) throw new IllegalArgumentException("Unknown storage provider");
      environment.getSystemProperties().put("document.storage.allow-file",
          System.getenv().getOrDefault("DOCUMENT_STORAGE_ALLOW_FILE", "false"));
      String legacy = System.getenv().getOrDefault("DOCUMENT_STORAGE_ENCRYPTION_KEY", "");
      if (provider.equals("s3")) environment.getSystemProperties().put("document.storage.s3.region", required("DOCUMENT_STORAGE_S3_REGION"));
      try (var client = provider.equals("s3") ? new S3DocumentStorageConfiguration()
              .documentS3Client(required("DOCUMENT_STORAGE_S3_REGION")) : null;
          Connection database = DriverManager.getConnection(required("SPRING_DATASOURCE_URL"),
          System.getenv().getOrDefault("SPRING_DATASOURCE_USERNAME", ""),
          System.getenv().getOrDefault("SPRING_DATASOURCE_PASSWORD", ""))) {
        EncryptedDocumentStorage storage = provider.equals("s3")
            ? new EncryptedS3DocumentStorage(client, required("DOCUMENT_STORAGE_S3_BUCKET"), legacy, null, environment)
            : new EncryptedFileDocumentStorage(Path.of(required("DOCUMENT_STORAGE_PATH")), legacy, null, environment);
        String cursor = "";
        while (true) {
          Batch result = rotateBatch(database, storage, cursor, batch);
          System.out.println("scanned=" + result.scanned() + " rotated=" + result.rotated());
          if (result.scanned() == 0) break;
          cursor = result.cursor();
        }
      }
    } catch (Exception failure) {
      // JDBC/crypto exception messages can contain credentials, paths or object identifiers.
      System.err.println("Document key rotation failed; preserve all keys and retry after repair.");
      System.exit(1);
    }
  }

  public record Batch(int scanned, int rotated, String cursor) {}
  private record ObjectRow(String key, String caseId, String documentId, int page) {}

  static Batch rotateBatch(Connection database, EncryptedDocumentStorage storage, String cursor, int size)
      throws SQLException {
    if (size < 1 || size > 1000) throw new IllegalArgumentException("Batch size must be 1..1000");
    if (!database.getAutoCommit()) throw new IllegalArgumentException("Dedicated auto-commit connection required");
    var rows = new ArrayList<ObjectRow>();
    // Include expired/quarantined retained objects: a key must remain usable until physical erasure.
    try (var query = database.prepareStatement("select object_key, case_id, document_id, page_number from ("
        + "select object_key, case_id, id as document_id, 0 as page_number from source_documents where original_deleted_at is null "
        + "union all select p.normalized_object_key, d.case_id, d.id, p.page_number from document_pages p "
        + "join source_documents d on d.id=p.document_id where p.normalized_deleted_at is null and p.normalized_object_key is not null"
        + ") objects where object_key > ? order by object_key limit ?")) {
      query.setString(1, cursor); query.setInt(2, size);
      try (var result = query.executeQuery()) {
        while (result.next()) rows.add(new ObjectRow(result.getString(1), result.getString(2), result.getString(3), result.getInt(4)));
      }
    }
    int rotated = 0;
    for (var row : rows) {
      String aad = row.caseId() + ":" + row.documentId() + (row.page() == 0 ? "" : ":page:" + row.page());
      if (storage.rotate(aad, row.key())) rotated++;
      // Blob replacement precedes metadata: a crash here is repaired by the next idempotent scan.
      if (row.page() == 0) try (var update = database.prepareStatement(
          "update source_documents set encryption_key_reference=? where id=? and case_id=?")) {
        update.setString(1, storage.activeKeyReference()); update.setString(2, row.documentId());
        update.setString(3, row.caseId()); update.executeUpdate();
      }
      cursor = row.key();
    }
    return new Batch(rows.size(), rotated, cursor);
  }

  private static String required(String name) {
    String value = System.getenv(name);
    if (value == null || value.isBlank()) throw new IllegalArgumentException("Missing configuration");
    return value;
  }
}
