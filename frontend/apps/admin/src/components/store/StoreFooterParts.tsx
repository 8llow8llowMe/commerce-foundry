'use client';

import { Button, Checkbox, cx, FieldError, FooterChannelIcon, TextField } from '@cf/ui';
import { useSortable } from '@dnd-kit/sortable';
import { CSS } from '@dnd-kit/utilities';
import { useId } from 'react';
import type { FormErrors } from '@/lib/api-errors';
import { urlProblem, type FooterForm, type LinkRow } from '@/lib/store-footer/form';
import {
  FOOTER_LINK_TYPES,
  LIMITS,
  TYPE_LABELS,
  type FooterLinkType,
} from '@/lib/store-footer/types';

const control =
  'h-10 rounded-md border border-border bg-bg px-3 text-fg outline-none focus:ring-2 focus:ring-primary';
const linkButton =
  'text-sm underline-offset-4 hover:underline disabled:cursor-not-allowed disabled:opacity-50';

export const URL_HINTS: Record<FooterLinkType, string> = {
  INSTAGRAM: '예: https://www.instagram.com/계정이름',
  KAKAO_CHANNEL: '예: https://pf.kakao.com/_채널ID (상담 바로가기는 끝에 /chat)',
  CUSTOM: '운영 설정에 등록된 도메인의 https 주소만 저장됩니다.',
};

export const DRAG_INSTRUCTIONS =
  '스페이스 또는 엔터로 링크를 집고, 위 · 아래 화살표로 옮긴 뒤 스페이스 또는 엔터로 놓습니다. Esc 로 취소합니다.';

/** 드래그 중 스크린리더 안내 — 자리는 1부터 센다 */
export const dragAnnouncements = {
  start: (name: string) => `${name} 링크를 집었습니다.`,
  over: (name: string, position: number) =>
    `${name} 링크를 ${position}번째 자리로 옮기는 중입니다.`,
  end: (name: string, position: number) =>
    `${name} 링크를 ${position}번째 자리에 놓았습니다. 초안을 저장하고 게시해야 스토어프론트에 반영됩니다.`,
  cancel: (name: string) => `${name} 링크 옮기기를 취소했습니다.`,
};

/** 줄 이름 — 국문 표시명, 비었으면 유형 */
export const rowName = (row: LinkRow) => row.labelKo.trim() || TYPE_LABELS[row.type];

export type LinkRowEditorProps = {
  row: LinkRow;
  index: number;
  total: number;
  errors: FormErrors;
  locked: boolean;
  onChange: (row: LinkRow) => void;
  onMove: (to: number) => void;
  onRemove: () => void;
};

/**
 * 링크 한 줄. 손잡이를 끌거나(마우스 · 터치) 손잡이에 포커스를 두고 스페이스 · 화살표로 옮긴다(dnd-kit 키보드 정렬).
 * 같은 일을 위 · 아래 버튼으로도 할 수 있다(메인 전시 `SectionRow` 와 같은 방식).
 */
