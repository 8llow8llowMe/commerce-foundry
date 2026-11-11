import { Checkbox } from '@cf/ui';

/**
 * 스토어 노출 칸(등록 · 기본 정보 수정 공용). 판매 상태가 "판매중" 이어도 이 칸이 꺼져 있으면 고객에게 보이지 않는다 —
 * #29 시연에서 놓치기 쉬운 칸으로 드러나, 라벨에 "켜야 보인다" 를 적고 꺼져 있으면 그 결과를 칸 바로 아래에 붉게 알린다.
 */
export function VisibleField({
  checked,
  onChange,
  errors,
  hiddenNote,
}: {
  checked: boolean;
  onChange: (visible: boolean) => void;
  errors: readonly string[];
  /** 꺼져 있을 때 덧붙일 화면별 문구 */
  hiddenNote: string;
}) {
  return (
    <div className="flex flex-col gap-2">
      <Checkbox
        label={
          <>
            <span className="font-medium">스토어에 노출</span> — 고객에게 보이려면 꼭 켜 두세요
          </>
        }
        checked={checked}
        onChange={(event) => onChange(event.currentTarget.checked)}
        errors={errors}
      />
      {!checked && (
        <p role="status" className="rounded-md border border-danger px-3 py-2 text-sm text-danger">
          노출이 꺼져 있습니다. 판매 상태와 상관없이 스토어에서 이 상품이 보이지 않습니다.{' '}
          {hiddenNote}
        </p>
      )}
    </div>
  );
}
