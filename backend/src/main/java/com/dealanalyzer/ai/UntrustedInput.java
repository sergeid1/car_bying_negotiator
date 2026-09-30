package com.dealanalyzer.ai;

/**
 * Uploaded or provider content handed to a model. It is data to be read, never instructions: the
 * harness places it only inside the user turn, escaped and labelled, and nothing in it can reach the
 * system prompt, the output schema, the model identifier, or the requested operation.
 */
public sealed interface UntrustedInput {
  /** A code-owned description such as "page 2 recognized text"; never derived from the content. */
  String label();

  record Text(String label, String content) implements UntrustedInput {
    public Text {
      requireLabel(label);
      content = content == null ? "" : content;
    }
  }

  record PngImage(String label, byte[] png) implements UntrustedInput {
    public PngImage {
      requireLabel(label);
      if (png == null || png.length == 0) throw new IllegalArgumentException("An image input needs bytes");
      png = png.clone();
    }

    @Override public byte[] png() { return png.clone(); }
  }

  private static void requireLabel(String label) {
    if (label == null || !label.matches("[a-z0-9 ]{1,60}")) {
      throw new IllegalArgumentException("Untrusted input labels are short code-owned lowercase text");
    }
  }
}
