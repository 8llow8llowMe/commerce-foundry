'use client';

import { Button, FieldError, TextField } from '@cf/ui';
import type { ReactNode } from 'react';
import { AccessNotice } from '@/components/AccessNotice';
import { LOAD_FAILED } from '@/lib/api-errors';
import type { StoreProfileForm } from '@/lib/store-profile/form';
import { FIELDS, type StoreProfileField } from '@/lib/store-profile/types';
import {
  useStoreProfileEditor,
  type Comparison,
  type StoreProfileEditor as Editor,
} from '@/lib/store-profile/use-store-profile-editor';

const SECTIONS: { title: string; description: string; keys: StoreProfileField[] }[] = [
  {
    title: '쇼핑몰',
    description:
      '브라우저 탭 제목과 푸터 저작권 표시에 쓰는 이름입니다. 로고 · 폰트 · 색은 브랜드 설정 그대로입니다.',
    keys: ['storeName'],
  },
  {
    title: '사업자 정보',
    description:
      '푸터에 법정 표시 정보로 공개됩니다. 국가 코드는 ISO 영문 2자리(예: KR, US, JP)이고, 등록번호 표기와 확인 링크는 국가에 맞춰 바뀝니다.',
    keys: [
      'companyName',
      'representativeName',
      'countryCode',
      'businessRegistrationNumber',
      'mailOrderRegistrationNumber',
      'address',
    ],
  },
  {
    title: '고객센터',
    description: '푸터 고객센터 칸에 공개됩니다. 전화 · 운영 시간은 비우면 보이지 않습니다.',
    keys: ['customerServiceEmail', 'customerServicePhone', 'customerServiceHours'],
  },
];

const INPUT: Partial<
  Record<StoreProfileField, { type?: string; autoComplete?: string; inputMode?: 'tel' | 'email' }>
> = {
  customerServiceEmail: { type: 'email', inputMode: 'email', autoComplete: 'off' },
  customerServicePhone: { type: 'tel', inputMode: 'tel', autoComplete: 'off' },
};

/**
 * 쇼핑몰 설정 > 쇼핑몰 정보(#568) — `GET · PUT /api/v1/admin/store/profile`(#566).
 * 저장 즉시 스토어프론트 푸터 · 표시 이름에 공개된다. 내부 version 은 보이지 않고 저장 때만 보낸다.
 */
export function StoreProfileEditor() {
  const editor = useStoreProfileEditor();
  const { status, form } = editor;

  return (
    <div className="flex max-w-3xl flex-col gap-6">
      <div>
        <h1 className="text-xl font-semibold">쇼핑몰 정보</h1>
        {status.kind === 'ready' && form?.version === null && (
          <p className="mt-1 text-sm text-muted-fg">
            아직 등록한 쇼핑몰 정보가 없습니다. 등록 전에는 스토어프론트 푸터에 사업자 정보가 보이지
            않습니다.
          </p>
        )}
      </div>

      {status.kind === 'loading' && <p className="text-sm text-muted-fg">불러오는 중…</p>}
      {status.kind === 'denied' && <AccessNotice denied={status.denied} />}
      {status.kind === 'loadFailed' && (
        <div role="alert" className="flex items-center gap-4">
          <p className="text-sm text-danger">{LOAD_FAILED}</p>
          <Button variant="secondary" onClick={() => void editor.reload()}>
            다시 불러오기
          </Button>
        </div>
      )}
      {status.kind === 'ready' && form && <ProfileForm editor={editor} form={form} />}
    </div>
  );
}

