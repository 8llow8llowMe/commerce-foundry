'use client';

import { apiFetch } from '@cf/api-client';
import { Button } from '@cf/ui';
import { useCallback, useEffect, useRef, useState } from 'react';
import type { AccessDenied } from '@/lib/api-errors';
import { readFailure } from '@/lib/sku-cost/errors';
import { skuCostsPath, type SkuCurrentCosts } from '@/lib/sku-cost/types';
import { CostAddForm } from './CostAddForm';
import { CostHistory } from './CostHistory';
import { CurrentCostList } from './CurrentCostList';

type Current =
  | { kind: 'loading' }
  | { kind: 'notFound' }
  | { kind: 'failed'; message: string }
  | { kind: 'ready'; current: SkuCurrentCosts };

/**
 * SKU 하나의 원가 패널(#395) — 현재 원가 · 추가 폼 · 이력.
 * 추가에 성공하면 현재 원가를 다시 읽고 폼 · 이력을 새로 시작한다(`revision`).
 * 그사이 타임라인이 바뀐 실패(`SKU_COST_002` · `003` · `004` · `006`)와 결과 모름은 원가 · 이력만 다시 읽고 폼 입력은 둔다.
 */
export function SkuCostPanel({
  skuId,
  label,
  onDenied,
}: {
  skuId: string;
  label: string;
  onDenied: (denied: AccessDenied) => void;
}) {
  const [current, setCurrent] = useState<Current>({ kind: 'loading' });
  /** 이력을 첫 페이지부터 다시 읽게 하는 값 */
  const [historyRevision, setHistoryRevision] = useState(0);
  /** 폼을 새로 시작하게 하는 값 — 추가 성공 때만 바꾼다 */
  const [formRevision, setFormRevision] = useState(0);
  const [notice, setNotice] = useState('');
  const titleRef = useRef<HTMLHeadingElement>(null);
  // 열리면 제목으로 포커스를 옮긴다 — 누른 버튼은 표 안에 있어 패널과 멀다
  useEffect(() => {
    titleRef.current?.focus();
  }, []);

  // 상태는 응답 콜백에서만 바꾼다 — 마운트 effect · 다시 읽기가 부른다. 다시 읽는 동안 이전 원가를 그대로 둔다
  const load = useCallback(
    () =>
      apiFetch<SkuCurrentCosts>(skuCostsPath(skuId)).then(
        (data) => setCurrent({ kind: 'ready', current: data }),
        (cause: unknown) => {
          const failure = readFailure(cause);
          if (failure.kind === 'access') onDenied(failure.denied);
          else if (failure.kind === 'notFound') setCurrent({ kind: 'notFound' });
          else setCurrent({ kind: 'failed', message: failure.message });
        },
      ),
    [skuId, onDenied],
  );

  useEffect(() => {
    void load();
  }, [load]);

  const reload = () => {
    void load();
    setHistoryRevision((n) => n + 1);
  };

  return (
    <section
      className="flex flex-col gap-6 rounded-md bg-muted/50 p-5"
      aria-label={`${label} 원가`}
    >
      <h2 ref={titleRef} tabIndex={-1} className="font-semibold outline-none">
        원가 — {label}
      </h2>

      {current.kind === 'loading' && <p className="text-sm text-muted-fg">불러오는 중…</p>}
      {current.kind === 'notFound' && (
        <p className="text-sm text-muted-fg">
          SKU 를 찾을 수 없습니다. 상품 화면을 새로 고쳐 확인해 주세요.
        </p>
      )}
      {current.kind === 'failed' && (
        <div role="alert" className="flex items-center gap-4">
          <p className="text-sm text-danger">{current.message}</p>
          <Button
            variant="secondary"
            onClick={() => {
              setCurrent({ kind: 'loading' });
              void load();
            }}
          >
            다시 불러오기
          </Button>
        </div>
      )}

      {/* live region 은 늘 두고 내용만 바꾼다 — 처음 나타날 때도 읽히게 */}
      <p role="status" className="text-sm empty:hidden">
        {notice}
      </p>

      {current.kind === 'ready' && (
        <>
          <section className="flex flex-col gap-3" aria-label="현재 원가">
            <h3 className="font-semibold">현재 원가</h3>
            <CurrentCostList current={current.current} />
          </section>
          <CostAddForm
            key={formRevision}
            skuId={skuId}
            current={current.current}
            onAdded={() => {
              setNotice('원가를 추가했습니다.');
              setFormRevision((n) => n + 1);
              reload();
            }}
            onStale={() => {
              setNotice('');
              reload();
            }}
            onDenied={onDenied}
          />
          <CostHistory key={historyRevision} skuId={skuId} onDenied={onDenied} />
        </>
      )}
    </section>
  );
}
