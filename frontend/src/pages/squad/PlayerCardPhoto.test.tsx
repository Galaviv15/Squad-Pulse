import { act, fireEvent, screen, waitFor, within } from "@testing-library/react";
import { http, HttpResponse } from "msw";
import { afterEach, beforeEach, describe, expect, it, vi, type MockInstance } from "vitest";
import he from "@/i18n/locales/he.json";
import type { CurrentUser } from "@/lib/auth/currentUser";
import type { Player } from "@/lib/squad/types";
import { deferred } from "@/test/deferred";
import { apiError, meReturns, refreshReturns } from "@/test/msw/auth";
import { server } from "@/test/msw/server";
import {
  conflict,
  photoDeleteReturns,
  photoUploadReturns,
  playerBody,
  playerReturns,
} from "@/test/msw/squad";
import { renderWithProviders } from "@/test/render";
import { pickedFile, stubNodeFormData } from "@/test/upload";

const PHOTO = "/squad/players/p1/photo";
const PNG = new Uint8Array([0x89, 0x50, 0x4e, 0x47]);

let fetchSpy: MockInstance<typeof fetch>;
let created: string[];
let revoked: string[];

beforeEach(async () => {
  fetchSpy = vi.spyOn(globalThis, "fetch");
  created = [];
  revoked = [];
  vi.spyOn(URL, "createObjectURL").mockImplementation(() => {
    created.push(`blob:test/${created.length + 1}`);
    return created[created.length - 1];
  });
  vi.spyOn(URL, "revokeObjectURL").mockImplementation((url) => void revoked.push(url));
  await stubNodeFormData();
});

afterEach(() => {
  vi.restoreAllMocks();
  vi.unstubAllGlobals();
});

/** Every non-GET request sent to the squad API, as "METHOD /path". */
const writes = () =>
  fetchSpy.mock.calls
    .map(([input, init]) => `${init?.method ?? "GET"} ${String(input)}`)
    .filter((request) => !request.startsWith("GET") && request.includes("/squad/"));

/** GET of the photo, counted; the server's player (store) and photo come and go with the writes. */
function serverState(initial: Player) {
  const store = { player: initial, photoGets: 0 };
  server.use(
    playerReturns(() => store.player),
    http.get(PHOTO, () => {
      store.photoGets += 1;
      return store.player.hasPhoto
        ? new HttpResponse(PNG, { headers: { "Content-Type": "image/png" } })
        : apiError(404, "Not Found", "This player has no photo");
    }),
  );
  return store;
}

async function renderCard(player: Player, user: Partial<CurrentUser> = {}) {
  server.use(refreshReturns("t1"), meReturns(user));
  const store = serverState(player);
  renderWithProviders({ initialEntries: [`/app/squad/${player.id}`] });
  await screen.findByRole("heading", { level: 2, name: player.fullName });
  return store;
}

const fileInput = () => document.querySelector<HTMLInputElement>('input[type="file"]')!;
const photoButton = (name: string) => screen.getByRole("button", { name });
const photo = () => document.querySelector("img");

function pick(file: File) {
  fireEvent.change(fileInput(), { target: { files: [file] } });
}

/** An upload that the server takes: the player then has a photo. */
function uploadsAccepted(store: { player: Player }) {
  const upload = photoUploadReturns("p1", () => {
    store.player = { ...store.player, hasPhoto: true };
    return new HttpResponse(null, { status: 204 });
  });
  server.use(upload.handler);
  return upload;
}

