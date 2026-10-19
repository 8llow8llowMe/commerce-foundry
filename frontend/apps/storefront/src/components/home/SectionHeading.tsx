import { cx } from '@cf/ui';

/** 섹션 머리 — 작은 대문자 라벨 + 영문 제목(display 글꼴). 섹션마다 같은 위계를 쓴다 */
export function SectionHeading({
  id,
  eyebrow,
  title,
  align = 'left',
}: {
  id: string;
  eyebrow?: string | null;
  title: string;
  align?: 'left' | 'center';
}) {
  return (
    <div className={cx('flex flex-col gap-3', align === 'center' && 'items-center text-center')}>
      {eyebrow && <p className="text-xs tracking-[0.25em] text-muted-fg uppercase">{eyebrow}</p>}
      <h2 id={id} className="font-display text-3xl lg:text-5xl">
        {title}
      </h2>
    </div>
  );
}
