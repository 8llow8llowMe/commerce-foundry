'use client';

import { Button, Checkbox, cx, FieldError, TextField } from '@cf/ui';
import { useId, useState } from 'react';
import type { FormErrors } from '@/lib/api-errors';
import {
  externalUrlProblem,
  nodeName,
  pathProblem,
  targetProblem,
} from '@/lib/store-navigation/form';
import { flatten, sizeOf, type MenuNode, type MoveDirection } from '@/lib/store-navigation/tree';
import { LIMITS, NAV_TYPES, TYPE_LABELS, type NavType } from '@/lib/store-navigation/types';

const control =
  'h-10 rounded-md border border-border bg-bg px-3 text-fg outline-none focus:ring-2 focus:ring-primary';
const linkButton =
  'text-sm underline-offset-4 hover:underline disabled:cursor-not-allowed disabled:opacity-50';

/** 카테고리 고르기 — 어드민 카테고리 트리(숨김 포함)를 편 목록. 불러오지 못하면 번호 입력으로 바꾼다 */
export type CategoryOption = { id: string; label: string; hidden: boolean };
export type CategoryChoices =
  { kind: 'loading' } | { kind: 'failed' } | { kind: 'ready'; options: CategoryOption[] };

export const POSITION_LABELS: Record<MoveDirection, string> = {
  up: '위로',
  down: '아래로',
  indent: '하위로 (들여쓰기)',
  outdent: '상위로 (내어쓰기)',
};

export type ItemDetailProps = {
  node: MenuNode;
  /** 0 부터(최상위 0) */
  depth: number;
  /** 저장 본문 번호 — 오류 칸 키 `items.{index}.*` */
  index: number;
  errors: FormErrors;
  locked: boolean;
  categories: CategoryChoices;
  /** 방향마다 옮길 수 있는가 */
  movable: Record<MoveDirection, boolean>;
  /** 하위 메뉴를 더할 수 있는가(깊이 · 형제 · 전체 상한) */
  canAddChild: boolean;
  onChange: (node: MenuNode) => void;
  onMove: (direction: MoveDirection) => void;
  onAddChild: () => void;
  onRemove: () => void;
};

/**
 * 고른 항목의 상세 — 표시명 · 설명(국문 · 영문), 링크 유형과 대상, 노출 · 1차 메뉴 · 바로가기, 위치 버튼, 하위 추가 · 삭제.
 * 위치 버튼은 끌기 · `Alt+화살표` 와 같은 일을 한다 — 마우스 · 터치만으로도, 끌기를 모르는 사람도 옮길 수 있게.
 */
