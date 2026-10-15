import { describe, expect, it } from "vitest";
import i18n from "@/i18n/i18n";
import he from "@/i18n/locales/he.json";
import { APPEARANCE_ROLE_KEYS, DETAILS_STATE_TEXT_KEYS, GAME_EVENT_KEYS } from "./labels";

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

describe("the game details' keys (KAN-81)", () => {
  it("has a Hebrew text for every role, event kind and details state", () => {
    const keys = [
      ...Object.values(APPEARANCE_ROLE_KEYS),
      ...Object.values(GAME_EVENT_KEYS),
      ...Object.values(DETAILS_STATE_TEXT_KEYS),
    ];
    for (const key of keys) {
      expect(i18n.exists(key), key).toBe(true);
    }
  });

  it("writes an event's minute the site's way, never stoppage-time notation", () => {
    expect(i18n.t("matches.game.eventAt", { event: he.matches.game.event.GOAL, minute: 93 })).toBe(
      "שער בדקה 93",
    );
  });
});
