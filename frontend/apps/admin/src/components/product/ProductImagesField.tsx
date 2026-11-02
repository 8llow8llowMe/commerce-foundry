'use client';

import { Button, FieldError, TextField } from '@cf/ui';
import { ImageUploadField } from '@/components/upload/ImageUploadField';
import type { AccessDenied } from '@/lib/api-errors';
import {
  ALT_TEXT_MAX,
  emptyImageSlot,
  imageEntries,
  IMAGES_MAX,
  type ImageSlot,
  type ProductCreateForm,
} from '@/lib/product/create';
import { PRODUCT_IMAGE_UPLOAD_PATH } from '@/lib/upload/image-upload';

type Images = Pick<ProductCreateForm, 'thumbnail' | 'gallery'>;

/**
 * 상품 등록 이미지(#29 · BE #9 F2) — 대표 한 칸(THUMBNAIL) + 추가 이미지(GALLERY), 합쳐 `IMAGES_MAX` 장.
 * 칸마다 고르자마자 스토리지에 올리고(`ImageUploadField`) 등록 본문에는 `uploadKey` 만 싣는다(`imageEntries`).
 * 대체 텍스트 칸 오류 키는 본문 번호(`images.{n}.altText`)라 칸 번호와 다를 수 있다 — 칸 id → 본문 번호로 찾는다.
 */
export function ProductImagesField({
  value,
  onChange,
  errorsOf,
  disabled,
  onUploadingChange,
  onAccessDenied,
}: {
  value: Images;
  onChange: (value: Images) => void;
  errorsOf: (key: string) => string[];
  disabled: boolean;
  /** 칸 id 별 올리는 중 — 화면은 올리는 동안 등록을 막는다(키 없이 보내면 이미지가 조용히 빠진다) */
  onUploadingChange: (slotId: string, uploading: boolean) => void;
  onAccessDenied: (denied: AccessDenied) => void;
}) {
  const indexOf = new Map(imageEntries(value).map((entry) => [entry.slot.id, entry.index]));
  const full = 1 + value.gallery.length >= IMAGES_MAX;

  const setGallery = (gallery: ImageSlot[]) => onChange({ ...value, gallery });
  const patchGallery = (id: string, patch: Partial<ImageSlot>) =>
    setGallery(value.gallery.map((slot) => (slot.id === id ? { ...slot, ...patch } : slot)));

  const slot = (item: ImageSlot, label: string, patch: (patch: Partial<ImageSlot>) => void) => {
    const index = indexOf.get(item.id);
    return (
      <div className="flex flex-col gap-3">
        <ImageUploadField
          label={label}
          issuePath={PRODUCT_IMAGE_UPLOAD_PATH}
          value={item.upload}
          onChange={(upload) => patch({ upload })}
          onUploadingChange={(busy) => onUploadingChange(item.id, busy)}
          onAccessDenied={onAccessDenied}
          disabled={disabled}
        />
        {item.upload && (
          <TextField
            label={`대체 텍스트 (선택, ${ALT_TEXT_MAX}자 이하 — 이미지를 볼 수 없는 고객에게 읽어 줍니다)`}
            value={item.altText}
            onChange={(event) => patch({ altText: event.currentTarget.value })}
            errors={index === undefined ? [] : errorsOf(`images.${index}.altText`)}
            className="max-w-xl"
          />
        )}
      </div>
    );
  };

  return (
    <div className="flex flex-col gap-6">
      {slot(value.thumbnail, '대표 이미지 (선택)', (patch) =>
        onChange({ ...value, thumbnail: { ...value.thumbnail, ...patch } }),
      )}
      {value.gallery.map((item, order) => (
        <div key={item.id} className="flex flex-col gap-2 border-t border-border pt-4">
          {slot(item, `추가 이미지 ${order + 1} (선택)`, (patch) => patchGallery(item.id, patch))}
          <button
            type="button"
            disabled={disabled}
            onClick={() => {
              onUploadingChange(item.id, false);
              setGallery(value.gallery.filter((other) => other.id !== item.id));
            }}
            className="w-fit text-sm text-muted-fg underline underline-offset-4 disabled:opacity-50"
          >
            추가 이미지 {order + 1} 칸 없애기
          </button>
        </div>
      ))}
      <div className="flex flex-col gap-1">
        <Button
          variant="secondary"
          disabled={disabled || full}
          onClick={() => setGallery([...value.gallery, emptyImageSlot(crypto.randomUUID())])}
          className="w-fit"
        >
          추가 이미지 칸 더하기
        </Button>
        <p className="text-xs text-muted-fg">
          대표를 포함해 {IMAGES_MAX}장까지, 추가 이미지는 위에서부터 차례로 보입니다. 올린 이미지는
          하루 안에 등록하지 않으면 지워집니다.
        </p>
      </div>
      <FieldError messages={errorsOf('images')} />
    </div>
  );
}
