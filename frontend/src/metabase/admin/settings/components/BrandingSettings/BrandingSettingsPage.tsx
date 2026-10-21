import { t } from "ttag";

import {
  DEFAULT_FAVICON_URL,
  DEFAULT_LOGO_URL,
} from "metabase/common/hooks/use-custom-logo-url";
import {
  SettingsPageWrapper,
  SettingsSection,
} from "metabase/settings-components";

import { BrandingImageUploadWidget } from "./BrandingImageUploadWidget";

export function BrandingSettingsPage() {
  return (
    <SettingsPageWrapper title={t`Branding`}>
      <SettingsSection>
        <BrandingImageUploadWidget
          name="application-logo-url"
          title={t`Logo`}
          description={t`Shown in the top bar, on the sign-in page, and in emails. For best results, upload a PNG or SVG with a transparent background.`}
          defaultUrl={DEFAULT_LOGO_URL}
          previewHeight={44}
        />
        <BrandingImageUploadWidget
          name="application-favicon-url"
          title={t`Favicon`}
          description={t`The small icon shown in the browser tab. A square PNG or ICO works best.`}
          defaultUrl={DEFAULT_FAVICON_URL}
          previewHeight={32}
        />
      </SettingsSection>
    </SettingsPageWrapper>
  );
}
