'use client';

import { ApiError, apiFetch } from '@cf/api-client';
import { Button, cx, FieldError, TextField } from '@cf/ui';
import {
  closestCenter,
  DndContext,
  KeyboardSensor,
  PointerSensor,
  useSensor,
  useSensors,
  type Announcements,
  type DragEndEvent,
  type UniqueIdentifier,
} from '@dnd-kit/core';
import {
  SortableContext,
  sortableKeyboardCoordinates,
  verticalListSortingStrategy,
} from '@dnd-kit/sortable';
import { useCallback, useEffect, useId, useRef, useState, type FormEvent } from 'react';
import { AccessNotice } from '@/components/AccessNotice';
import { accessDeniedOf, LOAD_FAILED, type AccessDenied, type FormErrors } from '@/lib/api-errors';
import { moveItem } from '@/lib/display/order';
import { CHECK_FIELDS, writeFailure } from '@/lib/store-footer/errors';
import {
  blankRow,
  contentOf,
  draftErrors,
  formOf,
  isDirty,
  publicStateText,
  publishState,
  TOO_MANY_LINKS,
  type FooterForm,
} from '@/lib/store-footer/form';
import {
  LIMITS,
  NOT_CONFIGURED,
  STORE_FOOTER_PATH,
  STORE_FOOTER_PUBLISH_PATH,
  type FooterDetail,
  type FooterDraftRequest,
  type FooterPublishRequest,
} from '@/lib/store-footer/types';
import {
  DRAG_INSTRUCTIONS,
  dragAnnouncements,
  FooterPreview,
  LinkRowEditor,
  PublishConfirm,
  rowName,
  type PreviewDevice,
  type PreviewLocale,
} from './StoreFooterParts';

type Load =
  | { kind: 'loading' }
  | { kind: 'denied'; denied: AccessDenied }
  | { kind: 'loadFailed' }
  /** base — 서버의 최신 상세(아직 없으면 null). 입력(form)은 따로 들고 다시 읽어도 지우지 않는다 */
  | { kind: 'ready'; base: FooterDetail | null };

type Notice = { tone: 'info' | 'error'; text: string };

/** 상세 읽기. 미등록 404 는 null 이다 */
async function loadDetail(): Promise<FooterDetail | null> {
  try {
    return await apiFetch<FooterDetail>(STORE_FOOTER_PATH);
  } catch (cause) {
    if (cause instanceof ApiError && cause.code === NOT_CONFIGURED) return null;
    throw cause;
  }
}

const toggleButton = (active: boolean) =>
  cx(
    'rounded-md border px-3 py-1 text-sm',
    active ? 'border-primary bg-primary text-primary-fg' : 'border-border bg-bg text-fg',
  );

/**
 * 쇼핑몰 설정 > 푸터(#600 · BE #597). 채널(Instagram · 카카오톡 채널) · 기타 링크와 저작권 문구를 국문 · 영문 함께 편집한다.
 * - 초안 저장(`PUT`)은 새 리비전을 더할 뿐 스토어프론트를 바꾸지 않는다. 게시(`POST /publication`)만 공개한다.
 * - 미리보기는 저장 · 게시 없이 지금 입력으로 그린다(PC · 모바일, 국문 · 영문).
 * - 409 · 결과 모름은 자동으로 다시 보내지 않는다(초안 저장은 멱등이 아니다). 입력은 지키고 최신 상세를 다시 읽는다.
 * 약관 화면(`LegalDocumentScreen`)과 같은 초안/게시 흐름, 메인 전시와 같은 dnd-kit 정렬을 쓴다.
 */
