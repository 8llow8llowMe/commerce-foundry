import { cx } from '@cf/ui';

/** 섹션 제목(display 글꼴). 제목 위 작은 라벨(eyebrow)은 두지 않는다 — 필요한 정보는 제목 · 첫 문장에 넣는다(#57) */
export function SectionHeading({
  id,
  title,
  align = 'left',
}: {
  id: string;
  title: string;
  align?: 'left' | 'center';
}) {
  return (
    <div className={cx('flex flex-col gap-3', align === 'center' && 'items-center text-center')}>
      <h2 id={id} className="font-display text-3xl lg:text-5xl">
        {title}
      </h2>
    </div>
  );
}
