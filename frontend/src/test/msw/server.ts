import { setupServer } from "msw/node";
import { handlers } from "./handlers";

/** The mock API for tests. Started and reset in src/test/setup.ts. */
export const server = setupServer(...handlers);
