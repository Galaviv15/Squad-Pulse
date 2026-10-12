// Configuration, from environment variables only. Invalid → refuse to start, naming the variable
// (never its value).

export class ConfigError extends Error {}

const DEFAULTS = {
  BACKEND_URL: "http://localhost:8080",
  SCRAPER_PORT: "8090",
  SCRAPER_HOST: "127.0.0.1",
};

export const REQUIRED_NODE_MAJOR = 24;

/** `engines` isn't enforced by npm, so the service checks the runtime it was tested on itself. */
export function checkNodeVersion(version = process.versions.node) {
  const major = Number(String(version).split(".")[0]);
  if (!(major >= REQUIRED_NODE_MAJOR)) {
    throw new ConfigError(
      `Node ${REQUIRED_NODE_MAJOR} or newer is required, this is Node ${Number.isNaN(major) ? "unknown" : major}`,
    );
  }
}

export function loadConfig(env = process.env, nodeVersion = process.versions.node) {
  checkNodeVersion(nodeVersion);
  const token = env.SCRAPER_SERVICE_TOKEN;
  if (token === undefined || token === "") {
    throw new ConfigError("SCRAPER_SERVICE_TOKEN is required");
  }
  if (token.length < 32 || /\s/.test(token)) {
    throw new ConfigError(
      "SCRAPER_SERVICE_TOKEN must be at least 32 characters, without whitespace",
    );
  }

  const backendUrlText = env.BACKEND_URL || DEFAULTS.BACKEND_URL;
  let backendUrl;
  try {
    backendUrl = new URL(backendUrlText);
  } catch {
    throw new ConfigError("BACKEND_URL must be a valid http(s) URL");
  }
  if (
    !["http:", "https:"].includes(backendUrl.protocol) ||
    backendUrl.username ||
    backendUrl.password
  ) {
    throw new ConfigError("BACKEND_URL must be a valid http(s) URL");
  }

  const portText = env.SCRAPER_PORT || DEFAULTS.SCRAPER_PORT;
  const port = Number(portText);
  if (!/^\d{1,5}$/.test(portText) || port < 1 || port > 65535) {
    throw new ConfigError("SCRAPER_PORT must be an integer in 1..65535");
  }

  const host = env.SCRAPER_HOST || DEFAULTS.SCRAPER_HOST;
  if (/\s/.test(host)) {
    throw new ConfigError("SCRAPER_HOST must be a host name or address");
  }

  return { token, backendUrl: backendUrl.href, port, host };
}
