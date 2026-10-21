import { enFindings } from "./en/findings";
import { esFindings } from "./es/findings";

export type FindingParameter = string | number | boolean | null | { minorUnits: number; currency: string } | { date: string } | { fromDate: string; toDate: string }
  | Finding | { items: Finding[]; mode: "and" | "or" | "sentences" };
export type Finding = { code: string; parameters?: Record<string, FindingParameter>; text: string };

/** Unknown codes retain their stored English wording. Missing parameters never leave raw placeholders. */
export function findingText(language: string, finding: Finding): string {
  const en: Record<string, string> = enFindings;
  const es: Record<string, string> = esFindings;
  const template = (language === "es" ? es[finding.code] : undefined) ?? en[finding.code];
  if (!template) return finding.text;
  let complete = true;
  const text = template.replace(/\{(\w+)\}/g, (_, key: string) => {
    const value = finding.parameters?.[key];
    if (value === undefined || value === null) { complete = false; return ""; }
    if (typeof value === "object") {
      if ("fromDate" in value) {
        const format = new Intl.DateTimeFormat(language === "es" ? "es-US" : "en-US", { dateStyle: "long", timeZone: "UTC" });
        return format.format(new Date(`${value.fromDate}T00:00:00Z`)) + (language === "es" ? " a " : " to ") + format.format(new Date(`${value.toDate}T00:00:00Z`));
      }
      if ("code" in value) return findingText(language, value);
      if ("items" in value) return value.items.map(item => findingText(language, item)).join(
        value.mode === "sentences" ? " " : language === "es" ? value.mode === "or" ? " o " : " y " : value.mode === "or" ? " or " : " and ");
      if ("minorUnits" in value) return new Intl.NumberFormat(language === "es" ? "es-US" : "en-US", { style: "currency", currency: value.currency }).format(value.minorUnits / 100);
      return new Intl.DateTimeFormat(language === "es" ? "es-US" : "en-US", { dateStyle: "long", timeZone: "UTC" }).format(new Date(`${value.date}T00:00:00Z`));
    }
    return key === "status" ? findingText(language, { code: String(value), text: String(value) }) : String(value);
  });
  return complete ? text : finding.text;
}

export function findingCode(language: string, code: string): string {
  return findingText(language, { code, text: code });
}

const readingTemplates = Object.entries(enFindings).filter(([code, template]) => !/^\{\w+\}$/.test(template)
  && !["DESK_COMBINED", "DESK_NAMED_ADDON"].includes(code)).map(([code, template]) => {
  const names: string[] = [];
  const pattern = template.split(/(\{\w+\})/).map(part => {
    if (part.startsWith("{")) { names.push(part.slice(1, -1)); return "(.+?)"; }
    return part.replace(/[.*+?^${}()|[\]\\]/g, "\\$&");
  }).join("");
  return { code, names, regex: new RegExp(`^${pattern}$`) };
});

/** Compatibility for generated template text served outside the report payload. Unknown text stays stored English. */
export function serverText(language: string, english: string): string {
  if (language !== "es") return english;
  for (const template of readingTemplates) {
    const match = template.regex.exec(english);
    if (!match) continue;
    const parameters: Record<string, FindingParameter> = {};
    template.names.forEach((name, index) => { parameters[name] = match[index + 1]; });
    return findingText(language, { code: template.code, parameters, text: english });
  }
  return english;
}

/** Reading copies never replace or mutate an English draft. Unknown edited prose is not guessed. */
export function dealerReadingCopy(english: string): string | undefined {
  const translated: string[] = [];
  for (const paragraph of english.split(/\n+/).filter(Boolean)) {
    const sentences = paragraph.trim().split(/(?<=[.?])\s+(?=[A-Z])/);
    const parts: string[] = [];
    for (const sentence of sentences) {
      let found = false;
      for (const template of readingTemplates) {
        if (!template.code.startsWith("DRAFT_")) continue;
        const match = template.regex.exec(sentence);
        if (!match) continue;
        const parameters: Record<string, FindingParameter> = {};
        template.names.forEach((name, index) => { parameters[name] = match[index + 1]; });
        parts.push(findingText("es", { code: template.code, parameters, text: sentence }));
        found = true; break;
      }
      if (!found) return undefined;
    }
    translated.push(parts.join(" "));
  }
  return translated.join("\n\n");
}

/** The sidecar holds JSON pointers into a report. Only known, existing text fields can be replaced. */
export function localizeReport<T extends { textFindings?: Record<string, Finding> }>(report: T, language: string): T {
  if (!report.textFindings || language === "en") return report;
  const copy: T = structuredClone(report);
  for (const [pointer, finding] of Object.entries(report.textFindings)) {
    if (!pointer.startsWith("/") || pointer.startsWith("/evidence/")) continue;
    const parts = pointer.slice(1).split("/").map(part => part.replaceAll("~1", "/").replaceAll("~0", "~"));
    // A report is data, and cannot select object prototypes or introduce new fields.
    if (parts.some(part => ["__proto__", "prototype", "constructor"].includes(part))) continue;
    let parent: unknown = copy;
    for (const part of parts.slice(0, -1)) {
      parent = parent && typeof parent === "object" && Object.hasOwn(parent, part)
        ? (parent as Record<string, unknown>)[part] : undefined;
    }
    const field = parts.at(-1)!;
    if (parent && typeof parent === "object" && Object.hasOwn(parent, field)
        && typeof (parent as Record<string, unknown>)[field] === "string")
      (parent as Record<string, unknown>)[field] = findingText(language, finding);
  }
  return copy;
}
