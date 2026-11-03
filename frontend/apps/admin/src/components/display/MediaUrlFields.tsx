'use client';

import { Button, FieldError } from '@cf/ui';
import { useState } from 'react';
import { DisplayImageUploadButton } from '@/components/upload/DisplayImageUploadButton';
import type { AccessDenied } from '@/lib/api-errors';
import {
  hasMediaCaption,
  hasMobileMedia,
  newImageItem,
  type MediaFormItem,
} from '@/lib/display/form';
import { mediaSpecHint, specFor } from '@/lib/display/media-spec';
import { normalizeText } from '@/lib/display/text';
import { ITEM_RANGES, LIMITS, type SectionTypeCode } from '@/lib/display/types';
import { MediaSizeNotice, useImageSize } from './MediaSizeCheck';
import { TextInput } from './SectionEditor';

/** 메인이 미디어를 몇 장 그리는지 — 히어로는 첫 장만, 스토리 · 디테일은 모두(스토어프론트 `home-sections`) */
const FIRST_ONLY: readonly SectionTypeCode[] = ['HERO'];

/**
 * 미디어 칸(#419 · #71). 주소 칸마다 파일 올리기(BE #508 — 확정한 공개 주소가 주소 칸에 들어간다)를 두고, 저장소에 이미 올린 주소도 그대로 넣을 수 있다.
 * 넣고 · 빼고 · 주소와 대체 텍스트를 고친다. 히어로는 모바일 주소를 따로(비우면 PC 사진), 디테일은 클로즈업마다 설명을 쓴다.
 * 저장소 주소인지는 서버가 본다(`PRODUCT_054`). 영상은 업로드 API 가 받지 않아 주소를 바꾸지 않고 빼기만 한다.
 * 올리는 동안(`uploading`)은 줄을 넣고 빼지 않는다 — 끝난 업로드가 줄 위치로 주소를 넣기 때문이다.
 */
