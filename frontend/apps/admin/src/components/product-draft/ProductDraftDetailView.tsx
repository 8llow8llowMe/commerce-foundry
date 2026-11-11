import Link from 'next/link';
import type { ReactNode } from 'react';
import { RefreshButton } from '@/components/product/RefreshButton';
import {
  canReject,
  FACT_LABELS,
  formatDraftTime,
  statusNote,
  type ProductDraft,
} from '@/lib/product-draft/detail';
import { ProductDraftRejectAction } from './ProductDraftRejectAction';

/**
 * AI 상품 초안 상세(#360) — 상태 · 요청/만료 시각 · 입력한 사실 · 사진 · 부족한 입력과 새로고침 · 거절.
 * 초안 · 회원 아이디와 코드는 보이지 않는다(#305 — 운영자 문구 `name` · `description` 만). 사실은 평문으로만 그린다(텍스트 노드).
 * `categoryName` 은 화면이 카테고리 트리로 찾은 경로 이름이다(`categoryLabel`).
 */
export function ProductDraftDetailView({
  draft,
  categoryName,
}: {
  draft: ProductDraft;
  categoryName: string | null;
}) {
  const { facts, status } = draft;
  const note = statusNote(status);
  const text = (value: string | null) => (value === null || value === '' ? null : value);
  const list = (items: string[]) => (items.length === 0 ? null : items.join(', '));
  const factRows: [label: string, value: string | null][] = [
    [FACT_LABELS.nameHint, text(facts.nameHint)],
    [FACT_LABELS.categoryId, categoryName],
    [FACT_LABELS.materials, list(facts.materials)],
    [FACT_LABELS.careNotes, list(facts.careNotes)],
    [FACT_LABELS.measurementsNote, text(facts.measurementsNote)],
    [FACT_LABELS.origin, text(facts.origin)],
    [FACT_LABELS.certifications, list(facts.certifications)],
    [FACT_LABELS.tone, text(facts.tone)],
    [FACT_LABELS.mustInclude, list(facts.mustInclude)],
    [FACT_LABELS.mustAvoid, list(facts.mustAvoid)],
  ];
  const filled = factRows.filter(([, value]) => value !== null);

  return (
    <div className="flex max-w-3xl flex-col gap-8">
      <div className="flex flex-col gap-2">
        <Link
          href="/products/new"
          className="w-fit text-sm text-muted-fg underline-offset-4 hover:underline"
        >
          ← 상품 등록
        </Link>
        <h1 className="text-xl font-semibold">AI 상품 초안</h1>
        <p className="text-sm text-muted-fg">
          초안 목록은 아직 없습니다. 이 화면의 주소를 저장해 두면 다시 찾을 수 있습니다.
        </p>
      </div>

      <Section title="상태">
        <dl className="grid grid-cols-[max-content_1fr] gap-x-6 gap-y-2 text-sm">
          <dt className="text-muted-fg">상태</dt>
          <dd className="font-medium">{status.name}</dd>
          <dt className="text-muted-fg">요청 시각</dt>
          <dd>{formatDraftTime(draft.requestedAt)}</dd>
          <dt className="text-muted-fg">보관 만료</dt>
          <dd>{formatDraftTime(draft.expiresAt)}</dd>
          {draft.rejectedAt && (
            <>
              <dt className="text-muted-fg">거절 시각</dt>
              <dd>{formatDraftTime(draft.rejectedAt)}</dd>
            </>
          )}
        </dl>
        <p className="text-sm">{status.description}</p>
        {note && (
          <p role="status" className="rounded-md bg-muted px-4 py-3 text-sm">
            {note}
          </p>
        )}
        <div className="flex flex-wrap items-start gap-3">
          <RefreshButton>새로고침</RefreshButton>
          {canReject(status) && <ProductDraftRejectAction draftId={draft.draftId} />}
        </div>
      </Section>

      {draft.missingRequiredInputs.length > 0 && (
        <Section title="상품 등록 전에 더 넣어야 할 것">
          <ul className="list-disc space-y-1 pl-5 text-sm">
            {draft.missingRequiredInputs.map((item) => (
              <li key={item.code}>
                <span className="font-medium">{item.name}</span> — {item.description}
              </li>
            ))}
          </ul>
          <p className="text-xs text-muted-fg">
            가격 · 옵션 · 재고는 초안에 담지 않고 상품 등록 단계에서 입력합니다.
          </p>
        </Section>
      )}

      <Section title="입력한 사실">
        {filled.length === 0 ? (
          <p className="text-sm text-muted-fg">입력한 사실이 없습니다.</p>
        ) : (
          <dl className="grid grid-cols-[max-content_1fr] gap-x-6 gap-y-2 text-sm">
            {filled.map(([label, value]) => (
              <div key={label} className="contents">
                <dt className="text-muted-fg">{label}</dt>
                <dd className="whitespace-pre-line break-all">{value}</dd>
              </div>
            ))}
          </dl>
        )}
      </Section>

      <Section title={`사진 ${draft.images.length}장`}>
        {draft.images.length === 0 ? (
          <p className="text-sm text-muted-fg">넣은 사진이 없습니다.</p>
        ) : (
          <ul className="flex flex-wrap gap-4">
            {draft.images.map((image, index) => {
              const label = image.imageType === 'THUMBNAIL' ? '대표 사진' : `사진 ${index + 1}`;
              return (
                <li key={image.url} className="flex flex-col gap-1 text-xs text-muted-fg">
                  {/* 상품 미디어 공개 주소라 next/image 최적화 대상이 아니다 */}
                  {/* eslint-disable-next-line @next/next/no-img-element */}
                  <img
                    src={image.url}
                    alt={label}
                    className="h-32 w-32 rounded-md border border-border bg-muted object-cover"
                  />
                  <span>{label}</span>
                </li>
              );
            })}
          </ul>
        )}
      </Section>
    </div>
  );
}

function Section({ title, children }: { title: string; children: ReactNode }) {
  return (
    <section className="flex flex-col gap-3">
      <h2 className="font-semibold">{title}</h2>
      {children}
    </section>
  );
}
