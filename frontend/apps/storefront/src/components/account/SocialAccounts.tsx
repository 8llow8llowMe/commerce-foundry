'use client';

import { ApiError, apiFetch } from '@cf/api-client';
import { Button, FieldError, TextField } from '@cf/ui';
import { useCallback, useEffect, useState, type FormEvent } from 'react';
import { useDictionary, useLocalePrefix } from '@/components/i18n/LocaleProvider';
import { navigateToProvider } from '@/components/auth/SocialLoginButtons';
import { SOCIAL_PROVIDERS, sessionFlowStorage, type SocialProvider } from '@/lib/auth/social';
import {
  SOCIAL_ACCOUNTS_PATH,
  SOCIAL_LINK_PAGE,
  readLinkedProviders,
  socialLinkStartErrorText,
  startSocialLink,
} from '@/lib/auth/social-link';
import { fill } from '@/lib/i18n/format';
import { useRetryCooldown } from '@/lib/use-retry-cooldown';
import { SignedOutNotice } from './SignedOutNotice';

type Load =
  | { status: 'loading' }
  | { status: 'failed' }
  | { status: 'signedOut' }
  | { status: 'loaded'; providers: SocialProvider[] };

/** 재인증 + 연결용 인가 URL 발급. 비밀번호는 이 요청 본문에만 싣는다(일반 BFF 프록시, 응답에 토큰 없음) */
export function requestLinkAuthorizeUrl(
  provider: SocialProvider,
  currentPassword: string,
): Promise<unknown> {
  return apiFetch<unknown>(`${SOCIAL_ACCOUNTS_PATH}/${provider}/authorize-url`, {
    method: 'POST',
    json: { currentPassword },
  });
}

/**
 * 간편 로그인 연결(#356 · BE #355). 연결 목록을 읽어 제공자마다 연결 여부를 보이고, 연결 안 된 제공자는
 * 현재 비밀번호 확인 → 제공자 인가 화면으로 보낸다(`startSocialLink`). 제공자별 1개 연결이며 연결 해제는 범위 밖이다.
 * 비밀번호는 이 컴포넌트 상태에만 잠깐 있다 — 보내면 바로 비우고, 입력 칸에 name 을 주지 않아 폼 제출로 주소에 실리지 않는다.
 * `linked` 는 연결 콜백이 성공 뒤 넘긴 제공자다(완료 안내).
 */
