#!/usr/bin/env python3
"""Sync Claude workflows into the skills, roles and subagents used by Codex and Cursor."""

import argparse
import json
import re
from pathlib import Path


ROOT = Path(__file__).resolve().parents[1]
DESCRIPTIONS = {
    "issue": "Commerce Foundry GitHub 이슈를 한국어로 작성·등록한다. 이슈 생성·분할 요청에 담당자, 서비스·타입 라벨, 마일스톤을 적용한다. 초안만 요청하면 등록하지 않는다.",
    "issue-start": "Commerce Foundry GitHub 이슈를 읽고 origin/develop 기준 작업 브랜치와 구현 계획을 만든다. 이슈 번호로 작업 시작이나 브랜치 생성을 요청할 때 사용한다.",
    "pr": "Commerce Foundry 변경을 확인하고 한국어 본문, 연결 이슈, 담당자·라벨·리뷰어를 갖춘 develop 대상 PR을 생성한다. PR 생성·본문 작성 요청에 사용한다.",
    "dev-orchestrator": "Commerce Foundry 개발 작업을 유형과 복잡도 T0–T3로 분류해 탐색·설계·구현·검증·검토 역할을 선택한다. 비단순 개발 작업이나 명시적인 오케스트레이션 요청에 사용한다.",
    "backend-feature-bootstrap": "Commerce Foundry 백엔드 새 서비스·컨텍스트를 시작할 때 책임, 고객·어드민·internal API, 패키지, 영속성, 연동과 검증 계획을 정리한다.",
    "backend-api-check": "Commerce Foundry Controller·DTO·Presenter 변경을 REST 경로, 권한, 응답 봉투, 식별자·금액, 페이지네이션과 Swagger 규칙에 따라 읽기 전용으로 점검한다.",
    "hexagonal-guard": "Commerce Foundry 백엔드의 Controller·UseCase·Facade·Processor·Presenter와 Port·Adapter 계층 경계를 읽기 전용으로 점검한다. 아키텍처 리뷰·새 컨텍스트·계층 리팩토링에 사용한다.",
    "payment-guard": "Commerce Foundry 결제 승인·취소·환불·웹훅·PG 연동의 금액 검증, 멱등성, 트랜잭션, UNKNOWN·재조회, 주문 동기화와 키 노출을 읽기 전용으로 점검한다.",
    "order-flow-guard": "Commerce Foundry 주문·재고·배송·클레임 변경의 상태 전이, 서버 금액, 스냅숏, 재고 선점·해제 멱등성과 동시성을 읽기 전용으로 점검한다.",
    "backend-multi-agent": "Commerce Foundry의 큰 백엔드 작업을 Leader·Executor·계층·DB·보안·결제 검토 역할로 나눈다. 여러 경계가 함께 바뀌거나 팀 작업을 요청할 때 사용하며 작은 수정에는 사용하지 않는다.",
}

COMMON_REPLACEMENTS = {
    "docs/claude-agents.md": "docs/ai-agents.md",
    ".claude/agents": ".agents/roles",
    "CLAUDE.md": "AGENTS.md",
    "Write 도구로": "파일 편집 도구로",
    "(세션 scratchpad)": "(운영체제 임시 디렉터리)",
    "빌드·CI·Claude 설정": "빌드·CI·AI 도구 설정",
    "역할·모델·권한의 정본은": "역할 적용·실행·권한 안내는",
    "깊은 검토가 필요하면 `be-payment-reviewer` 에이전트에 위임한다.":
        "깊은 검토는 `.agents/roles/be-payment-reviewer.md`를 읽어 수행한다. 위임이 허용되고 하위 에이전트를 사용할 수 있을 때만 위임한다.",
    "- `Grep`/`Glob` 으로 후보를 좁힌 뒤 필요한 부분만 `Read` 한다.":
        "- `rg`/`rg --files`로 후보를 좁힌 뒤 필요한 부분만 읽는다.",
    "- Bash 는 `git log`, `git diff`, `ls` 같은 조회에만 쓴다.":
        "- 셸은 `git log`, `git diff`, 파일 목록 같은 조회에만 쓴다.",
    "**한 메시지에서 병렬로** 부른다":
        "위임이 허용되고 하위 에이전트를 사용할 수 있으면 **병렬로** 부르고, 그 외에는 메인 세션이 순차 검토한다",
    "**한 메시지에서**": "**한 턴에서** (`spawn_agent` 를 연달아 호출해)",
    "Claude Agent `isolation: \"worktree\"`, Codex 는 작업자마다 별도 worktree": "작업자마다 별도 worktree",
}

