import type { Title } from "./currentUser";

/**
 * The i18n key of each staff title's Hebrew name. A Record over Title, so a title the backend adds
 * is a compile error here until it has a translation, never a blank line. Masculine forms: the
 * backend has no gender, and masculine is the usual default in Hebrew UIs.
 */
export const TITLE_KEYS: Record<Title, string> = {
  CLUB_MANAGER: "titles.CLUB_MANAGER",
  HEAD_COACH: "titles.HEAD_COACH",
  ASSISTANT_COACH: "titles.ASSISTANT_COACH",
  GOALKEEPING_COACH: "titles.GOALKEEPING_COACH",
  FITNESS_COACH: "titles.FITNESS_COACH",
  ANALYST: "titles.ANALYST",
};