function ProfileForm({ editor, form }: { editor: Editor; form: StoreProfileForm }) {
  const set = (key: StoreProfileField, value: string) => editor.update({ ...form, [key]: value });

  return (
    <form
      noValidate
      onSubmit={(event) => {
        event.preventDefault();
        void editor.save();
      }}
      className="flex flex-col gap-6"
    >
      {/* 저장 중에는 칸을 잠근다 — 성공 응답이 폼을 서버 값으로 바꾸므로 그사이 친 값이 말없이 사라지지 않게 */}
      <fieldset disabled={editor.saving} className="flex min-w-0 flex-col gap-6">
        {SECTIONS.map((section) => (
          <Section key={section.title} title={section.title} description={section.description}>
            <div className="grid gap-4 sm:grid-cols-2">
              {section.keys.map((key) => {
                const field = FIELDS.find((item) => item.key === key)!;
                const wide = key === 'address' || key === 'customerServiceHours';
                return (
                  <TextField
                    key={key}
                    className={wide ? 'sm:col-span-2' : undefined}
                    label={field.required ? field.label : `${field.label} (선택)`}
                    value={form[key]}
                    maxLength={field.max}
                    required={field.required}
                    {...INPUT[key]}
                    onChange={(event) => {
                      const value = event.currentTarget.value;
                      set(key, key === 'countryCode' ? value.toUpperCase() : value);
                    }}
                    errors={editor.errors[key] ?? []}
                  />
                );
              })}
            </div>
          </Section>
        ))}
      </fieldset>

      {editor.comparison && (
        <ComparisonPanel comparison={editor.comparison} onUseLatest={editor.applyLatest} />
      )}

      <div className="flex flex-col gap-3">
        <p className="text-sm text-muted-fg">
          저장하면 위 항목이 스토어프론트 모든 화면의 푸터에 바로 공개됩니다.
        </p>
        <FieldError messages={editor.errors.form ?? []} />
        {editor.notice && (
          <p role="status" className="text-sm">
            {editor.notice}
          </p>
        )}
        <Button type="submit" disabled={editor.saving} className="self-start">
          {editor.saving ? '저장 중…' : '공개 정보 저장'}
        </Button>
      </div>
    </form>
  );
}

function Section({
  title,
  description,
  children,
}: {
  title: string;
  description: string;
  children: ReactNode;
}) {
  return (
    <section className="flex flex-col gap-4 rounded-md bg-muted/50 p-5">
      <div>
        <h2 className="font-semibold">{title}</h2>
        <p className="mt-1 text-sm text-muted-fg">{description}</p>
      </div>
      {children}
    </section>
  );
}

/** 저장이 막힌 뒤 — 내 입력과 다른 곳에서 먼저 저장한 최신 값을 칸별로 나란히 */
export function ComparisonPanel({
  comparison,
  onUseLatest,
}: {
  comparison: Comparison;
  onUseLatest: () => void;
}) {
  return (
    <section
      aria-label="최신 값과 비교"
      className="flex flex-col gap-3 rounded-md border border-border p-5"
    >
      <h2 className="font-semibold">최신 값과 비교</h2>
      {comparison.diffs.length === 0 ? (
        <p className="text-sm text-muted-fg">
          최신 값과 입력한 값이 같습니다. 다시 저장하지 않아도 됩니다.
        </p>
      ) : (
        <div className="overflow-x-auto">
          <table className="w-full text-left text-sm">
            <thead className="text-muted-fg">
              <tr>
                <th scope="col" className="py-1 pr-4 font-medium">
                  항목
                </th>
                <th scope="col" className="py-1 pr-4 font-medium">
                  입력한 값
                </th>
                <th scope="col" className="py-1 font-medium">
                  최신 값
                </th>
              </tr>
            </thead>
            <tbody>
              {comparison.diffs.map((diff) => (
                <tr key={diff.key} className="border-t border-border align-top">
                  <th scope="row" className="py-2 pr-4 font-medium">
                    {diff.label}
                  </th>
                  <td className="py-2 pr-4 break-all">{diff.mine || '(비움)'}</td>
                  <td className="py-2 break-all">{diff.latest || '(비움)'}</td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      )}
      <p className="text-sm text-muted-fg">
        입력한 값으로 저장하려면 아래 &apos;공개 정보 저장&apos;을 다시 누르세요.
      </p>
      <Button variant="secondary" className="self-start" onClick={onUseLatest}>
        최신 값으로 바꾸기
      </Button>
    </section>
  );
}
