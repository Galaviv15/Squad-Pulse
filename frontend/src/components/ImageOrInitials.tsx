import type { LucideIcon } from "lucide-react";
import { useAuthorizedImage } from "@/lib/api/useAuthorizedImage";
import { initials } from "@/lib/initials";
import { cn } from "@/lib/utils";

interface ImageOrInitialsProps {
  /** The image endpoint, or null when the owner has none (hasPhoto / hasLogo false): no request. */
  path: string | null;
  /** Whose image it is: the fallback shows its initials. */
  name: string;
  /** Shown instead of the initials when the name has no letter. */
  fallbackIcon: LucideIcon;
  /** The frame (size, shape, colors, initials' type), shared by the image and the fallback. */
  className: string;
  /** Extra classes for the <img> only (object-fit). */
  imageClassName?: string;
}

/**
 * An avatar or logo: the image once it has loaded, otherwise (no image, still loading, 404, any
 * error) the name's initials, or an icon when the name has none. A failed image never shows an
 * error, it just stays on the fallback. Decorative either way: the name is always shown next to
 * it, so the image has an empty alt and the fallback is hidden from assistive technology.
 */
export function ImageOrInitials({
  path,
  name,
  fallbackIcon: FallbackIcon,
  className,
  imageClassName,
}: ImageOrInitialsProps) {
  const image = useAuthorizedImage(path);

  if (image.status === "loaded") {
    return <img src={image.url} alt="" className={cn("shrink-0", className, imageClassName)} />;
  }
  const letters = initials(name);
  return (
    <span
      aria-hidden="true"
      className={cn("flex shrink-0 items-center justify-center select-none", className)}
    >
      {letters !== "" ? letters : <FallbackIcon className="size-[18px]" />}
    </span>
  );
}
