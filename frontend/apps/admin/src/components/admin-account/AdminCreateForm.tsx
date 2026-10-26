'use client';

import { apiFetch } from '@cf/api-client';
import { Button, FieldError, TextField } from '@cf/ui';
import { useState, type FormEvent } from 'react';
import {
  createAdminFailure,
  PASSWORD_HINT,
  PASSWORD_MAX,
  passwordProblem,
} from '@/lib/admin-account/errors';
import { ADMIN_ACCOUNTS_PATH } from '@/lib/admin-account/query';
import type { AccessDenied, FormErrors } from '@/lib/api-errors';

const CHECK_INPUT = '입력값을 확인해 주세요.';

/**
 * 관리자 추가 폼(#352). 칸은 제어하지 않는다(`FormData` 로 읽는다) — 비밀번호를 React 상태에 두지 않으려고.
 * 성공하면 폼을 비우고(`reset`) 목록에 알린다. 실패하면 입력은 그대로 두고 오류를 칸에 붙인다(§9).
 */
export function AdminCreateForm({
  onCreated,
  onUncertain,
  onDenied,
  onCancel,
}: {
  onCreated: () => void;
  /** 결과를 모를 때 — 목록을 다시 읽는다 */
  onUncertain: () => void;
  onDenied: (denied: AccessDenied) => void;
  onCancel: () => void;
}) {
  const [submitting, setSubmitting] = useState(false);
  const [errors, setErrors] = useState<FormErrors>({});

  const onSubmit = async (event: FormEvent<HTMLFormElement>) => {
    event.preventDefault();
    if (submitting) return;
    const form = event.currentTarget;
    const data = new FormData(form);
    const password = String(data.get('initialPassword') ?? '');
    const problem = passwordProblem(password);
    if (problem) {
      setErrors({ password: [problem] });
      return;
    }
    setSubmitting(true);
    setErrors({});
    try {
      await apiFetch<{ memberId: string }>(ADMIN_ACCOUNTS_PATH, {
        method: 'POST',
        json: {
          email: String(data.get('staffEmail') ?? ''),
          password,
          name: String(data.get('name') ?? ''),
        },
      });
      form.reset();
      setSubmitting(false);
      onCreated();
    } catch (cause) {
      setSubmitting(false);
      const failure = createAdminFailure(cause);
      if (failure.kind === 'access') onDenied(failure.denied);
      else {
        setErrors(failure.errors);
        if (failure.uncertain) onUncertain();
      }
    }
  };

  const fieldOnly = !errors.form?.length && Object.values(errors).some((list) => list.length > 0);

  return (
    <form
      onSubmit={onSubmit}
      aria-label="관리자 추가"
      className="flex max-w-md flex-col gap-5 rounded-md bg-muted/50 p-5"
    >
      <fieldset disabled={submitting} className="flex flex-col gap-5">
        <TextField
          label="이메일"
          name="staffEmail"
          type="email"
          autoComplete="off"
          data-focus="add-email"
          inputMode="email"
          maxLength={254}
          required
          errors={errors.email}
        />
        <TextField
          label="이름"
          name="name"
          autoComplete="off"
          maxLength={50}
          required
          errors={errors.name}
        />
        <div className="flex flex-col gap-1">
          <TextField
            label="처음 비밀번호"
            // 칸 이름을 email · password 로 두지 않는다 — 브라우저가 로그인 폼으로 읽고 다른 사람의 비밀번호를
            // 내 자격 증명으로 저장하자고 하는 일을 줄인다(완전히 막지는 못한다). 보낼 때 BE 이름으로 바꾼다
            name="initialPassword"
            type="password"
            autoComplete="new-password"
            maxLength={PASSWORD_MAX}
            required
            errors={errors.password}
          />
          <p className="text-xs text-muted-fg">
            {PASSWORD_HINT} 추가한 분께 따로 전하고, 로그인한 뒤 바꾸도록 안내해 주세요.
          </p>
        </div>
      </fieldset>
      <FieldError messages={errors.form ?? (fieldOnly ? [CHECK_INPUT] : [])} />
      <div className="flex gap-3">
        <Button type="submit" disabled={submitting}>
          {submitting ? '추가하는 중…' : '추가'}
        </Button>
        <Button variant="secondary" onClick={onCancel} disabled={submitting}>
          취소
        </Button>
      </div>
    </form>
  );
}