export function ItemDetail({
  node,
  depth,
  index,
  errors,
  locked,
  categories,
  movable,
  canAddChild,
  onChange,
  onMove,
  onAddChild,
  onRemove,
}: ItemDetailProps) {
  const id = useId();
  const [confirming, setConfirming] = useState(false);
  const at = (field: string) => errors[`items.${index}.${field}`] ?? [];
  const name = nodeName(node);
  const descendants = sizeOf(node) - 1;

  return (
    <fieldset
      aria-label={`선택한 항목: ${name}`}
      disabled={locked}
      className="flex min-w-0 flex-col gap-5"
    >
      <div className="grid gap-3 sm:grid-cols-2">
        <TextField
          label="표시명 (한국어)"
          value={node.labelKo}
          maxLength={LIMITS.label}
          errors={at('labels.ko')}
          onChange={(event) => onChange({ ...node, labelKo: event.currentTarget.value })}
        />
        <TextField
          label="표시명 (영어)"
          value={node.labelEn}
          maxLength={LIMITS.label}
          errors={at('labels.en')}
          onChange={(event) => onChange({ ...node, labelEn: event.currentTarget.value })}
        />
      </div>

      <div className="flex flex-col gap-2">
        <p className="text-xs text-muted-fg">
          설명(선택) — 전체 메뉴에서 최상위 대제목 아래 한 줄로 보입니다. 쓰려면 두 언어 모두
          적습니다. 언어별 {LIMITS.description}자 이내.
        </p>
        <div className="grid gap-3 sm:grid-cols-2">
          <TextField
            label="설명 (한국어)"
            value={node.descriptionKo}
            maxLength={LIMITS.description}
            errors={at('descriptions.ko')}
            onChange={(event) => onChange({ ...node, descriptionKo: event.currentTarget.value })}
          />
          <TextField
            label="설명 (영어)"
            value={node.descriptionEn}
            maxLength={LIMITS.description}
            errors={at('descriptions.en')}
            onChange={(event) => onChange({ ...node, descriptionEn: event.currentTarget.value })}
          />
        </div>
      </div>

      <fieldset className="flex flex-col gap-2">
        <legend className="mb-2 text-sm font-medium">링크</legend>
        <div className="flex flex-wrap gap-x-5 gap-y-2">
          {NAV_TYPES.map((type) => (
            <label key={type} className="flex cursor-pointer items-center gap-2 text-sm">
              <input
                type="radio"
                name={`${id}-type`}
                value={type}
                checked={node.type === type}
                onChange={() => onChange({ ...node, type: type as NavType })}
                className="size-4 accent-primary"
              />
              {TYPE_LABELS[type]}
            </label>
          ))}
        </div>
        <TargetField
          id={`${id}-target`}
          node={node}
          errors={errors}
          index={index}
          categories={categories}
          onChange={onChange}
        />
      </fieldset>

      <div className="flex flex-col gap-2">
        <Checkbox
          label="스토어프론트에 노출"
          checked={node.visible}
          onChange={(event) => onChange({ ...node, visible: event.currentTarget.checked })}
        />
        {!node.visible && node.children.length > 0 && (
          <p className="text-xs text-muted-fg">숨기면 하위 메뉴도 함께 보이지 않습니다.</p>
        )}
        {depth === 0 ? (
          <>
            <Checkbox
              label="PC 1차 메뉴에 보이기 (로고 아래 줄)"
              checked={node.primary}
              onChange={(event) => onChange({ ...node, primary: event.currentTarget.checked })}
            />
            <Checkbox
              label="모바일 바로가기에 보이기 (헤더 아래 줄)"
              checked={node.shortcut}
              onChange={(event) => onChange({ ...node, shortcut: event.currentTarget.checked })}
            />
            {node.type === 'NONE' && (node.primary || node.shortcut) && (
              <p className="text-xs text-muted-fg">
                링크 없는 묶음 제목은 1차 메뉴 · 바로가기 줄에 나오지 않고 전체 메뉴에만 보입니다.
              </p>
            )}
          </>
        ) : (
          <p className="text-xs text-muted-fg">
            PC 1차 메뉴 · 모바일 바로가기는 최상위 메뉴에서만 켤 수 있습니다.
          </p>
        )}
      </div>

      <div className="flex flex-col gap-2">
        <p className="text-sm font-medium">위치</p>
        <div className="flex flex-wrap gap-x-4 gap-y-2">
          {(['up', 'down', 'indent', 'outdent'] as const).map((direction) => (
            <button
              key={direction}
              type="button"
              data-position={`${direction}:${node.key}`}
              className={linkButton}
              disabled={!movable[direction]}
              aria-label={`${name} ${POSITION_LABELS[direction]}`}
              onClick={() => onMove(direction)}
            >
              {POSITION_LABELS[direction]}
            </button>
          ))}
        </div>
      </div>

      <div className="flex flex-wrap items-center gap-3">
        <Button variant="secondary" disabled={!canAddChild} onClick={onAddChild}>
          하위 메뉴 추가
        </Button>
        {confirming ? (
          <div
            role="alertdialog"
            aria-label="항목 삭제 확인"
            className="flex flex-wrap items-center gap-3"
          >
            <span className="text-sm">
              {descendants > 0
                ? `${name}와(과) 하위 메뉴 ${descendants}개를 지울까요?`
                : `${name}을(를) 지울까요?`}
            </span>
            <button
              type="button"
              className={cx(linkButton, 'font-medium text-danger')}
              onClick={onRemove}
            >
              삭제
            </button>
            <button type="button" className={linkButton} onClick={() => setConfirming(false)}>
              취소
            </button>
          </div>
        ) : (
          <button
            type="button"
            className={cx(linkButton, 'text-danger')}
            aria-label={`${name} 삭제`}
            onClick={() => setConfirming(true)}
          >
            삭제
          </button>
        )}
      </div>
      {!canAddChild && depth + 1 >= LIMITS.depth && (
        <p className="text-xs text-muted-fg">
          메뉴는 {LIMITS.depth}단계까지라 이 항목 아래에는 더 둘 수 없습니다.
        </p>
      )}
    </fieldset>
  );
}

