'use client';

import { Button, FieldError } from '@cf/ui';
import { useState } from 'react';
import { newImageItem, type MediaFormItem } from '@/lib/display/form';
import { normalizeText } from '@/lib/display/text';
import { ITEM_RANGES, LIMITS, type SectionTypeCode } from '@/lib/display/types';
import { TextInput } from './SectionEditor';

/**
 * 주소로 넣는 이미지(#419, 메인 전시 LOOK) — 업로드(#9)가 아직 없어 쇼핑몰 이미지 저장소에 이미 올린 이미지의 주소를 넣는다(룩 관리 화면과 같다).
 * 넣고 · 빼고 · 주소와 대체 텍스트를 고친다. 저장소 주소인지는 서버가 본다(`PRODUCT_054`). 영상은 주소를 바꾸지 않고 빼기만 한다.
 * 메인은 첫 번째 이미지만 그린다(영상은 대표 사진).
 */
export function MediaUrlFields({
  type,
  media,
  errorsOf,
  onChange,
}: {
  type: SectionTypeCode;
  media: MediaFormItem[];
  errorsOf: (key: string) => string[];
  onChange: (media: MediaFormItem[]) => void;
}) {
  const [min, max] = ITEM_RANGES[type].media;
  const update = (index: number, patch: Partial<MediaFormItem>) =>
    onChange(media.map((item, at) => (at === index ? { ...item, ...patch } : item)));

  return (
    <fieldset className="flex min-w-0 flex-col gap-3">
      <legend className="text-sm font-medium">
        이미지 ({media.length} / {max}개)
      </legend>
      <p className="text-xs text-muted-fg">
        이미지 업로드는 준비 중입니다. 지금은 쇼핑몰 이미지 저장소에 이미 올린 이미지의 주소를 넣어
        주세요. 다른 사이트의 이미지 주소는 저장되지 않습니다. 메인에는 첫 번째 이미지만 보입니다.
      </p>
      <ul className="flex flex-col gap-3">
        {media.map((item, index) => (
          <MediaUrlRow
            // 불러오기 실패 표시는 주소로 판정하므로 줄을 빼도 다른 줄에 섞이지 않는다
            key={index}
            index={index}
            item={item}
            errorsOf={errorsOf}
            removable={media.length > min}
            onUrl={(url) => update(index, { url })}
            onAltText={(altText) => update(index, { altText })}
            onRemove={() => onChange(media.filter((_, at) => at !== index))}
          />
        ))}
      </ul>
      <div>
        <Button
          variant="secondary"
          disabled={media.length >= max}
          onClick={() => onChange([...media, newImageItem()])}
        >
          이미지 주소 추가
        </Button>
      </div>
      <FieldError messages={errorsOf('media')} />
    </fieldset>
  );
}

function MediaUrlRow({
  index,
  item,
  errorsOf,
  removable,
  onUrl,
  onAltText,
  onRemove,
}: {
  index: number;
  item: MediaFormItem;
  errorsOf: (key: string) => string[];
  removable: boolean;
  onUrl: (url: string) => void;
  onAltText: (altText: string) => void;
  onRemove: () => void;
}) {
  const isVideo = item.media.mediaType.code === 'VIDEO';
  const preview = isVideo ? item.media.pcPosterUrl : normalizeText(item.url);
  const [failedUrl, setFailedUrl] = useState<string | null>(null);
  const failed = preview !== null && failedUrl === preview;
  const position = `${index + 1}번째 ${item.media.mediaType.name}`;

  return (
    <li className="flex flex-wrap items-start gap-4 rounded-md bg-bg p-3">
      <div className="flex w-28 shrink-0 flex-col gap-1">
        {preview && !failed ? (
          // 이미지 CDN 이 정해지지 않아 next/image 를 쓰지 않는다(스토어프론트 메인과 같다)
          // eslint-disable-next-line @next/next/no-img-element
          <img
            key={preview}
            src={preview}
            alt=""
            decoding="async"
            onError={() => setFailedUrl(preview)}
            className="aspect-[4/5] w-full rounded bg-muted object-cover"
          />
        ) : (
          <div className="flex aspect-[4/5] w-full items-center justify-center rounded bg-muted p-2 text-center text-xs text-muted-fg">
            {failed ? '이미지를 불러오지 못했습니다' : '이미지 없음'}
          </div>
        )}
        <span className="text-xs text-muted-fg">
          {index + 1}. {item.media.mediaType.name}
          {isVideo && ' (대표 사진)'}
        </span>
      </div>
      <div className="flex min-w-0 flex-1 flex-col gap-4">
        {isVideo ? (
          <p className="text-xs text-muted-fg">
            영상 주소는 여기서 바꿀 수 없습니다. 메인에는 대표 사진이 보입니다.
          </p>
        ) : (
          <TextInput
            label={`${position} 주소`}
            value={item.url}
            max={LIMITS.mediaUrl}
            multiline={false}
            hint={
              failed
                ? '이 주소의 이미지를 불러오지 못했습니다. 주소가 맞는지 확인해 주세요.'
                : undefined
            }
            errors={errorsOf(`media.${index}.url`)}
            onChange={onUrl}
          />
        )}
        <TextInput
          label={`${position} 대체 텍스트`}
          value={item.altText}
          max={LIMITS.altText}
          multiline={false}
          hint={
            item.altText.trim() === ''
              ? '비어 있으면 화면 낭독기가 이 사진을 건너뜁니다. 사진에 보이는 것을 짧게 적어 주세요.'
              : '사진을 볼 수 없는 고객에게 읽어 주는 문장입니다.'
          }
          errors={errorsOf(`media.${index}.altText`)}
          onChange={onAltText}
        />
        {removable && (
          <div>
            <Button variant="secondary" onClick={onRemove} aria-label={`${position} 빼기`}>
              빼기
            </Button>
          </div>
        )}
      </div>
    </li>
  );
}