export function LinkRowEditor({
  row,
  index,
  total,
  errors,
  locked,
  onChange,
  onMove,
  onRemove,
}: LinkRowEditorProps) {
  const id = useId();
  const name = rowName(row);
  const {
    attributes,
    listeners,
    setNodeRef,
    setActivatorNodeRef,
    transform,
    transition,
    isDragging,
  } = useSortable({
    id: row.key,
    disabled: locked,
    attributes: { roleDescription: '순서 바꾸기 손잡이' },
  });
  const at = (field: string) => errors[`links.${index}.${field}`] ?? [];

  return (
    <li
      ref={setNodeRef}
      style={{ transform: CSS.Translate.toString(transform), transition }}
      className={cx('relative', isDragging && 'z-10')}
    >
      <fieldset
        aria-label={`${index + 1}번째 링크: ${name}`}
        className={cx(
          'flex flex-col gap-3 rounded-md border border-border bg-bg px-3 py-3',
          !row.visible && 'bg-muted/50',
          isDragging && 'shadow-md',
        )}
      >
        <div className="flex flex-wrap items-center gap-2">
          <button
            type="button"
            ref={setActivatorNodeRef}
            {...attributes}
            {...listeners}
            data-move={`drag:${row.key}`}
            aria-label={`${name} 순서 바꾸기`}
            disabled={locked}
            className="flex size-8 cursor-grab touch-none items-center justify-center rounded text-muted-fg hover:bg-muted disabled:cursor-not-allowed disabled:opacity-50"
          >
            <span aria-hidden>⠿</span>
          </button>
          <span className="text-sm font-medium">
            {index + 1}. {name}
          </span>
          {!row.visible && <span className="text-xs text-muted-fg">(숨김)</span>}
          <div className="ml-auto flex items-center gap-3">
            <button
              type="button"
              data-move={`up:${row.key}`}
              className={linkButton}
              disabled={locked || index === 0}
              aria-label={`${name} 위로`}
              onClick={() => onMove(index - 1)}
            >
              위로
            </button>
            <button
              type="button"
              data-move={`down:${row.key}`}
              className={linkButton}
              disabled={locked || index === total - 1}
              aria-label={`${name} 아래로`}
              onClick={() => onMove(index + 1)}
            >
              아래로
            </button>
            <button
              type="button"
              className={cx(linkButton, 'text-danger')}
              disabled={locked}
              aria-label={`${name} 삭제`}
              onClick={onRemove}
            >
              삭제
            </button>
          </div>
        </div>

        <div className="grid gap-3 md:grid-cols-[12rem_1fr_1fr]">
          <div className="flex flex-col gap-1">
            <label htmlFor={`${id}-type`} className="text-sm font-medium">
              유형
            </label>
            <select
              id={`${id}-type`}
              value={row.type}
              onChange={(event) =>
                onChange({ ...row, type: event.currentTarget.value as FooterLinkType })
              }
              className={control}
            >
              {FOOTER_LINK_TYPES.map((type) => (
                <option key={type} value={type}>
                  {TYPE_LABELS[type]}
                </option>
              ))}
            </select>
          </div>
          <TextField
            label="표시명 (국문)"
            value={row.labelKo}
            maxLength={LIMITS.label}
            errors={at('labels.ko')}
            onChange={(event) => onChange({ ...row, labelKo: event.currentTarget.value })}
          />
          <TextField
            label="표시명 (영문)"
            value={row.labelEn}
            maxLength={LIMITS.label}
            errors={at('labels.en')}
            onChange={(event) => onChange({ ...row, labelEn: event.currentTarget.value })}
          />
        </div>

        <UrlField
          id={`${id}-url`}
          type={row.type}
          value={row.url}
          errors={at('url')}
          onChange={(url) => onChange({ ...row, url })}
        />

        <Checkbox
          label="스토어프론트에 노출"
          checked={row.visible}
          onChange={(event) => onChange({ ...row, visible: event.currentTarget.checked })}
        />
      </fieldset>
    </li>
  );
}

/** 주소 칸 — 저장 전에도 입력하는 대로 형식 안내를 보인다(빈 칸은 저장할 때 알린다) */
function UrlField({
  id,
  type,
  value,
  errors,
  onChange,
}: {
  id: string;
  type: FooterLinkType;
  value: string;
  errors: string[];
  onChange: (value: string) => void;
}) {
  const live = value === '' ? null : urlProblem(type, value);
  const messages = errors.length > 0 ? errors : live ? [live] : [];
  return (
    <div className="flex flex-col gap-1">
      <label htmlFor={id} className="text-sm font-medium">
        주소
      </label>
      <input
        id={id}
        type="url"
        inputMode="url"
        autoComplete="off"
        spellCheck={false}
        value={value}
        maxLength={LIMITS.url}
        aria-invalid={messages.length > 0 || undefined}
        aria-describedby={`${id}-hint${messages.length > 0 ? ` ${id}-error` : ''}`}
        onChange={(event) => onChange(event.currentTarget.value)}
        className={cx(control, 'font-mono text-sm', messages.length > 0 && 'border-danger')}
      />
      <p id={`${id}-hint`} className="text-xs text-muted-fg">
        {URL_HINTS[type]}
      </p>
      <FieldError id={`${id}-error`} messages={messages} />
    </div>
  );
}

export type PreviewDevice = 'pc' | 'mobile';
export type PreviewLocale = 'ko' | 'en';

