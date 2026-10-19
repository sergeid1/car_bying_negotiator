package com.dealanalyzer.ai;

import com.dealanalyzer.ai.TotoGuideLoader.Article;
import com.dealanalyzer.ai.TotoGuideLoader.Guide;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;

/**
 * Toto's code-owned guide (ticket 118): one Markdown article per file under {@code toto/guide/}, listed in order by
 * {@code toto/guide.json}. Each article starts with front matter naming its {@code id}, {@code title}, {@code version},
 * {@code reviewed_by} and {@code reviewed_on}, and optionally a {@code link} into an existing client guide.
 *
 * <p>The served guide version is the manifest's version plus the first twelve hex digits of a SHA-256 digest of
 * everything the model sees (id, title, article version, link and text), so any article change changes the prompt
 * version and needs a new shadow comparison. The manifest also records the full digest: a changed article fails to
 * load until the author records the new digest, which is the moment to bump the manifest version. Review metadata
 * is not in the digest, so signing an article off does not change what the model is given.
 *
 * <p>The guide is served only when the manifest marks it released, and a released guide must have every article
 * reviewed. Until then Toto has no articles and answers nothing from it.
 */
public final class ReviewedTotoGuide {
  public static final String MANIFEST = "toto/guide.json";
  public static final String ARTICLES = "toto/guide/";
  /** What Toto has while the guide awaits the operator's review and release. */
  public static final String UNRELEASED_VERSION = "toto-guide.unavailable";
  /** Matches {@code TotoAnswerTask}'s paragraph limit, so every passage can be quoted whole. */
  public static final int PARAGRAPH_LIMIT = 600;
  static final List<String> REQUIRED_FIELDS = List.of("id", "title", "version", "reviewed_by", "reviewed_on");
  static final Set<String> OPTIONAL_FIELDS = Set.of("link");

  private static final ObjectMapper JSON = new ObjectMapper();

  private ReviewedTotoGuide() {}

  /** One article with its review record; {@code reviewedOn} and {@code reviewedBy} are null until it is reviewed. */
  public record Entry(Article article, int version, String reviewedBy, LocalDate reviewedOn) {
    public boolean reviewed() { return reviewedOn != null; }
  }

  public record Manifest(String version, boolean released, String digest, List<String> articles) {
    public Manifest { articles = List.copyOf(articles); }
  }

  /** The parsed guide. {@link #guide()} is what Toto would be given once released. */
  public record Draft(Manifest manifest, List<Entry> entries, String computedDigest) {
    public Draft { entries = List.copyOf(entries); }

    public String servedVersion() {
      return manifest.version() + "." + computedDigest.substring("sha256:".length(), "sha256:".length() + 12);
    }

    public List<String> pendingReview() {
      return entries.stream().filter(entry -> !entry.reviewed()).map(entry -> entry.article().id()).toList();
    }

    public Guide guide() {
      return new Guide(servedVersion(), entries.stream().map(Entry::article).toList());
    }

    /** The guide Toto is given: the reviewed articles once released, otherwise none. */
    public Guide served() {
      return manifest.released() ? guide() : new Guide(UNRELEASED_VERSION, List.of());
    }
  }

  /** The production loader: the classpath guide, served only once released. */
  public static Guide served() {
    return readClasspath().served();
  }

  public static Draft readClasspath() {
    return read(ReviewedTotoGuide::classpath);
  }

  /**
   * Parses and checks the whole guide: front matter, ids, paragraph limits, the recorded digest and, for a released
   * guide, every article's review. {@code source} maps a resource path to its text, or null when it is missing.
   */
  public static Draft read(Function<String, String> source) {
    Draft draft = parse(source);
    if (!draft.manifest().digest().equals(draft.computedDigest())) {
      throw new IllegalStateException("The Toto guide text changed. Bump the version in " + MANIFEST
          + " and record its digest as " + draft.computedDigest());
    }
    if (draft.manifest().released() && !draft.pendingReview().isEmpty()) {
      throw new IllegalStateException("A released Toto guide needs reviewed_by and reviewed_on on every article; "
          + "pending: " + draft.pendingReview());
    }
    return draft;
  }

  /** Everything except the recorded digest and the release check, so tests can build fixture guides. */
  static Draft parse(Function<String, String> source) {
    Manifest manifest = manifest(require(source.apply(MANIFEST), "Missing " + MANIFEST));
    var entries = new ArrayList<Entry>();
    var ids = new HashSet<String>();
    for (String id : manifest.articles()) {
      if (!ids.add(id)) throw new IllegalStateException("Duplicate Toto guide article id: " + id);
      String path = ARTICLES + id + ".md";
      Entry entry = article(path, require(source.apply(path), "Missing Toto guide article " + path));
      if (!entry.article().id().equals(id)) {
        throw new IllegalStateException(path + " declares id " + entry.article().id() + "; it must match its file name");
      }
      entries.add(entry);
    }
    return new Draft(manifest, entries, digest(entries));
  }

