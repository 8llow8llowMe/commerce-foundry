'use client';

import { apiFetch, type PageResponse } from '@cf/api-client';
import { Button } from '@cf/ui';
import { useCallback, useEffect, useState } from 'react';
import { AccessNotice } from '@/components/AccessNotice';
import { accessDeniedOf, LOAD_FAILED, type AccessDenied } from '@/lib/api-errors';
import { templateDeleteFailure } from '@/lib/size-chart/errors';
import {
  templatePath,
  TEMPLATES_PATH,
  type Template,
  type TemplateSummary,
} from '@/lib/size-chart/types';
import { TemplateEditor } from './TemplateEditor';

const PAGE_SIZE = 20;

type Loaded =
  | { page: number; version: number; kind: 'ok'; data: PageResponse<TemplateSummary> }
  | { page: number; version: number; kind: 'failed' };

/** 열린 폼. 한 번에 하나만 연다 — 폼이 열려 있으면 줄의 삭제를 막는다 */
type Editing = { templateId: string | null } | null;

/**
 * 사이즈표 템플릿 관리(#218) — 목록(최신순 · 페이지) · 만들기 · 고치기 · 삭제.
 * 버전이 없어 마지막 쓰기가 이긴다(BE 계약). 쓰기 뒤에는 목록을 다시 읽는다.
 */
