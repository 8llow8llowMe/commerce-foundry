'use client';

import { apiFetch } from '@cf/api-client';
import { Button, FieldError, TextField } from '@cf/ui';
import { useEffect, useRef, useState, type FormEvent } from 'react';
import {
  OTP_CHALLENGE_EXPIRED,
  otpOutcome,
  otpSubmitLabel,
  type OtpChallenge,
} from '@/lib/auth/otp-login';
import { useLoginCooldown } from '@/lib/auth/use-login-cooldown';

type Props = {
  challenge: OtpChallenge;
  /** 2단계 성공 — access 쿠키가 심겼다. 콘솔로 이동한다 */
  onSuccess: () => void;
  /** 챌린지를 더 쓸 수 없다 — 1단계로 돌아가 문구를 보인다 */
  onRestart: (notice: string) => void;
};

/**
 * 관리자 로그인 2단계(#414). 인증 앱의 6자리 코드 또는 복구 코드를 받아 BFF `POST /api/v1/auth/admin/login/otp` 로 보낸다.
 * 챌린지는 props(부모 state)로만 받아 요청 본문에만 싣는다 — 폼 칸 · URL · 저장소 · 로그에 두지 않는다.
 * `challengeExpiresIn` 이 지나거나 `AUTH_034` · `AUTH_044` 면 1단계로, `AUTH_035` 는 칸을 비우고 다시 입력,
 * 429 `AUTH_043` 은 `Retry-After` 동안 제출을 막고 남은 시간을 보인다.
 */
export function OtpForm({ challenge, onSuccess, onRestart }: Props) {
  const [code, setCode] = useState('');
  const [submitting, setSubmitting] = useState(false);
  const [codeErrors, setCodeErrors] = useState<string[]>([]);
  const [formErrors, setFormErrors] = useState<string[]>([]);
  const cooldown = useLoginCooldown();
  // 성공해 이동하는 동안 만료 타이머가 1단계로 되돌리지 않게
  const done = useRef(false);

  useEffect(() => {
    const timer = setTimeout(
      () => {
        if (!done.current) onRestart(OTP_CHALLENGE_EXPIRED);
      },
      Math.max(0, challenge.expiresAt - Date.now()),
    );
    return () => clearTimeout(timer);
  }, [challenge, onRestart]);

  const onSubmit = async (event: FormEvent<HTMLFormElement>) => {
    event.preventDefault();
    if (submitting || cooldown.remainingSec > 0) return;
    if (Date.now() >= challenge.expiresAt) {
      onRestart(OTP_CHALLENGE_EXPIRED);
      return;
    }
    setSubmitting(true);
    setCodeErrors([]);
    setFormErrors([]);
    try {
      await apiFetch<null>('/api/v1/auth/admin/login/otp', {
        method: 'POST',
        json: { challengeToken: challenge.token, code: code.trim() },
      });
      done.current = true;
      onSuccess();
    } catch (cause) {
      setSubmitting(false);
      const outcome = otpOutcome(cause);
      if (outcome.kind === 'restart') {
        onRestart(outcome.notice);
        return;
      }
      cooldown.startFrom(cause);
      if (outcome.code.length > 0) setCode('');
      setCodeErrors(outcome.code);
      setFormErrors(outcome.form);
    }
  };

  return (
    <form onSubmit={onSubmit} className="flex flex-col gap-5">
      <p className="text-sm text-muted-fg">
        인증 앱에 표시된 6자리 코드를 입력해 주세요. 인증 앱을 쓸 수 없으면 복구 코드를 입력할 수
        있습니다.
      </p>
      <TextField
        label="인증 코드"
        name="code"
        autoComplete="one-time-code"
        autoCapitalize="off"
        spellCheck={false}
        maxLength={32}
        required
        autoFocus
        value={code}
        onChange={(event) => setCode(event.currentTarget.value)}
        errors={codeErrors}
      />
      <FieldError messages={formErrors} />
      <Button
        type="submit"
        disabled={submitting || cooldown.remainingSec > 0}
        className="h-11 w-full"
      >
        {otpSubmitLabel(submitting, cooldown.remainingSec)}
      </Button>
      <Button
        variant="secondary"
        disabled={submitting}
        onClick={() => onRestart('')}
        className="h-11 w-full"
      >
        처음부터 다시 로그인
      </Button>
    </form>
  );
}
