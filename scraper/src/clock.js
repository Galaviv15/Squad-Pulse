import { setTimeout as delay } from "node:timers/promises";

/** Real time. Tests pass a fake with the same shape, so pauses and retries take no real time. */
export const realClock = {
  now: () => Date.now(),
  sleep: (ms, signal) => delay(ms, undefined, { signal }),
};
