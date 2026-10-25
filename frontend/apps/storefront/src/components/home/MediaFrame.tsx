import type { HomeMediaSlot } from '@cf/ui';
import { cx } from '@cf/ui';

/**
 * 고정 비율 이미지 칸. 비율은 className 의 aspect-* 가 정한다 — 이미지가 늦게 와도 레이아웃이 밀리지 않는다(CLS).
 * 화보가 아직 없으면 같은 비율의 빈 자리에 필요한 사진을 적어 둔다. 모바일 사진(mobileSrc)이 따로 있으면 <picture> 로 고른다
 * (히어로와 같은 1024px 기준).
 */
export function MediaFrame({
  slot,
  className,
  priority = false,
}: {
  slot: HomeMediaSlot;
  className?: string;
  /** 첫 화면 이미지(히어로)만 true — 나머지는 lazy */
  priority?: boolean;
}) {
  return (
    <div data-reveal="media" className={cx('relative overflow-hidden bg-muted', className)}>
      {slot.image ? (
        <picture>
          {slot.image.mobileSrc && <source media="(min-width: 1024px)" srcSet={slot.image.src} />}
          {/* 이미지 CDN 이 정해지지 않아 next/image 를 아직 쓰지 않는다 (상품 화면과 같다) */}
          <img
            src={slot.image.mobileSrc ?? slot.image.src}
            alt={slot.image.alt}
            width={slot.image.width}
            height={slot.image.height}
            loading={priority ? 'eager' : 'lazy'}
            fetchPriority={priority ? 'high' : undefined}
            decoding="async"
            className="absolute inset-0 size-full object-cover"
          />
        </picture>
      ) : (
        <span className="absolute inset-0 flex items-center justify-center p-4 text-center text-xs tracking-[0.1em] text-muted-fg">
          {slot.placeholder}
        </span>
      )}
    </div>
  );
}
