package com.dealanalyzer.report;
import com.fasterxml.jackson.databind.JsonNode;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import org.apache.pdfbox.pdmodel.*;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.PDFont;
import org.apache.pdfbox.pdmodel.font.PDType0Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.springframework.stereotype.Component;
/** Both formats traverse exactly the stored payload; this renderer has no analysis dependencies. */
@Component
public class StoredReportRenderer {
  private record Line(String text, boolean heading) {}
  private List<Line> lines(JsonNode payload) {
    var lines=new ArrayList<Line>();
    lines.add(new Line("Deal Analysis Report - version " + payload.path("version").asText(), true));
    var analysis=payload.path("analysis");
    var total=ReportLead.total(payload);
    lines.add(new Line("Out-the-door total", true));
    lines.add(new Line(ReportLead.value(total) + " (" + total.path("precision").asText("UNAVAILABLE") + ")", false));
    if (!total.isNull()) lines.add(new Line("Evidence: " + total.path("source").asText() + " " + total.path("pointer").asText(), false));
    lines.add(new Line("Three material findings", true));
    int count=0;
    for(var reason:analysis.path("topReasons")) { if (count++ == 3) break; lines.add(new Line(reason.asText(),false)); }
    if (analysis.path("topReasons").size() < 3) lines.add(new Line((3-analysis.path("topReasons").size()) + " further findings: unknown in this saved report.", false));
    lines.add(new Line("Next action",true));
    lines.add(new Line(analysis.path("nextAction").asText(),false));
    lines.add(new Line("Questions to ask the dealer",true));
    count=0;
    for(var reason:analysis.path("topReasons")) {
      if(count == 3) break;
      lines.add(new Line("Could you explain this finding: " + reason.asText(),false));
      lines.add(new Line("Source: /analysis/topReasons/" + count++ + "; /evidence",false));
    }
    lines.add(new Line("Saved immutable report. Screen edits to questions are not saved in this payload.", false));
    lines.add(new Line(analysis.path("reviewLabel").asText(analysis.path("reviewStatus").asText()), true));
    lines.add(new Line("Rating: " + analysis.path("dealRating").asText().replace('_',' '), false));
    lines.add(new Line("As of: " + payload.path("asOf").asText(payload.path("generatedAt").asText()), false));
    lines.add(new Line("Material amounts",true));
    for(var amount:analysis.path("materialAmounts")) {
      String value=ReportLead.value(amount);
      lines.add(new Line(amount.path("label").asText()+": "+value+" ("+amount.path("precision").asText()+")",false));
      lines.add(new Line("Evidence: "+amount.path("source").asText()+" "+amount.path("pointer").asText(),false));
    }
    lines.add(new Line("Market evidence",true));
    if (analysis.path("marketEvidence").path("paragraphs").isArray()) {
      for (var paragraph : analysis.path("marketEvidence").path("paragraphs"))
        lines.add(new Line(paragraph.asText(), false));
    } else lines.add(new Line("Market evidence details are unavailable in this saved report.", false));
    var standaloneTactics = new ArrayList<JsonNode>();
    var linkedTactics = new ArrayList<JsonNode>();
    for (var finding : analysis.path("tacticFindings"))
      (finding.path("tradeFindingCode").asText("").isEmpty() ? standaloneTactics : linkedTactics).add(finding);
    if (!linkedTactics.isEmpty()) {
      lines.add(new Line("Trade-in findings", true));
      for (var tradeFinding : analysis.at("/tradeCheck/findings")) {
        lines.add(new Line(tradeFinding.path("summary").asText(), false));
        for (var tactic : linkedTactics) if (tradeFinding.path("code").asText().equals(tactic.path("tradeFindingCode").asText())) {
          lines.add(new Line(tactic.path("label").asText(), false));
          lines.add(new Line("Draft desk question (" + tactic.path("questionReview").asText() + "): " + tactic.path("deskQuestion").asText(), false));
          lines.add(new Line("Calculation: " + tactic.path("effectBasis").asText(), false));
          if (!tactic.path("noDollarEffectReason").isNull()) lines.add(new Line(tactic.path("noDollarEffectReason").asText(), false));
        }
      }
    }
    if (!standaloneTactics.isEmpty()) {
      lines.add(new Line("Worksheet tactics", true));
      for (var finding : standaloneTactics) {
        lines.add(new Line(finding.path("label").asText() + ": " + finding.path("summary").asText(), false));
        if (finding.path("monthlyEffectMinor").isNumber()) lines.add(new Line("Monthly effect: " + tacticMoney(finding.path("monthlyEffectMinor").asLong()), false));
        if (finding.path("totalEffectMinor").isNumber()) lines.add(new Line("Full-term effect: " + tacticMoney(finding.path("totalEffectMinor").asLong()), false));
        lines.add(new Line("Draft desk question (" + finding.path("questionReview").asText() + "): " + finding.path("deskQuestion").asText(), false));
        lines.add(new Line("Calculation: " + finding.path("effectBasis").asText(), false));
        if (!finding.path("noDollarEffectReason").isNull()) lines.add(new Line(finding.path("noDollarEffectReason").asText(), false));
      }
    }
    // Reports before ticket 115 have no listing price check, and render as they did.
    var listing = analysis.path("listingPriceCheck");
    if (listing.isObject()) {
      lines.add(new Line("The listing you saved", true));
      lines.add(new Line(listing.path("summary").asText(), false));
      if (listing.path("deskQuestion").isTextual()) lines.add(new Line("Question to ask: " + listing.path("deskQuestion").asText(), false));
      for (var condition : listing.path("priceConditions")) lines.add(new Line("The ad's fine print: " + condition.asText(), false));
      lines.add(new Line("From your own capture of the dealer's listing.", false));
    }
    lines.add(new Line("Limitations",true));
    for(var limitation:analysis.path("limitations")) lines.add(new Line(limitation.asText(),false));
    lines.add(new Line("Complete saved payload and evidence",true));
    lines.add(new Line("The following reference paths preserve all recorded fields. Unsupported font characters are identified by their Unicode code point.",false));
    walk(payload, "", lines);
    return lines;
  }
  private void walk(JsonNode node, String path, List<Line> lines) {
    if (node.isObject()) node.fields().forEachRemaining(field -> {
      String child=path + "/" + field.getKey();
      if (path.isEmpty() && field.getValue().isContainerNode()) lines.add(new Line(field.getKey(), true));
      walk(field.getValue(), child, lines);
    });
    else if (node.isArray()) {
      if (node.isEmpty()) lines.add(new Line(path + ": none recorded", false));
      for (int i=0;i<node.size();i++) walk(node.get(i), path + "/" + i, lines);
    } else lines.add(new Line(path + ": " + (node.isNull() ? "Unavailable" : node.asText()), false));
  }
  private String tacticMoney(long minor) {
    return (minor < 0 ? "-$" : "$") + java.math.BigDecimal.valueOf(minor, 2).abs().toPlainString();
  }
  public byte[] html(JsonNode payload) {
    var out=new StringBuilder("<!doctype html><html lang=\"en\"><meta charset=\"utf-8\"><meta name=\"viewport\" content=\"width=device-width, initial-scale=1\"><title>Saved Deal Analysis Report</title><style>body{font:16px/1.5 system-ui,sans-serif;max-width:72ch;margin:2rem auto;padding:0 1rem;color:#172b3a}p{overflow-wrap:anywhere}h1,h2{break-after:avoid}@media print{body{font-size:10pt;margin:0}p{orphans:3;widows:3}}</style><main>");
    boolean first=true;
    for (var line:lines(payload)) {
      String tag=line.heading() ? first ? "h1" : "h2" : "p";
      out.append('<').append(tag).append('>').append(escape(line.text())).append("</").append(tag).append('>');
      first=false;
    }
    return out.append("</main></html>").toString().getBytes(StandardCharsets.UTF_8);
  }
  private String escape(String value) { return value.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;"); }
  public byte[] pdf(JsonNode payload) {
    try (var document=new PDDocument(); var output=new ByteArrayOutputStream()) {
      var font=PDType0Font.load(document, StoredReportRenderer.class.getResourceAsStream("/org/apache/pdfbox/resources/ttf/LiberationSans-Regular.ttf"));
      var bold=font;
      PDPageContentStream stream=null; float y=0; int pageNumber=0;
      try {
        for (var line:lines(payload)) {
          var selected=line.heading() ? bold : font; int size=line.heading() ? 14 : 10;
          String remaining=printable(line.text(), selected);
          do {
            if (stream==null || y < (line.heading() ? 115 : 64)) {
              if (stream!=null) stream.close();
              var page=new PDPage(PDRectangle.LETTER); document.addPage(page);
              stream=new PDPageContentStream(document, page); y=744; pageNumber++;
              stream.beginText(); stream.setFont(font, 9); stream.newLineAtOffset(48, 30);
              stream.showText("Saved Deal Analysis Report | Page " + pageNumber); stream.endText();
            }
            int end=remaining.length();
            while (end>1 && selected.getStringWidth(remaining.substring(0,end))*size/1000 > 516) end--;
            if (end<remaining.length()) { int space=remaining.lastIndexOf(' ',end); if(space>end/2) end=space; }
            stream.beginText(); stream.setFont(selected,size); stream.newLineAtOffset(48,y);
            stream.showText(remaining.substring(0,end)); stream.endText(); y-=size+5;
            remaining=remaining.substring(end).stripLeading();
          } while (!remaining.isEmpty());
          y-=line.heading() ? 8 : 4;
        }
      } finally { if(stream!=null) stream.close(); }
      document.save(output); return output.toByteArray();
    } catch (java.io.IOException failure) { throw new IllegalStateException("Report rendering unavailable; retry safely", failure); }
  }
  private String printable(String value, PDFont font) {
    var out=new StringBuilder();
    value.codePoints().forEach(cp -> {
      String character=new String(Character.toChars(cp));
      if (Character.isISOControl(cp)) { out.append(' '); return; }
      try { font.encode(character); out.append(character); }
      catch (IllegalArgumentException | java.io.IOException unsupported) { out.append(String.format("[U+%04X]",cp)); }
    });
    return out.toString();
  }
}
