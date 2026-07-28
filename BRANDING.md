# Branding — Epic Sign

This fork of Documenso is branded as **Epic Sign** for Epic Group.

## Identity

| | |
|---|---|
| Product name | Epic Sign |
| Primary | epic-blue `#358ab5` |
| Secondary / accent | epic-red `#e91d2d` |
| Neutrals | epic-charcoal `#3d3935`, epic-taupe `#958a82` |
| Domain | `epicgroup.ca` |
| Support | `support@epicgroup.ca` |
| From address | `noreply@epicgroup.ca` |

Palette scales are lifted from `Project-Operations/react/src/theme.ts`, the
reference Epic Group palette, so the two apps agree.

**Blue is primary, not the logo's red.** Documenso uses red for destructive
actions, so a red primary would make "Send document" and "Delete" look the same.
This also matches Project-Operations, whose `CLAUDE.md` states epic-blue is
primary and that red is being migrated out of general use.

## Where colour lives

| File | Holds |
|---|---|
| `packages/tailwind-config/index.cjs` | the `documenso` ramp (= epic-blue) plus `epic-red` / `epic-charcoal` / `epic-taupe` |
| `packages/ui/styles/theme.css` | CSS custom properties, light **and** dark blocks |
| `packages/lib/constants/theme.ts` | `DEFAULT_BRAND_COLORS` — defaults for the per-organisation branding colour pickers and the email colour fallback |

The Tailwind palette key is still **`documenso`** on purpose: ~40 components use
`text-documenso*` / `bg-documenso*` and four e2e specs assert
`svg.text-documenso`. Only the values changed.

`theme.ts` and `theme.css` must be kept in sync by hand — nothing enforces it.
Dark mode uses a lighter primary (55% vs 46% lightness) because the foreground
tokens are used as text, and 46% falls under the 4.5:1 AA threshold on the dark
background.

## Assets

All in `packages/assets/`, copied to `apps/remix/public/` and the `static/`
directories.

| File | Use |
|---|---|
| `epic-logo.svg` | full stacked logo, light |
| `epic-logo-dark.svg` | full stacked logo, dark (white wordmark) |
| `epic-mark.svg` | the red circle mark alone |
| `logo.png` | raster of the full logo — emails and the PDF certificate renderer, neither of which can use SVG |
| `favicon.ico`, `favicon-16x16.png`, `favicon-32x32.png`, `apple-touch-icon.png`, `android-chrome-192x192.png`, `android-chrome-512x512.png` | generated from `epic-mark.svg` |
| `opengraph-image.jpg` | 1200×630, full logo on white |

`logo.png` is duplicated by hand in four places — `packages/assets/`,
`packages/assets/static/`, `apps/remix/public/static/`, `packages/email/static/`
— with no build step syncing them. Update all four together.

`apple-touch-icon.png` has a white background on purpose: iOS composites
transparency onto black.

## Logo components

- `BrandingLogo` — the full stacked logo. Renders both variants and toggles with
  `dark:hidden` / `hidden dark:block`. Use only where there is vertical room.
- `BrandingLogoIcon` — the mark. Use in the app header, embeds, and any slot
  under roughly h-8, where the stacked wordmark would be unreadable. Its fill is
  hard-coded brand red, not `currentColor`: it is a logo, not an icon.

The Epic logo is stacked (~1.85:1); the old Davinci one was horizontal (3.33:1).
Do not drop it into a short, wide slot.

Never use `dark:invert` on the logo — it turns the red mark cyan.

## Keeping branding through upstream syncs

`tools/upstream-sync/` rewrites branding on every nightly merge from upstream
Documenso. Its substitution tables live in `branding_resolver/config.py` and
`config.yaml`. **Any new brand string or colour belongs there too**, or the next
sync reintroduces the upstream value. Its tests under `tools/upstream-sync/tests/`
assert the mappings — run them after any change.

## Deliberately not rebranded

- `@documenso/*` package scope — internal; renaming breaks hundreds of imports.
- The `documenso` Tailwind palette key and the `documenso-branded` DOM class.
- `X-Documenso-Secret` and the `X-Documenso-*` email headers — outgoing headers,
  so renaming breaks consumers, and they are not user-visible.
- Attribution links to documenso.com and the upstream GitHub repo.
- `packages/lib/translations/sq/` — Albanian is not in `SUPPORTED_LANGUAGE_CODES`,
  so the catalogue is unreachable and was left alone.
- `apps/docs/` product screenshots — documentation content showing the upstream
  UI, not branding.
