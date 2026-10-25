import { cx } from '@cf/ui';

/**
 * 섹션 제목(display 글꼴). 제목 위 작은 라벨(eyebrow)은 두지 않는다 — 필요한 정보는 제목 · 첫 문장에 넣는다(#57).
 * 제목이 비면(메인 전시 API 의 제목은 선택) 그리지 않는다 — 섹션은 aria-labelledby 를 빼야 한다(sectionLabel).
 */
export function SectionHeading({
  id,
  title,
  align = 'left',
  size = 'title',
}: {
  id: string;
  title: string;
  align?: 'left' | 'center';
  /** title: 섹션 제목(28 / 48px), display: 페이지의 대표 제목 하나(40 / 72~96px, #57 §4) */
  size?: 'title' | 'display';
}) {
  if (!title) return null;
  return (
    <div
      data-reveal="title"
      className={cx('flex flex-col gap-3', align === 'center' && 'items-center text-center')}
    >
      <h2
        id={id}
        className={cx(
          'font-display tracking-[-0.02em]',
          size === 'display'
            ? 'text-[2.5rem] leading-none font-light lg:text-[clamp(4.5rem,5.6vw,6rem)]'
            : 'text-[1.75rem] leading-[1.05] lg:text-5xl',
        )}
      >
        {title}
      </h2>
    </div>
  );
}

/** 제목이 있을 때만 섹션 이름을 제목에 잇는다(빈 제목은 그리지 않는다) */
export function sectionLabel(id: string, title: string): string | undefined {
  return title ? id : undefined;
}