export function SocialAccounts({ linked }: { linked: SocialProvider | null }) {
  const dict = useDictionary();
  const t = dict.auth;
  const texts = t.socialLink;
  const account = dict.account;
  const localePrefix = useLocalePrefix();
  const [load, setLoad] = useState<Load>({ status: 'loading' });
  const [open, setOpen] = useState<SocialProvider | null>(null);
  const [password, setPassword] = useState('');
  const [pending, setPending] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const cooldown = useRetryCooldown();

  const reload = useCallback(
    () =>
      apiFetch<unknown>(SOCIAL_ACCOUNTS_PATH).then(
        (value) => {
          const providers = readLinkedProviders(value);
          setLoad(providers ? { status: 'loaded', providers } : { status: 'failed' });
        },
        (cause: unknown) => {
          setLoad({
            status:
              cause instanceof ApiError && cause.kind === 'unauthorized' ? 'signedOut' : 'failed',
          });
        },
      ),
    [],
  );

  useEffect(() => {
    void reload();
  }, [reload]);

  // 제공자 화면에서 뒤로 와 bfcache 로 되살아나면 "이동 중" 인 채로 멈춰 있지 않게 푼다
  useEffect(() => {
    const onShow = (event: PageTransitionEvent) => {
      if (event.persisted) setPending(false);
    };
    window.addEventListener('pageshow', onShow);
    return () => window.removeEventListener('pageshow', onShow);
  }, []);

  if (load.status === 'signedOut') return <SignedOutNotice returnPath={SOCIAL_LINK_PAGE} />;
  if (load.status === 'failed') {
    return (
      <div className="flex flex-col items-center gap-4 py-16 text-center">
        <p role="alert" className="text-sm text-muted-fg">
          {account.loadFailed}
        </p>
        <Button
          variant="secondary"
          onClick={() => {
            setLoad({ status: 'loading' });
            void reload();
          }}
        >
          {account.reload}
        </Button>
      </div>
    );
  }
  if (load.status === 'loading') {
    return (
      <p role="status" className="py-16 text-center text-sm text-muted-fg">
        {account.loading}
      </p>
    );
  }

  const close = () => {
    setOpen(null);
    setPassword('');
    setError(null);
  };

  const onSubmit = async (event: FormEvent<HTMLFormElement>) => {
    event.preventDefault();
    if (!open || pending || cooldown.coolingDown) return;
    const currentPassword = password;
    // 보내는 순간 비운다 — 실패해도 다시 입력받는다
    setPassword('');
    setError(null);
    setPending(true);
    try {
      await startSocialLink({
        provider: open,
        currentPassword,
        localePrefix,
        storage: sessionFlowStorage(),
        requestAuthorizeUrl: requestLinkAuthorizeUrl,
        navigate: navigateToProvider,
      });
    } catch (cause) {
      setPending(false);
      if (cause instanceof ApiError && cause.kind === 'unauthorized') {
        setLoad({ status: 'signedOut' });
        return;
      }
      cooldown.startFrom(cause);
      setError(socialLinkStartErrorText(cause, t));
    }
  };

  return (
    <div className="flex flex-col gap-6">
      <p className="text-sm text-muted-fg">{texts.description}</p>
      {linked && load.providers.includes(linked) && (
        <p role="status" className="bg-muted px-5 py-4 text-sm">
          {fill(texts.done, { provider: texts.providers[linked] })}
        </p>
      )}
      <ul className="flex flex-col gap-2">
        {SOCIAL_PROVIDERS.map((provider) => {
          const name = texts.providers[provider];
          const isLinked = load.providers.includes(provider);
          return (
            <li key={provider} className="flex flex-col gap-4 bg-muted px-5 py-5">
              <div className="flex items-center justify-between gap-4">
                <div className="flex flex-col gap-1">
                  <span>{name}</span>
                  <span className="text-sm text-muted-fg">
                    {isLinked ? texts.linked : texts.notLinked}
                  </span>
                </div>
                {!isLinked && open !== provider && (
                  <Button
                    variant="secondary"
                    aria-label={fill(texts.linkItem, { provider: name })}
                    disabled={pending}
                    onClick={() => {
                      setOpen(provider);
                      setPassword('');
                      setError(null);
                    }}
                  >
                    {texts.link}
                  </Button>
                )}
              </div>
              {!isLinked && open === provider && (
                // method="post" + name 없는 입력 칸: 스크립트 전에 제출돼도 비밀번호가 주소에 실리지 않는다
                <form method="post" onSubmit={onSubmit} className="flex flex-col gap-4">
                  <h2 className="text-base font-medium">
                    {fill(texts.passwordTitle, { provider: name })}
                  </h2>
                  <p className="text-sm text-muted-fg">
                    {fill(texts.passwordNote, { provider: name })}
                  </p>
                  <TextField
                    label={texts.password}
                    type="password"
                    autoComplete="current-password"
                    maxLength={64}
                    required
                    value={password}
                    onChange={(event) => setPassword(event.currentTarget.value)}
                  />
                  <FieldError messages={error ? [error] : []} />
                  <div className="flex gap-2">
                    <Button
                      type="submit"
                      disabled={pending || cooldown.coolingDown}
                      className="h-12 flex-1"
                    >
                      {pending ? texts.starting : texts.submit}
                    </Button>
                    <Button
                      type="button"
                      variant="secondary"
                      disabled={pending}
                      onClick={close}
                      className="h-12"
                    >
                      {texts.cancel}
                    </Button>
                  </div>
                </form>
              )}
            </li>
          );
        })}
      </ul>
      <p className="text-sm text-muted-fg">{texts.passwordOnly}</p>
    </div>
  );
}