describe("the card's photo controls", () => {
  it("offer an upload for a player without a photo, through a hidden, typed file input", async () => {
    await renderCard(playerBody());
    const click = vi.spyOn(HTMLInputElement.prototype, "click").mockImplementation(() => {});

    fireEvent.click(photoButton(he.squad.photo.upload));

    expect(click).toHaveBeenCalledTimes(1);
    expect(fileInput()).toHaveAttribute("accept", "image/jpeg,image/png,image/webp");
    expect(fileInput()).toHaveClass("hidden");
    expect(fileInput()).toHaveAttribute("tabindex", "-1");
    expect(screen.queryByRole("button", { name: he.squad.photo.replace })).toBeNull();
    expect(screen.queryByRole("button", { name: he.squad.photo.remove })).toBeNull();
  });

  it("offer replace and remove for a player with a photo", async () => {
    await renderCard(playerBody({ hasPhoto: true }));

    expect(photoButton(he.squad.photo.replace)).toBeInTheDocument();
    expect(photoButton(he.squad.photo.remove)).toBeInTheDocument();
    expect(screen.queryByRole("button", { name: he.squad.photo.upload })).toBeNull();
  });

  it.each([
    ["VIEW_ONLY", true],
    ["EDIT_PARTIAL", true],
    ["ADMIN", false],
  ] as const)("aren't rendered for %s (player active: %s)", async (permissionLevel, active) => {
    await renderCard(playerBody({ active, hasPhoto: true }), { permissionLevel });

    for (const name of [he.squad.photo.upload, he.squad.photo.replace, he.squad.photo.remove]) {
      expect(screen.queryByRole("button", { name })).toBeNull();
    }
    expect(fileInput()).toBeNull();
  });

  it("upload the picked file as the part `file`, then show it", async () => {
    const store = await renderCard(playerBody());
    const upload = uploadsAccepted(store);

    pick(pickedFile("me.jpg", "image/jpeg", 1234));

    await waitFor(() => expect(photo()).toHaveAttribute("src", "blob:test/1"));
    expect(upload.uploads).toEqual([
      { partNames: ["file"], fileName: "me.jpg", size: 1234, type: "image/jpeg" },
    ]);
    expect(photoButton(he.squad.photo.replace)).toBeInTheDocument();
  });

  it("show the new image after a replace: the same path is fetched again", async () => {
    const store = await renderCard(playerBody({ hasPhoto: true }));
    await waitFor(() => expect(photo()).toHaveAttribute("src", "blob:test/1"));
    expect(store.photoGets).toBe(1);
    uploadsAccepted(store);

    pick(pickedFile("new.png", "image/png", 99));

    await waitFor(() => expect(photo()).toHaveAttribute("src", "blob:test/2"));
    expect(store.photoGets).toBe(2);
    expect(revoked).toContain("blob:test/1");
  });

  it("show the work in progress on the photo, with the buttons disabled", async () => {
    const store = await renderCard(playerBody({ hasPhoto: true }));
    const gate = deferred();
    server.use(
      photoUploadReturns("p1", async () => {
        await gate.promise;
        store.player = { ...store.player, hasPhoto: true };
        return new HttpResponse(null, { status: 204 });
      }).handler,
    );

    pick(pickedFile("new.png", "image/png", 99));

    expect(await screen.findByRole("status")).toHaveTextContent(he.squad.photo.uploading);
    expect(photoButton(he.squad.photo.replace)).toBeDisabled();
    expect(photoButton(he.squad.photo.remove)).toBeDisabled();
    await act(async () => gate.resolve());
    await waitFor(() => expect(screen.queryByRole("status")).toBeNull());
    expect(photoButton(he.squad.photo.replace)).toBeEnabled();
  });

  it.each([
    ["a PDF", pickedFile("cv.pdf", "application/pdf", 500), he.squad.photo.errors.type],
    ["a GIF", pickedFile("a.gif", "image/gif", 500), he.squad.photo.errors.type],
    ["an empty file", pickedFile("a.png", "image/png", 0), he.squad.photo.errors.empty],
    [
      "2 MiB + 1 byte",
      pickedFile("big.jpg", "image/jpeg", 2 * 1024 * 1024 + 1),
      he.squad.photo.errors.tooLarge,
    ],
  ])("refuse %s without sending it", async (_, file, message) => {
    await renderCard(playerBody());

    pick(file);

    expect(await screen.findByRole("alert")).toHaveTextContent(message);
    await new Promise((resolve) => setTimeout(resolve, 20));
    expect(writes()).toEqual([]);
  });

  it("send a file of exactly 2 MiB", async () => {
    const store = await renderCard(playerBody());
    const upload = uploadsAccepted(store);

    pick(pickedFile("max.jpg", "image/jpeg", 2 * 1024 * 1024));

    await waitFor(() => expect(upload.uploads).toHaveLength(1));
    expect(upload.uploads[0].size).toBe(2 * 1024 * 1024);
  });

  it.each([
    [
      "400 (not really an image)",
      () =>
        apiError(400, "Bad Request", "Validation failed", [
          "file: must be a JPEG, PNG or WebP image",
        ]),
      he.squad.photo.errors.notAnImage,
    ],
    ["413", () => apiError(413, "Payload Too Large", "Too large"), he.squad.photo.errors.tooLarge],
    ["403", () => apiError(403, "Forbidden", "Access denied"), he.squad.noPermission],
    ["500", () => apiError(500, "Internal Server Error", "Unexpected"), he.squad.dialog.failed],
    ["no response", () => HttpResponse.error(), he.squad.dialog.failed],
  ])("say what went wrong on a %s, under the photo", async (_, answer, message) => {
    await renderCard(playerBody());
    server.use(photoUploadReturns("p1", answer).handler);

    pick(pickedFile("x.jpg", "image/jpeg", 10));

    expect(await screen.findByRole("alert")).toHaveTextContent(message);
    expect(photoButton(he.squad.photo.upload)).toBeEnabled();
  });

  it("say the player was released meanwhile, and refresh the card (no more controls)", async () => {
    const store = await renderCard(playerBody());
    server.use(
      photoUploadReturns("p1", () => {
        store.player = { ...store.player, active: false };
        return conflict("PLAYER_RELEASED");
      }).handler,
    );

    pick(pickedFile("x.jpg", "image/jpeg", 10));

    expect(await screen.findByRole("alert")).toHaveTextContent(he.squad.form.released);
    await waitFor(() =>
      expect(screen.queryByRole("button", { name: he.squad.photo.upload })).toBeNull(),
    );
    expect(screen.getByRole("alert")).toHaveTextContent(he.squad.form.released);
  });

  it("remove the photo after a confirmation, then show the initials", async () => {
    const store = await renderCard(playerBody({ hasPhoto: true }));
    await waitFor(() => expect(photo()).toHaveAttribute("src", "blob:test/1"));
    const remove = photoDeleteReturns("p1", () => {
      store.player = { ...store.player, hasPhoto: false };
      return new HttpResponse(null, { status: 204 });
    });
    server.use(remove.handler);

    fireEvent.click(photoButton(he.squad.photo.remove));
    const dialog = await screen.findByRole("alertdialog");
    expect(dialog).toHaveAccessibleName("הסרת התמונה של Yossi Levi");
    expect(dialog).toHaveAccessibleDescription(he.squad.photo.removeText);
    fireEvent.click(within(dialog).getByRole("button", { name: he.squad.photo.removeConfirm }));

    await waitFor(() => expect(screen.queryByRole("alertdialog")).toBeNull());
    expect(remove.requests.count).toBe(1);
    await waitFor(() => expect(photo()).toBeNull());
    expect(screen.getByText("YL")).toBeInTheDocument();
    expect(revoked).toContain("blob:test/1");
    // Its button is gone: focus goes to the upload button.
    await waitFor(() => expect(photoButton(he.squad.photo.upload)).toHaveFocus());
  });

  it("send nothing when the removal is cancelled", async () => {
    await renderCard(playerBody({ hasPhoto: true }));

    fireEvent.click(photoButton(he.squad.photo.remove));
    const dialog = await screen.findByRole("alertdialog");
    fireEvent.click(within(dialog).getByRole("button", { name: he.squad.dialog.cancel }));

    await waitFor(() => expect(screen.queryByRole("alertdialog")).toBeNull());
    expect(writes()).toEqual([]);
    await waitFor(() => expect(photoButton(he.squad.photo.remove)).toHaveFocus());
  });

  it("show a failed removal in its dialog", async () => {
    await renderCard(playerBody({ hasPhoto: true }));
    server.use(photoDeleteReturns("p1", () => apiError(403, "Forbidden", "Access denied")).handler);

    fireEvent.click(photoButton(he.squad.photo.remove));
    const dialog = await screen.findByRole("alertdialog");
    fireEvent.click(within(dialog).getByRole("button", { name: he.squad.photo.removeConfirm }));

    expect(await within(dialog).findByRole("alert")).toHaveTextContent(he.squad.noPermission);
  });
});
