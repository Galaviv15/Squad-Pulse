import i18n from "i18next";
import { initReactI18next } from "react-i18next";
import he from "./locales/he.json";

// Hebrew is the only supported language at launch (see docs/spec.md section 01).
// All UI strings must go through this dictionary from day one — never hardcode
// user-facing text directly in components.
//
// Exception: football position codes (GK, CB, DM, ...) and formation notation
// (4-3-3) are NOT translated — they're rendered as literal pass-through values
// everywhere, including here. Don't add them as translation keys.
i18n.use(initReactI18next).init({
  resources: {
    he: { translation: he },
  },
  lng: "he",
  fallbackLng: "he",
  interpolation: {
    escapeValue: false,
  },
});

export default i18n;
