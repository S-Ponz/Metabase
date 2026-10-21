import { useHasTokenFeature } from "metabase/common/hooks";
import { PLUGIN_WHITELABEL } from "metabase/plugins";

import { BrandingSettingsPage } from "../BrandingSettings";

export function AppearanceSettingsPage({
  tab,
}: {
  tab?: "branding" | "conceal-metabase";
}) {
  const hasWhitelabeling = useHasTokenFeature("whitelabel");

  if (hasWhitelabeling) {
    return tab === "conceal-metabase" ? (
      <PLUGIN_WHITELABEL.WhiteLabelConcealSettingsPage />
    ) : (
      <PLUGIN_WHITELABEL.WhiteLabelBrandingSettingsPage />
    );
  }

  // Ricochet: open-source branding page (logo + favicon upload) instead of the upsell.
  return <BrandingSettingsPage />;
}
