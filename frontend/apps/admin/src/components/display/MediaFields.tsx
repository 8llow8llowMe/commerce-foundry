'use client';

import { FieldError, TextField } from '@cf/ui';
import type { MediaFormItem } from '@/lib/display/form';
import type { SectionTypeCode } from '@/lib/display/types';

/**
 * 섹션에 있는 이미지 · 영상 — 썸네일은 읽기만 하고 대체 텍스트만 고친다. 고친 값은 전체 교체 PUT 에 같은 주소와 함께 실린다.
 * 교체 · 추가 · 삭제는 어드민 업로드 기능(#9)이 생기면 붙인다.
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
  return (
    <fieldset className="flex min-w-0 flex-col gap-3">
      <legend className="text-sm font-medium">이미지 · 영상</legend>
      <p className="text-xs text-muted-fg">
        이미지 · 영상 교체는 업로드 기능이 준비되면 지원합니다. 지금은 대체 텍스트만 고칠 수
        있습니다.
        {type === 'HERO' && media.length > 1 && ' 지금 메인에는 첫 번째 이미지 · 영상만 보입니다.'}
      </p>
      <ul className="flex flex-col gap-3">
        {media.map((item, index) => {
          const isVideo = item.media.mediaType.code === 'VIDEO';
          const preview = isVideo ? item.media.pcPosterUrl : item.media.pcUrl;
          return (
            <li key={index} className="flex flex-wrap items-start gap-4 rounded-md bg-bg p-3">
              <div className="flex w-28 shrink-0 flex-col gap-1">
                {preview ? (
                  // 이미지 CDN 이 정해지지 않아 next/image 를 쓰지 않는다(스토어프론트 메인과 같다)
                  // eslint-disable-next-line @next/next/no-img-element
                  <img
                    src={preview}
                    alt=""
                    loading="lazy"
                    decoding="async"
                    className="aspect-square w-full rounded bg-muted object-cover"
                  />
                ) : (
                  <div className="aspect-square w-full rounded bg-muted" />
                )}
                <span className="text-xs text-muted-fg">
                  {index + 1}. {item.media.mediaType.name}
                  {isVideo && ' (대표 사진)'}
                </span>
              </div>
              <div className="flex min-w-0 flex-1 flex-col gap-1">
                <TextField
                  label={`${index + 1}번째 ${item.media.mediaType.name} 대체 텍스트`}
                  value={item.altText}
                  onChange={(event) => onAltText(index, event.currentTarget.value)}
                  errors={errorsOf(`media.${index}.altText`)}
                />
                <p className="text-xs text-muted-fg">
                  {item.altText.trim() === ''
                    ? '비어 있으면 화면 낭독기가 이 사진을 건너뜁니다. 사진에 보이는 것을 짧게 적어 주세요.'
                    : '사진을 볼 수 없는 고객에게 읽어 주는 문장입니다.'}
                </p>
                {item.caption && <p className="text-xs text-muted-fg">설명 문구: {item.caption}</p>}
              </div>
            </li>
          );
        })}
      </ul>
      <FieldError messages={errorsOf('media')} />
    </fieldset>
  );
}
