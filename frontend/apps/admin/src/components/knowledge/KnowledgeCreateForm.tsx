'use client';

import { apiFetch } from '@cf/api-client';
import { Button, FieldError } from '@cf/ui';
import Link from 'next/link';
import { useRouter } from 'next/navigation';
import { useId, useRef, useState, type FormEvent } from 'react';
import { AccessNotice } from '@/components/AccessNotice';
import type { AccessDenied, FormErrors } from '@/lib/api-errors';
import { createFailure } from '@/lib/knowledge/errors';
import { contentRequest, emptyContentForm, type ContentForm } from '@/lib/knowledge/form';
import { KNOWLEDGE_HREF, knowledgeHref } from '@/lib/knowledge/query';
import {
  KNOWLEDGE_LOCALES,
  KNOWLEDGE_PATH,
  KNOWLEDGE_TYPES,
  LOCALE_LABELS,
  TYPE_LABELS,
  type KnowledgeDetail,
  type KnowledgeLocaleCode,
  type KnowledgeTypeCode,
} from '@/lib/knowledge/types';
import { ContentFields } from './ContentFields';

/**
 * 새 지식 문서(#473) — `POST /api/v1/admin/support/knowledge`. 문서와 첫 초안(1번 리비전)을 만들고 상세로 옮긴다.
 * 유형 · 언어는 만든 뒤 바꿀 수 없다. 영문 고객용은 언어를 "영어" 로 따로 만든다(언어 대체는 없다).
 * 생성은 멱등이 아니라 결과 모름(502 · 504)이면 다시 보내지 않고 목록에서 확인하라고 안내한다.
 */
export function KnowledgeCreateForm() {
  const id = useId();
  const router = useRouter();
  const [type, setType] = useState<KnowledgeTypeCode>('FAQ');
  const [locale, setLocale] = useState<KnowledgeLocaleCode>('KO');
  const [form, setForm] = useState<ContentForm>(() => emptyContentForm());
  const [errors, setErrors] = useState<FormErrors>({});
  const [busy, setBusy] = useState(false);
  const [denied, setDenied] = useState<AccessDenied | null>(null);
  /** 더블 클릭 — 상태 갱신 전에 두 번째 submit 이 와도 한 번만 보낸다 */
  const sending = useRef(false);

  const submit = (event: FormEvent<HTMLFormElement>) => {
    event.preventDefault();
    if (sending.current) return;
    const request = contentRequest(form);
    if (request.kind === 'errors') {
      setErrors(request.errors);
      return;
    }
    sending.current = true;
    setBusy(true);
    setErrors({});
    apiFetch<KnowledgeDetail>(KNOWLEDGE_PATH, {
      method: 'POST',
      json: { type, locale, content: request.content },
    }).then(
      // 옮겨 가는 동안 다시 누르지 않게 sending 은 풀지 않는다
      (created) => router.push(knowledgeHref(created.documentId)),
      (cause: unknown) => {
        sending.current = false;
        setBusy(false);
        const failure = createFailure(cause);
        if (failure.kind === 'access') setDenied(failure.denied);
        else setErrors(failure.errors);
      },
    );
  };

  if (denied) return <AccessNotice denied={denied} />;

  return (
    <div className="flex max-w-3xl flex-col gap-6">
      <div className="flex flex-col gap-1">
        <Link href={KNOWLEDGE_HREF} className="text-sm text-muted-fg underline underline-offset-4">
          지식 목록
        </Link>
        <h1 className="text-xl font-semibold">새 지식 문서</h1>
        <p className="text-sm text-muted-fg">
          초안으로 저장됩니다. 상세에서 승인한 뒤 게시해야 상담에 쓰입니다.
        </p>
      </div>
      <form noValidate onSubmit={submit}>
        <fieldset disabled={busy} className="flex flex-col gap-5">
          <div className="flex flex-col gap-4 sm:flex-row">
            <div className="flex flex-col gap-1 sm:w-60">
              <label htmlFor={`${id}-type`} className="text-sm font-medium">
                유형(만든 뒤 바꿀 수 없음)
              </label>
              <select
                id={`${id}-type`}
                value={type}
                onChange={(event) =>
                  setType(KNOWLEDGE_TYPES.find((v) => v === event.currentTarget.value) ?? 'FAQ')
                }
                className="h-10 rounded-md border border-border bg-bg px-3"
              >
                {KNOWLEDGE_TYPES.map((value) => (
                  <option key={value} value={value}>
                    {TYPE_LABELS[value]}
                  </option>
                ))}
              </select>
            </div>
            <div className="flex flex-col gap-1 sm:w-60">
              <label htmlFor={`${id}-locale`} className="text-sm font-medium">
                언어(만든 뒤 바꿀 수 없음)
              </label>
              <select
                id={`${id}-locale`}
                value={locale}
                onChange={(event) =>
                  setLocale(KNOWLEDGE_LOCALES.find((v) => v === event.currentTarget.value) ?? 'KO')
                }
                className="h-10 rounded-md border border-border bg-bg px-3"
              >
                {KNOWLEDGE_LOCALES.map((value) => (
                  <option key={value} value={value}>
                    {LOCALE_LABELS[value]}
                  </option>
                ))}
              </select>
            </div>
          </div>
          <ContentFields form={form} errors={errors} onChange={setForm} />
          <FieldError id={`${id}-form`} messages={errors.form ?? []} />
          <div>
            <Button type="submit">{busy ? '저장 중…' : '초안 저장'}</Button>
          </div>
        </fieldset>
      </form>
    </div>
  );
}