SEARCH_BLOCKS = {
    "hexagonal-guard": r"""# application의 adapter 의존 (문서화된 예외를 구분해 확인)
rg -n 'import com\.commercefoundry\.domainlayer\..*\.adapter\.' backend/service -g '*.java' -g '**/application/**'

# Processor의 Response 생성 후보
rg -n 'Response\.builder\(\)|Response\(' backend/service -g '*Processor.java'

# Controller의 직접 주입 후보
rg -n 'private final .*?(Facade|Processor|Port|Repository) ' backend/service -g '*Controller.java'""",
    "payment-guard": r"""# 후보 탐지 결과에는 민감한 값 대신 경로·행과 마스킹한 근거만 보고한다
rg -l 'test_sk_|live_sk_|live_ck_' backend -g '*.java' -g '*.yml' -g '*.properties'
rg -n '(double|float|Double) .*[Aa]mount' backend/service -g '*.java'
rg -n -B 3 'paymentGatewayPort\.|PaymentGatewayPort|@Transactional' backend/service/payment-service -g '*.java'
# 검색은 후보 탐지다. PG 호출과 트랜잭션의 실제 호출 경계를 이어서 읽는다.""",
    "order-flow-guard": r"""rg -n 'setStatus\(|\.status = ' backend/service/order-service backend/service/product-service backend/service/delivery-service -g '*.java'
rg -n 'set s\.available|available =' backend/service/product-service -g '*.java'
rg -l '@Version' backend/service/order-service backend/service/product-service backend/service/delivery-service -g '*.java'""",
}

# 작업 등급(docs/claude-agents.md "작업 등급과 모델 · 추론"). 역할 정의의 기본값이고, 호출마다 등급을 바꿀 수 있다.
TIERS = ("design", "core", "general", "fast")
TIER_LABELS = {"design": "설계", "core": "핵심", "general": "일반", "fast": "빠름"}
TIER_TARGETS = {
    "design": "서비스 경계 · 교차 모듈 · 정합성 설계",
    "core": "치명 버그 · 결제 · 주문 · 재고 · 인증 등 주요 비즈니스 로직의 구현 · 검토",
    "general": "CRUD · 매핑 · 단순 화면 등 위험 신호 없는 개발",
    "fast": "git · gh, 코드 탐색, 대량 반복, 빠른 응답",
}
ROLE_TIERS = {
    "architect": "design",
    "be-executor": "core",
    "fe-implementer": "core",
    "be-hexagonal-reviewer": "core",
    "be-db-reviewer": "core",
    "be-security-reviewer": "core",
    "be-payment-reviewer": "core",
    "fe-reviewer": "core",
    "reviewer": "core",
    "explorer": "fast",
    "git-ops": "fast",
}
# Claude Code 등급별 모델 별칭과 기본 effort. .claude/agents frontmatter 의 model · effort 가 이 값(역할별 effort 예외 포함)과 같아야 한다.
CLAUDE_TIERS = {"design": ("fable", "xhigh"), "core": ("opus", "high"), "general": ("sonnet", "medium"), "fast": ("haiku", "medium")}
CLAUDE_EFFORTS = {"low", "medium", "high", "xhigh", "max"}
# 오케스트레이션 스킬의 Codex · Cursor 생성본에 남으면 안 되는 Claude 전용 표현.
CLAUDE_ONLY_TOKENS = ('isolation: "worktree"', "한 메시지에서", "`fable`", "`opus`", "`sonnet`", "`haiku`", "Agent 도구")
FAST_ROLES = {role for role, tier in ROLE_TIERS.items() if tier == "fast"}
CODEX_MODEL_CONFIG = ROOT / ".codex/agent-models.json"
EFFORT_ORDER = ("minimal", "low", "medium", "high", "xhigh", "max", "ultra")
REASONING_EFFORTS = set(EFFORT_ORDER)
# Codex 전용으로 관리하며 Claude 원본이 없는 스킬.
STANDALONE_SKILLS = {ROOT / ".agents/skills/codex-team/SKILL.md"}
CURSOR_FAST_MODEL = "claude-haiku-5-5"
GENERATED_NOTE = "scripts/sync-agent-skills.py 가 .claude/agents/ 에서 생성한다. 직접 고치지 않는다."