export function StoreFooterEditor() {
  const id = useId();
  const dndId = useId();
  const [load, setLoad] = useState<Load>({ kind: 'loading' });
  const [form, setForm] = useState<FooterForm>(formOf(null));
  const [errors, setErrors] = useState<FormErrors>({});
  const [notice, setNotice] = useState<Notice | null>(null);
  const [conflict, setConflict] = useState(false);
  const [publishing, setPublishing] = useState(false);
  const [pending, setPending] = useState<'save' | 'publish' | null>(null);
  const [device, setDevice] = useState<PreviewDevice>('pc');
  const [locale, setLocale] = useState<PreviewLocale>('ko');
  const sending = useRef(false);
  const focusTarget = useRef<string | null>(null);

  const start = useCallback(() => {
    loadDetail().then(
      (base) => {
        setLoad({ kind: 'ready', base });
        setForm(formOf(base));
      },
      (cause: unknown) => {
        const denied = accessDeniedOf(cause);
        setLoad(denied ? { kind: 'denied', denied } : { kind: 'loadFailed' });
      },
    );
  }, []);

  useEffect(() => {
    start();
  }, [start]);

  const base = load.kind === 'ready' ? load.base : null;
  const dirty = load.kind === 'ready' && isDirty(form, base);

  useEffect(() => {
    if (!dirty) return;
    const warn = (event: BeforeUnloadEvent) => event.preventDefault();
    window.addEventListener('beforeunload', warn);
    return () => window.removeEventListener('beforeunload', warn);
  }, [dirty]);

  // 위 · 아래 버튼으로 옮긴 뒤 같은 줄의 버튼으로 포커스를 따라 보낸다
  useEffect(() => {
    const target = focusTarget.current;
    if (!target) return;
    focusTarget.current = null;
    const button = document.querySelector<HTMLButtonElement>(`[data-move="${target}"]`);
    if (button && !button.disabled) button.focus();
  });

  const sensors = useSensors(
    useSensor(PointerSensor, { activationConstraint: { distance: 4 } }),
    useSensor(KeyboardSensor, { coordinateGetter: sortableKeyboardCoordinates }),
  );

  if (load.kind === 'loading') return <p className="text-sm text-muted-fg">불러오는 중…</p>;
  if (load.kind === 'denied') return <AccessNotice denied={load.denied} />;
  if (load.kind === 'loadFailed')
    return (
      <div role="alert" className="flex items-center gap-4">
        <p className="text-sm text-danger">{LOAD_FAILED}</p>
        <Button
          variant="secondary"
          onClick={() => {
            setLoad({ kind: 'loading' });
            start();
          }}
        >
          다시 불러오기
        </Button>
      </div>
    );

  const busy = pending !== null;
  const keys = form.links.map((row) => row.key);

  /** 줄 순서 · 개수가 바뀌면 칸 오류(번호 기준)가 어긋나므로 지운다 */
  const edit = (next: FooterForm, keepErrors = true) => {
    setForm(next);
    if (!keepErrors) setErrors({});
    setNotice(null);
  };
  const move = (from: number, to: number) =>
    edit({ ...form, links: moveItem(form.links, from, to) }, false);

  const begin = (kind: 'save' | 'publish') => {
    if (sending.current) return false;
    sending.current = true;
    setPending(kind);
    return true;
  };
  const release = () => {
    sending.current = false;
    setPending(null);
  };

  const applied = (next: FooterDetail, message: string) => {
    setLoad({ kind: 'ready', base: next });
    setForm(formOf(next));
    setErrors({});
    setConflict(false);
    setNotice({ tone: 'info', text: message });
  };

  /** 경합 · 결과 모름 — 입력은 두고 기준 상세만 바꾼다(다음 저장은 최신 version 으로) */
  const rebase = (message: string, isConflict: boolean) => {
    setNotice({ tone: 'error', text: message });
    return loadDetail().then(
      (next) => {
        setLoad({ kind: 'ready', base: next });
        setConflict(isConflict);
      },
      (cause: unknown) => {
        const denied = accessDeniedOf(cause);
        if (denied === 'forbidden') setLoad({ kind: 'denied', denied });
        else setNotice({ tone: 'error', text: `${message} ${LOAD_FAILED}` });
      },
    );
  };

  const writeFailed = (cause: unknown) => {
    const failure = writeFailure(cause);
    if (failure.kind === 'access') {
      release();
      setLoad({ kind: 'denied', denied: failure.denied });
    } else if (failure.kind === 'reload') {
      setPublishing(false);
      void rebase(failure.notice, failure.conflict).finally(release);
    } else {
      release();
      setErrors(failure.errors);
    }
  };

  const save = (event: FormEvent<HTMLFormElement>) => {
    event.preventDefault();
    const invalid = draftErrors(form);
    if (Object.keys(invalid).length > 0) {
      setErrors({ ...invalid, form: [CHECK_FIELDS] });
      setNotice(null);
      return;
    }
    if (!begin('save')) return;
    setErrors({});
    setNotice(null);
    apiFetch<FooterDetail>(STORE_FOOTER_PATH, {
      method: 'PUT',
      json: {
        expectedVersion: base?.version ?? null,
        ...contentOf(form),
      } satisfies FooterDraftRequest,
    }).then((next) => {
      release();
      applied(
        next,
        `${next.latestRevision}번 초안을 저장했습니다. 게시하기 전까지 고객에게 보이지 않습니다.`,
      );
    }, writeFailed);
  };

  const publish = () => {
    if (!base || !begin('publish')) return;
    setErrors({});
    setNotice(null);
    const revisionNumber = base.latestRevision;
    apiFetch<FooterDetail>(STORE_FOOTER_PUBLISH_PATH, {
      method: 'POST',
      json: { expectedVersion: base.version, revisionNumber } satisfies FooterPublishRequest,
    }).then((next) => {
      release();
      setPublishing(false);
      applied(next, `${revisionNumber}번을 게시했습니다. 스토어프론트 푸터에 바로 반영됩니다.`);
    }, writeFailed);
  };

  const nameOf = (key: UniqueIdentifier) => {
    const row = form.links.find((item) => item.key === key);
    return row ? rowName(row) : '';
  };
  const positionOf = (key: UniqueIdentifier) => keys.indexOf(String(key)) + 1;
  const announcements: Announcements = {
    onDragStart: ({ active }) => dragAnnouncements.start(nameOf(active.id)),
    onDragOver: ({ active, over }) =>
      over ? dragAnnouncements.over(nameOf(active.id), positionOf(over.id)) : undefined,
    onDragEnd: ({ active, over }) =>
      over ? dragAnnouncements.end(nameOf(active.id), positionOf(over.id)) : undefined,
    onDragCancel: ({ active }) => dragAnnouncements.cancel(nameOf(active.id)),
  };
  const onDragEnd = ({ active, over }: DragEndEvent) => {
    if (!over || active.id === over.id) return;
    move(keys.indexOf(String(active.id)), keys.indexOf(String(over.id)));
  };

  const state = publishState(form, base);
  const atLimit = form.links.length >= LIMITS.links;

  return (
    <div className="flex max-w-6xl flex-col gap-6">
      <div className="flex flex-col gap-1">
        <h1 className="text-xl font-semibold">푸터</h1>
        <p className="text-sm text-muted-fg">
          스토어프론트 공통 푸터의 채널 · 링크와 저작권 문구를 국문 · 영문 함께 편집합니다. 초안
          저장은 고객에게 공개되지 않고 <strong>게시</strong>해야 바뀝니다. 회사 · 고객센터 정보는
          &lsquo;쇼핑몰 정보&rsquo;, 약관 링크는 &lsquo;약관 · 개인정보처리방침&rsquo;에서
          관리합니다.
        </p>
      </div>

      <p role="status" className="text-sm">
        {publicStateText(base)}
        {dirty && ' 지금 편집기에 저장하지 않은 입력이 있습니다.'}
      </p>

      {notice && (
        <p
          role={notice.tone === 'error' ? 'alert' : 'status'}
          className={cx('text-sm', notice.tone === 'error' ? 'text-danger' : 'text-fg')}
        >
          {notice.text}
        </p>
      )}

      {conflict && (
        <div className="flex flex-wrap items-center gap-3 rounded-md border border-border px-4 py-3 text-sm">
          <span>
            최신 저장본은 {base ? `${base.latestRevision}번` : '없음'}입니다. 내 입력을 버리고 최신
            값으로 바꿀 수 있습니다.
          </span>
          <Button
            variant="secondary"
            onClick={() => {
              setForm(formOf(base));
              setConflict(false);
              setErrors({});
              setNotice({ tone: 'info', text: '최신 값으로 바꿨습니다.' });
            }}
          >
            최신 값으로 바꾸기
          </Button>
        </div>
      )}

      <div className="flex flex-col gap-8">
        <form noValidate onSubmit={save} className="flex max-w-3xl flex-col gap-6">
          <fieldset disabled={busy || publishing} className="flex flex-col gap-6">
            <section aria-labelledby={`${id}-links`} className="flex flex-col gap-3">
              <div className="flex flex-wrap items-center justify-between gap-2">
                <h2 id={`${id}-links`} className="font-semibold">
                  채널 · 링크 ({form.links.length}/{LIMITS.links})
                </h2>
                <Button
                  variant="secondary"
                  disabled={atLimit}
                  onClick={() => {
                    const row = blankRow();
                    edit({ ...form, links: [...form.links, row] });
                    focusTarget.current = `drag:${row.key}`;
                  }}
                >
                  링크 추가
                </Button>
              </div>
              {atLimit && <p className="text-xs text-muted-fg">{TOO_MANY_LINKS}</p>}
              <FieldError messages={errors.links ?? []} />
              {form.links.length === 0 ? (
                <p className="text-sm text-muted-fg">
                  링크가 없습니다. 비워 두고 게시하면 스토어프론트 푸터에 채널 링크가 보이지
                  않습니다.
                </p>
              ) : (
                <DndContext
                  id={dndId}
                  sensors={sensors}
                  collisionDetection={closestCenter}
                  onDragEnd={onDragEnd}
                  accessibility={{
                    announcements,
                    screenReaderInstructions: { draggable: DRAG_INSTRUCTIONS },
                  }}
                >
                  <SortableContext items={keys} strategy={verticalListSortingStrategy}>
                    <ul
                      className="flex flex-col gap-2"
                      aria-label="푸터 링크 목록 (위가 먼저 보입니다)"
                    >
                      {form.links.map((row, index) => (
                        <LinkRowEditor
                          key={row.key}
                          row={row}
                          index={index}
                          total={form.links.length}
                          errors={errors}
                          locked={busy || publishing}
                          onChange={(next) =>
                            edit({
                              ...form,
                              links: form.links.map((item) => (item.key === row.key ? next : item)),
                            })
                          }
                          onMove={(to) => {
                            move(index, to);
                            focusTarget.current = `${to < index ? 'up' : 'down'}:${row.key}`;
                          }}
                          onRemove={() =>
                            edit(
                              { ...form, links: form.links.filter((item) => item.key !== row.key) },
                              false,
                            )
                          }
                        />
                      ))}
                    </ul>
                  </SortableContext>
                </DndContext>
              )}
            </section>

            <section aria-labelledby={`${id}-copyright`} className="flex flex-col gap-3">
              <h2 id={`${id}-copyright`} className="font-semibold">
                저작권 문구
              </h2>
              <p className="text-xs text-muted-fg">
                비우면 그 언어는 &lsquo;© 연도 쇼핑몰 이름&rsquo; 기본 표기를 씁니다. 글자 그대로
                보입니다(HTML 은 해석하지 않습니다). 언어별 {LIMITS.copyright}자 이내.
              </p>
              <TextField
                label="국문"
                value={form.copyrightKo}
                maxLength={LIMITS.copyright}
                errors={errors['copyrightText.ko']}
                onChange={(event) => edit({ ...form, copyrightKo: event.currentTarget.value })}
              />
              <TextField
                label="영문"
                value={form.copyrightEn}
                maxLength={LIMITS.copyright}
                errors={errors['copyrightText.en']}
                onChange={(event) => edit({ ...form, copyrightEn: event.currentTarget.value })}
              />
            </section>

            <FieldError messages={errors.form ?? []} />
            <div className="flex flex-col gap-1">
              <div>
                <Button type="submit" disabled={!dirty && base !== null}>
                  {pending === 'save' ? '저장 중…' : '초안 저장 (공개 안 됨)'}
                </Button>
              </div>
              <p className="text-xs text-muted-fg">
                저장할 때마다 새 리비전이 쌓입니다. 스토어프론트는 게시할 때만 바뀝니다.
              </p>
            </div>
          </fieldset>
        </form>

        <section
          aria-labelledby={`${id}-preview`}
          className="flex flex-col gap-3 border-t border-border pt-6"
        >
          <h2 id={`${id}-preview`} className="font-semibold">
            미리보기 (저장 전 입력)
          </h2>
          <div className="flex flex-wrap gap-4">
            <div role="group" aria-label="미리보기 화면 크기" className="flex gap-1">
              {(['pc', 'mobile'] as const).map((value) => (
                <button
                  key={value}
                  type="button"
                  aria-pressed={device === value}
                  className={toggleButton(device === value)}
                  onClick={() => setDevice(value)}
                >
                  {value === 'pc' ? 'PC' : '모바일'}
                </button>
              ))}
            </div>
            <div role="group" aria-label="미리보기 언어" className="flex gap-1">
              {(['ko', 'en'] as const).map((value) => (
                <button
                  key={value}
                  type="button"
                  aria-pressed={locale === value}
                  className={toggleButton(locale === value)}
                  onClick={() => setLocale(value)}
                >
                  {value === 'ko' ? '국문' : '영문'}
                </button>
              ))}
            </div>
          </div>
          <FooterPreview form={form} device={device} locale={locale} />
        </section>
      </div>

      {base && (
        <section aria-label="게시" className="flex flex-col gap-2 border-t border-border pt-6">
          {state === 'published' && (
            <p className="text-sm">최신 {base.latestRevision}번이 이미 공개 중입니다.</p>
          )}
          {state === 'dirty' && (
            <p className="text-sm text-muted-fg">
              저장하지 않은 입력이 있습니다. 게시는 저장한 최신 초안만 할 수 있으니 먼저 초안을
              저장해 주세요.
            </p>
          )}
          {state === 'ready' && !publishing && (
            <div>
              <Button
                onClick={() => {
                  setPublishing(true);
                  setErrors({});
                }}
              >
                {base.latestRevision}번 게시하기…
              </Button>
            </div>
          )}
          {state === 'ready' && publishing && (
            <>
              <PublishConfirm
                revisionNumber={base.latestRevision}
                pending={pending === 'publish'}
                onCancel={() => setPublishing(false)}
                onPublish={publish}
              />
              <FieldError messages={errors.form ?? []} />
            </>
          )}
        </section>
      )}
    </div>
  );
}
