import type { ReactNode } from 'react';
import { Link } from '@/components/i18n/Link';

export type NavAnchorProps = {
  /** 앱 경로 · 외부 https 주소. null 이면 링크 없는 묶음 제목(글자만) */
  href: string | null;
  external?: boolean;
  /** 스크린리더 새 창 안내(사전 `header.newWindow`) */
  newWindowLabel: string;
  className?: string;
  onClick?: () => void;
  children: ReactNode;
};

/**
 * 메뉴 링크 한 개. 앱 경로는 언어 접두어를 붙이는 앱 `Link`, 다른 사이트는 새 창(`noopener noreferrer`) + 스크린리더 새 창 안내,
 * 링크가 없으면(묶음 제목) 글자만 둔다 — 누를 수 있는 것처럼 보이지 않게 span 이다.
 */
export function NavAnchor({
  href,
  external,
  newWindowLabel,
  className,
  onClick,
  children,
}: NavAnchorProps) {
  if (href === null) return <span className={className}>{children}</span>;
  if (external)
    return (
      <a
        href={href}
        target="_blank"
        rel="noopener noreferrer"
        className={className}
        onClick={onClick}
      >
        {children}
        {/* 눈으로 보는 새 창 표시(어드민 미리보기와 같다). 스크린리더는 아래 문구로 읽는다 */}
        <span aria-hidden="true"> ↗</span>
        <span className="sr-only"> ({newWindowLabel})</span>
      </a>
    );
  return (
    <Link href={href} className={className} onClick={onClick}>
      {children}
    </Link>
  );
}
