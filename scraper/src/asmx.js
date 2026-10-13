// The ASMX `ResponseData` envelope the site's Components.asmx endpoints answer with, when called
// without `content-type: application/json` (KAN-64 findings section 6, KAN-70 findings section 1).

import { load } from "cheerio";
import { ParseError } from "./text.js";

/**
 * Reads exactly one `HtmlData` from the XML envelope and loads its unescaped HTML.
 * `responseName` names the endpoint in the `ParseError` message (never a value from the page).
 */
export function loadHtmlData(xml, responseName) {
  const envelope = load(xml, { xml: true });
  const htmlData = envelope("HtmlData");
  if (htmlData.length !== 1) {
    throw new ParseError(`no HtmlData in the ${responseName} response`);
  }
  return load(htmlData.text());
}