/** 유형별 링크 대상 칸. 입력하는 대로 형식 안내를 보인다(빈 칸은 저장할 때 알린다) */
function TargetField({
  id,
  node,
  errors,
  index,
  categories,
  onChange,
}: {
  id: string;
  node: MenuNode;
  errors: FormErrors;
  index: number;
  categories: CategoryChoices;
  onChange: (node: MenuNode) => void;
}) {
  if (node.type === 'NONE')
    return (
      <p className="text-xs text-muted-fg">
        하위 메뉴를 묶는 제목입니다. 고객이 눌러도 이동하지 않습니다.
      </p>
    );

  if (node.type === 'CATEGORY') {
    const server = errors[`items.${index}.categoryId`] ?? [];
    if (categories.kind === 'ready') {
      const known = categories.options.some((option) => option.id === node.categoryId);
      return (
        <div className="flex flex-col gap-1">
          <label htmlFor={id} className="text-sm font-medium">
            카테고리
          </label>
          <select
            id={id}
            value={node.categoryId}
            aria-invalid={server.length > 0 || undefined}
            aria-describedby={server.length > 0 ? `${id}-error` : `${id}-hint`}
            onChange={(event) => onChange({ ...node, categoryId: event.currentTarget.value })}
            className={cx(control, server.length > 0 && 'border-danger')}
          >
            <option value="">카테고리를 고르세요</option>
            {!known && node.categoryId !== '' && (
              <option value={node.categoryId}>{`번호 ${node.categoryId} (목록에 없음)`}</option>
            )}
            {categories.options.map((option) => (
              <option key={option.id} value={option.id}>
                {`${option.label}${option.hidden ? ' (숨김)' : ''}`}
              </option>
            ))}
          </select>
          <p id={`${id}-hint`} className="text-xs text-muted-fg">
            고객 화면에서 그 카테고리의 상품 목록으로 갑니다. 숨긴 카테고리는 고객에게 빈 목록이
            보일 수 있습니다.
          </p>
          <FieldError id={`${id}-error`} messages={server} />
        </div>
      );
    }
    return (
      <TargetInput
        id={id}
        label="카테고리 번호"
        value={node.categoryId}
        inputMode="numeric"
        hint={
          categories.kind === 'loading'
            ? '카테고리 목록을 불러오는 중입니다.'
            : '카테고리 목록을 불러오지 못해 번호로 입력합니다. 번호는 상품 > 카테고리 화면에서 확인합니다.'
        }
        messages={server.length > 0 ? server : node.categoryId === '' ? [] : liveCategory(node)}
        onChange={(categoryId) => onChange({ ...node, categoryId })}
      />
    );
  }

  const server = errors[`items.${index}.url`] ?? [];
  if (node.type === 'INTERNAL') {
    const live = node.path === '' ? null : pathProblem(node.path.trim());
    return (
      <TargetInput
        id={id}
        label="경로"
        value={node.path}
        maxLength={LIMITS.path}
        placeholder="/shop"
        hint="쇼핑몰 안 주소에서 도메인 뒤 부분입니다(예: /shop). 영어 화면의 /en 은 자동으로 붙으니 적지 않습니다."
        messages={server.length > 0 ? server : live ? [live] : []}
        onChange={(path) => onChange({ ...node, path })}
      />
    );
  }
  const live = node.externalUrl === '' ? null : externalUrlProblem(node.externalUrl.trim());
  return (
    <TargetInput
      id={id}
      label="주소"
      value={node.externalUrl}
      maxLength={LIMITS.url}
      inputMode="url"
      placeholder="https://"
      hint="운영 설정에 등록된 사이트의 https 주소만 저장됩니다. 고객 화면에서는 새 창으로 열립니다."
      messages={server.length > 0 ? server : live ? [live] : []}
      onChange={(externalUrl) => onChange({ ...node, externalUrl })}
    />
  );
}

const liveCategory = (node: MenuNode) => {
  const problem = targetProblem(node);
  return problem ? [problem] : [];
};