ROUTING_HEAD = """## 모델 라우팅

토큰은 "누가 얼마나 읽느냐" 에서 가장 많이 든다. 메인 세션은 분류·계획·범위 결정·검증 확인·완료 판단에 쓰고,
나머지는 작업 등급에 맞는 모델·추론 수준의 역할로 내린다. Codex 값의 정본은 `.codex/agent-models.json`이다.

| 등급 | 대상 | Codex 모델 | 추론 (기본 → 최대) | 기본 역할 |
|------|------|------------|--------------------|-----------|
"""

ROUTING_TAIL = """
- 현재 Codex 실행기에서 프로젝트의 명명 역할은 로드된 역할 정의의 모델·추론을 쓴다. `agent_type="be_executor"`처럼 역할을 지정할 때는
  `model`·`reasoning_effort`를 생략한다. 세션마다 `spawn_agent` 역할 설명에서 고정 여부를 확인한다.
- 호출별 모델·추론 지정이 필요하고 위임 범위가 허용하면 `agent_type="default"`에 `model`·`reasoning_effort`·`fork_turns="none"`을 넘긴다.
  일반 등급 예시는 `spawn_agent(agent_type="default", model="{general_model}", reasoning_effort="{general_effort}", fork_turns="none", ...)`이다.
  작업 메시지에 적용할 역할 지침, 권한·편집 범위와 검증 방법을 담는다. [Codex 호출 실측](../../../docs/ai-agents.md#codex-병렬-호출-실측)
- 등급 고르기: T0 은 메인 세션 직접, T1(위험 신호 없음)은 일반, T1(신호 1개)·T2 는 핵심. T3 는 구현을 핵심에 추론 한 단계 위
  (돈·동시성이 꼬였거나 원인 미상 버그는 최대), 검토는 핵심 기본에 신호가 걸린 영역 리뷰어(결제·보안·DB)만 한 단계 위.
  ARCHITECTURE 는 설계(서비스 3개 이상·공개 계약 변경·재설계는 최대). 탐색은 빠름, 결제·인증처럼 틀리면 비싼 위치 찾기는 빠름 등급에 `high`.
- 같은 일에서 두 번 실패하고 호출별 지정이 허용되면 추론을 먼저, 그다음 모델을 한 단계 올린다. 최대 추론은 등급 표의 값을 넘기지 않는다.
- 보고에 실제로 쓴 등급·모델·추론 수준을 적는다. 위임할 수 없으면 메인 세션이 같은 순서로 처리한다.
- 위치를 아는 파일 몇 개 읽기, 단일 git 명령은 메인 세션이 직접 한다. 하위 에이전트는 새 컨텍스트로 시작하므로 띄우는 비용이 더 크다.
- `spawn_agent`의 기본 `fork_turns="all"`은 부모 기록 전체를 넘겨 토큰이 늘어난다. 탐색·검토처럼 지시로 충분한 일은 `fork_turns="none"`으로 띄우고
  경로·브랜치·이슈 번호·검증 결과·원하는 출력 형식을 메시지에 모두 담는다.
- 결과는 요약으로 받는다. diff·파일 원문을 통째로 돌려받지 않는다.
"""


def codex_role_list(tier):
    roles = [f"`{codex_name(role)}`" for role, role_tier in ROLE_TIERS.items() if role_tier == tier]
    return " · ".join(roles) if roles else "없음 (호출별 지정이 가능한 기본 에이전트 사용)"


