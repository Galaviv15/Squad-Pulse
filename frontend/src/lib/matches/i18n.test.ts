import { describe, expect, it } from "vitest";
import i18n from "@/i18n/i18n";
import he from "@/i18n/locales/he.json";

describe("the cooldown's Hebrew plurals (i18next with Intl.PluralRules)", () => {
  it("resolves he's one / two / other forms", () => {
    expect(new Intl.PluralRules("he").resolvedOptions().pluralCategories).toEqual([
      "one",
      "two",
      "other",
    ]);
    expect(i18n.t("matches.refresh.cooldown", { count: 1 })).toBe(he.matches.refresh.cooldown_one);
    expect(i18n.t("matches.refresh.cooldown", { count: 2 })).toBe(he.matches.refresh.cooldown_two);
    expect(i18n.t("matches.refresh.cooldown", { count: 5 })).toBe("אפשר לרענן שוב בעוד 5 דקות");
    expect(i18n.t("matches.refresh.cooldown", { count: 10 })).toBe("אפשר לרענן שוב בעוד 10 דקות");
  });
});
