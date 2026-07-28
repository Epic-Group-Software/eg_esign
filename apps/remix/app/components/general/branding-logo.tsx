import LogoImage from '@documenso/assets/epic-logo.svg';

import LogoDarkImage from '@documenso/assets/epic-logo-dark.svg';
import { cn } from '@documenso/ui/lib/utils';
import type { HTMLAttributes } from 'react';

export type LogoProps = HTMLAttributes<HTMLImageElement> & {
  className?: string;
};

/**
 * The full stacked Epic Group logo. Use where there is vertical room — auth
 * pages, emails, PDF certificates. In tight spots (app header, embeds) use
 * `BrandingLogoIcon`, which is just the mark: at h-6 the stacked wordmark is
 * unreadable.
 *
 * Dark mode swaps the asset rather than filtering it. The wordmark is charcoal
 * and the mark is red, so `dark:invert` would turn the mark cyan — which is
 * what the previous logo did.
 */
export const BrandingLogo = ({ className, ...props }: LogoProps) => {
  return (
    <>
      <img src={LogoImage} alt="Epic Sign" className={cn('dark:hidden', className)} {...props} />
      <img src={LogoDarkImage} alt="Epic Sign" className={cn('hidden dark:block', className)} {...props} />
    </>
  );
};
