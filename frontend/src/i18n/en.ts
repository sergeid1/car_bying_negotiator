import { account } from "./en/account";
import { paperwork } from "./en/paperwork";
import { dealer } from "./en/dealer";
import { messages } from "./en/messages";
import { money } from "./en/money";
import { content } from "./en/content";

/**
 * The English catalogue: every Buyer-visible string, grouped by feature (ticket 145). It is the reference every other
 * language is checked against, and the fallback. Keep sentences whole; put changing parts in `{placeholders}`.
 * Each journey's copy lives in its own file under `en/`, so journeys can move into the catalogue independently.
 */
export const en = {
  app: {
    skipToWorkflow: "Skip to case workflow",
    homeLink: "otdog home",
    casePanelTitle: "This case",
    accountPanelSignedIn: "Your account",
    accountPanelLogIn: "Log in",
    accountPanelSignUp: "Sign up",
    requestFailed: "Request failed with status {status}",
    analysisFailed: "Analysis could not be completed",
  },
  footer: {
    label: "About otdog",
    onYourSide: "otdog is on your side. No dealer pays us, and no company can pay to change what you see. {link}",
    moneyLink: "How otdog makes money",
    scope: "It checks the numbers on the dealer's paperwork. It isn't legal, tax or mechanical advice, and it can't promise savings. You send every email and make every decision. · {link}",
    partnerKitLink: "Referral partner kit",
  },
  nav: {
    label: "Main",
    checkMyDeal: "Check my deal",
    checkMyDealShort: "My deal",
    exampleReport: "Example report",
    exampleReportShort: "Example",
    exampleReportName: "See an example report",
    howItWorks: "How it works",
    guides: "Guides",
  },
  home: {
    heading: "See the whole deal before you decide.",
    lede: "Check your dealer's quote line by line. See the full out-the-door total, spot the fees and add-ons worth questioning, and get the questions to ask before you sign. You send every email and make every decision.",
    pointDecisions: "You make every decision",
    pointNoAccount: "No account needed",
    pointNoDealers: "We never work for dealers",
  },
  xray: {
    heading: "Example buyer's order",
    page: "Page 1",
    vehicle: "2024 Toyota RAV4 XLE",
    dealer: "Example Motors",
    sellingPrice: "Selling price",
    docFee: "Documentation fee",
    paintProtection: "Paint protection",
    highwayUseTax: "Highway use tax",
    titleAndRegistration: "Title and registration",
    question: "Question {number}",
    total: "Out-the-door total",
  },
  theme: {
    menu: "Appearance",
    current: "Appearance: {choice}",
    system: "System",
    light: "Light",
    dark: "Dark",
  },
  sidePanel: {
    close: "Close {title}",
  },
  signingNote: "This checks the numbers on the paperwork. It is not a legal review. Read every page before you sign.",
  fixture: {
    step: "Development fixture",
    vehicle: "2021 Honda Accord EX-L",
    cashPurchase: "Cash purchase",
    vin: "VIN",
    mileage: "Mileage",
    mileageValue: "{miles} mi",
    buyerZip: "Buyer ZIP",
    dealerZip: "Dealer ZIP",
    sellingPrice: "Selling price",
    quotedComponents: "Quoted components",
    quotedComponentsValue: "Fees, add-ons, taxes, government charges",
    analyzing: "Analyzing…",
    analyze: "Analyze synthetic deal",
    stages: {
      QUEUED: "Queued",
      LOADING_INPUTS: "Loading recorded inputs",
      FETCHING_MARKET: "Reading market evidence",
      CALCULATING: "Calculating cash economics",
      ASSEMBLING_REPORT: "Assembling immutable report",
      COMPLETE: "Complete",
      FAILED: "Failed",
    },
  },
  syntheticReport: {
    version: "Report v{version}",
    title: "Deal Analysis Report",
    matchesApproved: "Matches approved deal",
    totals: "Deal totals",
    outTheDoor: "Out-the-door total",
    calculation: "{precision} calculation",
    marketRange: "Synthetic market range",
    confidence: "{confidence} confidence · estimated",
    makesUpTotal: "What makes up the total",
    sellingPrice: "Selling price",
    dealerFees: "Dealer fees",
    addOns: "Add-ons",
    taxes: "Taxes",
    governmentCharges: "Government charges",
    whyReview: "Why this needs review",
    limitations: "Report limitations",
    scope: "Scope and limitations",
  },
  account, paperwork, dealer, messages, money, content,
} as const;
