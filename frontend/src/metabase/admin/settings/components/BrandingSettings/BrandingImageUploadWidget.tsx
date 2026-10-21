import { type ChangeEvent, useRef, useState } from "react";
import { t } from "ttag";

import { useAdminSetting } from "metabase/settings";
import { SettingHeader } from "metabase/settings-components";
import { Box, Button, Flex, Image, Text } from "metabase/ui";

type BrandingImageSettingKey = "application-logo-url" | "application-favicon-url";

// Uploaded images are stored as data URIs in the settings table and shipped to
// every browser with the public settings, so keep them small.
const MAX_FILE_SIZE_BYTES = 1024 * 1024;

const ACCEPTED_MIME_TYPES = [
  "image/png",
  "image/jpeg",
  "image/gif",
  "image/svg+xml",
  "image/x-icon",
  "image/vnd.microsoft.icon",
];

interface BrandingImageUploadWidgetProps {
  name: BrandingImageSettingKey;
  title: string;
  description: string;
  defaultUrl: string;
  previewHeight?: number;
}

export function BrandingImageUploadWidget({
  name,
  title,
  description,
  defaultUrl,
  previewHeight = 48,
}: BrandingImageUploadWidgetProps) {
  const { value, updateSetting } = useAdminSetting(name);
  const inputRef = useRef<HTMLInputElement>(null);
  const [error, setError] = useState<string | null>(null);

  const isCustom = Boolean(value) && value !== defaultUrl;

  const handleFileChange = (event: ChangeEvent<HTMLInputElement>) => {
    const file = event.target.files?.[0];
    // Reset so selecting the same file again still fires onChange.
    event.target.value = "";
    if (!file) {
      return;
    }

    if (!ACCEPTED_MIME_TYPES.includes(file.type)) {
      setError(t`Please choose a PNG, JPG, GIF, SVG, or ICO file.`);
      return;
    }
    if (file.size > MAX_FILE_SIZE_BYTES) {
      setError(t`The file is too large. Please choose an image under 1 MB.`);
      return;
    }

    const reader = new FileReader();
    reader.onload = () => {
      setError(null);
      updateSetting({ key: name, value: reader.result as string });
    };
    reader.onerror = () => setError(t`Could not read that file.`);
    reader.readAsDataURL(file);
  };

  const handleRemove = () => {
    setError(null);
    updateSetting({ key: name, value: null });
  };

  return (
    <Box>
      <SettingHeader id={name} title={title} description={description} />
      <Flex align="center" gap="md">
        <Flex
          align="center"
          justify="center"
          miw="6rem"
          h={previewHeight + 16}
          px="sm"
          bd="1px solid var(--mb-color-border)"
          style={{ borderRadius: "var(--mantine-radius-sm)" }}
        >
          {isCustom ? (
            <Image
              src={value}
              alt={title}
              h={previewHeight}
              w="auto"
              fit="contain"
              data-testid={`${name}-preview`}
            />
          ) : (
            <Text c="text-secondary">{t`Default`}</Text>
          )}
        </Flex>
        <input
          ref={inputRef}
          id={name}
          type="file"
          accept={ACCEPTED_MIME_TYPES.join(",")}
          hidden
          onChange={handleFileChange}
          data-testid={`${name}-input`}
        />
        <Button onClick={() => inputRef.current?.click()}>
          {isCustom ? t`Replace` : t`Upload`}
        </Button>
        {isCustom && (
          <Button variant="subtle" color="error" onClick={handleRemove}>
            {t`Remove`}
          </Button>
        )}
      </Flex>
      {error && (
        <Text c="error" mt="sm" role="alert">
          {error}
        </Text>
      )}
    </Box>
  );
}
