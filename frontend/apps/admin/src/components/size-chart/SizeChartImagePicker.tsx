'use client';

import { Button, Checkbox } from '@cf/ui';
import { inspectionText, MAX_DRAFT_IMAGES, type ImageSource } from '@/lib/size-chart/draft-image';
import type { SizeChartImageSourcesState } from './useSizeChartImageSources';

type Props = {
  images: SizeChartImageSourcesState;
  disabled: boolean;
  /** 패널 안 id 접두어(useId) */
  idPrefix: string;
  /** 생성 요청이 돌려준 이미지 오류 칸 id — 오류가 있을 때만 목록과 연결한다(FieldError 는 비면 그리지 않는다) */
  errorId: string | null;
};

/**
 * AI 초안 IMAGE 입력의 상품 관리 이미지 선택(#218). 쓸 수 있는 후보만 고를 수 있고, 쓸 수 없는 후보는 서버 사유를 그대로 보인다.
 * 고른 순서가 생성 순서다. 고르면 바로 확인(형식 · 크기 · 확인값)해 결과를 칸에 보인다.
 */
export function SizeChartImagePicker({ images, disabled, idPrefix, errorId }: Props) {
  if (images.status === 'idle' || (images.status === 'loading' && images.sources.length === 0))
    return <p className="text-sm text-muted-fg">이미지 목록을 불러오는 중…</p>;
  if (images.status === 'closed') return null;
  if (images.status === 'error' && images.sources.length === 0)
    return (
      <div className="flex flex-wrap items-center gap-3">
        <Button variant="secondary" disabled={disabled} onClick={() => void images.reload()}>
          이미지 목록 다시 불러오기
        </Button>
      </div>
    );
  if (images.sources.length === 0)
    return (
      <p className="text-sm text-muted-fg">
        이 상품에 등록된 이미지가 없습니다. 상품 이미지 · 상세 설명에 사이즈표 이미지를 올린 뒤 다시
        불러와 주세요.
      </p>
    );

  const full = images.selected.length >= MAX_DRAFT_IMAGES;
  return (
    <div className="flex flex-col gap-2">
      <p id={`${idPrefix}-images-help`} className="text-xs text-muted-fg">
        고른 이미지 {images.selected.length} / {MAX_DRAFT_IMAGES}장. 고른 순서대로 읽습니다. 상품에
        올린 이미지만 쓸 수 있습니다(JPEG · PNG · WebP, 5MB 이하).
      </p>
      <ul
        aria-label="사이즈표로 쓸 상품 이미지"
        aria-describedby={`${idPrefix}-images-help${errorId ? ` ${errorId}` : ''}`}
        className="grid grid-cols-2 gap-3 sm:grid-cols-3 lg:grid-cols-4"
      >
        {images.sources.map((source, index) => (
          <SourceItem
            key={source.sourceId}
            source={source}
            index={index}
            order={images.selected.indexOf(source.sourceId)}
            images={images}
            disabled={disabled || images.status === 'loading'}
            full={full}
            statusId={`${idPrefix}-image-${index}-status`}
          />
        ))}
      </ul>
      <div>
        <Button
          variant="secondary"
          disabled={disabled || images.status === 'loading'}
          onClick={() => void images.reload()}
        >
          {images.status === 'loading' ? '불러오는 중…' : '이미지 목록 다시 불러오기'}
        </Button>
      </div>
    </div>
  );
}

function SourceItem({
  source,
  index,
  order,
  images,
  disabled,
  full,
  statusId,
}: {
  source: ImageSource;
  index: number;
  /** 고른 순서(0부터). 안 골랐으면 -1 */
  order: number;
  images: SizeChartImageSourcesState;
  disabled: boolean;
  full: boolean;
  /** 확인 상태 문구 id — 체크박스 설명으로 잇는다 */
  statusId: string;
}) {
  const picked = order >= 0;
  const state = images.inspections[source.sourceId];
  const label = `이미지 ${index + 1}${picked ? ` (${order + 1}번째로 읽음)` : ''}`;
  return (
    <li
      className={`flex flex-col gap-2 rounded-md border p-2 ${picked ? 'border-primary' : 'border-border'}`}
    >
      {source.previewUrl ? (
        // 이미지 CDN 이 정해지지 않아 next/image 를 쓰지 않는다(상품 상세와 같다)
        // eslint-disable-next-line @next/next/no-img-element
        <img
          src={source.previewUrl}
          alt=""
          loading="lazy"
          className={`aspect-square w-full rounded-md bg-muted object-contain ${source.eligible ? '' : 'opacity-50'}`}
        />
      ) : (
        <div className="flex aspect-square w-full items-center justify-center rounded-md bg-muted p-2 text-center text-xs text-muted-fg">
          미리보기 없음
        </div>
      )}
      {source.eligible ? (
        <Checkbox
          label={label}
          checked={picked}
          disabled={disabled || (!picked && full)}
          aria-describedby={picked && state ? statusId : undefined}
          onChange={() => images.toggle(source.sourceId)}
        />
      ) : (
        <p className="text-sm">
          <span className="font-medium">이미지 {index + 1}</span>
          <span className="text-muted-fg"> · 사용할 수 없음</span>
        </p>
      )}
      {!source.eligible && source.reason && (
        <p className="text-xs text-muted-fg">
          {source.reason.name} — {source.reason.description}
        </p>
      )}
      {/* 확인 결과(특히 오류)를 알리게 live region 으로 둔다 — 고른 칸에만 있다 */}
      {picked && state && (
        <p
          id={statusId}
          role="status"
          className={`text-xs ${state.status === 'error' ? 'text-danger' : 'text-muted-fg'}`}
        >
          {state.status === 'checking' && '이미지 확인 중…'}
          {state.status === 'ok' && `확인됨 · ${inspectionText(state.inspection)}`}
          {state.status === 'error' && state.message}
        </p>
      )}
    </li>
  );
}
