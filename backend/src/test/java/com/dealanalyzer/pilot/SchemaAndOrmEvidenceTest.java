package com.dealanalyzer.pilot;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.persistence.EntityManagerFactory;
import jakarta.persistence.FetchType;
import jakarta.persistence.ManyToMany;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.OneToMany;
import jakarta.persistence.OneToOne;
import java.io.IOException;
import java.lang.reflect.AnnotatedElement;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.env.Environment;

/**
 * Readiness evidence for the production schema contract. Booting this context at all proves Flyway
 * applied every migration and Hibernate validated the resulting schema, because the application
 * runs ddl-auto=validate; the assertions then record migrations, constraints, ownership and fetch
 * rules, and prove no configuration lets Hibernate generate a schema instead.
 */
// CI supplies one shared PostgreSQL database to this and PersistenceHardeningTest.
@org.junit.jupiter.api.Tag("serial")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE, properties = {
    "spring.datasource.url=${HARDENING_DATABASE_URL:jdbc:h2:mem:readiness;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE}",
    "case.access.pepper=readiness-test-pepper-at-least-thirty-two-bytes",
    "analysis.worker.enabled=false", "document.worker.enabled=false"})
class SchemaAndOrmEvidenceTest {
  private static final Path REPOSITORY = Path.of("..").toAbsolutePath().normalize();
  private static final Path MIGRATIONS = Path.of("src/main/resources/db/migration");
  private static final Pattern VERSIONED = Pattern.compile("^V(\\d+)__[a-z0-9_]+\\.sql$");
  private static final List<String> GENERATING = List.of("create", "create-drop", "update", "create-only");
  private static final Pattern DDL_SETTING = Pattern.compile(
      "(?i)(?:ddl[-_]auto|hbm2ddl[._]auto)[\\\"']?\\s*[=:]\\s*[\\\"']?(\\$\\{[^}]+}|[a-z-]+)");

  @Autowired EntityManagerFactory entities;
  @Autowired DataSource dataSource;
  @Autowired Environment environment;

  private final ObjectMapper json = new ObjectMapper();

  @Test void hibernateRunsInSchemaValidationModeAgainstTheMigratedSchema() {
    assertThat(environment.getProperty("spring.jpa.hibernate.ddl-auto")).isEqualTo("validate");
    assertThat(entities.isOpen()).isTrue();
  }

  @Test void noConfigurationLetsHibernateGenerateTheProductionSchema() throws IOException {
    List<String> offending = new ArrayList<>();
    for (Path file : configurationFiles()) {
      var lines = Files.readAllLines(file, StandardCharsets.UTF_8);
      for (int number = 0; number < lines.size(); number++) {
        String line = lines.get(number).strip().toLowerCase(Locale.ROOT);
        if (generatesSchema(line)) {
          offending.add(REPOSITORY.relativize(file.toAbsolutePath()) + ":" + (number + 1) + " " + line);
        }
      }
    }
    assertThat(offending).as("Only validate or none may configure Hibernate DDL").isEmpty();
  }

  private static boolean generatesSchema(String line) {
    if (line.stripLeading().startsWith("#") || line.stripLeading().startsWith("//")) return false;
    var matcher = DDL_SETTING.matcher(line);
    while (matcher.find()) {
      String value = matcher.group(1).toLowerCase(Locale.ROOT);
      if (value.startsWith("${")) {
        value = value.substring(value.lastIndexOf(':') + 1, value.length() - 1);
      }
      if (GENERATING.contains(value) || value.equals("drop")) return true;
    }
    return false;
  }

  @Test void ddlGuardRecognizesSupportedConfigurationSpellings() throws Exception {
    try (var input = getClass().getResourceAsStream("/pilot/ddl-guard-cases.json")) {
      for (var example : json.readTree(input)) {
        String setting = example.path("setting").asText();
        assertThat(generatesSchema(setting)).as(setting).isEqualTo(example.path("generating").asBoolean());
      }
    }
  }

  @Test void everyMigrationIsSequentiallyVersionedAndApplied() throws Exception {
    var versions = new ArrayList<Integer>();
    try (Stream<Path> files = Files.list(MIGRATIONS)) {
      for (Path file : files.sorted().toList()) {
        Matcher matched = VERSIONED.matcher(file.getFileName().toString());
        assertThat(matched.matches()).as("unexpected migration name " + file.getFileName()).isTrue();
        versions.add(Integer.parseInt(matched.group(1)));
      }
    }
    assertThat(versions).isNotEmpty().doesNotHaveDuplicates()
        .containsExactlyInAnyOrderElementsOf(
            java.util.stream.IntStream.rangeClosed(1, versions.size()).boxed().toList());

    var applied = new ArrayList<Integer>();
    try (var connection = dataSource.getConnection();
        var statement = connection.createStatement();
        var rows = statement.executeQuery(
            "select version from flyway_schema_history where success = true and version is not null")) {
      while (rows.next()) applied.add(Integer.valueOf(rows.getString(1)));
    }
    assertThat(applied)
        .as("every versioned migration applied successfully")
        .containsExactlyInAnyOrderElementsOf(versions);
  }

