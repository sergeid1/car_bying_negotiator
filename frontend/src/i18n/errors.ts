/**
 * The text a Buyer sees for a failed action: the message the screen threw, without the "Error: " prefix that
 * `String(error)` adds (ticket 146). A browser's own network error, such as "Failed to fetch", is in the browser's
 * language rather than the Buyer's, so the screen's catalogue message replaces it.
 */
export function errorMessage(caught: unknown, fallback: string) {
  if (caught instanceof TypeError) return fallback;
  return caught instanceof Error && caught.message ? caught.message : fallback;
}
