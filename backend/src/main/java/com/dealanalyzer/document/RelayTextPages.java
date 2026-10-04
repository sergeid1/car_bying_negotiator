package com.dealanalyzer.document;

import java.awt.Color;
import java.awt.Font;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.util.ArrayList;
import java.util.List;
import javax.imageio.ImageIO;

/** Bounded plain text rendering; never interprets markup, URLs or dealer instructions. */
final class RelayTextPages {
  static List<String> pages(String text) {
    var lines = new ArrayList<String>();
    for (String line : text.split("\n", -1)) {
      if (line.isEmpty()) lines.add("");
      else for (int i = 0; i < line.length(); i += 90) lines.add(line.substring(i, Math.min(i + 90, line.length())));
    }
    var pages = new ArrayList<String>();
    for (int i = 0; i < lines.size(); i += 55) pages.add(String.join("\n", lines.subList(i, Math.min(i + 55, lines.size()))));
    if (pages.size() > 30) throw new InvalidDocumentException("PAGE_LIMIT_EXCEEDED", "The dealer quote exceeds the page limit.");
    return List.copyOf(pages);
  }
  static byte[] png(String text) throws java.io.IOException {
    var image = new BufferedImage(1275, 1650, BufferedImage.TYPE_INT_RGB);
    var graphics = image.createGraphics();
    try {
      graphics.setColor(Color.WHITE); graphics.fillRect(0, 0, 1275, 1650);
      graphics.setColor(Color.BLACK); graphics.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 20));
      int y = 65;
      for (String line : text.split("\n", -1)) { graphics.drawString(line, 50, y); y += 27; }
    } finally { graphics.dispose(); }
    var output = new ByteArrayOutputStream(); ImageIO.write(image, "png", output); return output.toByteArray();
  }
}
