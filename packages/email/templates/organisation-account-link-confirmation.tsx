import { msg } from '@lingui/core/macro';
import { useLingui } from '@lingui/react';
import { Trans } from '@lingui/react/macro';

import { Body, Button, Container, Head, Hr, Html, Img, Preview, Section, Text } from '../components';
import { useBranding } from '../providers/branding';
import { TemplateBrandingLogo } from '../template-components/template-branding-logo';
import { TemplateFooter } from '../template-components/template-footer';
import TemplateImage from '../template-components/template-image';

type OrganisationAccountLinkConfirmationTemplateProps = {
  type: 'create' | 'link';
  confirmationLink: string;
  organisationName: string;
  assetBaseUrl: string;
};

export const OrganisationAccountLinkConfirmationTemplate = ({
  type = 'link',
  confirmationLink = '<CONFIRMATION_LINK>',
  organisationName = '<ORGANISATION_NAME>',
  assetBaseUrl = 'http://localhost:3002',
}: OrganisationAccountLinkConfirmationTemplateProps) => {
  const { _ } = useLingui();
  const branding = useBranding();

  const previewText =
    type === 'create'
      ? msg`A request has been made to create an account for you`
      : msg`A request has been made to link your Epic Sign account`;

  return (
    <Html>
      <Head />
      <Body className="mx-auto my-auto font-sans">
        <Preview>{_(previewText)}</Preview>

        <Section className="bg-background">
          <Container className="mx-auto mt-8 mb-2 max-w-xl rounded-lg border border-border border-solid px-2 pt-2 backdrop-blur-sm">
            {branding.brandingEnabled && branding.brandingLogo ? (
              <Img src={branding.brandingLogo} alt="Branding Logo" className="mb-4 h-6 p-2" />
            ) : (
              <TemplateBrandingLogo assetBaseUrl={assetBaseUrl} className="mb-4 h-6 p-2" />
            )}

            <Section>
              <TemplateImage className="mx-auto h-12 w-12" assetBaseUrl={assetBaseUrl} staticAsset="building-2.png" />
            </Section>

            <Section className="p-2 text-muted-foreground">
              <Text className="text-center font-medium text-foreground text-lg">
                {type === 'create' ? (
                  <Trans>Account creation request</Trans>
                ) : (
                  <Trans>Link your Epic Sign account</Trans>
                )}
              </Text>

              <Text className="text-center text-base">
                {type === 'create' ? (
                  <Trans>
                    <span className="font-bold">{organisationName}</span> has requested to create an account on your
                    behalf.
                  </Trans>
                ) : (
                  <Trans>
                    <span className="font-bold">{organisationName}</span> has requested to link your current Epic Sign
                    account to their organisation.
                  </Trans>
                )}
              </Text>

              <Section className="mt-8 mb-6 text-center">
                <Button
                  className="inline-flex items-center justify-center rounded-lg bg-documenso-500 px-6 py-3 text-center font-medium text-black text-sm no-underline"
                  href={confirmationLink}
                >
                  <Trans>Review request</Trans>
                </Button>
              </Section>
            </Section>

            <Text className="text-center text-muted-foreground text-xs">
              <Trans>Link expires in 30 minutes.</Trans>
            </Text>
          </Container>

          <Hr className="mx-auto mt-12 max-w-xl" />

          <Container className="mx-auto max-w-xl">
            <TemplateFooter isDocument={false} />
          </Container>
        </Section>
      </Body>
    </Html>
  );
};

export default OrganisationAccountLinkConfirmationTemplate;
