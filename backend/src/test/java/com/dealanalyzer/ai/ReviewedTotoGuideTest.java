package com.dealanalyzer.ai;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** Ticket 118: the guide's front matter, ids, digest-tied version and the review gate on release. */
class ReviewedTotoGuideTest {
  private static final Path GUIDE_DIRECTORY = Path.of("src/main/resources/toto/guide");
  private static final ObjectMapper JSON = new ObjectMapper();

  @Test void theShippedGuideLoadsWithItsRecordedDigest() {
    var draft = ReviewedTotoGuide.readClasspath();
    assertThat(draft.entries()).hasSizeBetween(30, 50);
    assertThat(draft.manifest().digest()).isEqualTo(draft.computedDigest());
    assertThat(draft.servedVersion()).matches("toto-guide\\.v1\\.[0-9a-f]{12}");
  }

  @Test void everyArticleFileIsListedInTheManifestAndEveryListedArticleExists() throws IOException {
    try (Stream<Path> files = Files.list(GUIDE_DIRECTORY)) {
      var onDisk = files.map(file -> file.getFileName().toString())
          .peek(name -> assertThat(name).as("only Markdown articles belong in the guide").endsWith(".md"))
          .map(name -> name.substring(0, name.length() - 3)).sorted().toList();
      assertThat(onDisk).containsExactlyInAnyOrderElementsOf(ReviewedTotoGuide.readClasspath().manifest().articles());
    }
  }

  /**
   * The operator has not reviewed the articles yet, so the shipped guide is not released and Toto serves none of
   * it. Once released, {@link ReviewedTotoGuide#read} refuses any article whose review is not recorded.
   */
  @Test void theShippedGuideIsServedOnlyOnceReleasedAndFullyReviewed() {
    var draft = ReviewedTotoGuide.readClasspath();
    var bean = new TotoAnswerTask().reviewedTotoGuideLoader().load();
    if (draft.manifest().released()) {
      assertThat(draft.pendingReview()).isEmpty();
      assertThat(bean).isEqualTo(draft.guide());
    } else {
      System.out.println("Toto guide " + draft.servedVersion() + " is not released; awaiting operator review: "
          + draft.pendingReview());
      assertThat(bean.version()).isEqualTo(ReviewedTotoGuide.UNRELEASED_VERSION);
      assertThat(bean.articles()).isEmpty();
    }
  }

  /** Every paragraph is a passage Toto's own code check accepts when quoted whole, citing its article. */
  @Test void everyShippedParagraphPassesTotoAnswerChecksWhenQuoted() {
    var guide = ReviewedTotoGuide.readClasspath().guide();
    var task = TotoAnswerTask.task(guide);
    for (var article : guide.articles()) {
      for (String paragraph : article.text().split("\n\\s*\n")) {
        ObjectNode output = JSON.createObjectNode().put("kind", "ANSWER").putNull("decline_reason");
        ObjectNode item = output.putArray("paragraphs").addObject().put("text", paragraph.replaceAll("\\s+", " "));
        item.putArray("article_ids").add(article.id());
        assertThat(task.parse().apply(output).paragraphs()).as(article.id() + ": " + paragraph).hasSize(1);
      }
    }
  }

  @ParameterizedTest
  @ValueSource(strings = {"id", "title", "version", "reviewed_by", "reviewed_on"})
  void anArticleMissingAFrontMatterFieldFails(String field) {
    var files = fixture(false, false);
    files.put(path("doc-fee"), files.get(path("doc-fee")).replaceFirst("(?m)^" + field + ":.*\n", ""));
    assertThatThrownBy(() -> ReviewedTotoGuide.read(withDigest(files)::get))
        .hasMessageContaining("missing the front matter field " + field);
  }

  @Test void aDuplicateIdFails() {
    var listedTwice = fixture(false, false);
    listedTwice.put(ReviewedTotoGuide.MANIFEST, manifest(listedTwice, false, List.of("doc-fee", "four-square", "doc-fee")));
    assertThatThrownBy(() -> ReviewedTotoGuide.read(listedTwice::get)).hasMessageContaining("Duplicate Toto guide article id");

    var declaredTwice = fixture(false, false);
    declaredTwice.put(path("four-square"), declaredTwice.get(path("four-square")).replaceFirst("id: four-square", "id: doc-fee"));
    assertThatThrownBy(() -> ReviewedTotoGuide.read(withDigest(declaredTwice)::get)).hasMessageContaining("must match its file name");
  }

  @Test void aReleasedGuideFailsOnAnArticleWhoseReviewedOnIsEmpty() {
    var files = fixture(true, true);
    files.put(path("four-square"), files.get(path("four-square"))
        .replaceFirst("reviewed_by: .*\n", "reviewed_by:\n").replaceFirst("reviewed_on: .*\n", "reviewed_on:\n"));
    assertThatThrownBy(() -> ReviewedTotoGuide.read(withDigest(files)::get))
        .hasMessageContaining("pending: [four-square]");
  }

  @Test void aPartlyRecordedOrMalformedReviewFailsEvenBeforeRelease() {
    var partly = fixture(false, true);
    partly.put(path("doc-fee"), partly.get(path("doc-fee")).replaceFirst("reviewed_on: .*\n", "reviewed_on:\n"));
    assertThatThrownBy(() -> ReviewedTotoGuide.read(withDigest(partly)::get)).hasMessageContaining("only partly");

    var malformed = fixture(false, true);
    malformed.put(path("doc-fee"), malformed.get(path("doc-fee")).replaceFirst("reviewed_on: .*\n", "reviewed_on: soon\n"));
    assertThatThrownBy(() -> ReviewedTotoGuide.read(withDigest(malformed)::get)).hasMessageContaining("ISO date");
  }