def routing(config):
    rows = []
    for tier in TIERS:
        effort = f"{config['reasoning_effort'][tier]} → {config['max_reasoning_effort'][tier]}"
        exceptions = [f"`{codex_name(role)}` {value}" for role, value in config["role_reasoning_effort"].items()
                      if ROLE_TIERS[role] == tier]
        if exceptions:
            effort += f" ({', '.join(exceptions)})"
        rows.append(f"| {TIER_LABELS[tier]} | {TIER_TARGETS[tier]} | `{config['models'][tier]}` | {effort} | {codex_role_list(tier)} |")
    tail = ROUTING_TAIL.replace("{general_model}", config["models"]["general"]).replace(
        "{general_effort}", config["reasoning_effort"]["general"])
    return ROUTING_HEAD + "\n".join(rows) + "\n" + tail

ORCHESTRATION = """## Codex · Cursor 실행 방식

- 역할 지침 원문은 저장소 루트 기준 `.agents/roles/<역할>.md`다. 네이티브 하위 에이전트로는
  Cursor `.cursor/agents/`와 Codex `.codex/agents/`에 11개 역할이 모두 생성돼 있다.
- Codex에서는 `spawn_agent`에 `agent_type`으로 `.codex/agents/`의 snake_case 이름을 지정한다
  (예: `be_executor`, `be_db_reviewer`). 도구 목록에 이름이 없으면 현재 세션에서 인식되지 않은 것이므로 새 세션에서 확인한다.
- 병렬은 `dev-orchestrator`의 `병렬 실행` 절(네 패턴 · 금지 조건 · 합치기)을 따른다. 한 턴에서 `spawn_agent`를 여러 번 연달아 호출한 뒤 `wait_agent`로 결과를 모은다.
  검토 fan-out 은 영역 리뷰어와 `reviewer`를 같은 턴에 띄운다. 쓰기 병렬은 작업자마다 별도 worktree 를 만들어 그 경로를 지시에 넣는다.
- 하위 에이전트를 사용할 수 있고 위임이 허용된 경우에만 역할 지침·범위·기대 출력을 전달해 위임한다.
  그 외에는 메인 세션이 동일한 역할을 순차 수행하고, 실제 수행 방식을 보고한다.
- 읽기 전용 역할은 소스·설정·문서를 편집하지 않는다. 실행 도구의 권한 제한을 적용할 수 있으면 함께 적용한다.
  검사 명령은 빌드 산출물을 만들 수 있지만, 파괴적인 셸 명령과 외부 변경은 허용되지 않는다.
- 쓰기 병렬의 조건(소유권 분리 · worktree 격리 · 금지 조건 없음)을 모두 만족하지 않으면 쓰기는 한 번에 한 역할만 수행한다.
  Codex 역할은 부모 세션 sandbox 를 상속하므로 읽기 전용은 역할 본문 지시로만 제한된다(Cursor 는 생성된 `readonly`).
- Codex 쓰기 병렬은 작업자 worktree 가 현재 sandbox 쓰기 범위 안일 때만 쓴다. 범위 밖이면 sandbox 를 넓히지 말고 순차로 진행한다.
- 범위 밖 이슈 등록·커밋·푸시·PR·머지는 실행하지 않는다. FE 작업은 `fe-implementer`와 `fe-reviewer`를 선택한다.

"""


def split_source(path):
    text = path.read_text(encoding="utf-8")
    match = re.match(r"\A---\n(.*?)\n---\n", text, re.S)
    if not match:
        raise ValueError(f"Invalid source frontmatter: {path.relative_to(ROOT)}")
    meta = {}
    for line in match.group(1).splitlines():
        key, sep, value = line.partition(":")
        if sep:
            meta[key.strip()] = value.strip().strip('"')
    return meta, text[match.end():].lstrip("\n")


def read_body(path):
    return split_source(path)[1]


def adapt(body):
    for before, after in COMMON_REPLACEMENTS.items():
        body = body.replace(before, after)
    return body


