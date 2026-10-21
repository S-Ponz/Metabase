import { useSetting } from "metabase/settings";

export const DEFAULT_LOGO_URL = "app/assets/img/logo.svg";
export const DEFAULT_FAVICON_URL = "app/assets/img/favicon.ico";

/**
 * Returns the logo uploaded in Admin > Appearance, or `null` when the
 * instance is still using the built-in default.
 */
export const useCustomLogoUrl = (): string | null => {
  const logoUrl = useSetting("application-logo-url");

  if (!logoUrl || logoUrl === DEFAULT_LOGO_URL) {
    return null;
  }
  return logoUrl;
};
