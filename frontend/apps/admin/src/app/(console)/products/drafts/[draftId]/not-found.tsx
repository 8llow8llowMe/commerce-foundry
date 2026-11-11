import Link from 'next/link';

/** 없는 초안 · 형식이 틀린 주소(#360). 주소의 아이디는 다시 보이지 않는다(#305) */
export default function ProductDraftNotFound() {
  return (
    <div role="status" className="flex max-w-xl flex-col gap-3 rounded-md bg-muted px-5 py-6">
      <p className="font-medium">없는 초안입니다.</p>
      <p className="text-sm text-muted-fg">
        주소가 맞는지 확인해 주세요. 초안 목록은 아직 없어 요청 뒤 옮겨 간 화면의 주소로만 다시 찾을
        수 있습니다.
      </p>
      <Link href="/products/drafts/new" className="text-sm text-fg underline underline-offset-4">
        새 초안 요청하기
      </Link>
    </div>
  );
}