def skill_content(path, config):
    name = path.parent.name
    body = adapt(read_body(path))
    if name in SEARCH_BLOCKS:
        # Only replace the first bash block that contains the old grep probes.
        body = re.sub(
            r"```bash\n(?:(?!```)[\s\S])*?\bgrep\b(?:(?!```)[\s\S])*?```",
            lambda _: "```bash\n" + SEARCH_BLOCKS[name] + "\n```",
            body, count=1,
        )
    if name in {"dev-orchestrator", "backend-multi-agent"}:
        heading, body = body.split("\n", 1)
        body = heading + "\n\n" + ORCHESTRATION + body.lstrip("\n")
        body = body.replace(
            "FE 영향은 이슈로 분리해 `seonghoho` 에게 넘긴다.",
            "FE 영향은 별도 작업 단위로 정리한다. FE 이슈 등록·담당자 지정은 요청받은 범위에서만 수행한다.",
        ).replace(
            "CRITICAL/HIGH 지적만 구현 역할이 고친 뒤 다시 검증한다.",
            "CRITICAL/HIGH는 해결하고 다시 검증한다. MEDIUM/LOW도 영향과 범위에 따라 수정하거나 남긴 이유를 보고한다.",
        ).replace(
            "CRITICAL/HIGH 만 Executor 가 고치고 다시 검증한다.",
            "CRITICAL/HIGH는 Executor가 해결하고 다시 검증한다. MEDIUM/LOW는 영향·범위에 따른 처리 이유를 남긴다.",
        ).replace(
            "2. Reviewers 병렬 검토", "2. Reviewers 검토 (위임 가능 시 병렬, 그 외 순차)",
        ).replace(
            "`be-executor` 한 명", "BE는 `be-executor`, FE는 `fe-implementer` 한 명",
        )
        if name == "dev-orchestrator":
            body, count = re.subn(r"## 모델 라우팅\n.*?(?=\n## )", lambda _: routing(config).rstrip() + "\n", body, count=1, flags=re.S)
            if count != 1:
                raise ValueError("dev-orchestrator: '## 모델 라우팅' section not found")
            body = body.replace(
                "## 7. REVIEW",
                "FE 변경은 `frontend/`에서 `pnpm lint`, `pnpm format:check`, `pnpm typecheck`, `pnpm test`, `pnpm build`를 실행한다.\n"
                "문서·AI 설정만 바꾼 경우에는 관련 형식·경로·인코딩 검증을 실행한다.\n\n## 7. REVIEW",
            ).replace(
                "| 최종 diff 전반 | `reviewer` |",
                "| 프론트엔드 BFF·쿠키·금액·테마 | `fe-reviewer` |\n| 최종 diff 전반 | `reviewer` |",
            )
    if name == "pr":
        # The template includes a nested SQL fence.
        body = body.replace("```markdown\n", "````markdown\n", 1)
        body = body.replace("Issue Number: #\n```", "Issue Number: #\n````", 1)
    if name in {"dev-orchestrator", "backend-multi-agent"}:
        # 치환이 원문 변경으로 조용히 빠지면 Claude 전용 표현이 Codex · Cursor 판에 새어 나간다.
        leaked = [token for token in CLAUDE_ONLY_TOKENS if token in body]
        if leaked:
            raise ValueError(f"{name}: Claude-only wording left in generated skill: {leaked}")
    description = json.dumps(DESCRIPTIONS[name], ensure_ascii=False)
    return f"---\nname: {name}\ndescription: {description}\n---\n\n{body.rstrip()}\n"


def role_content(path):
    body = adapt(read_body(path))
    # Preserve each role's review criteria without Claude-specific tool/model metadata.
    body = body.replace(
        'grep -rn -A5 "for (\\|\\.stream()\\|\\.forEach(" backend/service/*/src/main/java --include=*.java | grep -E "Port\\.|Repository\\.|Client\\."',
        "rg -n -A 5 'for \\(|\\.stream\\(\\)|\\.forEach\\(' backend/service -g '*.java'\n# 후보의 루프·람다 본문에서 Port/Repository/Client 단건 호출을 확인한다.",
    )
    body = re.sub(
        r"   grep -rnE .*?\n",
        "   rg -l 'secret|password|sk_live|live_sk|test_sk|jwt.*key' backend -g '*.yml' -g '*.properties' -g '*.java'\n   # 후보 파일을 읽고 플레이스홀더와 실값을 구분한다. 값은 출력·보고에서 마스킹한다.\n",
        body,
    )
    return f"# {path.stem}\n\nCodex·Cursor 공통 역할 지침. 실행 방식은 `docs/ai-agents.md`를 따른다.\n\n{body.rstrip()}\n"


