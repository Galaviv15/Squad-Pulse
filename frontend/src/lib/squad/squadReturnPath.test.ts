import { describe, expect, it } from "vitest";
import { createBrowserSession } from "@/lib/api/session";
import { loginReturns } from "@/test/msw/auth";
import { server } from "@/test/msw/server";
import { parseSquadFilters } from "./filters";
import {
  bindSessionToSquadReturnPath,
  forgetSquadReturnPath,
  rememberSquadPage,
  squadReturnPath,
} from "./squadReturnPath";
import { parseSquadView } from "./view";

/** Remembers the squad page at `search`, parsed as useSquadPageQuery parses its URL. */
function rememberAt(search: string) {
  const params = new URLSearchParams(search);
  rememberSquadPage(parseSquadFilters(params), parseSquadView(params));
}

describe("squadReturnPath", () => {
  it("is the bare squad when nothing is remembered", () => {
    expect(squadReturnPath()).toBe("/app/squad");
  });

  it("is the remembered filters and view, in normalized form", () => {
    rememberAt("view=cards&position=CB&status=released");

    expect(squadReturnPath()).toBe("/app/squad?status=released&position=CB&view=cards");
  });

  it("is the bare squad, without '?', for the default list and filters", () => {
    rememberAt("status=active&view=list");

    expect(squadReturnPath()).toBe("/app/squad");
  });

  it("never keeps what normalization drops (unknown keys, invalid values, a reversed age range)", () => {
    rememberAt("position=XX&view=bogus&foo=1&medicalStatus=INJURED&minAge=40&maxAge=20");

    expect(squadReturnPath()).toBe("/app/squad?medicalStatus=INJURED");
  });

  it("keeps the last one remembered", () => {
    rememberAt("position=CB");
    rememberAt("view=cards");

    expect(squadReturnPath()).toBe("/app/squad?view=cards");
  });

  it("is the bare squad again once forgotten", () => {
    rememberAt("position=CB");

    forgetSquadReturnPath();

    expect(squadReturnPath()).toBe("/app/squad");
  });
});

describe("bindSessionToSquadReturnPath", () => {
  it("forgets the remembered URL when the session ends, and only then", async () => {
    server.use(loginReturns("t1"));
    const session = createBrowserSession();
    bindSessionToSquadReturnPath(session);
    rememberAt("position=CB");

    await session.login("coach@example.com", "pw");
    expect(squadReturnPath()).toBe("/app/squad?position=CB");

    session.clear();
    expect(squadReturnPath()).toBe("/app/squad");
  });

  it("stops once unbound", () => {
    const session = createBrowserSession();
    const unbind = bindSessionToSquadReturnPath(session);
    rememberAt("position=CB");

    unbind();
    session.clear();

    expect(squadReturnPath()).toBe("/app/squad?position=CB");
  });
});
