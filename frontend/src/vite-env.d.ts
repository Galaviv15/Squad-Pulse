/// <reference types="vite/client" />

interface ImportMetaEnv {
  /**
   * Where the backend is, e.g. "https://api.example.com". Empty or unset (the default): the
   * same origin as the app, i.e. the dev proxy or the production reverse proxy.
   */
  readonly VITE_API_BASE_URL?: string;
}

interface ImportMeta {
  readonly env: ImportMetaEnv;
}
