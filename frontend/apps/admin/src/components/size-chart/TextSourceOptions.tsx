'use client';

import { Button } from '@cf/ui';
import {
  sourceLabel,
  TEXT_SOURCES_CHECK,
  TEXT_SOURCES_EMPTY,
  type TextSource,
  type TextSourcesState,
} from '@/lib/size-chart/text-source';

type Props = {
  state: TextSourcesState;
  /** 라디오 묶음 이름 — 한 화면에 여러 번 나오므로 `useId()` 로 만든 값을 준다 */
  name: string;
  /** 고른 후보 아이디. 직접 입력이면 null */
  selectedId: string | null;
  onPick: (source: TextSource) => void;
  onReload: () => void;
  disabled: boolean;
};

/**
 * 상품 본문에서 찾은 치수표 후보 고르기(#218). 단건 패널과 일괄 화면이 같이 쓴다.
 * 후보 텍스트는 줄바꿈을 살려 그대로 보인다 — 이 블록만 안에서 가로로 스크롤한다(화면 전체는 넘치지 않는다).
 */
export function TextSourceOptions({ state, name, selectedId, onPick, onReload, disabled }: Props) {
  if (state.status === 'idle') return null;
  if (state.status === 'loading')
    return <p className="text-sm text-muted-fg">상품 설명에서 표를 찾는 중…</p>;
  if (state.status === 'error')
    return (
      <div className="flex flex-wrap items-center gap-3">
        <p className="text-sm text-danger">{state.message}</p>
        <Button variant="secondary" className="h-8 px-3" disabled={disabled} onClick={onReload}>
          다시 불러오기
        </Button>
      </div>
    );
  if (state.sources.length === 0)
    return <p className="text-sm text-muted-fg">{TEXT_SOURCES_EMPTY}</p>;
  return (
    <div className="flex min-w-0 flex-col gap-2">
      <p className="text-xs text-muted-fg">{TEXT_SOURCES_CHECK}</p>
      <ul className="flex min-w-0 flex-col gap-3">
        {state.sources.map((source) => {
          const textId = `${name}-${source.sourceId}-text`;
          const label = sourceLabel(source.sourceId);
          return (
            <li key={source.sourceId} className="flex min-w-0 flex-col gap-1">
              <label className="flex cursor-pointer items-center gap-2 text-sm">
                <input
                  type="radio"
                  name={name}
                  value={source.sourceId}
                  checked={selectedId === source.sourceId}
                  disabled={disabled}
                  aria-describedby={textId}
                  onChange={() => onPick(source)}
                  className="size-4 cursor-pointer accent-primary"
                />
                {label}
              </label>
              {/* 긴 줄은 이 블록 안에서만 스크롤한다. 키보드로도 스크롤하게 포커스를 받는다 */}
              <pre
                id={textId}
                tabIndex={0}
                aria-label={`${label} 내용`}
                className="ml-6 max-h-48 min-w-0 overflow-auto whitespace-pre rounded-md bg-muted px-3 py-2 text-xs leading-relaxed text-fg focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-primary"
              >
                {source.sourceText}
              </pre>
            </li>
          );
        })}
      </ul>
    </div>
  );
}
