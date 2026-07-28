import type { SVGAttributes } from 'react';

export type LogoProps = SVGAttributes<SVGSVGElement>;

/**
 * The Epic Group mark on its own. Use in tight spots — app header, embeds,
 * mobile nav — where the full stacked `BrandingLogo` wordmark would be too
 * small to read.
 *
 * Fill is the brand red rather than `currentColor`: this is a logo, not an
 * icon, so it should not recolour with surrounding text. Red reads correctly on
 * both light and dark backgrounds, so it needs no dark-mode variant.
 */
export const BrandingLogoIcon = ({ ...props }: LogoProps) => {
  return (
    <svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 155 125" {...props}>
      <g fill="#e91d2d">
        <path
          d="M163.1448,240.3631V306.989h65.8163a1.011,1.011,0,0,1,0-.1557,70.3557,70.3557,0,0,0-65.7683-66.47c-.048,0,.06.0079-.048,0"
          transform="translate(-88.2235 -240.3631)"
        />
        <path
          d="M211.0307,320.8218s-4.6978,26.18-30.3081,38.7024A45.0672,45.0672,0,0,1,149.8,362.2959V240.7527a70.4286,70.4286,0,1,0,78.4739,80.1131v-.044c-.1077-.004-17.2436,0-17.2436,0"
          transform="translate(-88.2235 -240.3631)"
        />
      </g>
    </svg>
  );
};
