import type { SVGProps } from 'react';

/**
 * Decorative icons after the Phosphor set the design uses (regular weight,
 * 256 grid), drawn inline so no icon package is added (FE25). All aria-hidden.
 */
type Props = SVGProps<SVGSVGElement>;

function Icon({ children, className, ...rest }: Props) {
  return (
    <svg
      viewBox="0 0 256 256"
      fill="none"
      stroke="currentColor"
      strokeWidth={16}
      strokeLinecap="round"
      strokeLinejoin="round"
      className={className ? `icon ${className}` : 'icon'}
      aria-hidden="true"
      focusable="false"
      {...rest}
    >
      {children}
    </svg>
  );
}

export const WarningCircle = (p: Props) => (
  <Icon {...p}>
    <circle cx={128} cy={128} r={96} />
    <line x1={128} y1={80} x2={128} y2={136} />
    <circle cx={128} cy={172} r={10} fill="currentColor" stroke="none" />
  </Icon>
);

export const CircleNotch = (p: Props) => (
  <Icon {...p} className={p.className ? `spin ${p.className}` : 'spin'}>
    <path d="M168 40.7a96 96 0 1 1-80 0" />
  </Icon>
);

export const ArrowRight = (p: Props) => (
  <Icon {...p}>
    <line x1={40} y1={128} x2={216} y2={128} />
    <polyline points="144 56 216 128 144 200" />
  </Icon>
);

export const ArrowLeft = (p: Props) => (
  <Icon {...p}>
    <line x1={216} y1={128} x2={40} y2={128} />
    <polyline points="112 56 40 128 112 200" />
  </Icon>
);

export const CheckCircle = (p: Props) => (
  <Icon {...p}>
    <circle cx={128} cy={128} r={96} />
    <polyline points="172 104 113.3 160 84 132" />
  </Icon>
);

export const PlusCircle = (p: Props) => (
  <Icon {...p}>
    <circle cx={128} cy={128} r={96} />
    <line x1={88} y1={128} x2={168} y2={128} />
    <line x1={128} y1={88} x2={128} y2={168} />
  </Icon>
);

export const ShoppingBag = (p: Props) => (
  <Icon {...p}>
    <path d="M40 72h176v136a8 8 0 0 1-8 8H48a8 8 0 0 1-8-8Z" />
    <path d="M88 104V72a40 40 0 0 1 80 0v32" />
  </Icon>
);

export const PencilSimple = (p: Props) => (
  <Icon {...p}>
    <path d="M92.7 216H48a8 8 0 0 1-8-8v-44.7a8 8 0 0 1 2.3-5.6L165.7 34.3a8 8 0 0 1 11.3 0l44.7 44.7a8 8 0 0 1 0 11.3L98.3 213.7a8 8 0 0 1-5.6 2.3Z" />
    <line x1={136} y1={64} x2={192} y2={120} />
  </Icon>
);
