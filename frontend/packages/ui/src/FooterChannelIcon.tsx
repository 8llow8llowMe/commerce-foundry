import { cx } from './cx';

/** 푸터 채널 유형 제어 코드(BE #597 `StoreFooter` type) */
export type FooterChannelType = 'INSTAGRAM' | 'KAKAO_CHANNEL' | 'CUSTOM';

/**
 * 푸터 채널 아이콘 — 꾸밈이다(aria-hidden). 접근 가능한 이름은 링크가 로케일 표시명으로 단다.
 * 색은 글자색(currentColor)을 따른다 — 브랜드 고유색을 쓰지 않는다. 스토어프론트 푸터와 어드민 미리보기가 같이 쓴다.
 */
export function FooterChannelIcon({
  type,
  className,
}: {
  type: FooterChannelType;
  className?: string;
}) {
  const common = {
    'aria-hidden': true,
    focusable: false,
    viewBox: '0 0 24 24',
    className: cx('size-5 shrink-0', className),
  } as const;

  if (type === 'INSTAGRAM')
    return (
      <svg {...common} fill="none" stroke="currentColor" strokeWidth={1.6}>
        <rect x="3" y="3" width="18" height="18" rx="5" />
        <circle cx="12" cy="12" r="4.2" />
        <circle cx="17.4" cy="6.6" r="0.9" fill="currentColor" stroke="none" />
      </svg>
    );
  if (type === 'KAKAO_CHANNEL')
    return (
      <svg {...common} fill="currentColor">
        <path d="M12 3.5c-5.24 0-9.5 3.33-9.5 7.44 0 2.64 1.76 4.96 4.4 6.28l-.9 3.3a.35.35 0 0 0 .53.38l3.92-2.6c.5.06 1.02.09 1.55.09 5.24 0 9.5-3.33 9.5-7.45S17.24 3.5 12 3.5Z" />
      </svg>
    );
  return (
    <svg {...common} fill="none" stroke="currentColor" strokeWidth={1.6}>
      <path d="M14 4h6v6M20 4l-9 9M18 14v5a1 1 0 0 1-1 1H5a1 1 0 0 1-1-1V7a1 1 0 0 1 1-1h5" />
    </svg>
  );
}