export function TemplateManager() {
  const [page, setPage] = useState(0);
  const [version, setVersion] = useState(0);
  const [loaded, setLoaded] = useState<Loaded | null>(null);
  const [denied, setDenied] = useState<AccessDenied | null>(null);
  const [editing, setEditing] = useState<Editing>(null);
  const [confirming, setConfirming] = useState<string | null>(null);
  const [deleting, setDeleting] = useState(false);
  const [notice, setNotice] = useState<string | null>(null);

  useEffect(() => {
    let alive = true;
    apiFetch<PageResponse<TemplateSummary>>(
      `${TEMPLATES_PATH}?page=${page}&size=${PAGE_SIZE}`,
    ).then(
      (data) => {
        if (!alive) return;
        // 마지막 쪽의 마지막 템플릿을 지우면 빈 쪽이 된다 — 남은 마지막 쪽으로 옮겨 다시 읽는다
        if (page > 0 && data.contents.length === 0) setPage(Math.max(0, data.totalPages - 1));
        else setLoaded({ page, version, kind: 'ok', data });
      },
      (cause: unknown) => {
        if (!alive) return;
        const kind = accessDeniedOf(cause);
        if (kind) setDenied(kind);
        else setLoaded({ page, version, kind: 'failed' });
      },
    );
    return () => {
      alive = false;
    };
  }, [page, version]);

  const onDenied = useCallback((kind: AccessDenied) => setDenied(kind), []);
  const reload = (message: string | null, firstPage = false) => {
    // 목록이 최신순이라 새로 만든 템플릿은 첫 쪽에 있다
    if (firstPage) setPage(0);
    setEditing(null);
    setConfirming(null);
    setNotice(message);
    setVersion((n) => n + 1);
  };

  const remove = async (template: TemplateSummary) => {
    if (deleting) return;
    setDeleting(true);
    setNotice(null);
    try {
      await apiFetch<null>(templatePath(template.templateId), { method: 'DELETE' });
      reload(`‘${template.name}’ 템플릿을 삭제했습니다.`);
    } catch (cause) {
      const failure = templateDeleteFailure(cause);
      if (failure.kind === 'access') setDenied(failure.denied);
      else if (failure.kind === 'reload') reload(failure.notice);
      else if (failure.kind === 'message') {
        setConfirming(null);
        setNotice(failure.message);
      }
    } finally {
      setDeleting(false);
    }
  };

  if (denied) return <AccessNotice denied={denied} />;
  const current = loaded?.page === page && loaded.version === version ? loaded : null;
  const busy = editing !== null || deleting;

  return (
    <div className="flex max-w-4xl flex-col gap-6">
      <div className="flex flex-wrap items-end justify-between gap-4">
        <div>
          <h1 className="text-xl font-semibold">사이즈표 템플릿</h1>
          <p className="mt-1 text-sm text-muted-fg">
            상품 사이즈표의 측정 항목과 단위를 정합니다. 상품마다 템플릿을 골라 치수만 넣습니다.
          </p>
        </div>
        <Button
          disabled={busy}
          onClick={() => {
            setNotice(null);
            setConfirming(null);
            setEditing({ templateId: null });
          }}
        >
          새 템플릿
        </Button>
      </div>

      {/* live region 은 늘 두고 내용만 바꾼다 — 처음 나타날 때도 읽히게 */}
      <p role="status" className="text-sm empty:hidden">
        {notice ?? ''}
      </p>

      {editing && (
        <TemplateEditor
          key={editing.templateId ?? 'new'}
          templateId={editing.templateId}
          onSaved={(saved: Template) =>
            reload(`‘${saved.name}’ 템플릿을 저장했습니다.`, editing.templateId === null)
          }
          onCancel={() => setEditing(null)}
          onReload={reload}
          onDenied={onDenied}
        />
      )}

      {!current && <p className="text-sm text-muted-fg">불러오는 중…</p>}
      {current?.kind === 'failed' && (
        <div role="alert" className="flex items-center gap-4">
          <p className="text-sm text-danger">{LOAD_FAILED}</p>
          <Button variant="secondary" onClick={() => setVersion((n) => n + 1)}>
            다시 불러오기
          </Button>
        </div>
      )}
      {current?.kind === 'ok' && current.data.totalElements === 0 && (
        <p className="text-sm text-muted-fg">
          아직 템플릿이 없습니다. 새 템플릿을 만들면 상품 사이즈표를 입력할 수 있습니다.
        </p>
      )}
      {current?.kind === 'ok' && current.data.contents.length > 0 && (
        <>
          <p className="text-sm text-muted-fg">{current.data.totalElements}개</p>
          <div className="relative overflow-x-auto">
            <table className="w-full min-w-[36rem] text-left text-sm">
              <thead className="text-muted-fg">
                <tr>
                  <th scope="col" className="py-2 pr-4 font-medium">
                    이름
                  </th>
                  <th scope="col" className="py-2 pr-4 font-medium">
                    단위
                  </th>
                  <th scope="col" className="py-2 pr-4 text-right font-medium">
                    항목 수
                  </th>
                  <th scope="col" className="py-2 font-medium">
                    <span className="sr-only">동작</span>
                  </th>
                </tr>
              </thead>
              <tbody>
                {current.data.contents.map((template) => (
                  <tr key={template.templateId} className="border-t border-border">
                    <th scope="row" className="py-3 pr-4 font-medium">
                      {template.name}
                    </th>
                    <td className="py-3 pr-4 whitespace-nowrap">{template.baseUnit.name}</td>
                    <td className="py-3 pr-4 text-right tabular-nums">{template.columnCount}</td>
                    <td className="py-3 whitespace-nowrap">
                      {confirming === template.templateId ? (
                        <div className="flex items-center gap-2">
                          <span className="text-sm">삭제할까요?</span>
                          <Button
                            className="h-8 px-3"
                            disabled={deleting}
                            onClick={() => void remove(template)}
                          >
                            {deleting ? '삭제 중…' : '삭제'}
                          </Button>
                          <Button
                            variant="secondary"
                            className="h-8 px-3"
                            disabled={deleting}
                            onClick={() => setConfirming(null)}
                          >
                            취소
                          </Button>
                        </div>
                      ) : (
                        <div className="flex gap-2">
                          <Button
                            variant="secondary"
                            className="h-8 px-3"
                            aria-label={`${template.name} 고치기`}
                            disabled={busy}
                            onClick={() => {
                              setNotice(null);
                              setConfirming(null);
                              setEditing({ templateId: template.templateId });
                            }}
                          >
                            고치기
                          </Button>
                          <Button
                            variant="secondary"
                            className="h-8 px-3"
                            aria-label={`${template.name} 삭제`}
                            disabled={busy}
                            onClick={() => {
                              setNotice(null);
                              setConfirming(template.templateId);
                            }}
                          >
                            삭제
                          </Button>
                        </div>
                      )}
                    </td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
          {current.data.totalPages > 1 && (
            <div className="flex items-center gap-3 text-sm">
              <Button variant="secondary" disabled={page === 0} onClick={() => setPage(page - 1)}>
                이전
              </Button>
              <span>
                {page + 1} / {current.data.totalPages}
              </span>
              <Button
                variant="secondary"
                disabled={page + 1 >= current.data.totalPages}
                onClick={() => setPage(page + 1)}
              >
                다음
              </Button>
            </div>
          )}
        </>
      )}
    </div>
  );
}
