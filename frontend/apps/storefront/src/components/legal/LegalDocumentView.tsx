import type { Locale } from '@cf/ui';
import { Link } from '@/components/i18n/Link';
import type { Dictionary } from '@/lib/i18n/dictionaries';
import { fill } from '@/lib/i18n/format';
import { formatPublishedDate, type LegalLoad } from '@/lib/legal/document';

export type LegalDocumentViewProps = {
  /** 문서 이름(사전) — 문서가 없을 때도 제목으로 쓴다 */
  name: string;
  /** 언어 없는 앱 경로(`/terms` · `/privacy`) */
  path: string;
  /** 이전 게시본 보기 폼의 action — 언어 접두어를 붙인 주소 */
  formAction: string;
  locale: Locale;
  /** 요청한 이전 게시본 번호. 현재 게시본이면 null */
  revision: number | null;
  load: LegalLoad;
  labels: Dictionary['legal'];
};

/**
 * 약관 · 개인정보처리방침 공개 페이지 본문(#570). 서버 컴포넌트에서만 그린다.
 * - 제목 · 본문은 **React 텍스트로만** 그린다(이스케이프). HTML 로 해석하지 않고 `dangerouslySetInnerHTML` 을 쓰지 않는다.
 *   줄바꿈 · 들여쓰기는 `whitespace-pre-wrap` 으로 그대로 보인다.
 * - 게시본이 없으면 다른 언어로 대신 보이거나 문서를 만들지 않고 "게시된 문서 없음" 만 알린다.
 * - 이전 게시본은 `?revision=N` 으로 고른다. 공개 API 에 지난 게시본 목록이 없어 번호를 입력받는다(JS 없이 GET 폼).
 */
export function LegalDocumentView({
  name,
  path,
  formAction,
  locale,
  revision,
  load,
  labels,
}: LegalDocumentViewProps) {
  const document = load.kind === 'ok' ? load.document : null;
  const date = document ? formatPublishedDate(document.publishedAt, locale) : null;

  return (
    <main className="mx-auto flex max-w-3xl flex-col gap-8 px-4 pt-10 pb-24 lg:px-8 lg:pt-14">
      <header className="flex flex-col gap-3">
        <h1 className="font-serif text-2xl break-words lg:text-3xl">{document?.title ?? name}</h1>
        {document && (
          <p className="text-sm text-muted-fg">
            {fill(labels.revision, { number: document.revisionNumber })}
            {date && <> · {fill(labels.publishedAt, { date })}</>}
          </p>
        )}
        {revision !== null && (
          <p role="status" className="flex flex-wrap gap-x-3 gap-y-1 bg-muted px-4 py-3 text-sm">
            {document && <span>{fill(labels.viewingPrevious, { number: revision })}</span>}
            <Link href={path} className="underline underline-offset-4 hover:text-primary">
              {labels.viewCurrent}
            </Link>
          </p>
        )}
      </header>

      {load.kind === 'ok' && (
        <div className="text-sm leading-7 break-words whitespace-pre-wrap lg:text-base lg:leading-8">
          {load.document.body}
        </div>
      )}
      {load.kind === 'missing' && (
        <p className="py-12 text-center text-sm text-muted-fg">
          {revision === null ? labels.missing : fill(labels.revisionMissing, { number: revision })}
        </p>
      )}
      {load.kind === 'failed' && (
        <div role="alert" className="flex flex-col items-center gap-4 py-12 text-center">
          <p className="text-sm text-muted-fg">{labels.loadFailed}</p>
          {/* 같은 주소를 다시 받는다 — 클라이언트 라우팅은 같은 URL 을 다시 그리지 않는다 */}
          <a
            href={formAction + (revision === null ? '' : `?revision=${revision}`)}
            className="text-sm underline underline-offset-4"
          >
            {labels.reload}
          </a>
        </div>
      )}

      {(load.kind !== 'missing' || revision !== null) && (
        <form
          action={formAction}
          method="get"
          className="flex flex-col gap-2 border-t border-border pt-6"
        >
          <label htmlFor="legal-revision" className="text-sm font-medium">
            {labels.previousLabel}
          </label>
          <div className="flex gap-2">
            <input
              id="legal-revision"
              name="revision"
              type="number"
              inputMode="numeric"
              min={1}
              step={1}
              required
              defaultValue={revision ?? undefined}
              aria-describedby="legal-revision-hint"
              className="h-10 w-28 border border-border bg-bg px-3 text-fg outline-none focus:ring-2 focus:ring-primary"
            />
            <button
              type="submit"
              className="h-10 bg-primary px-4 text-sm text-primary-fg hover:opacity-90"
            >
              {labels.previousSubmit}
            </button>
          </div>
          <p id="legal-revision-hint" className="text-xs text-muted-fg">
            {labels.previousHint}
          </p>
        </form>
      )}
    </main>
  );
}
