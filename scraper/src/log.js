// One line per event: `<time> <LEVEL> run=<runId> <message> key=value ...`.
// Never pass the token, headers, page HTML, the payload, team names or a part's `detail` here.

const LEVELS = ["DEBUG", "INFO", "WARN", "ERROR"];

export function createLogger({
  minLevel = "INFO",
  out = (line) => process.stdout.write(`${line}\n`),
  err = (line) => process.stderr.write(`${line}\n`),
  now = () => new Date(),
} = {}) {
  const threshold = LEVELS.indexOf(minLevel);
  const write =
    (level) =>
    (runId, message, fields = {}) => {
      if (LEVELS.indexOf(level) < threshold) return;
      const pairs = Object.entries(fields)
        .filter(([, value]) => value !== undefined)
        .map(([key, value]) => ` ${key}=${value}`)
        .join("");
      const line = `${now().toISOString()} ${level} run=${runId ?? "-"} ${message}${pairs}`;
      (level === "WARN" || level === "ERROR" ? err : out)(line);
    };
  return { debug: write("DEBUG"), info: write("INFO"), warn: write("WARN"), error: write("ERROR") };
}

/** A logger that records lines, for tests. */
export function createMemoryLogger() {
  const lines = [];
  const logger = createLogger({
    minLevel: "DEBUG",
    out: (line) => lines.push(line),
    err: (line) => lines.push(line),
  });
  return { ...logger, lines };
}
