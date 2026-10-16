package com.dealanalyzer.ai;

import java.util.HashSet;
import java.util.List;

/** Code-owned reviewed context, loaded once at startup; never supplied by a Buyer request. */
@FunctionalInterface
public interface TotoGuideLoader {
  Guide load();

  record Article(String id, String title, String text, String link) {
    public Article(String id, String title, String text) {
      this(id, title, text, null);
    }

    public Article {
      if (id == null || !id.matches("[a-z0-9-]{1,80}")
          || title == null || title.isBlank() || text == null || text.isBlank()) {
        throw new IllegalArgumentException("A guide article needs an id, title and text");
      }
      if (link != null && !link.matches("\\?guide=[a-z0-9-]{1,80}")) {
        throw new IllegalArgumentException("Guide links must identify an existing local client guide");
      }
    }
  }

  record Guide(String version, List<Article> articles) {
    public Guide {
      if (version == null || !version.matches("[a-z0-9.-]{1,60}")) {
        throw new IllegalArgumentException("A guide needs a short code-owned version");
      }
      articles = List.copyOf(articles);
      var ids = new HashSet<String>();
      for (Article article : articles) {
        if (!ids.add(article.id())) throw new IllegalArgumentException("Duplicate guide article");
      }
    }
  }
}