function TargetInput({
  id,
  label,
  value,
  hint,
  messages,
  maxLength,
  inputMode,
  placeholder,
  onChange,
}: {
  id: string;
  label: string;
  value: string;
  hint: string;
  messages: string[];
  maxLength?: number;
  inputMode?: 'url' | 'numeric';
  placeholder?: string;
  onChange: (value: string) => void;
}) {
  return (
    <div className="flex flex-col gap-1">
      <label htmlFor={id} className="text-sm font-medium">
        {label}
      </label>
      <input
        id={id}
        type="text"
        inputMode={inputMode}
        autoComplete="off"
        spellCheck={false}
        value={value}
        maxLength={maxLength}
        placeholder={placeholder}
        aria-invalid={messages.length > 0 || undefined}
        aria-describedby={`${id}-hint${messages.length > 0 ? ` ${id}-error` : ''}`}
        onChange={(event) => onChange(event.currentTarget.value)}
        className={cx(control, 'font-mono text-sm', messages.length > 0 && 'border-danger')}
      />
      <p id={`${id}-hint`} className="text-xs text-muted-fg">
        {hint}
      </p>
      <FieldError id={`${id}-error`} messages={messages} />
    </div>
  );
}

/**
 * 문서 클릭이 이 화면을 떠나는 앱 안 이동인가 — 저장하지 않은 변경이 있을 때 확인할 클릭이다.
 * 새 창 · 수정 키(새 탭) · 다른 출처 · 같은 화면(같은 경로 · 질의)은 떠나는 것이 아니다.
 */
export function leavesScreen(
  link: { href: string; target: string },
  current: { origin: string; pathname: string; search: string },
  modified: boolean,
): boolean {
  if (modified || (link.target !== '' && link.target !== '_self')) return false;
  let url: URL;
  try {
    url = new URL(link.href, current.origin);
  } catch {
    return false;
  }
  return (
    url.origin === current.origin &&
    (url.pathname !== current.pathname || url.search !== current.search)
  );
}

export type PreviewDevice = 'pc' | 'mobile';
export type PreviewLocale = 'ko' | 'en';

/** 미리보기 · 헤더에 나오는 노드 — 숨김 항목은 하위째 빠진다(서버 공개 트리와 같다) */
export function shownTree(tree: readonly MenuNode[]): MenuNode[] {
  return tree
    .filter((node) => node.visible)
    .map((node) => ({ ...node, children: shownTree(node.children) }));
}

const labelOf = (node: MenuNode, locale: PreviewLocale) =>
  (locale === 'ko' ? node.labelKo : node.labelEn).trim();
const descriptionOf = (node: MenuNode, locale: PreviewLocale) =>
  (locale === 'ko' ? node.descriptionKo : node.descriptionEn).trim();

/** 미리보기 글자 — 표시명이 비었거나 링크 대상이 틀리면 공개 전에 고칠 곳이라 표시해 둔다(링크는 이동하지 않는다) */
function PreviewLabel({
  node,
  locale,
  className,
}: {
  node: MenuNode;
  locale: PreviewLocale;
  className?: string;
}) {
  const label = labelOf(node, locale);
  const broken = label === '' || targetProblem(node) !== null;
  return (
    <span className={cx('break-words', className)}>
      {label || '(표시명 없음)'}
      {node.type === 'EXTERNAL' && <span className="text-muted-fg"> ↗</span>}
      {broken && <span className="ml-1 text-xs text-danger">확인 필요</span>}
    </span>
  );
}

/** 1차 메뉴 · 바로가기 줄에 나오는가 — 링크 없는 묶음 제목은 줄에 나오지 않는다(스토어프론트 헤더와 같다) */
const inLine = (node: MenuNode) => node.type !== 'NONE';

/**
 * 저장 · 게시 없이 지금 입력으로 그리는 헤더 미리보기 — 스토어프론트 헤더와 같은 구조다.
 * PC: 로고 아래 1차 메뉴 줄(primary) + 펼친 전체 메뉴(대제목 · 설명 · 하위 링크, 2열).
 * 모바일: 바로가기 줄(shortcut, 넘치면 가로 스크롤) + 전체 화면 메뉴(1열).
 * 숨김 항목은 하위째 빠지고, 고른 언어의 표시명 · 설명만 쓴다(다른 언어로 대신하지 않는다 — 서버와 같다).
 */
