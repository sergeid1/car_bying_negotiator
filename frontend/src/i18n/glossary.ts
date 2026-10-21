/**
 * The glossary of car-buying terms (ticket 144, working default 3; ticket 146). Dealers write their paperwork and
 * emails in English, so a Spanish-speaking Buyer needs the Spanish word to understand a term and the English word to
 * recognise it on the dealer's page. Every Spanish screen, finding and email uses the `spanish` term listed here, so
 * the same thing has the same name everywhere.
 *
 * `showEnglish` terms are printed by dealers: the first time one appears on a screen, the Spanish copy adds the
 * English term in parentheses, as in "precio total final (out-the-door)". Later uses on that screen give the Spanish
 * term alone. `forms` lists other accepted Spanish forms (plurals, short forms) a sentence may use instead.
 *
 * Register: neutral US Latin American Spanish that addresses the Buyer as "usted" (ticket 144, working default 2).
 * Product names (Desk Check, Toto) and the brand stay in English.
 */
export type GlossaryTerm = {
  readonly english: string;
  readonly spanish: string;
  readonly forms?: readonly string[];
  readonly showEnglish: boolean;
};

export const glossary = {
  outTheDoor: { english: "out-the-door", spanish: "precio total final", forms: ["total final"], showEnglish: true },
  sellingPrice: { english: "selling price", spanish: "precio de venta", showEnglish: true },
  buyersOrder: { english: "buyer's order", spanish: "orden de compra", forms: ["órdenes de compra"], showEnglish: true },
  docFee: { english: "doc fee", spanish: "cargo por documentación", forms: ["cargos por documentación"], showEnglish: true },
  dealerFees: { english: "dealer fees", spanish: "cargos del concesionario", forms: ["cargo del concesionario"], showEnglish: true },
  addOns: { english: "add-ons", spanish: "productos adicionales", forms: ["producto adicional"], showEnglish: true },
  marketAdjustment: { english: "market adjustment", spanish: "sobreprecio de mercado", showEnglish: true },
  msrp: { english: "MSRP", spanish: "precio sugerido por el fabricante", showEnglish: true },
  rebate: { english: "rebate", spanish: "reembolso del fabricante", forms: ["reembolsos del fabricante", "reembolso", "reembolsos"], showEnglish: true },
  tradeIn: { english: "trade-in", spanish: "vehículo en parte de pago", forms: ["vehículos en parte de pago", "parte de pago"], showEnglish: true },
  payoff: { english: "payoff", spanish: "saldo del préstamo", forms: ["saldos del préstamo", "saldo pendiente"], showEnglish: true },
  negativeEquity: { english: "negative equity", spanish: "saldo negativo", showEnglish: true },
  apr: { english: "APR", spanish: "tasa de porcentaje anual", forms: ["tasa anual"], showEnglish: true },
  downPayment: { english: "down payment", spanish: "pago inicial", showEnglish: true },
  monthlyPayment: { english: "monthly payment", spanish: "pago mensual", forms: ["pagos mensuales"], showEnglish: false },
  loanTerm: { english: "loan term", spanish: "plazo del préstamo", forms: ["plazo"], showEnglish: false },
  preApproval: { english: "pre-approval", spanish: "preaprobación", forms: ["preaprobaciones"], showEnglish: true },
  gap: { english: "GAP", spanish: "seguro GAP", showEnglish: false },
  serviceContract: { english: "service contract", spanish: "contrato de servicio", forms: ["contratos de servicio"], showEnglish: true },
  salesTax: { english: "sales tax", spanish: "impuesto sobre las ventas", showEnglish: true },
  titleAndRegistration: { english: "title and registration", spanish: "título y registro", showEnglish: true },
  highwayUseTax: { english: "highway use tax", spanish: "impuesto por uso de carreteras", showEnglish: true },
  governmentCharges: { english: "government charges", spanish: "cargos del gobierno", showEnglish: false },
  vin: { english: "VIN", spanish: "número de identificación del vehículo", showEnglish: true },
  fourSquare: { english: "four-square", spanish: "hoja de cuatro cuadros", showEnglish: true },
  financeOffice: { english: "F&I", spanish: "oficina de financiamiento", showEnglish: true },
  dealer: { english: "dealer", spanish: "concesionario", forms: ["concesionarios"], showEnglish: false },
  quote: { english: "quote", spanish: "cotización", forms: ["cotizaciones"], showEnglish: false },
  testDrive: { english: "test drive", spanish: "prueba de manejo", forms: ["pruebas de manejo"], showEnglish: false },
  lender: { english: "lender", spanish: "prestamista", forms: ["prestamistas"], showEnglish: false },
  financing: { english: "financing", spanish: "financiamiento", showEnglish: false },
  buyingCase: { english: "Buying Case", spanish: "caso de compra", forms: ["casos de compra"], showEnglish: false },
  buyingJourney: { english: "Buying Journey", spanish: "plan de compra", forms: ["planes de compra"], showEnglish: false },
  buyerAccount: { english: "Buyer Account", spanish: "cuenta de comprador", forms: ["cuenta"], showEnglish: false },
  agreedPrice: { english: "Agreed Price", spanish: "precio acordado", showEnglish: false },
  mandate: { english: "mandate", spanish: "límites de negociación", showEnglish: false },
} as const satisfies Record<string, GlossaryTerm>;

export type GlossaryId = keyof typeof glossary;

/** The Spanish term with the English term the dealer prints, for a term's first use on a screen. */
export function withEnglish(id: GlossaryId) {
  const term: GlossaryTerm = glossary[id];
  return `${term.spanish} (${term.english})`;
}