  @Test void everyEntityAssociationIsExplicitlyLazy() {
    var eager = associations().entrySet().stream()
        .filter(entry -> entry.getValue() == FetchType.EAGER).map(Map.Entry::getKey).toList();
    assertThat(eager)
        .as("Eager associations load unbounded case graphs; declare FetchType.LAZY")
        .isEmpty();
  }

  @Test void theReadinessEvidenceIsWritten() throws Exception {
    var evidence = new LinkedHashMap<String, Object>();
    evidence.put("schemaVersion", "schema-and-orm-evidence.v1");
    evidence.put("database", databaseDescription());
    evidence.put("hibernateDdlAuto", environment.getProperty("spring.jpa.hibernate.ddl-auto"));
    evidence.put("hibernateSchemaValidation", "PASSED: the context started with ddl-auto=validate");
    evidence.put("migrations", migrationNames());
    evidence.put("entityOwnership", entityOwnership());
    evidence.put("fetchRules", Map.of(
        "mappedAssociations", associations().size(),
        "eagerAssociations", associations().values().stream().filter(fetch -> fetch == FetchType.EAGER).count(),
        "associations", associations(),
        "rule", "the model is predominantly scalar; every mapped association declares FetchType.LAZY",
        "openInView", environment.getProperty("spring.jpa.open-in-view")));
    evidence.put("constraints", constraintInventory());

    Path output = Path.of("target/readiness/schema-evidence.json");
    Files.createDirectories(output.getParent());
    json.writerWithDefaultPrettyPrinter().writeValue(output.toFile(), evidence);

    assertThat(output).exists();
    assertThat(json.readTree(output.toFile()).path("constraints").path("PRIMARY KEY").asInt())
        .as("every migrated table should carry a primary key").isPositive();
  }

  private Map<String, FetchType> associations() {
    var mapped = new TreeMap<String, FetchType>();
    for (var type : entities.getMetamodel().getEntities()) {
      // getAttributes includes inherited mappings; JavaMember reflects the actual field/getter access.
      for (var attribute : type.getAttributes()) {
        if (!attribute.isAssociation()) continue;
        var member = (AnnotatedElement) attribute.getJavaMember();
        FetchType fetch = fetchOf(member);
        assertThat(fetch).as("fetch mapping for %s.%s", type.getName(), attribute.getName()).isNotNull();
        mapped.put(type.getName() + "." + attribute.getName(), fetch);
      }
    }
    return mapped;
  }

  private static FetchType fetchOf(AnnotatedElement field) {
    if (field.isAnnotationPresent(ManyToOne.class)) return field.getAnnotation(ManyToOne.class).fetch();
    if (field.isAnnotationPresent(OneToOne.class)) return field.getAnnotation(OneToOne.class).fetch();
    if (field.isAnnotationPresent(OneToMany.class)) return field.getAnnotation(OneToMany.class).fetch();
    if (field.isAnnotationPresent(ManyToMany.class)) return field.getAnnotation(ManyToMany.class).fetch();
    return null;
  }

  private List<String> migrationNames() throws IOException {
    try (Stream<Path> files = Files.list(MIGRATIONS)) {
      return files.map(file -> file.getFileName().toString())
          .sorted(java.util.Comparator.comparingInt(SchemaAndOrmEvidenceTest::versionOf)).toList();
    }
  }

  private static int versionOf(String name) {
    Matcher matched = VERSIONED.matcher(name);
    return matched.matches() ? Integer.parseInt(matched.group(1)) : Integer.MAX_VALUE;
  }

  private Map<String, String> entityOwnership() {
    var ownership = new TreeMap<String, String>();
    for (var type : entities.getMetamodel().getEntities()) {
      var table = type.getJavaType().getAnnotation(jakarta.persistence.Table.class);
      ownership.put(type.getJavaType().getName(),
          table == null || table.name().isBlank() ? type.getName() : table.name());
    }
    return ownership;
  }

  private Map<String, Integer> constraintInventory() throws SQLException {
    var counts = new TreeMap<String, Integer>();
    try (var connection = dataSource.getConnection();
        var statement = connection.createStatement();
        var rows = statement.executeQuery(
            "select constraint_type, count(*) from information_schema.table_constraints "
                + "where constraint_schema not in ('pg_catalog','information_schema') "
                + "group by constraint_type")) {
      while (rows.next()) counts.merge(rows.getString(1).toUpperCase(Locale.ROOT), rows.getInt(2), Integer::sum);
    }
    return counts;
  }

  private String databaseDescription() throws SQLException {
    try (var connection = dataSource.getConnection()) {
      var metadata = connection.getMetaData();
      return metadata.getDatabaseProductName() + " " + metadata.getDatabaseProductVersion();
    }
  }

  private static List<Path> configurationFiles() throws IOException {
    var files = new ArrayList<Path>();
    for (Path root : List.of(Path.of("src/main/resources"), Path.of("src/test/java"),
        Path.of("src/test/resources"), REPOSITORY.resolve("compose.yaml"))) {
      if (Files.isRegularFile(root)) {
        files.add(root);
      } else if (Files.isDirectory(root)) {
        try (Stream<Path> walked = Files.walk(root)) {
          files.addAll(walked.filter(Files::isRegularFile)
              .filter(file -> Stream.of(".properties", ".yaml", ".yml", ".java")
                  .anyMatch(suffix -> file.toString().endsWith(suffix)))
              .toList());
        }
      }
    }
    return files;
  }
}
