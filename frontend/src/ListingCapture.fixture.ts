import type { ListingCaptureItem, ListingCaptureView, ListingPriceCheck } from "./ListingCapture";

/** Synthetic listing captures for ticket 115's tests and browser checks; the dealer and the car are invented. */
const line = (index: number) => ({ pageNumber: 1, x: 0.03, y: (45 + 27 * index) / 1650, width: 0.4, height: 27 / 1650 });

export const readCapture: ListingCaptureItem = {
  documentId: "listing-1", filename: "pasted-listing.txt", mediaType: "text/plain", pageCount: 1,
  uploadedAt: "2026-10-01T13:14:00Z", stage: "EXTRACTION_COMPLETE", problem: null,
  reading: { status: "READ", extractorVersion: "deterministic-listing-text.v1", warnings: [], fields: [
    { field: "VEHICLE_YEAR", originalWording: "2021 Honda Accord EX-L", value: "2021", unit: "YEAR", evidence: line(0), confidence: 0.7 },
    { field: "VEHICLE_MAKE", originalWording: "2021 Honda Accord EX-L", value: "Honda", unit: "TEXT", evidence: line(0), confidence: 0.7 },
    { field: "VEHICLE_MODEL", originalWording: "2021 Honda Accord EX-L", value: "Accord", unit: "TEXT", evidence: line(0), confidence: 0.7 },
    { field: "VEHICLE_TRIM", originalWording: "2021 Honda Accord EX-L", value: "EX-L", unit: "TEXT", evidence: line(0), confidence: 0.6 },
    { field: "LISTING_PRICE", originalWording: "Our Price: $23,995*", value: "2399500", unit: "USD_MINOR", evidence: line(1), confidence: 0.95 },
    { field: "VEHICLE_VIN", originalWording: "VIN: 1HGCV1F34MA000001", value: "1HGCV1F34MA000001", unit: "VIN", evidence: line(2), confidence: 0.95 },
    { field: "ODOMETER", originalWording: "Mileage: 38,012 miles", value: "38012", unit: "MILES", evidence: line(3), confidence: 0.95 },
    { field: "DEALER_NAME", originalWording: "Dealer: Gulf Coast Honda of Sarasota and Bradenton", value: "Gulf Coast Honda of Sarasota and Bradenton",
      unit: "TEXT", evidence: line(4), confidence: 0.95 },
    { field: "LISTING_PRICE_CONDITIONS", originalWording: "*Price requires financing through the dealer with approved credit.",
      value: "Price requires financing through the dealer with approved credit.", unit: "TEXT", evidence: line(5), confidence: 0.9 },
    { field: "LISTING_PRICE_CONDITIONS", originalWording: "Includes $1,500 loyalty rebate. Plus tax, title, license and an $899 dealer fee.",
      value: "Includes $1,500 loyalty rebate. Plus tax, title, license and an $899 dealer fee.", unit: "TEXT", evidence: line(6), confidence: 0.9 },
  ] },
};

export const unreadableCapture: ListingCaptureItem = {
  documentId: "listing-2", filename: "IMG_0412.png", mediaType: "image/png", pageCount: 1, uploadedAt: "2026-10-01T13:20:00Z",
  stage: "EXTRACTION_COMPLETE", problem: null,
  reading: { status: "UNREADABLE", extractorVersion: "deterministic-listing-text.v1", fields: [],
    warnings: ["No listing details could be read from this capture."] },
};

export const higherCheck: ListingPriceCheck = {
  policyVersion: "listing-price-check.v1", status: "HIGHER", listingDate: "2026-10-01", advertisedPriceMinor: 2399500,
  paperworkPriceMinor: 2549000, differenceMinor: 149500, paperwork: "DEALER_OFFER", verified: true,
  listingVin: "1HGCV1F34MA000001", paperworkVin: "1HGCV1F34MA000001",
  priceConditions: readCapture.reading!.fields.filter(field => field.field === "LISTING_PRICE_CONDITIONS").map(field => field.value!),
  summary: "The listing you saved on October 1, 2026 showed $23,995. The vehicle price on this paperwork is $25,490, $1,495 more.",
  deskQuestion: "The listing on October 1, 2026 showed $23,995. Why is the price on this paperwork $25,490?",
};

export const savedListing: ListingCaptureView = {
  captures: [readCapture],
  snapshot: { id: "snapshot-1", vin: "1HGCV1F34MA000001", year: 2021, make: "Honda", model: "Accord", trim: "EX-L", mileage: 38012,
    advertisedPriceMinor: 2399500, currency: "USD", source: "BUYER_SUPPLIED_LISTING_CAPTURE", retrievedAt: "2026-10-01T13:16:00Z",
    dealer: { name: "Gulf Coast Honda of Sarasota and Bradenton" }, capturedOn: "2026-10-01", priceConditions: higherCheck.priceConditions },
  check: higherCheck,
  identityConflicts: [],
};

export const conflictingListing: ListingCaptureView = {
  ...savedListing,
  snapshot: { ...savedListing.snapshot!, vin: "1M8GDM9AXKP042788" },
  check: { ...higherCheck, status: "DIFFERENT_VIN", listingVin: "1M8GDM9AXKP042788", differenceMinor: null,
    summary: "The listing you saved is for VIN 1M8GDM9AXKP042788, but this paperwork is for VIN 1HGCV1F34MA000001, so their prices are not compared.",
    deskQuestion: "Is this paperwork for the car in the listing, VIN 1M8GDM9AXKP042788?" },
  identityConflicts: [{ id: "conflict-1", field: "VIN", confirmedValue: "1HGCV1F34MA000001", listingValue: "1M8GDM9AXKP042788", status: "UNRESOLVED" }],
};
