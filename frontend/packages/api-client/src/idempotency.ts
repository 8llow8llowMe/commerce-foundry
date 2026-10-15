/**
 * 쓰기 요청 멱등 키. "사용자 의도 한 번"(주문하기 버튼 한 번)에 하나를 만들고,
 * 같은 의도의 재시도에는 같은 키를 다시 보낸다. backend/docs/api-design-guide.md §6
 */
export function createIdempotencyKey(): string {
  return crypto.randomUUID();
}