/**
 * 저장 · 게시 없이 지금 입력(form)으로 그리는 푸터 미리보기. 노출 링크만, 화면 순서대로.
 * 회사 · 고객센터 정보와 약관 링크는 각 설정 화면의 값이 들어갈 자리만 보인다(여기서 복제하지 않는다).
 * 링크는 눌러도 이동하지 않는다. 주소 형식이 틀린 링크는 스토어프론트에서 그려지지 않으므로 표시해 둔다.
 */
export function FooterPreview({
  form,
  device,
  locale,
}: {
  form: FooterForm;
  device: PreviewDevice;
  locale: PreviewLocale;
}) {
  const shown = form.links.filter((row) => row.visible);
  const copyright = (locale === 'ko' ? form.copyrightKo : form.copyrightEn).trim();
  const mobile = device === 'mobile';

  return (
    <div className="overflow-x-auto rounded-md border border-border bg-muted/30 p-4">
      <div
        data-device={device}
        className={cx(
          'mx-auto flex flex-col gap-6 rounded-md bg-muted px-4 py-8 text-fg',
          mobile ? 'w-[375px] max-w-full' : 'w-full min-w-[720px]',
        )}
      >
        <div className={cx('flex gap-6', mobile ? 'flex-col' : 'items-start justify-between')}>
          <p className="text-xs text-muted-fg">
            {locale === 'ko' ? '이용약관 · 개인정보처리방침' : 'Terms · Privacy'}
            <span className="block">(약관 설정에서 게시한 문서 링크)</span>
          </p>
          {shown.length > 0 ? (
            <ul
              aria-label="채널 · 링크 미리보기"
              className="flex flex-wrap items-center gap-x-4 gap-y-3 text-sm"
            >
              {shown.map((row) => {
                const label = (locale === 'ko' ? row.labelKo : row.labelEn).trim();
                const broken = urlProblem(row.type, row.url) !== null || label === '';
                return (
                  <li key={row.key} className="flex items-center gap-1.5">
                    <FooterChannelIcon type={row.type} />
                    <span className={cx(row.type !== 'CUSTOM' && 'sr-only')}>
                      {label || TYPE_LABELS[row.type]}
                    </span>
                    {broken && (
                      <span className="rounded bg-danger/10 px-1 text-xs text-danger">
                        확인 필요 · 공개 안 됨
                      </span>
                    )}
                  </li>
                );
              })}
            </ul>
          ) : (
            <p className="text-xs text-muted-fg">노출할 채널 · 링크가 없습니다.</p>
          )}
        </div>
        <p className="text-xs text-muted-fg">
          (쇼핑몰 정보 설정의 상호 · 대표 · 사업자 번호 · 주소 · 고객센터)
        </p>
        <p className="text-xs tracking-[0.15em] text-muted-fg">
          {copyright || '© 연도 쇼핑몰 이름 (저작권 문구가 비면 기본 표기)'}
        </p>
      </div>
      <p className="mt-2 text-xs text-muted-fg">
        아이콘 링크(Instagram · 카카오톡 채널)는 표시명이 화면에 보이지 않고 스크린리더 이름으로
        읽힙니다. 기타 링크는 아이콘과 표시명이 함께 보입니다.
      </p>
    </div>
  );
}

/** 게시 확인 — 게시할 리비전과 바뀌는 범위를 다시 보인다 */
export function PublishConfirm({
  revisionNumber,
  pending,
  onCancel,
  onPublish,
}: {
  revisionNumber: number;
  pending: boolean;
  onCancel: () => void;
  onPublish: () => void;
}) {
  const id = useId();
  return (
    <div
      role="alertdialog"
      aria-labelledby={`${id}-title`}
      className="flex flex-col gap-3 rounded-md border border-primary px-4 py-3"
    >
      <p id={`${id}-title`} className="text-sm font-medium">
        {revisionNumber}번 초안을 게시할까요? 스토어프론트 국문 · 영문 푸터의 채널 링크와 저작권
        문구가 바로 바뀝니다.
      </p>
      <div className="flex gap-2">
        <Button onClick={onPublish} disabled={pending}>
          {pending ? '게시 중…' : '게시'}
        </Button>
        <Button variant="secondary" onClick={onCancel} disabled={pending}>
          취소
        </Button>
      </div>
    </div>
  );
}
