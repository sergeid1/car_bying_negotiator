import type { en } from "./en";
import { translation } from "./messages";
import { account } from "./es/account";
import { paperwork } from "./es/paperwork";
import { dealer } from "./es/dealer";
import { messages } from "./es/messages";
import { money } from "./es/money";
import { content } from "./es/content";

/**
 * The Spanish catalogue (ticket 146): neutral US Latin American Spanish that addresses the Buyer as "usted", written
 * for understanding rather than word for word. `translation<typeof en>()` holds it to every English id and
 * placeholder, so a missing string fails the build. Terms dealers print in English follow `glossary.ts`.
 */
export const es = translation<typeof en>()({
  app: {
    skipToWorkflow: "Ir al flujo del caso",
    homeLink: "Inicio de otdog",
    casePanelTitle: "Este caso",
    accountPanelSignedIn: "Su cuenta",
    accountPanelLogIn: "Iniciar sesión",
    accountPanelSignUp: "Crear cuenta",
    requestFailed: "La solicitud falló con el código {status}",
    analysisFailed: "No se pudo completar el análisis",
  },
  footer: {
    label: "Acerca de otdog",
    onYourSide: "otdog está de su lado. Ningún concesionario nos paga, y ninguna empresa puede pagar para cambiar lo que usted ve. {link}",
    moneyLink: "Cómo gana dinero otdog",
    scope: "Revisa los números de los documentos del concesionario. No es orientación legal, fiscal ni mecánica, y no puede prometer que pagará menos. Usted envía cada correo y toma cada decisión. · {link}",
    partnerKitLink: "Material para socios de referencia",
  },
  nav: {
    label: "Principal",
    checkMyDeal: "Revisar mi trato",
    checkMyDealShort: "Mi trato",
    exampleReport: "Informe de ejemplo",
    exampleReportShort: "Ejemplo",
    exampleReportName: "Ver un informe de ejemplo",
    howItWorks: "Cómo funciona",
    guides: "Guías",
  },
  home: {
    heading: "Vea el trato completo antes de decidir.",
    lede: "Revise la cotización del concesionario línea por línea. Vea el precio total final (out-the-door), detecte los cargos y productos adicionales (add-ons) que conviene cuestionar, y obtenga las preguntas que debe hacer antes de firmar. Usted envía cada correo y toma cada decisión.",
    pointDecisions: "Usted toma cada decisión",
    pointNoAccount: "No necesita cuenta",
    pointNoDealers: "Nunca trabajamos para concesionarios",
  },
  xray: {
    heading: "Ejemplo de orden de compra (buyer's order)",
    page: "Página 1",
    vehicle: "2024 Toyota RAV4 XLE",
    dealer: "Example Motors",
    sellingPrice: "Precio de venta (selling price)",
    docFee: "Cargo por documentación (doc fee)",
    paintProtection: "Protección de pintura",
    highwayUseTax: "Impuesto por uso de carreteras (highway use tax)",
    titleAndRegistration: "Título y registro (title and registration)",
    question: "Pregunta {number}",
    total: "Precio total final (out-the-door)",
  },
  theme: {
    menu: "Tema e idioma",
    current: "Tema e idioma: {choice}, {language}",
    themeGroup: "Tema",
    languageGroup: "Idioma",
    system: "Sistema",
    light: "Claro",
    dark: "Oscuro",
  },
  sidePanel: {
    close: "Cerrar {title}",
  },
  signingNote: "Esto revisa los números de los documentos. No es una revisión legal. Lea cada página antes de firmar.",
  fixture: {
    step: "Caso de prueba para desarrollo",
    vehicle: "2021 Honda Accord EX-L",
    cashPurchase: "Compra al contado",
    vin: "Número de identificación del vehículo (VIN)",
    mileage: "Millaje",
    mileageValue: "{miles} millas",
    buyerZip: "Código postal del comprador",
    dealerZip: "Código postal del concesionario",
    sellingPrice: "Precio de venta (selling price)",
    quotedComponents: "Partes cotizadas",
    quotedComponentsValue: "Cargos, productos adicionales (add-ons), impuestos, cargos del gobierno",
    analyzing: "Analizando…",
    analyze: "Analizar el trato de prueba",
    stages: {
      QUEUED: "En espera",
      LOADING_INPUTS: "Cargando los datos registrados",
      FETCHING_MARKET: "Leyendo datos del mercado",
      CALCULATING: "Calculando el costo al contado",
      ASSEMBLING_REPORT: "Preparando el informe definitivo",
      COMPLETE: "Completo",
      FAILED: "Falló",
    },
  },
  syntheticReport: {
    version: "Informe v{version}",
    title: "Informe de análisis del trato",
    matchesApproved: "Coincide con el trato aprobado",
    totals: "Totales del trato",
    outTheDoor: "Precio total final (out-the-door)",
    calculation: "Cálculo: {precision}",
    marketRange: "Rango de mercado de prueba",
    confidence: "Confianza: {confidence} · estimado",
    makesUpTotal: "Qué forma el total",
    sellingPrice: "Precio de venta (selling price)",
    dealerFees: "Cargos del concesionario (dealer fees)",
    addOns: "Productos adicionales (add-ons)",
    taxes: "Impuestos",
    governmentCharges: "Cargos del gobierno",
    whyReview: "Por qué hay que revisarlo",
    limitations: "Límites del informe",
    scope: "Alcance y límites",
  },
  account, paperwork, dealer, messages, money, content,
});
