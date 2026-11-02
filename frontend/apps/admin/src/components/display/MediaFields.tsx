'use client';

import { FieldError, TextField } from '@cf/ui';
import type { MediaFormItem } from '@/lib/display/form';
import { mediaSpecHint, specFor } from '@/lib/display/media-spec';
import type { SectionTypeCode } from '@/lib/display/types';
import { MediaSizeNotice, useImageSize } from './MediaSizeCheck';

/**
 * 섹션에 있는 이미지 · 영상 — 썸네일은 읽기만 하고 대체 텍스트만 고친다. 고친 값은 전체 교체 PUT 에 같은 주소와 함께 실린다.
 * 교체 · 추가 · 삭제는 어드민 업로드 기능(#9)이 생기면 붙인다.
 * 히어로는 PC · 모바일 사진을 따로 보인다(메인이 `<picture>` 로 나눠 받는다). 썸네일의 원본 크기를 재어 권장 비율 · 해상도와 다르면 안내한다(#71).
 */
export function MediaFields({
  type,
  media,
  errorsOf,
  onAltText,
}: {
  type: SectionTypeCode;
  media: MediaFormItem[];
  errorsOf: (key: string) => string[];
  onAltText: (index: number, altText: string) => void;
}) {
  const specHint = mediaSpecHint(type);
  return (
    <fieldset className="flex min-w-0 flex-col gap-3">
      <legend className="text-sm font-medium">이미지 · 영상</legend>
      <p className="text-xs text-muted-fg">
        이미지 · 영상 교체는 업로드 기능이 준비되면 지원합니다. 지금은 대체 텍스트만 고칠 수
        있습니다.
        {type === 'HERO' && media.length > 1 && ' 지금 메인에는 첫 번째 이미지 · 영상만 보입니다.'}
      </p>
      {specHint && <p className="text-xs text-muted-fg">{specHint}</p>}
      <ul className="flex flex-col gap-3">
        {media.map((item, index) => (
          <MediaRow
            key={index}
            type={type}
            index={index}
            item={item}
            errors={errorsOf(`media.${index}.altText`)}
            onAltText={(altText) => onAltText(index, altText)}
          />
        ))}
      </ul>
      <FieldError messages={errorsOf('media')} />
    </fieldset>
  );
}

function MediaRow({
  type,
  index,
  item,
  errors,
  onAltText,
}: {
  type: SectionTypeCode;
  index: number;
  item: MediaFormItem;
  errors: string[];
  onAltText: (altText: string) => void;
}) {
  const isVideo = item.media.mediaType.code === 'VIDEO';
  const pcSrc = isVideo ? item.media.pcPosterUrl : item.media.pcUrl;
  const mobileSrc = isVideo ? item.media.mobilePosterUrl : item.media.mobileUrl;
  // 히어로만 PC · 모바일 칸이 따로다. 다른 유형의 모바일 주소는 메인이 같은 칸에 쓴다
  const split = type === 'HERO';
  const pc = useImageSize(pcSrc || null);
  const mobile = useImageSize(split ? mobileSrc || null : null);

  return (
    <li className="flex flex-wrap items-start gap-4 rounded-md bg-bg p-3">
      <div className="flex shrink-0 gap-2">
        <Thumb src={pcSrc} label={split ? 'PC' : null} measure={pc} />
        {split && <Thumb src={mobileSrc} label="모바일" measure={mobile} fallback="PC 사진을 씀" />}
      </div>
      <div className="flex min-w-0 flex-1 flex-col gap-1">
        <span className="text-xs text-muted-fg">
          {index + 1}. {item.media.mediaType.name}
          {isVideo && ' (대표 사진)'}
        </span>
        {isVideo ? (
          <TextField
            label={`${index + 1}번째 영상 대체 텍스트 (선택)`}
            value={item.altText}
            onChange={(event) => onAltText(event.currentTarget.value)}
            errors={errors}
          />
        ) : (
          <TextField
            label={`${index + 1}번째 이미지 대체 텍스트 (필수)`}
            value={item.altText}
            onChange={(event) => onAltText(event.currentTarget.value)}
            errors={errors}
            required
          />
        )}
        <p className="text-xs text-muted-fg">
          {isVideo
            ? '메인의 영상은 화면 낭독기가 읽지 않습니다.'
            : '사진을 볼 수 없는 고객에게 읽어 주는 문장입니다. 사진에 보이는 것을 짧게 적어 주세요.'}
        </p>
        <MediaSizeNotice
          spec={specFor(type, 'PC')}
          size={pc.size}
          label={split ? 'PC' : undefined}
        />
        {split && (
          <MediaSizeNotice spec={specFor(type, '모바일')} size={mobile.size} label="모바일" />
        )}
        {item.caption && <p className="text-xs text-muted-fg">설명 문구: {item.caption}</p>}
      </div>
    </li>
  );
}

function Thumb({
  src,
  label,
  measure,
  fallback = '사진 없음',
}: {
  src: string | null;
  label: string | null;
  measure: ReturnType<typeof useImageSize>;
  fallback?: string;
}) {
  return (
    <div className="flex w-28 flex-col gap-1">
      {src ? (
        // 이미지 CDN 이 정해지지 않아 next/image 를 쓰지 않는다(스토어프론트 메인과 같다)
        // eslint-disable-next-line @next/next/no-img-element
        <img
          src={src}
          alt=""
          loading="lazy"
          decoding="async"
          onLoad={measure.onLoad}
          className="aspect-[4/5] w-full rounded bg-muted object-cover"
        />
      ) : (
        <div className="flex aspect-[4/5] w-full items-center justify-center rounded bg-muted p-2 text-center text-xs text-muted-fg">
          {fallback}
        </div>
      )}
      {label && (
        <span className="text-xs text-muted-fg">
          {label}
          {measure.size && ` · ${measure.size.width}×${measure.size.height}`}
        </span>
      )}
    </div>
  );
}