  @Test void aReleasedReviewedGuideIsWhatTheModelTaskIsGiven() {
    var draft = ReviewedTotoGuide.read(withDigest(fixture(true, true))::get);
    var guide = draft.served();
    assertThat(guide.articles()).extracting(TotoGuideLoader.Article::id).containsExactly("doc-fee", "four-square");
    assertThat(guide.version()).isEqualTo(draft.servedVersion()).startsWith("toto-guide.v1.");
    assertThat(guide.articles().get(0).link()).isEqualTo("?guide=dealer-fees");
    var task = TotoAnswerTask.task(guide);
    assertThat(task.promptVersion()).isEqualTo("toto-answer-prompt.v1." + guide.version());
    assertThat(task.instructions()).contains("Version: " + guide.version())
        .contains(guide.articles().get(1).text()).doesNotContain("reviewed_by");
  }

  @Test void changedArticleTextFailsUntilItsDigestIsRecordedAndThenChangesTheVersion() {
    var original = withDigest(fixture(true, true));
    String before = ReviewedTotoGuide.read(original::get).servedVersion();
    var edited = new HashMap<>(original);
    edited.put(path("doc-fee"), edited.get(path("doc-fee")) + "\nA new passage.\n");
    assertThatThrownBy(() -> ReviewedTotoGuide.read(edited::get)).hasMessageContaining("guide text changed");
    assertThat(ReviewedTotoGuide.read(withDigest(edited)::get).servedVersion()).isNotEqualTo(before);
  }

  @Test void signingAnArticleOffDoesNotChangeWhatTheModelSees() {
    assertThat(ReviewedTotoGuide.parse(fixture(false, true)::get).computedDigest())
        .isEqualTo(ReviewedTotoGuide.parse(fixture(false, false)::get).computedDigest());
  }

  @Test void unquotableOrUnknownArticleContentFails() {
    var longParagraph = fixture(false, false);
    longParagraph.put(path("doc-fee"), longParagraph.get(path("doc-fee")) + "\n" + "word ".repeat(130) + "\n");
    assertThatThrownBy(() -> ReviewedTotoGuide.read(withDigest(longParagraph)::get)).hasMessageContaining("over 600");

    var contact = fixture(false, false);
    contact.put(path("doc-fee"), contact.get(path("doc-fee")) + "\nCall 555-123-4567 for help.\n");
    assertThatThrownBy(() -> ReviewedTotoGuide.read(withDigest(contact)::get)).hasMessageContaining("contact detail");

    var unknown = fixture(false, false);
    unknown.put(path("doc-fee"), unknown.get(path("doc-fee")).replaceFirst("version: 1\n", "version: 1\nauthor: someone\n"));
    assertThatThrownBy(() -> ReviewedTotoGuide.read(withDigest(unknown)::get)).hasMessageContaining("unknown front matter field");
  }

  /** Two shipped articles, optionally signed off and released, with the right digest. */
  private static Map<String, String> fixture(boolean released, boolean reviewed) {
    var files = new HashMap<String, String>();
    for (String id : List.of("doc-fee", "four-square")) {
      String text = classpath(path(id));
      if (reviewed) {
        text = text.replaceFirst("reviewed_by:\n", "reviewed_by: Fixture reviewer\n")
            .replaceFirst("reviewed_on:\n", "reviewed_on: 2026-10-20\n");
      }
      files.put(path(id), text);
    }
    files.put(ReviewedTotoGuide.MANIFEST, manifest(files, released, List.of("doc-fee", "four-square")));
    return withDigest(files);
  }

  private static String manifest(Map<String, String> files, boolean released, List<String> ids) {
    ObjectNode manifest = JSON.createObjectNode().put("version", "toto-guide.v1").put("released", released)
        .put("digest", "sha256:unrecorded");
    ArrayNode articles = manifest.putArray("articles");
    ids.forEach(articles::add);
    return manifest.toString();
  }

  /** Records the digest of the fixture's current text, as an author does after reviewing a change. */
  private static Map<String, String> withDigest(Map<String, String> files) {
    var copy = new HashMap<>(files);
    try {
      ObjectNode manifest = (ObjectNode) JSON.readTree(copy.get(ReviewedTotoGuide.MANIFEST));
      manifest.put("digest", ReviewedTotoGuide.parse(copy::get).computedDigest());
      copy.put(ReviewedTotoGuide.MANIFEST, manifest.toString());
    } catch (IOException invalid) {
      throw new AssertionError(invalid);
    } catch (IllegalStateException unparseable) {
      return copy; // The test expects this fixture to fail parsing; leave the digest as it is.
    }
    return copy;
  }

  private static String path(String id) {
    return ReviewedTotoGuide.ARTICLES + id + ".md";
  }

  private static String classpath(String path) {
    try (var in = ReviewedTotoGuideTest.class.getClassLoader().getResourceAsStream(path)) {
      return new String(in.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
    } catch (IOException cause) {
      throw new AssertionError(cause);
    }
  }
}
