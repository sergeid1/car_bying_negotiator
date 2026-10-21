package com.dealanalyzer.ai;

import java.util.Map;
import java.util.regex.Pattern;

/**
 * One code-owned list for shipped-copy checks and untrusted model answers. {@link #PATTERNS} is the English list;
 * {@link #BY_LANGUAGE} holds one list per language a Buyer can see, so each catalogue is checked in its own words.
 */
public final class ProhibitedClaims {
  private ProhibitedClaims() {}

  public static final Map<String, Pattern> PATTERNS = Map.of(
      "illegal", word("illegal|unlawful|violates the law"),
      "fraud", word("fraud|fraudulent|scam|deceptive"),
      "advice", word("advice|we advise|you should|we recommend that you"),
      "safe to sign", Pattern.compile("safe to sign", Pattern.CASE_INSENSITIVE),
      "guarantee", word("guarantee|guaranteed|guarantees|risk-free|best[\\s_-]*rate|cheapest"),
      "savings", Pattern.compile("(?i)\\b(you will save|savings of|save you \\$)"),
      "negotiates for you", word("(we|it) negotiates?|negotiates? (for|with dealers for)"
          + " (you|the buyer)|negotiating for (you|the buyer)"),
      "on your behalf", word("on (your|the buyer['’]s|a buyer['’]s|buyers['’]) behalf"),
      "agent", word("our agent|your agent|ai agent|ai negotiator|autonomous|autonomously"
          + "|(your|the buyer['’]s) representative|represents? (you|the buyer)"),
      "contacts dealers", word("(we|deal x-ray|the platform|the product) (will )?(contacts?|emails?|writes? to)"
          + " (the |a |your )?dealers?|(contacts?|emails?|writes? to) (the |a |your )?dealers?"
          + " (for (you|the buyer)|itself|in its own name)"));

  /**
   * The Spanish list: the same claims in Spanish words, reviewed against the Spanish catalogue in ticket 146.
   * "Garantía" alone is left out on purpose: in Spanish it is also the word for a vehicle warranty, which findings
   * name. A claim needs its subject next to its verb ("otdog se comunica con el concesionario"), so the catalogue's
   * negations ("otdog no se comunica con el concesionario") read as what they are.
   */
  public static final Map<String, Pattern> SPANISH = Map.of(
      "illegal", palabra("ilegal|ilegales|ilícit[oa]s?|viola la ley"),
      "fraud", palabra("fraude|fraudulent[oa]s?|estafa|estafas|engaños[oa]s?"),
      "advice", palabra("asesoría|asesoramiento|le aconsejamos|le recomendamos que|usted debería|debería usted"),
      "safe to sign", palabra("es seguro (de )?firmar(lo|la)?|puede firmar(lo|la)? (con seguridad|con confianza|sin riesgo|sin miedo|tranquil[oa])"
          + "|firmar(lo|la)? sin riesgo|firme (tranquil[oa]|con confianza|sin miedo)"),
      "guarantee", palabra("garantizad[oa]s?|garantiza(r|n|mos|remos)?|garantía de ahorro|sin riesgos?"
          + "|(la|las) mejor(es)? tasas?|(el|los) mejor(es)? precios?|(la|las) tasas? (de interés )?más baj[oa]s?"
          + "|(el|los) precios? más baj[oa]s?|más barat[oa]s?|el más barato"),
      "savings", palabra("(usted )?ahorrará(n)?|va a ahorrar|le ahorramos|le ahorrará|le haremos ahorrar|ahorros? garantizados?"
          + "|ahorros? de \\$"),
      "negotiates for you", palabra("(nosotros )?negociamos|negociaremos|(otdog|la plataforma) negocia(rá)?"
          + "|negocia(rá)? (por|para) (usted|el comprador)|negociando (por|para) (usted|el comprador)"),
      "on your behalf", palabra("en su nombre|en nombre (suyo|del comprador|de usted)|en su representación"
          + "|en representación (suya|del comprador)|de parte suya|por cuenta suya"),
      "agent", palabra("nuestro agente|su agente|como su agente|agente de ia|negociador de ia|autónom[oa]|autónomamente"
          + "|su representante|su intermediario|(lo|la|le) representa(mos)?"),
      "contacts dealers", palabra("(contactamos|escribimos|llamamos|enviamos correos?) (a|al|a los) (concesionarios?|vendedores?)"
          + "|nos comunicamos con (el|los) (concesionarios?|vendedores?)"
          + "|(otdog|la plataforma) (contacta|escribe|llama) (a|al|a los) (concesionarios?|vendedores?)"
          + "|(otdog|la plataforma) se comunica(rá)? con (el|los|un) (concesionarios?|vendedores?)"));

  /** Claim patterns by language code, matching the client's message catalogues under {@code frontend/src/i18n}. */
  public static final Map<String, Map<String, Pattern>> BY_LANGUAGE = Map.of("en", PATTERNS, "es", SPANISH);

  public static boolean contains(String text) {
    return contains(text, "en");
  }

  /**
   * Whether text in the given language makes a prohibited claim. Text in another language is also checked against
   * English, because a translation can carry an English phrase through unchanged.
   */
  public static boolean contains(String text, String language) {
    String normalized = text.replaceAll("\\s+", " ").strip();
    return BY_LANGUAGE.getOrDefault(language, PATTERNS).values().stream()
            .anyMatch(pattern -> pattern.matcher(normalized).find())
        || !"en".equals(language) && PATTERNS.values().stream().anyMatch(pattern -> pattern.matcher(normalized).find());
  }

  private static Pattern word(String alternatives) {
    return Pattern.compile("(?i)\\b(" + alternatives + ")\\b");
  }

  /** Like {@link #word}, but with Unicode letters, so accented words such as "autónomo" keep their boundaries. */
  private static Pattern palabra(String alternatives) {
    return Pattern.compile("(?iu)(?<![\\p{L}\\p{N}_])(" + alternatives + ")(?![\\p{L}\\p{N}_])");
  }
}
