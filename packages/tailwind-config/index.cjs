/* eslint-disable @typescript-eslint/no-var-requires */
const { fontFamily } = require('tailwindcss/defaultTheme');
const { default: flattenColorPalette } = require('tailwindcss/lib/util/flattenColorPalette');

/** @type {import('tailwindcss').Config} */
module.exports = {
  darkMode: ['variant', '&:is(.dark:not(.dark-mode-disabled) *)'],
  content: ['src/**/*.{ts,tsx}'],
  theme: {
    extend: {
      fontFamily: {
        sans: ['var(--font-sans)', ...fontFamily.sans],
        signature: ['var(--font-signature)'],
        noto: ['var(--font-noto)'],
      },
      zIndex: {
        9999: '9999',
      },
      aspectRatio: {
        'signature-pad': '16 / 7',
      },
      colors: {
        border: 'hsl(var(--border))',
        'field-border': 'hsl(var(--field-border))',
        input: 'hsl(var(--input))',
        ring: 'hsl(var(--ring))',
        background: 'hsl(var(--background))',
        foreground: 'hsl(var(--foreground))',
        primary: {
          DEFAULT: 'hsl(var(--primary))',
          foreground: 'hsl(var(--primary-foreground))',
        },
        'envelope-editor-background': 'hsl(var(--envelope-editor-background))',
        secondary: {
          DEFAULT: 'hsl(var(--secondary))',
          foreground: 'hsl(var(--secondary-foreground))',
        },
        warning: {
          DEFAULT: 'hsl(var(--warning))',
        },
        destructive: {
          DEFAULT: 'hsl(var(--destructive))',
          foreground: 'hsl(var(--destructive-foreground))',
        },
        muted: {
          DEFAULT: 'hsl(var(--muted))',
          foreground: 'hsl(var(--muted-foreground))',
        },
        accent: {
          DEFAULT: 'hsl(var(--accent))',
          foreground: 'hsl(var(--accent-foreground))',
        },
        popover: {
          DEFAULT: 'hsl(var(--popover))',
          foreground: 'hsl(var(--popover-foreground))',
        },
        card: {
          DEFAULT: 'hsl(var(--card))',
          foreground: 'hsl(var(--card-foreground))',
        },
        'field-card': {
          DEFAULT: 'hsl(var(--field-card))',
          border: 'hsl(var(--field-card-border))',
          foreground: 'hsl(var(--field-card-foreground))',
        },
        widget: {
          DEFAULT: 'hsl(var(--widget))',
          foreground: 'hsl(var(--widget-foreground))',
        },
        // Epic Group brand palette.
        //
        // The key is still `documenso` on purpose: ~40 components use
        // `text-documenso*` / `bg-documenso*` and four e2e specs assert
        // `svg.text-documenso`. Renaming it would be a large, purely cosmetic
        // diff, so only the values change. This is the primary (epic-blue).
        //
        // Scales come from Project-Operations/react/src/theme.ts, which is the
        // reference Epic Group palette. Note the previous ramp was fake —
        // only DEFAULT/500 were brand, 50-400 and 600-950 were stock Tailwind
        // `sky`, so 600 was barely darker than 500.
        documenso: {
          DEFAULT: '#358ab5',
          50: '#eff6f9',
          100: '#dbeaf2',
          200: '#bedae7',
          300: '#9ec7db',
          400: '#7cb3cf',
          500: '#358ab5',
          600: '#2b7194',
          700: '#215670',
          800: '#153748',
          900: '#0f2734',
          950: '#0b1d26',
        },
        'epic-blue': {
          DEFAULT: '#358ab5',
          50: '#eff6f9',
          100: '#dbeaf2',
          200: '#bedae7',
          300: '#9ec7db',
          400: '#7cb3cf',
          500: '#358ab5',
          600: '#2b7194',
          700: '#215670',
          800: '#153748',
          900: '#0f2734',
          950: '#0b1d26',
        },
        // Secondary / accent. Kept distinct from `destructive`, which stays on
        // its own token so "delete" never looks like a brand action.
        'epic-red': {
          DEFAULT: '#e91d2d',
          50: '#ffeaeb',
          100: '#fdd4d6',
          200: '#f4a8ab',
          300: '#ee7a7e',
          400: '#ea4f55',
          500: '#e91d2d',
          600: '#d11525',
          700: '#b8101f',
          800: '#9e0c19',
          900: '#850814',
          950: '#5c050e',
        },
        'epic-charcoal': {
          DEFAULT: '#3d3935',
          50: '#f7f5f3',
          100: '#eae6e1',
          200: '#d8d1ca',
          300: '#bcb2a9',
          400: '#9a8f84',
          500: '#6f665d',
          600: '#3d3935',
          700: '#2c2926',
          800: '#1e1c1a',
          900: '#131211',
          950: '#0a0909',
        },
        'epic-taupe': {
          DEFAULT: '#958a82',
          50: '#f7f5f4',
          100: '#ece8e5',
          200: '#d9d2cc',
          300: '#c6bcb3',
          400: '#b3a69a',
          500: '#958a82',
          600: '#7a7068',
          700: '#5f574f',
          800: '#453f39',
          900: '#2c2824',
          950: '#1a1714',
        },
        dawn: {
          DEFAULT: '#aaa89f',
          50: '#f8f8f8',
          100: '#f1f1ef',
          200: '#e6e5e2',
          300: '#d4d3cd',
          400: '#b9b7b0',
          500: '#aaa89f',
          600: '#88857a',
          700: '#706e65',
          800: '#5f5d55',
          900: '#52514a',
          950: '#2a2925',
        },
        water: {
          DEFAULT: '#d7e4f3',
          50: '#f3f6fb',
          100: '#e3ebf6',
          200: '#d7e4f3',
          300: '#abc7e5',
          400: '#82abd8',
          500: '#658ecc',
          600: '#5175bf',
          700: '#4764ae',
          800: '#3e538f',
          900: '#364772',
          950: '#252d46',
        },
        recipient: {
          green: 'hsl(var(--recipient-green))',
          blue: 'hsl(var(--recipient-blue))',
          purple: 'hsl(var(--recipient-purple))',
          orange: 'hsl(var(--recipient-orange))',
          yellow: 'hsl(var(--recipient-yellow))',
          pink: 'hsl(var(--recipient-pink))',
        },
      },
      backgroundImage: {
        'gradient-radial': 'radial-gradient(var(--tw-gradient-stops))',
        'gradient-conic': 'conic-gradient(from 180deg at 50% 50%, var(--tw-gradient-stops))',
      },
      borderRadius: {
        DEFAULT: 'calc(var(--radius) - 3px)',
        '2xl': 'calc(var(--radius) + 4px)',
        xl: 'calc(var(--radius) + 2px)',
        lg: 'var(--radius)',
        md: 'calc(var(--radius) - 2px)',
        sm: 'calc(var(--radius) - 4px)',
      },
      keyframes: {
        'accordion-down': {
          from: { height: 0 },
          to: { height: 'var(--radix-accordion-content-height)' },
        },
        'accordion-up': {
          from: { height: 'var(--radix-accordion-content-height)' },
          to: { height: 0 },
        },
        'caret-blink': {
          '0%,70%,100%': { opacity: '1' },
          '20%,50%': { opacity: '0' },
        },
      },
      animation: {
        'accordion-down': 'accordion-down 0.2s ease-out',
        'accordion-up': 'accordion-up 0.2s ease-out',
        'caret-blink': 'caret-blink 1.25s ease-out infinite',
      },
      screens: {
        '3xl': '1920px',
        '4xl': '2560px',
        '5xl': '3840px',
        print: { raw: 'print' },
      },
    },
  },
  plugins: [
    require('tailwindcss-animate'),
    require('@tailwindcss/typography'),
    require('@tailwindcss/container-queries'),
    addVariablesForColors,
  ],
};

function addVariablesForColors({ addBase, theme }) {
  const allColors = flattenColorPalette(theme('colors'));
  const newVars = Object.fromEntries(Object.entries(allColors).map(([key, val]) => [`--${key}`, val]));

  addBase({
    ':root': newVars,
  });
}
