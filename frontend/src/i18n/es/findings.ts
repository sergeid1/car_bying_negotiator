import { translation } from "../messages";
import type { enFindings } from "../en/findings";
import { templates } from "./findingTemplates";

export const esFindings = translation<typeof enFindings>()({
  englishName: "English", spanishName: "Español",
  caseLanguage: "Idioma de este caso", caseLanguageNote: "Los correos y los informes descargados usan este idioma.",
  caseLanguageFailed: "No se pudo guardar el idioma del caso. Inténtelo de nuevo.", caseLanguageSaved: "Se guardó el idioma del caso.",
  readingCopy: "Para que usted lo lea — el concesionario recibe el texto en inglés.",
  readingUnavailable: "No hay una copia de lectura disponible para este texto editado. Revise el texto exacto en inglés antes de aprobarlo.",
  MATCHES_APPROVED_BASELINE: "Coincide con la versión de referencia aprobada", REVIEW_REQUIRED: "Requiere revisión", UNABLE_TO_VERIFY: "No se puede verificar",
  STRONG_DEAL: "Oferta favorable", FAIR_NEGOTIATE: "Razonable, negociar", POOR_DEAL: "Oferta desfavorable", INSUFFICIENT_DATA: "Datos insuficientes",
  EXACT: "Exacto", ESTIMATED: "Estimado", UNAVAILABLE: "No disponible", AVAILABLE: "Disponible", STALE_OR_UNVERIFIED: "Desactualizado o sin verificar",
  LOW: "Baja", MEDIUM: "Media", HIGH: "Alta", NOT_APPLICABLE: "No corresponde", UNSUPPORTED: "No admitido",
  COMPLETE: "Completo", PARTIAL: "Parcial", VERIFIED: "Verificado", UNVERIFIED: "Sin verificar",
  BASELINE: "Versión de referencia", PREVIOUS: "Versión anterior", MATERIAL: "Relevante", INFORMATIONAL: "Informativo",
  ADDED: "Agregado", MISSING: "Faltante", CHANGED: "Modificado", UNCERTAIN: "Incierto", LIMIT_VIOLATION: "Supera el límite aprobado",
  SELLING_PRICE: "Precio de venta (selling price)", DISCOUNT: "Descuento", DISCOUNT_TOTAL: "Descuento total", REBATE: "Reembolso del fabricante (rebate)", REBATE_TOTAL: "Reembolsos totales",
  DEALER_FEE: "Cargo del concesionario", ADD_ON: "Producto adicional (add-on)", TAX: "Impuesto", TAXABLE: "Gravable", NON_TAXABLE: "No gravable", EXEMPT: "Exento",
  OPTIONAL: "Opcional", REQUIRED: "Obligatorio", GOVERNMENT_CHARGE: "Cargo del gobierno", TRADE: "Vehículo en parte de pago (trade-in)", CREDIT: "Crédito",
  TRADE_ALLOWANCE: "Valor en parte de pago", TRADE_PAYOFF: "Saldo del préstamo del vehículo en parte de pago", TRADE_VIN: "VIN del vehículo en parte de pago", VEHICLE_VIN: "VIN del vehículo",
  VEHICLE_YEAR: "Año del vehículo", VEHICLE_MAKE: "Marca", VEHICLE_MODEL: "Modelo", VEHICLE_TRIM: "Versión", VEHICLE_MILEAGE: "Millaje",
  APR: "Tasa de porcentaje anual (APR)", TERM_MONTHS: "Plazo del préstamo", MONTHLY_PAYMENT: "Pago mensual", CASH_DOWN: "Pago inicial (down payment)",
  OUT_THE_DOOR_TOTAL: "Precio total final (out-the-door)", SUBTOTAL: "Subtotal", TAX_TOTAL: "Impuestos totales", FINANCE_CHARGE: "Cargo de financiamiento", TOTAL_OF_PAYMENTS: "Total de pagos",
  DEALER_CHARGES_TOTAL: "Cargos totales del concesionario", GOVERNMENT_CHARGES_TOTAL: "Cargos totales del gobierno", ADD_ON_TOTAL: "Productos adicionales totales",
  MONTH: "Mes", MONTHS: "Meses", GOOD: "Bueno", FAIR: "Regular", EXCELLENT: "Excelente", POOR: "Malo",
  ...templates,
});
