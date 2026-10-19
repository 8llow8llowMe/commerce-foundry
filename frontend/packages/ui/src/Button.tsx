import type { ButtonHTMLAttributes } from 'react';
import { cx } from './cx';

type Variant = 'primary' | 'secondary';

export type ButtonProps = ButtonHTMLAttributes<HTMLButtonElement> & {
  variant?: Variant;
};

const VARIANT_CLASS: Record<Variant, string> = {
  primary: 'bg-primary text-primary-fg hover:opacity-90',
  // 버튼은 한 가지 모양(직각 + 단색 채움)으로 — 보조는 테두리 대신 강조 배경(accent)으로 채운다
  secondary: 'bg-accent text-accent-fg hover:opacity-90',
};

/** 기본 type 은 button 이다. 폼 제출은 type="submit" 을 명시한다. */
export function Button({ variant = 'primary', className, type = 'button', ...rest }: ButtonProps) {
  return (
    <button
      type={type}
      className={cx(
        'inline-flex h-10 items-center justify-center rounded-md px-4 text-sm font-medium transition disabled:cursor-not-allowed disabled:opacity-50',
        VARIANT_CLASS[variant],
        className,
      )}
      {...rest}
    />
  );
}