  static Manifest manifest(String text) {
    JsonNode node;
    try {
      node = JSON.readTree(text);
    } catch (IOException invalid) {
      throw new IllegalStateException("Unreadable " + MANIFEST, invalid);
    }
    String version = node.path("version").asText("");
    if (!version.matches("toto-guide\\.v[1-9][0-9]{0,3}")) {
      throw new IllegalStateException(MANIFEST + " needs a version such as toto-guide.v1");
    }
    if (!node.path("released").isBoolean()) throw new IllegalStateException(MANIFEST + " needs released true or false");
    String digest = node.path("digest").asText("");
    if (!node.path("articles").isArray() || node.path("articles").isEmpty()) {
      throw new IllegalStateException(MANIFEST + " needs a nonempty articles list");
    }
    var articles = new ArrayList<String>();
    node.path("articles").forEach(id -> articles.add(id.asText()));
    return new Manifest(version, node.path("released").asBoolean(), digest, articles);
  }

  static Entry article(String path, String markdown) {
    String text = markdown.replace("\r\n", "\n");
    if (!text.startsWith("---\n")) throw new IllegalStateException(path + " must start with front matter");
    int end = text.indexOf("\n---\n", 3);
    if (end < 0) throw new IllegalStateException(path + " has unterminated front matter");
    Map<String, String> fields = new LinkedHashMap<>();
    for (String line : text.substring(4, end).split("\n")) {
      if (line.isBlank()) continue;
      int colon = line.indexOf(':');
      if (colon <= 0) throw new IllegalStateException(path + " has a front matter line without a field name: " + line);
      String key = line.substring(0, colon).strip();
      if (!REQUIRED_FIELDS.contains(key) && !OPTIONAL_FIELDS.contains(key)) {
        throw new IllegalStateException(path + " has an unknown front matter field: " + key);
      }
      if (fields.put(key, line.substring(colon + 1).strip()) != null) {
        throw new IllegalStateException(path + " repeats the front matter field " + key);
      }
    }
    for (String field : REQUIRED_FIELDS) {
      if (!fields.containsKey(field)) throw new IllegalStateException(path + " is missing the front matter field " + field);
    }
    for (String field : List.of("id", "title", "version")) {
      if (fields.get(field).isEmpty()) throw new IllegalStateException(path + " has an empty " + field);
    }
    int version;
    try {
      version = Integer.parseInt(fields.get("version"));
    } catch (NumberFormatException invalid) {
      throw new IllegalStateException(path + " needs a whole-number article version");
    }
    if (version < 1) throw new IllegalStateException(path + " needs an article version of 1 or more");
    String reviewedBy = emptyToNull(fields.get("reviewed_by"));
    String reviewedOnText = emptyToNull(fields.get("reviewed_on"));
    if ((reviewedBy == null) != (reviewedOnText == null)) {
      throw new IllegalStateException(path + " records a review only partly; set both reviewed_by and reviewed_on");
    }
    LocalDate reviewedOn = null;
    if (reviewedOnText != null) {
      try {
        reviewedOn = LocalDate.parse(reviewedOnText);
      } catch (DateTimeParseException invalid) {
        throw new IllegalStateException(path + " needs reviewed_on as an ISO date such as 2026-10-20");
      }
    }
    String body = text.substring(end + 5).strip();
    for (String paragraph : body.split("\n\\s*\n")) {
      String passage = paragraph.replaceAll("\\s+", " ").strip();
      if (passage.length() > PARAGRAPH_LIMIT) {
        throw new IllegalStateException(path + " has a paragraph over " + PARAGRAPH_LIMIT
            + " characters, which Toto could not quote whole: " + passage.substring(0, 60));
      }
      if (TotoAnswerTask.CONTACT.matcher(passage).find()) {
        throw new IllegalStateException(path + " has text that Toto's check reads as a link or contact detail: "
            + passage.substring(0, Math.min(60, passage.length())));
      }
    }
    String link = emptyToNull(fields.get("link"));
    Article article;
    try {
      article = new Article(fields.get("id"), fields.get("title"), body, link);
    } catch (IllegalArgumentException invalid) {
      throw new IllegalStateException(path + ": " + invalid.getMessage(), invalid);
    }
    return new Entry(article, version, reviewedBy, reviewedOn);
  }

  static String digest(List<Entry> entries) {
    try {
      MessageDigest sha = MessageDigest.getInstance("SHA-256");
      for (Entry entry : entries) {
        Article article = entry.article();
        for (String part : List.of(article.id(), article.title(), Integer.toString(entry.version()),
            article.link() == null ? "" : article.link(), article.text())) {
          sha.update(part.getBytes(StandardCharsets.UTF_8));
          sha.update((byte) 0);
        }
        sha.update((byte) 1);
      }
      return "sha256:" + HexFormat.of().formatHex(sha.digest());
    } catch (NoSuchAlgorithmException missing) {
      throw new IllegalStateException(missing);
    }
  }

  private static String classpath(String path) {
    try (InputStream in = ReviewedTotoGuide.class.getClassLoader().getResourceAsStream(path)) {
      return in == null ? null : new String(in.readAllBytes(), StandardCharsets.UTF_8);
    } catch (IOException cause) {
      throw new UncheckedIOException(cause);
    }
  }

  private static String require(String value, String message) {
    if (value == null) throw new IllegalStateException(message);
    return value;
  }

  private static String emptyToNull(String value) {
    return value == null || value.isEmpty() ? null : value;
  }
}
