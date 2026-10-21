package com.dealanalyzer.explanation;

import java.util.Map;

/** Closed translations of the grounded English templates; paperwork and model prose are never interpolated. */
public final class SpanishExplanation {
  public static final String SCOPE = "Estas explicaciones abarcan solo la coherencia financiera. No evalúan el cumplimiento legal, el estado mecánico, el título, el historial del vehículo ni la seguridad de la compra. No se prometen ahorros.";
  private static final Map<String, String> TEXT = Map.ofEntries(
      Map.entry("This report cannot verify the deal against the approved terms.", "Este informe no puede verificar la oferta frente a los términos aprobados."),
      Map.entry("The financial checks require review before you decide.", "Las comprobaciones financieras requieren revisión antes de que usted decida."),
      Map.entry("The recorded financial checks match the approved deal within their stated scope.", "Las comprobaciones financieras registradas coinciden con la oferta aprobada dentro de su alcance declarado."),
      Map.entry("The quoted payment differs from the calculated payment beyond the recorded tolerance.", "El pago cotizado difiere del pago calculado más allá de la tolerancia registrada."),
      Map.entry("The report provides calculated financing costs for the verified inputs and recorded schedule.", "El informe presenta costos de financiamiento calculados con los datos verificados y el calendario registrado."),
      Map.entry("The verified financing inputs include financed add-ons.", "Los datos verificados de financiamiento incluyen productos adicionales financiados."),
      Map.entry("Financing costs are unavailable for reliance in this report.", "Los costos de financiamiento no están disponibles para basarse en ellos en este informe."),
      Map.entry("The saved cash calculation is stale or unverified for this report.", "El cálculo guardado de la compra al contado está desactualizado o sin verificar para este informe."),
      Map.entry("A market comparison is unavailable; this report does not establish a market price advantage.", "La comparación de mercado no está disponible; este informe no establece una ventaja de precio de mercado."),
      Map.entry("The verified selling price is above the estimated market selling-price range. This is not an out-the-door comparison.", "El precio de venta verificado está por encima del rango estimado de precios de venta de mercado. Esta comparación excluye los cargos adicionales de compra."));
  private SpanishExplanation() {}
  public static String text(String english) {
    String translated = TEXT.get(english);
    if (translated == null) throw new IllegalArgumentException("Missing Spanish explanation template");
    return translated;
  }
}