def codex_name(role):
    # Every documented Codex agent name is snake_case.
    return role.replace("-", "_")


def cursor_agent_content(path):
    meta, _ = split_source(path)
    tools = {tool.strip() for tool in meta.get("tools", "").split(",") if tool.strip()}
    # A Claude role with an explicit tool list and no Write/Edit is read-only.
    readonly = bool(tools) and not tools & {"Write", "Edit"}
    model = CURSOR_FAST_MODEL if path.stem in FAST_ROLES else "inherit"
    description = json.dumps(adapt(meta["description"]), ensure_ascii=False)
    return (f"---\nname: {path.stem}\ndescription: {description}\nmodel: {model}\n"
            f"readonly: {str(readonly).lower()}\n---\n\n{role_content(path)}")


def effort_rank(effort):
    return EFFORT_ORDER.index(effort)


def load_codex_config(path):
    config = json.loads(path.read_text(encoding="utf-8"))
    if set(config) != {"models", "reasoning_effort", "max_reasoning_effort", "role_reasoning_effort"}:
        raise ValueError("keys must be models, reasoning_effort, max_reasoning_effort and role_reasoning_effort")
    for key in ("models", "reasoning_effort", "max_reasoning_effort"):
        if set(config[key]) != set(TIERS):
            raise ValueError(f"{key} must contain exactly {', '.join(TIERS)}")
    if any(not isinstance(model, str) or not re.fullmatch(r"[a-zA-Z0-9][a-zA-Z0-9._-]*", model)
           for model in config["models"].values()):
        raise ValueError("invalid Codex model ID")
    overrides = config["role_reasoning_effort"]
    if not set(overrides) <= set(ROLE_TIERS):
        raise ValueError(f"role_reasoning_effort has unknown roles: {sorted(set(overrides) - set(ROLE_TIERS))}")
    efforts = [*config["reasoning_effort"].values(), *config["max_reasoning_effort"].values(), *overrides.values()]
    if any(not isinstance(effort, str) or effort not in REASONING_EFFORTS for effort in efforts):
        raise ValueError("invalid Codex reasoning effort")
    for tier in TIERS:
        if effort_rank(config["reasoning_effort"][tier]) > effort_rank(config["max_reasoning_effort"][tier]):
            raise ValueError(f"{tier}: reasoning_effort is above max_reasoning_effort")
    for role, effort in overrides.items():
        if effort_rank(effort) > effort_rank(config["max_reasoning_effort"][ROLE_TIERS[role]]):
            raise ValueError(f"{role}: role_reasoning_effort is above its tier maximum")
    return config


def codex_profile(config, role):
    tier = ROLE_TIERS[role]
    return config["models"][tier], config["role_reasoning_effort"].get(role, config["reasoning_effort"][tier])


def validate_catalog(path, config):
    catalog = json.loads(path.read_text(encoding="utf-8"))
    supported = {
        model["slug"]: {level["effort"] for level in model["supported_reasoning_levels"]}
        for model in catalog["models"] if model.get("visibility") == "list"
    }
    checks = [(tier, config["models"][tier], config[key][tier])
              for tier in TIERS for key in ("reasoning_effort", "max_reasoning_effort")]
    checks += [(role, config["models"][ROLE_TIERS[role]], effort)
               for role, effort in config["role_reasoning_effort"].items()]
    for owner, model, effort in checks:
        if model not in supported:
            raise ValueError(f"{owner}: {model} is not selectable in {path}")
        if effort not in supported[model]:
            raise ValueError(f"{owner}: {model} does not support {effort} in {path}")


