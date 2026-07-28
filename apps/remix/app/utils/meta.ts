import { NEXT_PUBLIC_WEBAPP_URL } from '@documenso/lib/constants/app';
import { i18n, type MessageDescriptor } from '@lingui/core';

export const appMetaTags = (title?: MessageDescriptor | string) => {
  const description =
    'Epic Sign - Professional electronic signature solution by Epic Group. Fast, secure, and easy document signing for businesses. Streamline your workflow with our powerful e-signature platform.';

  const resolvedTitle = typeof title === 'string' ? title : title ? i18n._(title) : '';

  return [
    {
      title: resolvedTitle ? `${resolvedTitle} - Epic Sign` : 'Epic Sign',
    },
    {
      name: 'description',
      content: description,
    },
    {
      name: 'keywords',
      content:
        'Epic Sign, electronic signature, document signing, e-signature, digital signature, Epic Group, secure signing, business documents',
    },
    {
      name: 'author',
      content: 'Epic Group',
    },
    {
      name: 'robots',
      content: 'index, follow',
    },
    {
      property: 'og:title',
      content: 'Epic Sign - Professional Electronic Signature Solution',
    },
    {
      property: 'og:description',
      content: description,
    },
    {
      property: 'og:image',
      content: `${NEXT_PUBLIC_WEBAPP_URL()}/opengraph-image.jpg`,
    },
    {
      property: 'og:type',
      content: 'website',
    },
    {
      name: 'twitter:card',
      content: 'summary_large_image',
    },
    {
      name: 'twitter:description',
      content: description,
    },
    {
      name: 'twitter:image',
      content: `${NEXT_PUBLIC_WEBAPP_URL()}/opengraph-image.jpg`,
    },
  ];
};
