import { describe, expect, it } from "vitest";
import { percentile } from "../src/stats.js";
describe("percentile", () => { it("uses nearest-rank percentiles", () => { expect(percentile([5, 1, 4, 2, 3], 50)).toBe(3); expect(percentile([1, 2, 3, 4, 5], 95)).toBe(5); }); });
