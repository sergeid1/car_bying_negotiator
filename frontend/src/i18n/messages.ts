import { createElement, Fragment, type ReactNode } from "react";

/**
 * Message catalogues (ticket 145). Every Buyer-visible string lives in one catalogue per language, grouped by
 * feature. A message is a whole sentence with named `{placeholders}`, never assembled from fragments, because word
 * order differs between languages. A count-dependent message is a `plural(...)` with one form per plural category.
 */

/** The placeholder names a message uses, read from its text at the type level: "{count} of {total}" → "count" | "total". */
export type Placeholders<S> = S extends `${string}{${infer Name}}${infer Rest}` ? Name | Placeholders<Rest> : never;

type PluralCategory = "zero" | "one" | "two" | "few" | "many" | "other";

/** A message whose form depends on `count`, chosen with `Intl.PluralRules`; `other` is the fallback form. */
export type Plural = { readonly kind: "plural"; readonly forms: { readonly [C in PluralCategory]?: string } & { readonly other: string } };

export function plural<const F extends { [C in PluralCategory]?: string } & { other: string }>(forms: F) {
  return { kind: "plural", forms } as const;
}

export type Message = string | Plural;

/** The placeholders of a message, over every plural form; a plural message always takes `count`. */
type MessagePlaceholders<M> = M extends string ? Placeholders<M>
  : M extends { forms: infer F } ? Placeholders<F[keyof F]> | "count" : never;

/** The values a message needs: one for each placeholder. Text fills a string; a node (a link, a strong) fills `fillNodes`. */
export type Values<M, V = string | number> = [MessagePlaceholders<M>] extends [never] ? Record<string, never>
  : { [K in MessagePlaceholders<M>]: K extends "count" ? number : V };

/** Each translation leaf must use exactly the placeholders the English message uses. */
type Same<A, B> = [A] extends [B] ? ([B] extends [A] ? true : false) : false;
type Parity<E, S> = {
  readonly [K in keyof E]: K extends keyof S
    ? E[K] extends Message
      ? Same<MessagePlaceholders<E[K]>, MessagePlaceholders<S[K]>> extends true ? S[K]
        : { error: "placeholders differ from English"; english: MessagePlaceholders<E[K]> }
      : Parity<E[K], S[K]>
    : E[K];
} & { readonly [K in Exclude<keyof S, keyof E>]: never };

/**
 * Checks a translation against the English catalogue at compile time: a missing id, an id English does not have, or
 * a message whose placeholders differ from English fails the type check instead of falling back at run time.
 */
export function translation<E>() {
  return <const S>(messages: S & Parity<E, S>): S => messages;
}

let pluralRules: { language: string; rules: Intl.PluralRules } | undefined;

function chooseForm(message: Plural, language: string, count: number) {
  if (pluralRules?.language !== language) pluralRules = { language, rules: new Intl.PluralRules(language) };
  const forms: { [C in PluralCategory]?: string } = message.forms;
  return forms[pluralRules.rules.select(count) as PluralCategory] ?? message.forms.other;
}

function textOf(message: Message, language: string, values: Record<string, unknown>) {
  return typeof message === "string" ? message : chooseForm(message, language, Number(values.count));
}

const PLACEHOLDER = /\{(\w+)\}/g;

/** Fills a message's placeholders with text; numbers format through the language's number rules. */
export function fillIn<M extends Message>(language: string, message: M, values: Values<M>): string {
  const given = values as Record<string, string | number>;
  return textOf(message, language, given).replace(PLACEHOLDER, (whole, name: string) => {
    const value = given[name];
    if (value === undefined) return whole;
    return typeof value === "number" ? value.toLocaleString(language) : value;
  });
}

/** Fills a message's placeholders with nodes, such as a link inside a sentence, keeping the sentence whole. */
export function fillInNodes<M extends Message>(language: string, message: M, values: Values<M, ReactNode>): ReactNode {
  const given = values as Record<string, ReactNode>;
  const text = textOf(message, language, given);
  const parts: ReactNode[] = [];
  let last = 0;
  for (const match of text.matchAll(PLACEHOLDER)) {
    parts.push(text.slice(last, match.index));
    const value = given[match[1]];
    parts.push(createElement(Fragment, { key: parts.length },
      typeof value === "number" ? value.toLocaleString(language) : value === undefined ? match[0] : value));
    last = match.index + match[0].length;
  }
  parts.push(text.slice(last));
  return createElement(Fragment, null, ...parts);
}
