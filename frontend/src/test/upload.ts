import { File as NodeFile } from "node:buffer";
import { vi } from "vitest";

/**
 * Uploads in component tests. Under Vitest's jsdom environment FormData and File are jsdom's but
 * fetch is Node's, which sends a jsdom file as the text "undefined" (see CLAUDE.md). So a test
 * that uploads swaps in Node's FormData (taken from a parsed Response, as Node doesn't export it
 * under jsdom's globals) and picks Node files. Undone by vi.unstubAllGlobals().
 */
export async function stubNodeFormData() {
  const parsed = await new Response(new URLSearchParams("a=1")).formData();
  vi.stubGlobal("FormData", parsed.constructor);
}

/** A picked file of `size` bytes, as Node's File (see stubNodeFormData). */
export function pickedFile(name: string, type: string, size: number): File {
  return new NodeFile([new Uint8Array(size)], name, { type }) as unknown as File;
}