export function MediaUrlFields({
  type,
  media,
  errorsOf,
  onChange,
  uploading = false,
  onUploadingChange,
  onAccessDenied,
}: {
  type: SectionTypeCode;
  media: MediaFormItem[];
  errorsOf: (key: string) => string[];
  onChange: (media: MediaFormItem[]) => void;
  /** 이 폼에서 올리는 칸이 있는지 */
  uploading?: boolean;
  onUploadingChange?: (uploading: boolean) => void;
  onAccessDenied?: (denied: AccessDenied) => void;
}) {
  const [min, max] = ITEM_RANGES[type].media;
  const specHint = mediaSpecHint(type);
  const update = (index: number, patch: Partial<MediaFormItem>) =>
    onChange(media.map((item, at) => (at === index ? { ...item, ...patch } : item)));

  return (
    <fieldset className="flex min-w-0 flex-col gap-3">
      <legend className="text-sm font-medium">
        이미지 ({media.length} / {max}개)
      </legend>
      <p className="text-xs text-muted-fg">
        파일을 올리면 주소 칸이 채워집니다. 쇼핑몰 이미지 저장소에 이미 올린 이미지는 주소를 바로
        넣어도 됩니다. 다른 사이트의 이미지 주소는 저장되지 않습니다.
        {FIRST_ONLY.includes(type)
          ? ' 메인에는 첫 번째 이미지만 보입니다.'
          : ' 메인에는 넣은 순서대로 모두 보입니다.'}
      </p>
      {specHint && <p className="text-xs text-muted-fg">{specHint}</p>}
      <ul className="flex flex-col gap-3">
        {media.map((item, index) => (
          <MediaUrlRow
            // 불러오기 실패 표시는 주소로 판정하므로 줄을 빼도 다른 줄에 섞이지 않는다
            key={index}
            type={type}
            index={index}
            item={item}
            errorsOf={errorsOf}
            removable={media.length > min}
            locked={uploading}
            onUploadingChange={onUploadingChange}
            onAccessDenied={onAccessDenied}
            onChange={(patch) => update(index, patch)}
            onRemove={() => onChange(media.filter((_, at) => at !== index))}
          />
        ))}
      </ul>
      <div>
        <Button
          variant="secondary"
          disabled={media.length >= max || uploading}
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
  type,
  index,
  item,
  errorsOf,
  removable,
  locked,
  onUploadingChange,
  onAccessDenied,
  onChange,
  onRemove,
}: {
  type: SectionTypeCode;
  index: number;
  item: MediaFormItem;
  errorsOf: (key: string) => string[];
  removable: boolean;
  locked: boolean;
  onUploadingChange?: (uploading: boolean) => void;
  onAccessDenied?: (denied: AccessDenied) => void;
  onChange: (patch: Partial<MediaFormItem>) => void;
  onRemove: () => void;
}) {
  const isVideo = item.media.mediaType.code === 'VIDEO';
  // 히어로만 PC · 모바일 칸이 따로다. 다른 유형의 모바일 주소는 메인이 같은 칸에 쓴다
  const split = hasMobileMedia(type);
  const pcSrc = isVideo ? item.media.pcPosterUrl : normalizeText(item.url);
  const mobileSrc = isVideo ? item.media.mobilePosterUrl : normalizeText(item.mobileUrl);
  const pc = useThumb(pcSrc);
  const mobile = useThumb(split ? mobileSrc : null);
  const position = `${index + 1}번째 ${item.media.mediaType.name}`;
  const slot = (name: string) => (split ? ` ${name}` : '');

  return (
    <li className="flex flex-wrap items-start gap-4 rounded-md bg-bg p-3">
      <div className="flex shrink-0 gap-2">
        <Thumb thumb={pc} label={split ? 'PC' : null} />
        {split && <Thumb thumb={mobile} label="모바일" empty="PC 사진을 씀" />}
      </div>
      <div className="flex min-w-0 flex-1 flex-col gap-4">
        <span className="text-xs text-muted-fg">
          {index + 1}. {item.media.mediaType.name}
          {isVideo && ' (대표 사진)'}
        </span>
        {isVideo ? (
          <p className="text-xs text-muted-fg">
            영상 주소는 여기서 바꿀 수 없습니다. 메인에는 대표 사진이 보입니다.
          </p>
        ) : (
          <>
            <TextInput
              label={`${position}${slot('PC')} 주소`}
              value={item.url}
              max={LIMITS.mediaUrl}
              multiline={false}
              hint={pc.failed ? LOAD_FAILED : undefined}
              errors={errorsOf(`media.${index}.url`)}
              required
              onChange={(url) => onChange({ url })}
            />
            <DisplayImageUploadButton
              label={`${position}${slot('PC')} 파일 올리기`}
              onUploaded={(url) => onChange({ url })}
              onUploadingChange={onUploadingChange}
              onAccessDenied={onAccessDenied}
            />
            {split && (
              <TextInput
                label={`${position} 모바일 주소 (선택)`}
                value={item.mobileUrl}
                max={LIMITS.mediaUrl}
                multiline={false}
                hint={
                  mobile.failed
                    ? LOAD_FAILED
                    : '비우면 모바일에서도 PC 사진을 씁니다. 모바일 화면에 맞춰 자른 사진이 있으면 넣어 주세요.'
                }
                errors={errorsOf(`media.${index}.mobileUrl`)}
                onChange={(mobileUrl) => onChange({ mobileUrl })}
              />
            )}
            {split && (
              <DisplayImageUploadButton
                label={`${position} 모바일 파일 올리기 (선택)`}
                onUploaded={(mobileUrl) => onChange({ mobileUrl })}
                onUploadingChange={onUploadingChange}
                onAccessDenied={onAccessDenied}
              />
            )}
          </>
        )}
        <MediaSizeNotice
          spec={specFor(type, 'PC')}
          size={pc.measure.size}
          label={split ? 'PC' : undefined}
        />
        {split && (
          <MediaSizeNotice
            spec={specFor(type, '모바일')}
            size={mobile.measure.size}
            label="모바일"
          />
        )}
        <TextInput
          label={isVideo ? `${position} 대체 텍스트 (선택)` : `${position} 대체 텍스트 (필수)`}
          value={item.altText}
          max={LIMITS.altText}
          multiline={false}
          hint={
            isVideo
              ? '메인의 영상은 화면 낭독기가 읽지 않습니다.'
              : '사진을 볼 수 없는 고객에게 읽어 주는 문장입니다. 사진에 보이는 것을 짧게 적어 주세요.'
          }
          errors={errorsOf(`media.${index}.altText`)}
          required={!isVideo}
          onChange={(altText) => onChange({ altText })}
        />
        {hasMediaCaption(type) && (
          <TextInput
            label={`${position} 설명 (선택)`}
            value={item.caption}
            max={LIMITS.caption}
            multiline={false}
            hint="메인에서 이 클로즈업 옆에 보이는 한 줄입니다(예: 손바느질 깃)."
            errors={errorsOf(`media.${index}.caption`)}
            onChange={(caption) => onChange({ caption })}
          />
        )}
        {removable && (
          <div>
            <Button
              variant="secondary"
              disabled={locked}
              onClick={onRemove}
              aria-label={`${position} 빼기`}
            >
              빼기
            </Button>
          </div>
        )}
      </div>
    </li>
  );
}

const LOAD_FAILED = '이 주소의 이미지를 불러오지 못했습니다. 주소가 맞는지 확인해 주세요.';

/** 썸네일 한 칸 — 불러오기 실패와 원본 크기(권장 비율 · 해상도 안내)를 잰다 */
function useThumb(src: string | null) {
  const [failedSrc, setFailedSrc] = useState<string | null>(null);
  const failed = src !== null && failedSrc === src;
  const measure = useImageSize(failed ? null : src);
  return { src, failed, measure, onError: () => setFailedSrc(src) };
}

function Thumb({
  thumb,
  label,
  empty = '이미지 없음',
}: {
  thumb: ReturnType<typeof useThumb>;
  label: string | null;
  empty?: string;
}) {
  const { src, failed, measure } = thumb;
  return (
    <div className="flex w-28 flex-col gap-1">
      {src && !failed ? (
        // 이미지 CDN 이 정해지지 않아 next/image 를 쓰지 않는다(스토어프론트 메인과 같다)
        // eslint-disable-next-line @next/next/no-img-element
        <img
          key={src}
          src={src}
          alt=""
          decoding="async"
          onLoad={measure.onLoad}
          onError={thumb.onError}
          className="aspect-[4/5] w-full rounded bg-muted object-cover"
        />
      ) : (
        <div className="flex aspect-[4/5] w-full items-center justify-center rounded bg-muted p-2 text-center text-xs text-muted-fg">
          {failed ? '이미지를 불러오지 못했습니다' : empty}
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
