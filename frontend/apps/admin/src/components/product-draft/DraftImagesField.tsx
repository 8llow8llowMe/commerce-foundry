'use client';

import { FieldError } from '@cf/ui';
import { DisplayImageUploadButton } from '@/components/upload/DisplayImageUploadButton';
import type { AccessDenied } from '@/lib/api-errors';
import {
  checkDraftImageFile,
  DRAFT_GALLERY_MAX,
  DRAFT_IMAGE_MAX_BYTES,
  type ProductDraftForm,
} from '@/lib/product-draft/form';
import { formatBytes } from '@/lib/upload/image-upload';

type Images = Pick<ProductDraftForm, 'thumbnail' | 'gallery'>;

export const PHOTO_PRIVACY_NOTICE =
  '사진에 고객 정보(이름 · 연락처 · 주소 · 주문 화면 · 송장)를 넣지 마세요. 사진 속 글자는 가려지지 않고 그대로 AI 에 전달됩니다.';

const HINT = `JPG · PNG · WebP, 한 장에 ${formatBytes(DRAFT_IMAGE_MAX_BYTES)} 이하. 고르면 바로 올립니다.`;

/**
 * AI 상품 초안 사진(#360) — 대표 1장 + 추가 최대 7장. 업로드는 전시 · 룩과 같은 확정 흐름(`DisplayImageUploadButton`)이다 —
 * 초안은 확정이 만든 상품 미디어 공개 주소(`products/media/...`)만 받는다(BE `PRODUCT_DRAFT_031`). 미리보기는 그 공개 주소로 그린다.
 * 올리는 칸은 대표 칸과 "추가 사진" 칸 둘이고, 칸 이름(`thumbnail` · `gallery`)으로 올리는 중을 알린다.
 */
export function DraftImagesField({
  value,
  onChange,
  errors,
  disabled,
  onUploadingChange,
  onAccessDenied,
}: {
  value: Images;
  onChange: (value: Images) => void;
  errors: readonly string[];
  disabled: boolean;
  onUploadingChange: (slot: 'thumbnail' | 'gallery', uploading: boolean) => void;
  onAccessDenied: (denied: AccessDenied) => void;
}) {
  const galleryFull = value.gallery.length >= DRAFT_GALLERY_MAX;
  return (
    <div className="flex flex-col gap-4">
      <p role="note" className="rounded-md bg-muted px-4 py-3 text-sm">
        {PHOTO_PRIVACY_NOTICE}
      </p>

      <div className="flex flex-col gap-2">
        <p className="text-sm font-medium">대표 사진 (선택, 1장)</p>
        {value.thumbnail ? (
          <Preview
            url={value.thumbnail}
            label="대표 사진"
            disabled={disabled}
            onRemove={() => onChange({ ...value, thumbnail: null })}
          />
        ) : (
          <DisplayImageUploadButton
            label="대표 사진 파일 올리기"
            hint={HINT}
            checkFile={checkDraftImageFile}
            onUploaded={(url) => onChange({ ...value, thumbnail: url })}
            onUploadingChange={(busy) => onUploadingChange('thumbnail', busy)}
            onAccessDenied={onAccessDenied}
            disabled={disabled}
          />
        )}
      </div>

      <div className="flex flex-col gap-2">
        <p className="text-sm font-medium">
          추가 사진 (선택, {DRAFT_GALLERY_MAX}장까지 — {value.gallery.length}장)
        </p>
        {value.gallery.length > 0 && (
          <ul className="flex flex-wrap gap-4">
            {value.gallery.map((url, index) => (
              <li key={url}>
                <Preview
                  url={url}
                  label={`추가 사진 ${index + 1}`}
                  disabled={disabled}
                  onRemove={() =>
                    onChange({ ...value, gallery: value.gallery.filter((_, at) => at !== index) })
                  }
                />
              </li>
            ))}
          </ul>
        )}
        {galleryFull ? (
          <p className="text-xs text-muted-fg">추가 사진을 모두 넣었습니다.</p>
        ) : (
          <DisplayImageUploadButton
            label="추가 사진 파일 올리기"
            hint={HINT}
            checkFile={checkDraftImageFile}
            onUploaded={(url) =>
              onChange({
                ...value,
                gallery: value.gallery.includes(url) ? value.gallery : [...value.gallery, url],
              })
            }
            onUploadingChange={(busy) => onUploadingChange('gallery', busy)}
            onAccessDenied={onAccessDenied}
            disabled={disabled}
          />
        )}
      </div>
      <FieldError messages={errors} />
    </div>
  );
}

function Preview({
  url,
  label,
  disabled,
  onRemove,
}: {
  url: string;
  label: string;
  disabled: boolean;
  onRemove: () => void;
}) {
  return (
    <div className="flex items-center gap-3">
      {/* 업로드 확정한 공개 주소의 미리보기라 next/image 최적화 대상이 아니다 */}
      {/* eslint-disable-next-line @next/next/no-img-element */}
      <img
        src={url}
        alt={label}
        className="h-24 w-24 rounded-md border border-border bg-muted object-cover"
      />
      <button
        type="button"
        disabled={disabled}
        aria-label={`${label} 빼기`}
        onClick={onRemove}
        className="text-sm text-muted-fg underline underline-offset-4 disabled:opacity-50"
      >
        빼기
      </button>
    </div>
  );
}
