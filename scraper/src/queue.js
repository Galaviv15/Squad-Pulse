// One scrape at a time: one running plus a FIFO of at most `maxWaiting`, deduplicated by
// (teamId, leagueId, seasonId) across waiting and running. KAN-68's Redis locks come first;
// this is the second line of defence.

export const MAX_WAITING = 5;

const keyOf = (job) => `${job.teamId}:${job.leagueId}:${job.seasonId}`;

export class ScrapeQueue {
  #running = null; // { job, controller, done }
  #waiting = [];
  #closed = false;

  /** @param runJob async (job, signal) => void; never expected to throw (errors are logged) */
  constructor({ runJob, log, maxWaiting = MAX_WAITING }) {
    this.runJob = runJob;
    this.log = log;
    this.maxWaiting = maxWaiting;
  }

  /** → {status: "QUEUED" | "ALREADY_QUEUED" | "FULL" | "CLOSED", runId?} */
  submit(job) {
    if (this.#closed) return { status: "CLOSED" };
    const key = keyOf(job);
    const existing =
      this.#running && keyOf(this.#running.job) === key
        ? this.#running.job
        : this.#waiting.find((waiting) => keyOf(waiting) === key);
    if (existing) return { status: "ALREADY_QUEUED", runId: existing.runId };
    if (this.#waiting.length >= this.maxWaiting) return { status: "FULL" };
    this.#waiting.push(job);
    queueMicrotask(() => this.#pump());
    return { status: "QUEUED", runId: job.runId };
  }

  #pump() {
    if (this.#running || this.#closed || this.#waiting.length === 0) return;
    const job = this.#waiting.shift();
    const controller = new AbortController();
    const done = Promise.resolve()
      .then(() => this.runJob(job, controller.signal))
      .catch((error) => {
        if (!controller.signal.aborted)
          this.log.error(job.runId, "scrape crashed", { error: error?.name });
      })
      .finally(() => {
        this.#running = null;
        this.#pump();
      });
    this.#running = { job, controller, done };
  }

  /** Resolves once nothing is running or waiting (tests). */
  async idle() {
    while (this.#running || this.#waiting.length > 0) {
      await (this.#running?.done ?? new Promise((resolve) => setImmediate(resolve)));
    }
  }

  /** Stops accepting, aborts the running scrape, drops the waiting ones. → the dropped runIds. */
  async shutdown() {
    this.#closed = true;
    const dropped = this.#waiting.map((job) => job.runId);
    this.#waiting = [];
    const running = this.#running;
    if (running) {
      running.controller.abort();
      await running.done;
    }
    return { dropped, aborted: running?.job.runId };
  }
}
