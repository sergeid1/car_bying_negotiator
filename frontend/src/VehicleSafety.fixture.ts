import type { VehicleSafetyView } from "./VehicleSafety";

export const vehicleSafetyFixture: VehicleSafetyView = {
  vin: "1HGCM82633A004352",
  facts: {
    status: "AVAILABLE", source: "NHTSA", retrievedAt: "2026-10-14T12:00:00Z",
    vehicle: { year: 2021, make: "Honda", model: "Accord" },
    recalls: [{ campaignNumber: "21V001000", component: "AIR BAGS", summary: "A model recall summary.",
      consequence: "A model consequence.", remedy: "Replace the part.", reportDate: "2021-01-01" }],
    ratings: [{ vehicleId: "101", name: "Accord FWD", overall: "5", frontal: "4", side: null, rollover: "4", rolloverRisk: "0.15" },
      { vehicleId: "102", name: "Accord AWD", overall: null, frontal: null, side: null, rollover: null, rolloverRisk: null }],
  },
};
