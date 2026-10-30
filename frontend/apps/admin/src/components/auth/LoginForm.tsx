'use client';

import { apiFetch } from '@cf/api-client';
import { Button, FieldError, TextField } from '@cf/ui';
import { useRouter } from 'next/navigation';
import { useCallback, useEffect, useState, type FormEvent } from 'react';
import { OtpForm } from '@/components/auth/OtpForm';
import { formatWait } from '@/lib/auth/login-cooldown';
import { loginErrors, NO_LOGIN_ERRORS, type LoginErrors } from '@/lib/auth/login-errors';
import { readOtpChallenge, type OtpChallenge } from '@/lib/auth/otp-login';
import { useLoginCooldown } from '@/lib/auth/use-login-cooldown';
import { onboardingTasks } from '@/lib/onboarding/tasks-store';
import { tourProgress } from '@/lib/tour/progress';

/**
 * 관리자 로그인 폼(#204). BFF `POST /api/v1/auth/admin/login` 이 access 토큰을 httpOnly 쿠키로 심고 토큰 없는 성공만 돌려준다.
 * 성공하면 돌아갈 경로로 바꾸고 서버 컴포넌트를 다시 그린다. 이동하는 동안 버튼은 막힌 채다.
 * 시도 제한(429 `AUTH_040`)이면 안내와 함께 `Retry-After` 동안 제출을 막고 버튼에 남은 시간을 보인다(#407).
 * 로그인 화면에 오면(로그아웃 · 세션 만료 · 계정 전환) 이전 운영자의 안내 투어 기록(#228)을 지운다 — 다른 운영자에게 보이지 않게.
 * OTP 를 등록한 관리자는 1단계 성공이 쿠키 대신 챌린지를 준다(#414) — state 에만 두고 `OtpForm` 으로 2단계를 받는다.
 * 2단계가 챌린지를 더 쓸 수 없다고 하면(만료 · `AUTH_034` · `AUTH_044`) 이 폼으로 돌아와 그 안내를 보인다. 이메일은 채워 둔다.
 */
export function LoginForm({ returnPath }: { returnPath: string }) {
  const router = useRouter();
  const [submitting, setSubmitting] = useState(false);
  const [errors, setErrors] = useState<LoginErrors>(NO_LOGIN_ERRORS);
  const cooldown = useLoginCooldown();
  const [challenge, setChallenge] = useState<OtpChallenge | null>(null);
  const [email, setEmail] = useState('');

  // 시작 체크리스트(#358) 업무 상태 · 카드 접기도 같이 지운다
  useEffect(() => {
    tourProgress.signOut();
    onboardingTasks.signOut();
  }, []);

  const onSubmit = async (event: FormEvent<HTMLFormElement>) => {
    event.preventDefault();
    if (submitting || cooldown.remainingSec > 0) return;
    const data = new FormData(event.currentTarget);
    const submittedEmail = String(data.get('email') ?? '');
    setSubmitting(true);
    setErrors(NO_LOGIN_ERRORS);
    try {
      const result = await apiFetch<unknown>('/api/v1/auth/admin/login', {
        method: 'POST',
        json: { email: submittedEmail, password: String(data.get('password') ?? '') },
      });
      const next = readOtpChallenge(result);
      if (next) {
        setEmail(submittedEmail);
        setChallenge(next);
        setSubmitting(false);
        return;
      }
      enterConsole();
    } catch (cause) {
      setSubmitting(false);
      cooldown.startFrom(cause);
      setErrors(loginErrors(cause));
    }
  };

  const enterConsole = () => {
    router.replace(returnPath);
    router.refresh();
  };

  // 챌린지를 버리고 1단계로 — 비밀번호 칸은 다시 그려져 비고, 이메일은 남긴다
  const restart = useCallback((notice: string) => {
    setChallenge(null);
    setErrors(notice ? { ...NO_LOGIN_ERRORS, form: [notice] } : NO_LOGIN_ERRORS);
  }, []);

  if (challenge) {
    return <OtpForm challenge={challenge} onSuccess={enterConsole} onRestart={restart} />;
  }

  return (
    <form onSubmit={onSubmit} className="flex flex-col gap-5">
      <TextField
        label="이메일"
        name="email"
        type="email"
        autoComplete="username"
        inputMode="email"
        maxLength={254}
        defaultValue={email}
        required
        errors={errors.email}
      />
      <TextField
        label="비밀번호"
        name="password"
        type="password"
        autoComplete="current-password"
        required
        errors={errors.password}
      />
      {/* 폼 전체 오류는 버튼 바로 위 — 누른 자리 가까이에서 읽힌다 */}
      <FieldError messages={errors.form} />
      <Button
        type="submit"
        disabled={submitting || cooldown.remainingSec > 0}
        className="h-11 w-full"
      >
        {submitLabel(submitting, cooldown.remainingSec)}
      </Button>
    </form>
  );
}

/** 제출 버튼 문구 — 보내는 중 · 시도 제한 남은 시간(1분 이상은 분으로 올림) · 기본 */
export function submitLabel(submitting: boolean, remainingSec: number): string {
  if (submitting) return '로그인하는 중…';
  if (remainingSec > 0) return `${formatWait(remainingSec)} 뒤에 다시 시도할 수 있습니다`;
  return '로그인';
}
