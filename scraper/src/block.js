// Detects the source's bot-protection block (fixture: test/fixtures/cloudflare-block-403.html).

const BLOCK_MARKERS = ["Attention Required! | Cloudflare", "Sorry, you have been blocked"];

/** A 403, or Cloudflare's block page whatever the status (even a 200). */
export function isBlocked(status, body) {
  return status === 403 || BLOCK_MARKERS.some((marker) => body.includes(marker));
}
