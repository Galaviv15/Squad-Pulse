import { describe, expect, it } from "vitest";
import { ApiError, NetworkError } from "@/lib/api/errors";
import { PHOTO_ACCEPT, PHOTO_MAX_BYTES, photoFileError, photoUploadErrorKey } from "./photo";

const file = (type: string, size: number) => ({ type, size });

const apiError = (status: number, code: string | null = null) =>
  new ApiError(status, { timestamp: "t", status, error: "Error", code, message: "m", details: [] });

describe("photoFileError", () => {
  it.each(["image/jpeg", "image/png", "image/webp"])("accepts %s", (type) => {
    expect(photoFileError(file(type, 1000))).toBeNull();
  });

  it.each(["application/pdf", "image/gif", "image/svg+xml", "image/heic", "", "IMAGE/PNG"])(
    "refuses the type %j",
    (type) => {
      expect(photoFileError(file(type, 1000))).toBe("squad.photo.errors.type");
    },
  );

  it("refuses an empty file, whatever its type", () => {
    expect(photoFileError(file("image/png", 0))).toBe("squad.photo.errors.empty");
    expect(photoFileError(file("", 0))).toBe("squad.photo.errors.empty");
  });

  it("accepts exactly 2 MiB, as the server does (size > max is refused)", () => {
    expect(PHOTO_MAX_BYTES).toBe(2 * 1024 * 1024);
    expect(photoFileError(file("image/jpeg", PHOTO_MAX_BYTES))).toBeNull();
  });

  it("refuses 2 MiB + 1 byte", () => {
    expect(photoFileError(file("image/jpeg", PHOTO_MAX_BYTES + 1))).toBe(
      "squad.photo.errors.tooLarge",
    );
  });

  it("gives the picker the same types", () => {
    expect(PHOTO_ACCEPT).toBe("image/jpeg,image/png,image/webp");
  });
});

describe("photoUploadErrorKey", () => {
  it.each([
    [apiError(400), "squad.photo.errors.notAnImage"],
    [apiError(413), "squad.photo.errors.tooLarge"],
    [apiError(409, "PLAYER_RELEASED"), "squad.form.released"],
    [apiError(409, "SOMETHING_ELSE"), "squad.dialog.failed"],
    [apiError(403), "squad.noPermission"],
    [apiError(404), "squad.player.notFound"],
    [apiError(500), "squad.dialog.failed"],
    [new NetworkError(new TypeError("Failed to fetch")), "squad.dialog.failed"],
  ])("maps %s to %s", (error, key) => {
    expect(photoUploadErrorKey(error)).toBe(key);
  });
});
