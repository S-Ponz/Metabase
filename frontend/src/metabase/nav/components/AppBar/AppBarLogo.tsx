import type { MouseEvent } from "react";

import ricochetAnalyticsLogo from "assets/img/ricochet-analytics-logo.png";
import { useCustomLogoUrl } from "metabase/common/hooks/use-custom-logo-url";
import { useIsAtHomepageDashboard } from "metabase/common/hooks/use-is-at-homepage-dashboard";
import { useSetting } from "metabase/settings";

import { LogoLink } from "./AppBarLogo.styled";

export interface AppBarLogoProps {
  isSmallAppBar?: boolean;
  isLogoVisible?: boolean;
  isNavBarEnabled?: boolean;
  isGitSyncVisible?: boolean;
  onLogoClick?: () => void;
}

export function AppBarLogo({
  isLogoVisible,
  isSmallAppBar,
  isNavBarEnabled,
  isGitSyncVisible,
  onLogoClick,
}: AppBarLogoProps): JSX.Element | null {
  const isAtHomepageDashboard = useIsAtHomepageDashboard();
  const customLogoUrl = useCustomLogoUrl();
  const siteName = useSetting("site-name");

  if (!isLogoVisible) {
    return null;
  }

  const handleClick = (event: MouseEvent) => {
    // Prevent navigating to the dashboard homepage when a user is already there
    // https://github.com/metabase/metabase/issues/43800
    if (isAtHomepageDashboard) {
      event.preventDefault();
    }
    onLogoClick?.();
  };

  return (
    <LogoLink
      to="/"
      isSmallAppBar={Boolean(isSmallAppBar)}
      isGitSyncVisible={Boolean(isGitSyncVisible)}
      onClick={handleClick}
      disabled={!isNavBarEnabled}
      data-testid="main-logo-link"
    >
      <img
        src={customLogoUrl ?? ricochetAnalyticsLogo}
        alt={customLogoUrl ? siteName : "Ricochet Analytics"}
        height={44}
        style={{ maxWidth: "100%", objectFit: "contain" }}
        data-testid="main-logo"
      />
    </LogoLink>
  );
}
