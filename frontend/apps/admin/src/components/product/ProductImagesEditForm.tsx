'use client';

import { apiFetch } from '@cf/api-client';
import { Button, FieldError, TextField } from '@cf/ui';
import { useRouter } from 'next/navigation';
import { useState } from 'react';
import { AccessNotice } from '@/components/AccessNotice';
import { ImageUploadField } from '@/components/upload/ImageUploadField';
import type { AccessDenied } from '@/lib/api-errors';
import { ALT_TEXT_MAX, IMAGES_MAX } from '@/lib/product/create';
import type { AdminProductDetail } from '@/lib/product/detail';
import {
  editImageEntries,
  emptyEditSlot,
  imagesFormOf,
  imagesRequest,
  sameImages,
  videoCount,
  type EditImageSlot,
  type ImagesForm,
} from '@/lib/product/images';
import { moveSlot, productImagesPath, underAny } from '@/lib/product/section';
import { useSectionSave } from '@/lib/product/use-section-save';
import { PRODUCT_IMAGE_UPLOAD_PATH } from '@/lib/upload/image-upload';
import { SectionEditHeader, SectionForm } from './SectionEditFrame';

const linkButton = 'w-fit text-sm text-muted-fg underline underline-offset-4 disabled:opacity-50';

/**
 * 어드민 상품 정지 이미지 수정(#29 · BE #560) — 대표 한 칸 + 추가 이미지, 합쳐 `IMAGES_MAX` 장. 전체 교체다.
 * 남길 기존 이미지는 그대로 두고, 빼면 그 칸이 업로드 칸이 된다. 새 파일은 고르자마자 올리고(등록과 같다) 본문에는 `uploadKey` 만 싣는다.
 * 올리는 중에는 저장을 막는다. 영상은 이 화면에서 다루지 않고 그대로 남는다.
 */