def validate_claude_roles(roles, config):
    """.claude/agents frontmatter 의 model · effort 가 등급 기본값(역할별 effort 예외 포함)과 같은지 본다."""
    names = {path.stem for path in roles}
    if names != set(ROLE_TIERS):
        raise ValueError(f"Update ROLE_TIERS: added={sorted(names - set(ROLE_TIERS))}, missing={sorted(set(ROLE_TIERS) - names)}")
    for path in roles:
        meta, _ = split_source(path)
        model, effort = CLAUDE_TIERS[ROLE_TIERS[path.stem]]
        effort = config["role_reasoning_effort"].get(path.stem, effort)
        if effort not in CLAUDE_EFFORTS:
            raise ValueError(f"{path.stem}: Claude Code does not support effort {effort}")
        if (meta.get("model"), meta.get("effort")) != (model, effort):
            raise ValueError(f"{path.relative_to(ROOT)}: expected model: {model}, effort: {effort} "
                             f"(found {meta.get('model')}, {meta.get('effort')})")


def codex_agent_content(path, config):
    meta, _ = split_source(path)
    body = role_content(path)
    if "'''" in body:
        raise ValueError(f"Role body cannot be a TOML literal string: {path.relative_to(ROOT)}")
    description = json.dumps(adapt(meta["description"]), ensure_ascii=False)
    model, effort = codex_profile(config, path.stem)
    return (f"# {GENERATED_NOTE}\n"
            f'name = "{codex_name(path.stem)}"\n'
            f"description = {description}\n"
            f'model = "{model}"\n'
            f'model_reasoning_effort = "{effort}"\n'
            f"developer_instructions = '''\n{body}'''\n")


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--write", action="store_true", help="Write generated skills and role instructions; otherwise check for drift")
    parser.add_argument("--model-catalog", type=Path, help="Validate models and effort against a local Codex models cache")
    args = parser.parse_args()
    roles = sorted((ROOT / ".claude/agents").glob("*.md"))
    try:
        config = load_codex_config(CODEX_MODEL_CONFIG)
        if args.model_catalog is not None:
            validate_catalog(args.model_catalog, config)
        validate_claude_roles(roles, config)
    except (OSError, ValueError, KeyError, TypeError, AttributeError) as error:
        parser.error(f"Agent model configuration: {error}")
    sources = sorted((ROOT / ".claude/skills").glob("*/SKILL.md"))
    names = {path.parent.name for path in sources}
    if names != set(DESCRIPTIONS):
        parser.error(f"Update DESCRIPTIONS for source skills: added={sorted(names - set(DESCRIPTIONS))}, missing={sorted(set(DESCRIPTIONS) - names)}")
    try:
        expected = {ROOT / ".agents/skills" / p.parent.name / "SKILL.md": skill_content(p, config) for p in sources}
    except ValueError as error:
        parser.error(f"Skill conversion: {error}")
    expected.update({ROOT / ".agents/roles" / p.name: role_content(p) for p in roles})
    expected.update({ROOT / ".cursor/agents" / p.name: cursor_agent_content(p) for p in roles})
    expected.update({ROOT / ".codex/agents" / f"{codex_name(p.stem)}.toml": codex_agent_content(p, config)
                     for p in roles})
    actual = (set((ROOT / ".agents/skills").glob("*/SKILL.md")) | set((ROOT / ".agents/roles").glob("*.md"))
              | set((ROOT / ".cursor/agents").glob("*.md")) | set((ROOT / ".codex/agents").glob("*.toml")))
    stale = actual - set(expected) - STANDALONE_SKILLS
    failures = []
    for path, content in expected.items():
        if args.write:
            path.parent.mkdir(parents=True, exist_ok=True)
            path.write_text(content, encoding="utf-8", newline="\n")
        elif not path.exists() or path.read_bytes() != content.encode("utf-8"):
            failures.append(str(path.relative_to(ROOT)))
    failures.extend(f"stale: {p.relative_to(ROOT)}" for p in sorted(stale))
    if failures:
        print("Out of sync:\n" + "\n".join(failures))
        return 1
    print(f"{'Synced' if args.write else 'Verified'} {len(sources)} skills, {len(roles)} roles, "
          f"{len(roles)} Cursor agents and {len(roles)} Codex agents.")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
