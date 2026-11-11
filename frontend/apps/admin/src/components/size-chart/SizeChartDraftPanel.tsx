'use client';

import { Button, Checkbox, FieldError } from '@cf/ui';
import Link from 'next/link';
import { useId, useState } from 'react';
import { formatDateTime } from '@/lib/display/text';
import { confirmationKey, imageFollowUpPlan } from '@/lib/size-chart/draft-image';
import {
  candidateSummary,
  SOURCE_TEXT_MAX,
  statusOf,
  type SizeChartDraft,
} from '@/lib/size-chart/draft';
import { SizeChartImagePicker } from './SizeChartImagePicker';
import type { SizeChartDraftState } from './useSizeChartDraft';
import type { SizeChartImageSourcesState } from './useSizeChartImageSources';

type InputMode = 'TEXT' | 'IMAGE';

type Props = {
  ai: SizeChartDraftState;
  /** IMAGE 입력의 상품 이미지 후보 · 선택 · 확인 */
  images: SizeChartImageSourcesState;
  /** 편집기에서 고른 템플릿. 없으면 요청할 수 없다 */
  templateId: string | null;
  /** 이 초안을 표에서 검토 중이다 */
  reviewing: boolean;
  /** 저장 · 적용 중 — 패널 동작을 막는다 */
  busy: boolean;
  onReview: (draft: SizeChartDraft) => void;
};

/**
 * AI 사이즈표 초안 패널(#218). 치수 텍스트 또는 상품 관리 이미지 → 초안 요청 → 생성 진행 → 표에 불러와 검토 → (편집기의) 초안 적용.
 * 이미지 입력은 BE 플래그가 열린 환경에서만 쓸 수 있다 — 닫혀 있으면(011) 안내만 두고 텍스트로 요청하게 한다.
 */