export function ProductImagesEditForm({ product: initial }: { product: AdminProductDetail }) {
  const router = useRouter();
  const [product, setProduct] = useState(initial);
  const [form, setForm] = useState<ImagesForm>(() => imagesFormOf(initial));
  const [uploading, setUploading] = useState<ReadonlySet<string>>(new Set());
  const [denied, setDenied] = useState<AccessDenied | null>(null);
  const section = useSectionSave({
    product,
    keepBusyAfterSave: true,
    onSaved: (saved) => router.replace(`/products/${saved.productId}`),
    onReloaded: (latest) => {
      setProduct(latest);
      setForm(imagesFormOf(latest));
      setUploading(new Set());
    },
    isField: underAny('images'),
  });

  if (section.forbidden || denied === 'forbidden') return <AccessNotice denied="forbidden" />;
  if (denied) return <AccessNotice denied={denied} />;

  const busy = section.busy !== null;
  const indexOf = new Map(editImageEntries(form).map((entry) => [entry.slot.id, entry.index]));
  const full = 1 + form.gallery.length >= IMAGES_MAX;
  const videos = videoCount(product);

  const changeUploading = (slotId: string, value: boolean) =>
    setUploading((current) => {
      if (current.has(slotId) === value) return current;
      const next = new Set(current);
      if (value) next.add(slotId);
      else next.delete(slotId);
      return next;
    });
  const setGallery = (gallery: EditImageSlot[]) => setForm((value) => ({ ...value, gallery }));
  const patchGallery = (id: string, patch: Partial<EditImageSlot>) =>
    setForm((value) => ({
      ...value,
      gallery: value.gallery.map((slot) => (slot.id === id ? { ...slot, ...patch } : slot)),
    }));
  const patchThumbnail = (patch: Partial<EditImageSlot>) =>
    setForm((value) => ({ ...value, thumbnail: { ...value.thumbnail, ...patch } }));

  const slot = (
    item: EditImageSlot,
    label: string,
    patch: (patch: Partial<EditImageSlot>) => void,
  ) => {
    const index = indexOf.get(item.id);
    const filled = item.existing !== null || item.upload !== null;
    return (
      <div className="flex flex-col gap-3">
        {item.existing && !item.upload ? (
          <div className="flex flex-col gap-2">
            <p className="text-sm font-medium">{label}</p>
            {/* 이미지 CDN 이 정해지지 않아 next/image 를 쓰지 않는다(상세와 같다) */}
            {/* eslint-disable-next-line @next/next/no-img-element */}
            <img
              src={item.existing.url}
              alt={item.altText}
              className="aspect-square w-40 rounded-md bg-muted object-cover"
            />
            <button
              type="button"
              disabled={busy}
              onClick={() => patch({ existing: null, altText: '' })}
              className={linkButton}
            >
              이 이미지 빼고 새로 올리기
            </button>
          </div>
        ) : (
          <ImageUploadField
            label={label}
            issuePath={PRODUCT_IMAGE_UPLOAD_PATH}
            value={item.upload}
            onChange={(upload) => patch({ upload, existing: null })}
            onUploadingChange={(value) => changeUploading(item.id, value)}
            onAccessDenied={setDenied}
            disabled={busy}
          />
        )}
        {filled && (
          <TextField
            label={`대체 텍스트 (선택, ${ALT_TEXT_MAX}자 이하 — 이미지를 볼 수 없는 고객에게 읽어 줍니다)`}
            value={item.altText}
            onChange={(event) => patch({ altText: event.currentTarget.value })}
            errors={index === undefined ? [] : section.errorsOf(`images.${index}.altText`)}
            className="max-w-xl"
          />
        )}
      </div>
    );
  };

  return (
    <div className="flex max-w-3xl flex-col gap-8">
      <SectionEditHeader
        productId={product.productId}
        productName={product.name}
        title="이미지 수정"
      >
        대표 이미지와 추가 이미지를 통째로 바꿉니다. 추가 이미지는 위에서부터 차례로 보입니다.
        {videos > 0 && ` 영상 ${videos}개는 이 화면에서 바꾸지 않고 그대로 남습니다.`}
      </SectionEditHeader>
      <SectionForm
        section={section}
        unchanged={sameImages(form, imagesFormOf(product))}
        blocked={uploading.size > 0}
        saveLabel={uploading.size > 0 ? '이미지 올리는 중…' : '저장'}
        cancelHref={`/products/${product.productId}`}
        onSave={() =>
          void section.save(
            (version) => imagesRequest(form, version),
            (request) =>
              apiFetch<AdminProductDetail>(productImagesPath(product.productId), {
                method: 'PUT',
                json: request,
              }),
          )
        }
      >
        {slot(form.thumbnail, '대표 이미지 (선택)', patchThumbnail)}
        {form.gallery.map((item, order) => (
          <div key={item.id} className="flex flex-col gap-2 border-t border-border pt-4">
            {slot(item, `추가 이미지 ${order + 1}`, (patch) => patchGallery(item.id, patch))}
            <div className="flex flex-wrap gap-4">
              <button
                type="button"
                disabled={busy || order === 0}
                onClick={() => setGallery(moveSlot(form.gallery, order, -1))}
                className={linkButton}
              >
                위로
              </button>
              <button
                type="button"
                disabled={busy || order === form.gallery.length - 1}
                onClick={() => setGallery(moveSlot(form.gallery, order, 1))}
                className={linkButton}
              >
                아래로
              </button>
              <button
                type="button"
                disabled={busy}
                onClick={() => {
                  changeUploading(item.id, false);
                  setGallery(form.gallery.filter((other) => other.id !== item.id));
                }}
                className={linkButton}
              >
                추가 이미지 {order + 1} 칸 없애기
              </button>
            </div>
          </div>
        ))}
        <div className="flex flex-col gap-1">
          <Button
            variant="secondary"
            disabled={busy || full}
            onClick={() => setGallery([...form.gallery, emptyEditSlot(crypto.randomUUID())])}
            className="w-fit"
          >
            추가 이미지 칸 더하기
          </Button>
          <p className="text-xs text-muted-fg">
            대표를 포함해 {IMAGES_MAX}장까지입니다. 새로 올린 이미지는 하루 안에 저장하지 않으면
            지워집니다. 뺀 이미지는 고객 화면에서 빠집니다.
          </p>
        </div>
        <FieldError messages={section.errorsOf('images')} />
      </SectionForm>
    </div>
  );
}