export function MenuPreview({
  tree,
  device,
  locale,
}: {
  tree: readonly MenuNode[];
  device: PreviewDevice;
  locale: PreviewLocale;
}) {
  const shown = shownTree(tree);
  const mobile = device === 'mobile';
  const line = shown.filter((node) => (mobile ? node.shortcut : node.primary) && inLine(node));

  return (
    <div className="overflow-x-auto bg-muted px-4 py-6">
      <div
        data-device={device}
        className={cx(
          'mx-auto flex flex-col bg-bg text-fg',
          mobile ? 'w-[390px] max-w-full' : 'w-full min-w-[720px]',
        )}
      >
        <div className="flex h-14 items-center justify-between px-4">
          <span aria-hidden className="text-sm text-muted-fg">
            ☰
          </span>
          <span className="text-sm tracking-[0.25em] uppercase">쇼핑몰 이름</span>
          <span aria-hidden className="text-xs text-muted-fg">
            검색 · 계정 · 장바구니
          </span>
        </div>
        {line.length > 0 ? (
          <div className="relative overflow-x-auto border-b border-border">
            <ul
              aria-label={mobile ? '모바일 바로가기 미리보기' : 'PC 1차 메뉴 미리보기'}
              className={cx(
                'mx-auto flex w-max items-center px-4 text-xs tracking-[0.2em] whitespace-nowrap uppercase',
                mobile ? 'h-9 gap-8' : 'h-10 gap-12',
              )}
            >
              {line.map((node) => (
                <li key={node.key}>
                  <PreviewLabel node={node} locale={locale} />
                </li>
              ))}
            </ul>
          </div>
        ) : (
          <p className="border-b border-border px-4 py-2 text-xs text-muted-fg">
            {mobile
              ? '바로가기에 보이기를 켠 최상위 메뉴가 없어 바로가기 줄이 없습니다.'
              : 'PC 1차 메뉴에 보이기를 켠 최상위 메뉴가 없어 1차 메뉴 줄이 없습니다.'}
          </p>
        )}

        <div className="px-4 py-6">
          <p className="mb-2 text-xs text-muted-fg">전체 메뉴 (☰ 를 누르면 열리는 패널)</p>
          {shown.length === 0 ? (
            <p className="text-sm text-muted-fg">노출할 메뉴가 없습니다.</p>
          ) : (
            <ul
              aria-label="전체 메뉴 미리보기"
              className={cx('grid gap-x-12', mobile ? 'grid-cols-1' : 'grid-cols-2')}
            >
              {shown.map((node) => {
                const description = descriptionOf(node, locale);
                return (
                  <li key={node.key} className="flex flex-col gap-1 py-4">
                    <PreviewLabel
                      node={node}
                      locale={locale}
                      className="text-sm font-medium tracking-[0.2em] uppercase"
                    />
                    {description && <span className="text-sm text-muted-fg">{description}</span>}
                    {node.children.length > 0 && (
                      <ul className="mt-2 flex flex-col gap-2 text-sm">
                        {node.children.map((child) => (
                          <li key={child.key} className="flex flex-col gap-2">
                            <PreviewLabel node={child} locale={locale} />
                            {child.children.length > 0 && (
                              <ul className="ml-4 flex flex-col gap-2">
                                {child.children.map((grandchild) => (
                                  <li key={grandchild.key}>
                                    <PreviewLabel node={grandchild} locale={locale} />
                                  </li>
                                ))}
                              </ul>
                            )}
                          </li>
                        ))}
                      </ul>
                    )}
                  </li>
                );
              })}
            </ul>
          )}
        </div>
      </div>
      <p className="mt-2 text-xs text-muted-fg">
        미리보기 글자는 눌러도 이동하지 않습니다. ↗ 는 새 창으로 열리는 다른 사이트입니다.
        {flatten(tree).length > shown.length && ' 숨긴 항목과 그 하위는 빠져 있습니다.'}
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
        {revisionNumber}번 초안을 게시할까요? 스토어프론트 국문 · 영문 헤더의 PC 1차 메뉴 · 모바일
        바로가기 · 전체 메뉴가 바로 바뀝니다.
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