export function SizeChartDraftPanel({ ai, images, templateId, reviewing, busy, onReview }: Props) {
  const id = useId();
  const [mode, setMode] = useState<InputMode>('TEXT');
  const [sourceText, setSourceText] = useState('');
  // 개인정보 확인은 승인할 이미지 바이트(이미지 · 확인값 묶음)에 대한 것이다 — 선택이나 확인값이 바뀌면 다시 확인받는다
  const [confirmedFor, setConfirmedFor] = useState<string | null>(null);
  const selectionKey = confirmationKey(images.approved);
  const confirmed = selectionKey !== null && confirmedFor === selectionKey;
  const { draft } = ai;
  const status = draft ? statusOf(draft) : null;
  const open =
    status === null || status === 'FAILED' || status === 'CANCELLED' || status === 'APPLIED';
  const tooLong = sourceText.length > SOURCE_TEXT_MAX;
  const imageClosed = images.status === 'closed';
  const canRequest =
    templateId !== null &&
    !ai.submitting &&
    !busy &&
    (mode === 'TEXT'
      ? sourceText.trim() !== '' && !tooLong
      : !imageClosed && images.approved !== null && confirmed);

  const switchMode = (next: InputMode) => {
    setMode(next);
    ai.clearSourceError();
    if (next === 'IMAGE') images.load();
  };

  const submit = async () => {
    if (!templateId) return;
    if (mode === 'TEXT') {
      await ai.request(templateId, { mode: 'TEXT', sourceText });
      return;
    }
    if (!images.approved) return;
    const follow = await ai.request(templateId, { mode: 'IMAGE', images: images.approved });
    if (!follow) return;
    const plan = imageFollowUpPlan(follow);
    if (plan.clearSourceError) ai.clearSourceError();
    if (plan.resetConfirmation) setConfirmedFor(null);
    if (plan.images.kind === 'close') images.close(plan.images.message);
    else if (plan.images.kind === 'reload')
      void images.reload(plan.images.message, { clearSelection: plan.images.clearSelection });
    else images.reinspect();
  };

  return (
    <section
      data-tour="size-ai-draft"
      aria-labelledby={`${id}-title`}
      className="flex flex-col gap-3 rounded-md border border-border p-4"
    >
      <div>
        <h2 id={`${id}-title`} className="text-base font-semibold">
          AI 초안 (선택)
        </h2>
        <p className="mt-1 text-xs text-muted-fg">
          기존 상세 설명 · 다른 표의 치수 부분을 붙여넣거나, 이 상품에 올린 사이즈표 이미지를 고르면
          고른 템플릿 항목에 맞춘 초안을 만듭니다. 초안은 표에 불러와 검토하고 적용하기 전까지
          스토어에 보이지 않습니다.
        </p>
      </div>

      {draft && !open && (
        <div className="flex flex-col gap-2 text-sm">
          <p>
            <span className="font-medium">{draft.status.name}</span>
            <span className="text-muted-fg"> · {draft.status.description}</span>
          </p>
          {status === 'REQUESTED' && (
            <>
              <p className="text-xs text-muted-fg">
                생성에는 보통 몇십 초가 걸립니다. 다른 화면으로 가도 되고, 끝나면 상단 작업 표시와
                알림 센터로 알려 드립니다.
                {draft.jobId && (
                  <>
                    {' '}
                    <Link
                      href={`/jobs/${encodeURIComponent(draft.jobId)}`}
                      className="underline underline-offset-4"
                    >
                      작업 진행 보기
                    </Link>
                  </>
                )}
              </p>
              <div className="flex flex-wrap gap-2">
                <Button
                  variant="secondary"
                  disabled={ai.checking || busy}
                  onClick={() => void ai.check()}
                >
                  {ai.checking ? '확인 중…' : '결과 확인'}
                </Button>
                <Button variant="secondary" disabled={busy} onClick={() => ai.dismiss()}>
                  다른 내용으로 새로 요청
                </Button>
              </div>
            </>
          )}
          {status === 'READY' && <ReadySummary draft={draft} />}
          {status === 'READY' && (
            <div className="flex flex-wrap gap-2">
              {reviewing ? (
                <p className="text-sm">
                  표에서 검토 중입니다. 아래에서 고친 뒤 초안 적용을 눌러 주세요.
                </p>
              ) : (
                <Button disabled={busy} onClick={() => onReview(draft)}>
                  표에 불러와 검토
                </Button>
              )}
              {!reviewing && (
                <Button variant="secondary" disabled={busy} onClick={() => ai.dismiss()}>
                  버리고 새로 요청
                </Button>
              )}
            </div>
          )}
        </div>
      )}

      {open && (
        <div className="flex flex-col gap-2">
          {draft && (
            <p className="text-sm">
              <span className="font-medium">{draft.status.name}</span>
              <span className="text-muted-fg"> · {draft.status.description}</span>
            </p>
          )}
          <fieldset className="flex flex-col gap-2">
            <legend className="text-sm font-medium">입력 방식</legend>
            <div className="flex flex-wrap gap-4">
              {(
                [
                  ['TEXT', '치수 텍스트'],
                  ['IMAGE', '상품 이미지'],
                ] as const
              ).map(([value, label]) => (
                <label key={value} className="flex cursor-pointer items-center gap-2 text-sm">
                  <input
                    type="radio"
                    name={`${id}-mode`}
                    value={value}
                    checked={mode === value}
                    disabled={ai.submitting || busy}
                    onChange={() => switchMode(value)}
                    className="size-4 cursor-pointer accent-primary"
                  />
                  {label}
                </label>
              ))}
            </div>
          </fieldset>

          {mode === 'TEXT' && (
            <>
              <label htmlFor={`${id}-source`} className="text-sm font-medium">
                치수 텍스트
              </label>
              <textarea
                id={`${id}-source`}
                value={sourceText}
                rows={6}
                disabled={ai.submitting || busy}
                placeholder={'예:\nS 가슴단면 50 총장 60\nM 가슴단면 52 총장 62'}
                aria-invalid={ai.sourceError !== null || tooLong || undefined}
                aria-describedby={`${id}-source-help${ai.sourceError ? ` ${id}-source-error` : ''}`}
                onChange={(event) => {
                  setSourceText(event.currentTarget.value);
                  ai.clearSourceError();
                }}
                className="rounded-md border border-border bg-bg px-3 py-2 text-sm text-fg outline-none focus:ring-2 focus:ring-primary"
              />
              <p id={`${id}-source-help`} className="text-xs text-muted-fg">
                {sourceText.length.toLocaleString('ko-KR')} /{' '}
                {SOURCE_TEXT_MAX.toLocaleString('ko-KR')}
                자. 이름 · 연락처 · 주소 같은 문장은 넣지 마세요(서버가 거절합니다).
              </p>
            </>
          )}

          {mode === 'IMAGE' && (
            <>
              <SizeChartImagePicker
                images={images}
                disabled={ai.submitting || busy}
                idPrefix={id}
                errorId={ai.sourceError ? `${id}-source-error` : null}
              />
              {/* 목록 안내(닫힘 · 실패 · 다시 읽음)도 live region 으로 알린다 */}
              <p role="status" className="text-sm empty:hidden">
                {images.message ?? ''}
              </p>
              {!imageClosed && images.selected.length > 0 && (
                <Checkbox
                  label="고른 이미지에 주문자 이름 · 연락처 · 주소 같은 고객 개인정보가 없음을 확인했습니다."
                  checked={confirmed}
                  disabled={ai.submitting || busy || selectionKey === null}
                  onChange={(event) =>
                    setConfirmedFor(event.currentTarget.checked ? selectionKey : null)
                  }
                />
              )}
            </>
          )}
          <FieldError id={`${id}-source-error`} messages={ai.sourceError ? [ai.sourceError] : []} />
          <div className="flex flex-wrap items-center gap-3">
            <Button disabled={!canRequest} onClick={() => void submit()}>
              {ai.submitting ? '요청 중…' : 'AI 초안 요청'}
            </Button>
            {templateId === null && (
              <p className="text-xs text-muted-fg">먼저 위에서 템플릿을 골라 주세요.</p>
            )}
          </div>
        </div>
      )}

      {/* live region 은 늘 두고 내용만 바꾼다 */}
      <p role="status" className="text-sm empty:hidden">
        {ai.message ?? ''}
      </p>
    </section>
  );
}

function ReadySummary({ draft }: { draft: SizeChartDraft }) {
  const summary = candidateSummary(draft);
  return (
    <ul className="list-disc pl-5 text-xs text-muted-fg">
      <li>
        사이즈 {summary.rows}개 · 빈 치수 {summary.missing}칸 · 확인할 경고 {summary.warnings}건
      </li>
      <li>{formatDateTime(draft.expiresAt)}까지 적용할 수 있습니다.</li>
      {summary.missing > 0 && <li>빈 치수는 0으로 채우지 않았습니다. 검토하면서 채워 주세요.</li>}
    </ul>
  );
}
