// commerce-foundry 프론트엔드 공용 배포 파이프라인. 규칙 정본: frontend/docs/deploy.md
//
// 백엔드 Jenkinsfile.backend-common.groovy(#198)와 구조 · 게이트 규칙이 같다. 다른 점은 deploy.md §5 에 적었다.
//   - PR 빌드는 검사(format · lint · typecheck · test) + build 만 하고 배포하지 않는다. 배포는 develop(dev) · main(prod) 에 **머지된 뒤**의 브랜치 빌드에서만 한다.
//   - 배포 대상은 머지된 PR 의 라벨 `frontend-<app>` 이 정한다. 라벨을 읽지 못하거나 없으면 배포하지 않는다(fail-closed).
//     `frontend-web` 은 영역 라벨이라 배포 라벨이 아니다(.github/labeler.yml).
//   - 빌드 대상은 변경 경로로 고른다. 자기 앱 · frontend/packages/** · frontend/ 바로 아래 빌드 설정이 바뀌었을 때만 돈다.
//     판단할 수 없으면 빌드는 한다(fail-open). 배포는 fail-closed 다 — 라벨 게이트가 따로 막고, 배포 브랜치에 이전 성공 커밋이 없으면
//     (첫 빌드 · 잡 재등록 · 인덱싱) 무엇이 바뀌었는지 모르므로 자동 배포하지 않는다.
//   - 첫 배포 · 라벨을 빠뜨린 머지 · 이전 커밋으로 되돌리기는 FORCE_DEPLOY 로 수동 배포한다. prod 는 다른 승인자의 입력 승인을 받는다.
//   - 배포 규칙(이름 · .env.runtime · 포트 키 · compose 명령)은 frontend/docs/deploy.md 를 따른다. 백엔드 deploy-guide.md 와 같은 규칙이다.
//
// **공개 저장소다.** 서버 주소 · 호스트 이름 · 포트 값 · 시크릿을 이 파일에 쓰지 않는다. 라벨 · 경로 · 키 이름만 둔다.
//   - Vault 주소는 Jenkins 전역 환경변수 VAULT_ADDR 로 받는다. 같은 이름의 잡 파라미터를 만들지 않는다 — 파라미터가 전역 env 를 빈 값으로 덮는다.
//   - 시크릿 값은 Groovy 를 지나지 않는다. 빌드 시점 키(NEXT_PUBLIC_* · BUSINESS_* 등)는 빌드 agent 의 sh 하나가(buildShellScript),
//     런타임 키는 배포 agent 의 sh 하나가(deployShellScript) Vault 에서 읽고 쓰고 정리한다. Groovy 는 키 이름만 다룬다.
//   - sh 는 `set +x` 로 돌고, 실패 시 컨테이너 로그는 sh 안에서 Vault 값을 가린 뒤 출력한다.
//   - PR 빌드는 PR 브랜치의 이 파일을 그대로 실행한다. credential 보호는 Jenkins 쪽 범위 설정이 맡는다(jenkins-cicd-guide.md §3 과 같다).
//
// 잡 정의는 Jenkinsfile-<app> 이 이 파일을 load 해서 run(config) 를 부른다.

import com.cloudbees.groovy.cps.NonCPS

// ── 고정 값 ──────────────────────────────────────────────────────────────
// 이름만 둔다. 값(credential 내용 · Vault secret)은 Jenkins · Vault 에 있다(#197).

String vaultSecretRoot() { return 'kv/commerce-foundry/frontend' }
// 환경별 Vault AppRole credential ID. 프론트는 백엔드와 다른 AppRole 을 쓴다 — 프론트 잡이 백엔드 시크릿(JWT 개인키 · DB 비밀번호)을 읽을 수 없게.
// 정책은 kv/commerce-foundry/frontend/* 읽기만. 지금은 dev · prod 가 같은 AppRole 이고, 나누면 prod 만 별도 ID 로 바꾼다(deploy.md §6).
@NonCPS
Map<String, String> vaultCredentialIds(String deployEnv) {
    Map<String, Map<String, String>> ids = [
        dev : [roleId: 'commerce-foundry-frontend-vault-role-id', secretId: 'commerce-foundry-frontend-vault-secret-id'],
        prod: [roleId: 'commerce-foundry-frontend-vault-role-id', secretId: 'commerce-foundry-frontend-vault-secret-id']
    ]
    if (!ids.containsKey(deployEnv)) {
        throw new IllegalArgumentException("Vault credential 이 없는 환경입니다: ${deployEnv}".toString())
    }
    return ids[deployEnv]
}
String githubAppCredentialId() { return 'github-app-followfollowme-jenkins' }
// 프론트 잡끼리만 공유한다. 프론트는 네트워크를 만들지 않고 백엔드 컨테이너를 건드리지 않으므로 백엔드 lock 을 기다리지 않는다
String deployLockName() { return 'commerce-foundry-frontend-deploy' }
String deployLabelPrefix() { return 'frontend-' }
// 배포 agent 의 $HOME 아래 경로. 앱은 <이 경로>/frontend/<app> 에 놓인다(frontend/ 와 같은 배치 — compose 의 빌드 컨텍스트 ../..)
String deployBaseDir() { return 'deploy/commerce-foundry' }
// compose --wait 제한(초)과, healthy 뒤 재시작 여부를 다시 볼 때까지 기다리는 시간(초). healthcheck 가 15초 간격 · 5회라 30초면 두 번은 본다
String composeWaitTimeoutSeconds() { return '180' }
String postDeploySettleSeconds() { return '30' }
// 빌드 산출물 묶음. 한 파일로 묶어 stash 한다 — pnpm standalone 의 node_modules 는 심볼릭 링크라 tar 로 그대로 옮긴다
String bundleFileName() { return 'frontend-bundle.tar.gz' }
// 빌드 agent 의 CPU 아키텍처를 적어 두는 파일(앱 디렉터리 기준). 배포 호스트와 다르면 sharp 네이티브 바이너리가 뜨지 않는다(deploy.md §1)
String buildArchFileName() { return '.build-arch' }

// ── config 검사 ──────────────────────────────────────────────────────────

// Jenkinsfile-<app> 의 config 가 이름 규칙(deploy.md §1)과 맞는지 본다. 틀리면 오류 목록, 맞으면 빈 목록.
@NonCPS
List<String> configErrors(Map config) {
    List<String> errors = []
    List<String> requiredKeys = [
        'appName', 'packageName', 'fsPath', 'dockerfile', 'composeFile',
        'composeServiceName', 'containerNamePrefix', 'deploySubdir', 'smokePath', 'buildAgentLabel'
    ]
    for (String key : requiredKeys) {
        Object value = config[key]
        if (!(value instanceof CharSequence) || !value.toString().trim()) {
            errors.add("config.${key} 가 비어 있다".toString())
        }
    }
    Object agentLabels = config.deployAgentLabels
    if (!(agentLabels instanceof Map) || !agentLabels.dev?.toString()?.trim() || !agentLabels.prod?.toString()?.trim()) {
        errors.add('config.deployAgentLabels 에 dev · prod 라벨이 모두 있어야 한다')
    }
    if (!errors.isEmpty()) {
        return errors
    }

    String name = config.appName.toString()
    if (!(name ==~ /[a-z][a-z0-9]*(-[a-z0-9]+)*/)) {
        errors.add("config.appName='${name}' — 소문자 kebab-case 여야 한다".toString())
    }
    // 컨테이너 안에서 fetch 할 경로다. 셸 · URL 에 그대로 들어가므로 문자를 좁힌다
    String smokePath = config.smokePath.toString()
    if (!(smokePath ==~ /\/[A-Za-z0-9\/_-]*/)) {
        errors.add("config.smokePath='${smokePath}' — '/' 로 시작하는 경로(영문 · 숫자 · / _ -)여야 한다".toString())
    }
    Map<String, String> expected = [
        packageName        : "@cf/${name}".toString(),
        fsPath             : "frontend/apps/${name}".toString(),
        dockerfile         : "${name}.Dockerfile".toString(),
        composeFile        : "docker-compose-${name}.yml".toString(),
        composeServiceName : name,
        containerNamePrefix: "commerce-${name}".toString(),
        deploySubdir       : "frontend/${name}".toString()
    ]
    for (Map.Entry<String, String> entry : expected.entrySet()) {
        String actual = config[entry.key]?.toString()
        if (actual != entry.value) {
            errors.add("config.${entry.key}='${actual}' — deploy.md §1 규칙상 '${entry.value}' 여야 한다".toString())
        }
    }
    return errors
}

// ── 체크아웃 ─────────────────────────────────────────────────────────────

void checkoutSource() {
    Map scmVars
    if (env.CHECKED_OUT_COMMIT?.trim()) {
        // 한 빌드에서 체크아웃이 두 번(배포 문맥 확인 · 빌드) 일어난다. 그사이 머지가 끼면 브랜치 최신이 바뀌어
        // 판단한 커밋과 빌드하는 커밋이 갈리므로, 두 번째부터는 첫 체크아웃이 고정한 커밋을 다시 받는다(hondigagae #1514).
        echo "같은 빌드에서 고정한 커밋을 다시 체크아웃합니다: ${env.CHECKED_OUT_COMMIT.take(12)}"
        scmVars = checkoutRevision(env.CHECKED_OUT_COMMIT.trim())
    } else {
        // 브랜치 빌드의 첫 체크아웃은 멀티브랜치가 고정한 리비전을 그대로 받는다. 래퍼와 같은 SCM 을 써야 BuildData 가 하나로 합쳐져
        // GIT_PREVIOUS_SUCCESSFUL_COMMIT 을 같은 브랜치 키에서 읽는다(hondigagae #1514). PR 빌드도 멀티브랜치 기본 SCM 을 쓴다.
        echo '멀티브랜치가 고정한 리비전으로 체크아웃합니다.'
        scmVars = checkout(scm)
    }
    exportCheckoutVars(scmVars)
}

Map checkoutRevision(String revision) {
    return checkout([
        $class           : 'GitSCM',
        branches         : [[name: revision]],
        userRemoteConfigs: scm.userRemoteConfigs
    ])
}

// 직접 부른 checkout 은 GIT_COMMIT · GIT_PREVIOUS_SUCCESSFUL_COMMIT 을 반환 맵으로만 준다. env 로 옮겨야 변경 파일 계산과
// 머지 빌드의 라벨 수집이 이전 성공 커밋을 본다(hondigagae #1514). 첫 체크아웃 값만 옮긴다 — 두 번째가 덮지 않게.
void exportCheckoutVars(Map scmVars) {
    if (env.CHECKOUT_VARS_EXPORTED == 'true') {
        return
    }
    env.CHECKOUT_VARS_EXPORTED = 'true'

    String commit = scmVars?.get('GIT_COMMIT')?.toString()?.trim()
    String previousSuccessful = scmVars?.get('GIT_PREVIOUS_SUCCESSFUL_COMMIT')?.toString()?.trim()
    if (commit) {
        env.GIT_COMMIT = commit
        // PR 빌드는 배포하지 않고 변경 판단도 CHANGE_TARGET 과의 diff 라 고정하지 않는다
        if (!env.CHANGE_ID?.trim()) {
            env.CHECKED_OUT_COMMIT = commit
        }
    }
    if (previousSuccessful) {
        env.GIT_PREVIOUS_SUCCESSFUL_COMMIT = previousSuccessful
        echo "이전 성공 빌드 커밋: ${previousSuccessful.take(8)} / 이번 커밋: ${commit ? commit.take(8) : '알 수 없음'}"
    } else {
        echo '이전 성공 빌드 커밋이 없습니다. (첫 빌드 등)'
    }
}

// DEPLOY_COMMIT(되돌리기)을 이 브랜치에 이미 있는 커밋으로만 받는다. 머지되지 않은 커밋을 운영에 올리는 길을 막는다.
void pinDeployCommit(String requested) {
    String sha = requested.trim().toLowerCase()
    if (!(sha ==~ /[0-9a-f]{7,40}/)) {
        error "DEPLOY_COMMIT 은 커밋 SHA(16진수 7~40자)여야 합니다: ${requested}"
    }
    String full = sh(returnStdout: true, script: "git rev-parse --verify --quiet '${sha}^{commit}' || true").trim()
    if (!full) {
        error "DEPLOY_COMMIT ${sha} 를 저장소에서 찾지 못했습니다. (얕은 클론이면 전체 이력을 받게 잡 설정을 고친다)"
    }
    if (sh(returnStatus: true, script: "git merge-base --is-ancestor '${full}' HEAD") != 0) {
        error "DEPLOY_COMMIT ${full.take(12)} 은 ${env.BRANCH_NAME} 에 머지된 커밋이 아닙니다. 이 브랜치의 이력에 있는 커밋만 배포합니다."
    }
    checkoutRevision(full)
    env.CHECKED_OUT_COMMIT = full
    env.GIT_COMMIT = full
    echo "DEPLOY_COMMIT 으로 고정했습니다: ${full.take(12)}"
}

// ── 빌드 문맥 ────────────────────────────────────────────────────────────

// 배포 환경은 브랜치 잡의 이름으로만 정한다. 파라미터로 환경을 바꾸는 길은 두지 않는다 — 아무 브랜치나 prod 로 보내는 구멍이 된다.
Map<String, Object> resolveGitContext() {
    boolean isPullRequest = env.CHANGE_ID?.trim() ? true : false
    String branch = env.BRANCH_NAME?.trim() ?: ''
    String deployEnv = 'none'
    if (!isPullRequest && branch == 'main') {
        deployEnv = 'prod'
    } else if (!isPullRequest && branch == 'develop') {
        deployEnv = 'dev'
    }
    // 브랜치 이름은 셸 명령(git rev-parse · diff)과 API 경로에 들어간다. 규칙 밖의 이름이면 쓰기 전에 멈춘다
    String changeTarget = env.CHANGE_TARGET?.trim() ?: ''
    for (String ref : [branch, changeTarget]) {
        if (ref && !isSafeRefName(ref)) {
            error "브랜치 이름이 허용 문자(영문 · 숫자 · . _ / -) 밖이라 진행하지 않습니다: ${ref}"
        }
    }
    return [
        branch       : branch,
        changeTarget : changeTarget,
        deployEnv    : deployEnv,
        isPullRequest: isPullRequest ? 'true' : 'false'
    ]
}

// 셸 · URL 에 넣어도 되는 브랜치 이름인가. `-` 로 시작하면 git 옵션으로 읽히므로 막는다.
@NonCPS
boolean isSafeRefName(String ref) {
    return ref != null && ref ==~ /[A-Za-z0-9._\/-]+/ && !ref.startsWith('-') && !ref.contains('..')
}

@NonCPS
boolean isCommitSha(String value) {
    return value != null && value ==~ /[0-9a-f]{7,40}/
}

// 이 잡의 이전 성공 빌드 커밋(형식이 맞고 저장소에 있을 때만). 없으면 빈 문자열.
String previousSuccessfulCommit() {
    String previous = env.GIT_PREVIOUS_SUCCESSFUL_COMMIT?.trim() ?: ''
    if (!previous) {
        return ''
    }
    if (!isCommitSha(previous) || sh(returnStatus: true, script: "git cat-file -e '${previous}^{commit}'") != 0) {
        echo "이전 성공 커밋(${previous.take(12)})이 형식에 맞지 않거나 저장소에 없습니다."
        return ''
    }
    return previous
}

// 배포 브랜치에서 자동 배포의 기준이 되는 이전 성공 커밋이 있는가. 없으면 자동 배포하지 않는다(fail-closed, run 참고).
boolean hasUsableBaseline() {
    return previousSuccessfulCommit() != ''
}

String resolveDeployAgentLabel(Map config, Map ctx) {
    String label = (config.deployAgentLabels as Map)[ctx.deployEnv]?.toString()?.trim()
    if (!label) {
        error "배포 agent 라벨을 찾을 수 없습니다. deployEnv=${ctx.deployEnv}"
    }
    return label
}

// 잡을 사람이 직접 실행했을 때 그 사용자 ID. 웹훅 · 스캔 · 상위 잡이 띄운 빌드면 빈 문자열.
String resolveTriggeringUser() {
    def causes = currentBuild.getBuildCauses('hudson.model.Cause$UserIdCause')
    if (!causes) {
        return ''
    }
    return causes[0]?.userId?.toString() ?: ''
}

// ── 변경 경로 ────────────────────────────────────────────────────────────

// 이번 빌드에서 바뀐 파일. 판단할 수 없으면 null — 호출부가 "영향 있음" 으로 본다(fail-open). 배포는 라벨 게이트가 따로 막는다.
List<String> resolveChangedFiles(Map ctx) {
    try {
        String diffOutput
        // core.quotePath=false: 기본값이면 git 이 한글 경로를 따옴표로 감싸 앱 밖 파일로 판정된다(backend-ci #211).
        // --no-renames: 앱 사이로 옮긴 파일의 옛 경로도 남긴다.
        String diff = 'git -c core.quotePath=false diff --name-only --no-renames'
        if (ctx.isPullRequest == 'true' && ctx.changeTarget) {
            // 여기서 git fetch 를 하지 않는다. sh 에는 checkout 의 credential 이 없어 실패하고, 그러면 무관한 PR 도 전체 빌드가 된다.
            // 멀티브랜치 PR 잡의 checkout 이 대상 브랜치를 origin/<target> 으로 이미 받아 둔다.
            String target = ctx.changeTarget
            if (sh(returnStatus: true, script: "git rev-parse --verify --quiet 'refs/remotes/origin/${target}' >/dev/null") != 0) {
                echo "origin/${target} ref 가 워크스페이스에 없어 변경 파일을 판단할 수 없습니다. 영향 있음으로 진행합니다."
                return null
            }
            diffOutput = sh(returnStdout: true, script: "${diff} 'origin/${target}...HEAD'").trim()
        } else if (ctx.isPullRequest != 'true') {
            // 브랜치 빌드: 이 잡의 마지막 성공 빌드 이후 변경분. 실패한 배포는 성공 커밋을 당기지 않아 다음 빌드가 다시 본다.
            // 기준이 없으면 영향 있음으로 보되, 배포 브랜치는 run 이 자동 배포를 막는다(hasUsableBaseline)
            String previous = previousSuccessfulCommit()
            if (!previous) {
                echo '변경 파일 기준 커밋(이전 성공 빌드)이 없어 영향 있음으로 진행합니다. (첫 빌드 · 잡 재등록 · 인덱싱)'
                return null
            }
            diffOutput = sh(returnStdout: true, script: "${diff} '${previous}' HEAD").trim()
        } else {
            echo '변경 파일 기준 커밋을 판단할 수 없어 영향 있음으로 진행합니다.'
            return null
        }
        List<String> files = parseChangedFiles(diffOutput)
        if (files == null) {
            echo '그대로 믿을 수 없는 경로(따옴표로 감싼 경로 · 앞뒤 공백)가 있어 영향 있음으로 진행합니다.'
        }
        return files
    } catch (Exception e) {
        echo "변경 파일 계산에 실패해 영향 있음으로 진행합니다: ${e.message}"
        return null
    }
}

// git diff --name-only 출력 → 경로 목록. 그대로 믿을 수 없는 경로가 하나라도 있으면 null.
@NonCPS
List<String> parseChangedFiles(String output) {
    List<String> files = []
    if (!output) {
        return files
    }
    for (String raw : output.split('\n')) {
        String line = raw.endsWith('\r') ? raw.substring(0, raw.length() - 1) : raw
        if (!line.trim()) {
            continue
        }
        // core.quotePath=false 여도 `"` · `\` · 제어문자가 든 경로는 git 이 감싸서 낸다
        if (line.startsWith('"') || line != line.trim() || line.contains('\\')) {
            return null
        }
        files.add(line)
    }
    return files
}

// 바뀌면 두 앱을 모두 다시 빌드하는 빌드 설정 — frontend/ 바로 아래 파일(package.json · pnpm-lock.yaml · pnpm-workspace.yaml · turbo.json ·
// tsconfig.base.json · .nvmrc · eslint · prettier · vitest 설정 등). 키 목록 문서인 .env.example 은 뺀다(문서 *.md 는 isOwnScopePath 가 먼저 뺀다).
@NonCPS
boolean isBuildSettingPath(String path) {
    return path ==~ /frontend\/[^\/]+/ && path != 'frontend/.env.example'
}

// 자기 앱 · 공용 패키지 · 빌드 설정인가. 문서(*.md)는 어디에 있든 빌드 · 배포 대상이 아니다.
// 변경 감지(classifyAffectedScope)와 머지 빌드의 라벨 수집(resolveScopedCommitsSincePreviousSuccess)이 같은 판단을 써야 둘이 어긋나지 않는다.
// 공용 패키지는 앱별 의존을 좁히지 않는다 — 두 앱이 api-client · ui · 테마를 모두 쓴다. 다른 앱의 디렉터리는 범위 밖이다.
@NonCPS
boolean isOwnScopePath(String fsPath, String path) {
    if (path.toLowerCase().endsWith('.md')) {
        return false
    }
    return path.startsWith(fsPath + '/') || path.startsWith('frontend/packages/') || isBuildSettingPath(path)
}

// 이 잡의 파이프라인 정의 파일인가. 다른 앱 · 백엔드의 Jenkinsfile 은 세지 않는다.
@NonCPS
boolean isPipelineScopePath(String appName, String path) {
    return path == 'Jenkinsfile.frontend-common.groovy' || path == 'Jenkinsfile-' + appName
}

// 변경 파일이 이 앱 잡에 주는 영향.
//   'own'      — 자기 앱 · 공용 패키지 · 빌드 설정 변경, 또는 판단 불가(null, fail-open)
//   'pipeline' — 이 잡의 파이프라인 정의 파일만 변경
//   'none'     — 무관. 빌드 · 배포를 건너뛴다
@NonCPS
Map classifyAffectedScope(String fsPath, String appName, List<String> changedFiles) {
    if (changedFiles == null) {
        return [scope: 'own', matched: [], reason: '변경 파일을 판단할 수 없음']
    }
    if (changedFiles.isEmpty()) {
        return [scope: 'none', matched: [], reason: '변경 파일 없음']
    }
    List<String> own = changedFiles.findAll { path -> isOwnScopePath(fsPath, path) }
    if (!own.isEmpty()) {
        return [scope: 'own', matched: own, reason: '앱 · 공용 패키지 · 빌드 설정 변경']
    }
    List<String> pipeline = changedFiles.findAll { path -> isPipelineScopePath(appName, path) }
    if (!pipeline.isEmpty()) {
        return [scope: 'pipeline', matched: pipeline, reason: '파이프라인 정의 변경']
    }
    return [scope: 'none', matched: [], reason: "변경 파일 ${changedFiles.size()}건 중 ${appName} 관련 변경 없음".toString()]
}

// ── GitHub PR 라벨 ───────────────────────────────────────────────────────

// origin remote URL 에서 owner/repo. 판단할 수 없으면 빈 문자열.
@NonCPS
String parseRepositorySlug(String url) {
    if (!url) {
        return ''
    }
    String slug = url.trim()
    if (slug.endsWith('.git')) {
        slug = slug.substring(0, slug.length() - 4)
    }
    int hostIndex = slug.indexOf('github.com')
    if (hostIndex < 0) {
        return ''
    }
    slug = slug.substring(hostIndex + 'github.com'.length())
    while (slug.startsWith(':') || slug.startsWith('/')) {
        slug = slug.substring(1)
    }
    return (slug ==~ /[A-Za-z0-9_.-]+\/[A-Za-z0-9_.-]+/) ? slug : ''
}

String resolveRepositorySlug() {
    return parseRepositorySlug(sh(returnStdout: true, script: 'git config --get remote.origin.url || true').trim())
}

// GitHub REST API GET. GitHub App credential 의 installation token 을 쓴다. 토큰은 argv 에 올리지 않고 stdin 헤더로 넘긴다.
String githubApiGet(String apiPath) {
    String response = ''
    withCredentials([usernamePassword(
        credentialsId: githubAppCredentialId(),
        usernameVariable: 'GITHUB_API_USER',
        passwordVariable: 'GITHUB_API_TOKEN'
    )]) {
        withEnv(["GITHUB_API_URL=https://api.github.com/${apiPath}"]) {
            response = sh(
                returnStdout: true,
                script: '''#!/usr/bin/env bash
set +x
set -euo pipefail
printf 'Authorization: Bearer %s\\n' "$GITHUB_API_TOKEN" | curl --fail --silent --show-error \
  --connect-timeout 10 --max-time 30 \
  --header @- \
  --header "Accept: application/vnd.github+json" \
  --header "X-GitHub-Api-Version: 2022-11-28" \
  "$GITHUB_API_URL"
'''
            ).trim()
        }
    }
    return response
}

// 커밋 → 그 커밋을 base 브랜치로 들여온 머지된 PR. 없으면 null.
// commits/{sha}/pulls 가 기본 브랜치 밖의 커밋(예: main 의 릴리스 merge commit)에 머지된 PR 을 돌려주지 않을 수 있어,
// 못 찾으면 base 로 닫힌 최근 PR 에서 merge_commit_sha 가 같은 것을 찾는다. 목록은 cache 에 한 번만 받는다.
def findMergedPullRequest(String slug, String sha, String baseBranch, Map cache) {
    def associated = readJSON text: githubApiGet("repos/${slug}/commits/${sha}/pulls")
    def merged = null
    associated?.each { candidate ->
        // JSON null 은 JSONNull 객체라 참 · 거짓으로 가르지 않고 문자열로 비교한다
        String mergedAt = candidate?.merged_at?.toString()
        if (merged == null && mergedAt && mergedAt != 'null' && candidate?.base?.ref?.toString() == baseBranch) {
            merged = candidate
        }
    }
    if (merged != null) {
        return merged
    }
    if (!cache.containsKey('closed')) {
        cache.closed = readJSON text: githubApiGet("repos/${slug}/pulls?state=closed&base=${baseBranch}&sort=updated&direction=desc&per_page=50")
    }
    cache.closed?.each { candidate ->
        if (merged == null && candidate?.merge_commit_sha?.toString() == sha) {
            merged = candidate
        }
    }
    return merged
}

@NonCPS
List<String> labelNames(def pullRequest) {
    List<String> names = []
    pullRequest?.labels?.each { label ->
        String name = label?.name?.toString()
        if (name && name != 'null' && !names.contains(name)) {
            names.add(name)
        }
    }
    return names
}

// 이 빌드와 연결된 PR 의 라벨.
// 반환: [resolved: 'true'|'false', reason: 사유, labels: List<String>]
//   resolved='true'  — 라벨 목록을 확정했다(0개도 확정이다. PR 없는 직접 push 도 여기 — 라벨 없음으로 배포하지 않는다)
//   resolved='false' — 조회 자체를 못 했다. 배포를 막고 UNSTABLE 로 알린다
Map resolveDeployLabelContext(Map ctx) {
    try {
        String slug = resolveRepositorySlug()
        if (!slug) {
            return [resolved: 'false', reason: 'NO_REPOSITORY_SLUG', labels: []]
        }
        def pullRequest
        if (ctx.isPullRequest == 'true') {
            String prNumber = env.CHANGE_ID?.trim() ?: ''
            if (!(prNumber ==~ /[0-9]+/)) {
                return [resolved: 'false', reason: 'INVALID_PR_NUMBER', labels: []]
            }
            pullRequest = readJSON text: githubApiGet("repos/${slug}/pulls/${prNumber}")
        } else {
            String headSha = sh(returnStdout: true, script: 'git rev-parse HEAD').trim()
            pullRequest = findMergedPullRequest(slug, headSha, ctx.branch as String, [:])
            if (pullRequest == null) {
                echo "커밋 ${headSha.take(8)} 을 ${ctx.branch} 로 들여온 PR 이 없습니다. (직접 push) — 라벨 없음으로 봅니다."
                return [resolved: 'true', reason: 'NO_PULL_REQUEST', labels: []]
            }
        }
        List<String> labels = labelNames(pullRequest)
        echo "PR #${pullRequest.number} 라벨: ${labels ? labels.join(', ') : '없음'}"
        return [resolved: 'true', reason: '', labels: labels]
    } catch (Exception e) {
        echo "PR 라벨 조회에 실패했습니다: ${e.message}"
        return [resolved: 'false', reason: 'API_ERROR', labels: []]
    }
}

// 머지 빌드의 배포 라벨을 모은다(hondigagae #1269).
// HEAD 커밋의 PR 하나만 보면 연달아 머지한 앞 PR 의 라벨을 놓친다 — 대기 빌드는 마지막 커밋 하나로 합쳐지고, 생략도 성공 빌드라
// 이전 성공 커밋이 앞으로 당겨져 앞 PR 의 변경이 다음 빌드에서도 빠진다. 그래서 이전 성공 커밋 이후 이 잡의 범위에 닿은 커밋마다
// 이 브랜치로 머지된 PR 을 찾아 라벨을 합친다. 범위 커밋을 못 잡으면(첫 빌드 등) HEAD 커밋의 PR 로 판단한다.
Map resolveMergedDeployLabelContext(Map config, Map ctx) {
    List<String> commits = resolveScopedCommitsSincePreviousSuccess(config)
    if (!commits) {
        return resolveDeployLabelContext(ctx)
    }
    try {
        String slug = resolveRepositorySlug()
        if (!slug) {
            return [resolved: 'false', reason: 'NO_REPOSITORY_SLUG', labels: []]
        }
        Map cache = [:]
        List<String> labels = []
        List<String> seen = []
        for (String sha : commits) {
            def merged = findMergedPullRequest(slug, sha, ctx.branch as String, cache)
            if (merged == null) {
                echo "커밋 ${sha.take(8)} 을 ${ctx.branch} 로 들여온 PR 이 없습니다. (직접 push)"
                continue
            }
            String number = merged.number.toString()
            if (seen.contains(number)) {
                continue
            }
            seen.add(number)
            List<String> prLabels = labelNames(merged)
            for (String name : prLabels) {
                if (!labels.contains(name)) {
                    labels.add(name)
                }
            }
            echo "PR #${number} 라벨: ${prLabels ? prLabels.join(', ') : '없음'} (커밋 ${sha.take(8)})"
        }
        echo "이전 성공 빌드 이후 이 앱에 닿은 PR ${seen.size()}건의 라벨: ${labels ? labels.join(', ') : '없음'}"
        return [resolved: 'true', reason: '', labels: labels]
    } catch (Exception e) {
        echo "PR 라벨 조회에 실패했습니다: ${e.message}"
        return [resolved: 'false', reason: 'API_ERROR', labels: []]
    }
}

// 이전 성공 빌드 이후 이 잡의 범위(자기 앱 · 공용 패키지 · 빌드 설정 · 자기 파이프라인 파일)에 닿은 커밋, 최신순. 판단할 수 없으면 빈 목록.
// GitHub API 호출 수를 묶으려고 최근 30개까지만 본다.
List<String> resolveScopedCommitsSincePreviousSuccess(Map config) {
    String previous = previousSuccessfulCommit()
    if (!previous) {
        return []
    }
    // --first-parent -m: main 의 릴리스 merge commit 을 첫 부모와의 차이(릴리스 전체 변경)로 잡아 릴리스 PR(base=main)을 찾는다.
    // develop 은 Rebase and merge 라 선형이고 결과가 같다.
    String output = sh(
        returnStdout: true,
        script: "git -c core.quotePath=false log --first-parent -m --no-renames --format=@@%H --name-only '${previous}..HEAD'"
    ).trim()
    List<String> commits = parseScopedCommits(output, config.fsPath as String, config.appName as String)
    if (commits.size() > 30) {
        echo "이 앱에 닿은 커밋 ${commits.size()}건 중 최근 30건의 PR 라벨만 봅니다."
        return commits.take(30)
    }
    return commits
}

// `git log --format=@@%H --name-only` 출력 → 범위에 닿은 커밋. 커밋 줄은 '@@' 로 시작해 파일 경로 줄과 가른다.
@NonCPS
List<String> parseScopedCommits(String output, String fsPath, String appName) {
    List<String> commits = []
    String current = ''
    for (String raw : (output ?: '').split('\n')) {
        String line = raw.trim()
        if (line.startsWith('@@')) {
            current = line.substring(2)
        } else if (line && current && !commits.contains(current)
            && (isOwnScopePath(fsPath, line) || isPipelineScopePath(appName, line))) {
            commits.add(current)
        }
    }
    return commits
}

// 라벨로 이 앱을 배포할지 정한다. `frontend-<app>` 라벨이 있는 PR 만 배포한다.
// 라벨이 없으면 배포하지 않는다(fail-closed) — "라벨 없음 = 전체 배포" 로 넓히지 않는다.
boolean isAppDeployAllowedByLabels(Map config, Map labelContext) {
    String prefix = deployLabelPrefix()
    if (labelContext.resolved != 'true') {
        // credential 오설정 · API 장애로 배포가 조용히 멈춘 것을 알아채도록 UNSTABLE 로 남긴다
        echo "PR 라벨을 확인할 수 없어 배포하지 않습니다. reason=${labelContext.reason}"
        currentBuild.result = 'UNSTABLE'
        currentBuild.description = "라벨 확인 실패(${labelContext.reason}) - 배포 생략"
        return false
    }
    Map decision = decideDeployByLabels(prefix, config.appName as String, labelContext.labels as List<String>)
    echo decision.message as String
    return decision.allowed == 'true'
}

@NonCPS
Map decideDeployByLabels(String prefix, String appName, List<String> labels) {
    String expected = prefix + appName
    // frontend-web 은 영역 라벨이다(모든 frontend/** PR 에 붙는다). 배포 라벨로 세지 않는다
    List<String> scoped = (labels ?: []).findAll { it.startsWith(prefix) && it != prefix + 'web' }
    if (scoped.contains(expected)) {
        return [allowed: 'true', message: "라벨 ${expected} 이 있어 배포 대상입니다.".toString()]
    }
    if (scoped.isEmpty()) {
        return [allowed: 'false', message: "배포 라벨(${prefix}<app>)이 없어 배포하지 않습니다. 배포하려면 PR 에 ${expected} 라벨을 붙이거나 FORCE_DEPLOY 로 실행하세요.".toString()]
    }
    return [allowed: 'false', message: "배포 라벨: ${scoped.join(', ')} — ${expected} 이 없어 배포하지 않습니다.".toString()]
}

// ── Vault ────────────────────────────────────────────────────────────────

Map resolveVaultSpec(String deployEnv) {
    String path = "${vaultSecretRoot()}/${deployEnv}/env".toString()
    return [path: path, apiPath: resolveVaultApiPath(path)]
}

// KV v2 는 mount 와 secret 경로 사이에 /data/ 가 들어간다. kv/commerce-foundry/frontend/dev/env → kv/data/commerce-foundry/frontend/dev/env
@NonCPS
String resolveVaultApiPath(String secretPath) {
    List<String> parts = secretPath.tokenize('/')
    if (parts.size() < 2) {
        throw new IllegalArgumentException("Vault KV v2 경로에는 mount 와 secret 경로가 모두 있어야 합니다: ${secretPath}")
    }
    return parts[0] + '/data/' + parts.drop(1).join('/')
}

// Vault 로그인 · 읽기 · 토큰 폐기를 하는 셸 함수. 빌드 · 배포 스크립트가 같이 쓴다.
// 입력(env): VAULT_BASE · VAULT_AUTH_PATH · VAULT_API_PATH · VAULT_SECRET_PATH · VAULT_ROLE_ID · VAULT_SECRET_ID, 쓰는 파일: $VALUES_JSON
String vaultShellFunctions() {
    return '''
vault_token=''
revoke_token() {
  if [ -n "${vault_token}" ]; then
    # 이 빌드에서만 쓰는 토큰이다. TTL 을 기다리지 않고 폐기한다(실패해도 TTL 로 끝난다)
    printf 'X-Vault-Token: %s\\n' "${vault_token}" | curl --silent --output /dev/null --connect-timeout 10 --max-time 15 \
      --request POST --header @- "${VAULT_BASE}/v1/auth/token/revoke-self" || true
    vault_token=''
  fi
}

# AppRole 로그인 → secret 전체를 $VALUES_JSON 으로 → 토큰 폐기. null 은 빈 문자열, 숫자 · 불리언은 문자열로
read_vault_secret() {
  login_json="$(jq -n '{role_id: env.VAULT_ROLE_ID, secret_id: env.VAULT_SECRET_ID}' \
    | curl --fail --silent --show-error --connect-timeout 10 --max-time 30 \
        --request POST --data @- "${VAULT_BASE}/v1/auth/${VAULT_AUTH_PATH}/login")"
  vault_token="$(printf '%s' "${login_json}" | jq -r '.auth.client_token // empty')"
  login_json=''
  if [ -z "${vault_token}" ]; then
    echo 'Vault AppRole 로그인이 client_token 을 돌려주지 않았습니다.'
    exit 1
  fi
  printf 'X-Vault-Token: %s\\n' "${vault_token}" | curl --fail --silent --show-error --connect-timeout 10 --max-time 30 \
    --header @- "${VAULT_BASE}/v1/${VAULT_API_PATH}" \
    | jq '.data.data // {} | with_entries(.value |= (if . == null then "" elif type == "string" then . else tojson end))' > "${VALUES_JSON}"
  revoke_token

  if [ "$(jq 'length' "${VALUES_JSON}")" = 0 ]; then
    echo "Vault secret 에 key-value 가 없습니다: ${VAULT_SECRET_PATH}"
    exit 1
  fi
  bad_names="$(jq -r 'keys[] | select(test("^[A-Za-z_][A-Za-z0-9_]*$") | not)' "${VALUES_JSON}")"
  if [ -n "${bad_names}" ]; then
    echo "Vault 키 이름이 환경변수 이름 규칙에 맞지 않습니다: $(printf '%s' "${bad_names}" | tr '\\n' ' ')"
    exit 1
  fi
}
'''
}

// ── 빌드 시점 키 (이름만) ─────────────────────────────────────────────────

// turbo.json 의 build.env → 빌드 때 Vault 에서 꺼내 넘길 키 이름의 정규식. turbo 는 strict env 모드라 여기 없는 키는 next build 에 가지 않는다
// (deploy.md §2) — 같은 목록을 정본으로 쓴다. `BUSINESS_*` 같은 끝 와일드카드만 받는다.
@NonCPS
Map buildEnvKeyPattern(List<String> names) {
    List<String> errors = []
    List<String> regexes = []
    for (String name : (names ?: [])) {
        if (name ==~ /[A-Z_][A-Z0-9_]*/) {
            regexes.add('^' + name + '$')
        } else if (name ==~ /[A-Z_][A-Z0-9_]*\*/) {
            regexes.add('^' + name.substring(0, name.length() - 1) + '[A-Za-z0-9_]*$')
        } else {
            errors.add("turbo.json build.env 의 '${name}' 를 해석할 수 없습니다(대문자 키 또는 끝 와일드카드만)".toString())
        }
    }
    return [regex: regexes.join('|'), errors: errors]
}

// ── .env.runtime 에 쓸 키 (이름만) ───────────────────────────────────────
// 값은 Groovy 를 지나지 않는다. 여기서는 compose 를 읽어 키 **이름**만 정하고, 값 읽기 · 검사 · 쓰기는 배포 agent 의 sh 하나가 한다(deployShellScript).

// compose 파일이 보간하는 키(`${KEY...}` · `$KEY`)와 그중 필수(`${KEY:?...}` · `${KEY?...}`). 한 줄 주석은 뺀다(YAML 이 버리는 줄).
@NonCPS
Map composeInterpolation(String composeText) {
    StringBuilder code = new StringBuilder()
    for (String line : (composeText ?: '').split('\n')) {
        if (!line.trim().startsWith('#')) {
            code.append(line).append('\n')
        }
    }
    String source = code.toString()
    // `$$` 는 compose 의 이스케이프라 보간이 아니다. 정규식은 `$` 보간을 피하려고 작은따옴표 문자열로 둔다
    List<String> referenced = []
    def referenceMatcher = source =~ '(?<!\\$)\\$\\{?([A-Za-z_][A-Za-z0-9_]*)'
    while (referenceMatcher.find()) {
        String key = referenceMatcher.group(1)
        if (!referenced.contains(key)) {
            referenced.add(key)
        }
    }
    List<String> required = []
    def requiredMatcher = source =~ '(?<!\\$)\\$\\{([A-Za-z_][A-Za-z0-9_]*):?\\?'
    while (requiredMatcher.find()) {
        String key = requiredMatcher.group(1)
        if (!required.contains(key)) {
            required.add(key)
        }
    }
    return [referenced: referenced.sort(), required: required.sort()]
}

@NonCPS
String appPortKey(String appName, String deployEnv) {
    return appName.toUpperCase().replace('-', '_') + '_PORT_' + deployEnv.toUpperCase()
}

// compose → .env.runtime 키 계획(이름만). 백엔드 planRuntimeKeys 와 같다.
//   keep        — compose 가 보간하는 키. 이것만 쓴다. 환경당 secret 하나라 다른 앱의 키(BUSINESS_* 등)가 이 앱 배포에 내려가지 않게.
//   placeholder — 반대 환경의 `<APP>_PORT_<ENV>`. 한 compose 에 dev · prod 가 같이 있고 파일 전체가 보간되므로 '0' 으로 채운다(deploy.md §3).
//   required    — compose 의 `:?` 키에서 placeholder 를 뺀 것. 비면 sh 가 키 이름만 모아 실패한다.
//   errors      — compose 가 규칙(자기 · 반대 포트, 네트워크를 `:?` 로)과 다르면.
@NonCPS
Map planRuntimeKeys(String appName, String deployEnv, String composeText) {
    List<String> errors = []
    String opposite = deployEnv == 'prod' ? 'dev' : 'prod'
    String ownPort = appPortKey(appName, deployEnv)
    String oppositePort = appPortKey(appName, opposite)
    Map interpolation = composeInterpolation(composeText)
    List<String> referenced = interpolation.referenced as List<String>
    List<String> required = interpolation.required as List<String>
    for (String key : [ownPort, oppositePort, 'COMMERCE_DOCKER_NETWORK']) {
        if (!required.contains(key)) {
            errors.add("compose 가 ${key} 를 필수(:?)로 보간하지 않습니다 — deploy.md §3 규칙과 다릅니다".toString())
        }
    }
    return [
        keep       : referenced.findAll { it != oppositePort },
        required   : required.findAll { it != oppositePort },
        placeholder: oppositePort,
        ownPort    : ownPort,
        errors     : errors
    ]
}

// ── 빌드 스크립트 ────────────────────────────────────────────────────────

// 빌드 agent 의 도구 확인. Node 메이저는 frontend/.nvmrc 와 같아야 하고, glibc 리눅스여야 한다 — standalone 의 sharp 네이티브 바이너리가
// 빌드 머신의 OS · CPU 로 들어가 런타임 이미지(node:24-bookworm-slim, glibc)에서 그대로 쓰인다(deploy.md §1).
String toolchainCheckScript() {
    return '''#!/usr/bin/env bash
set -euo pipefail
cd frontend
expected="$(tr -d '[:space:]v' < .nvmrc)"
actual="$(node -p 'process.versions.node.split(".")[0]')"
if [ "${actual}" != "${expected%%.*}" ]; then
  echo "빌드 agent 의 Node 메이저(${actual})가 frontend/.nvmrc(${expected})와 다릅니다."
  exit 1
fi
if [ "$(uname -s)" != Linux ] || [ -z "$(node -p 'process.report.getReport().header.glibcVersionRuntime || ""')" ]; then
  echo '빌드 agent 는 glibc 리눅스여야 합니다(alpine · macOS 빌드 산출물은 런타임 이미지에서 뜨지 않는다).'
  exit 1
fi
# turbo 는 PATH 의 pnpm 을 부른다. 전역 corepack enable 은 root 가 필요해 워크스페이스에 pnpm 심을 둔다(packageManager 버전을 따른다)
mkdir -p "${WORKSPACE}/.corepack-bin"
corepack enable --install-directory "${WORKSPACE}/.corepack-bin" pnpm
PATH="${WORKSPACE}/.corepack-bin:${PATH}" pnpm --version
'''
}

// 검사 · 빌드 · 묶음. 배포 빌드(WITH_VAULT=true)일 때만 Vault 에서 빌드 시점 키를 읽어 이 셸의 env 로만 넘긴다 — 값은 Groovy 를 지나지 않는다.
// PR · CI 빌드는 Vault 를 읽지 않고 앱 기본값으로 빌드한다(배포하지 않으므로 박히는 값이 쓰이지 않는다).
String buildShellScript() {
    return '''#!/usr/bin/env bash
set +x
set -euo pipefail
umask 077

WORK_DIR="${WORKSPACE}/.build-secrets"
VALUES_JSON="${WORK_DIR}/values.json"
rm -rf "${WORK_DIR}"
''' + vaultShellFunctions() + '''
on_exit() {
  status=$?
  set +e
  revoke_token
  rm -rf "${WORK_DIR}"
  exit "${status}"
}
trap on_exit EXIT

cd frontend
umask 022
# toolchainCheckScript 가 둔 pnpm 심(turbo 가 PATH 의 pnpm 을 부른다)
export PATH="${WORKSPACE}/.corepack-bin:${PATH}"
export NEXT_TELEMETRY_DISABLED=1 TURBO_TELEMETRY_DISABLED=1
pnpm install --frozen-lockfile

if [ "${RUN_TESTS}" = true ]; then
  # frontend-ci 와 같은 검사. 테스트가 0건이면 실패한다(frontend-test-count.mjs)
  pnpm format:check
  pnpm lint
  pnpm typecheck
  pnpm test:ci
  node ../.github/scripts/frontend-test-count.mjs test-results/vitest.json
else
  echo '경고: RUN_TESTS=false — format · lint · typecheck · test 없이 빌드만 합니다.'
fi

if [ "${WITH_VAULT}" = true ]; then
  ( umask 077; mkdir -m 700 "${WORK_DIR}" )
  read_vault_secret
  # 필수 빌드 키(이름만 출력)
  missing="$(jq -r --arg req "${BUILD_REQUIRED_KEYS}" '. as $d | $req | split(" ")[] | select(length > 0)
    | select(($d[.] // "") | test("^[[:space:]]*$"))' "${VALUES_JSON}")"
  if [ -n "${missing}" ]; then
    echo "${VAULT_SECRET_PATH} 에 빌드 필수 키가 없거나 비어 있습니다(deploy.md §2): $(printf '%s' "${missing}" | tr '\\n' ' ')"
    exit 1
  fi
  # turbo.json build.env 에 있는 키만 이 셸의 env 로 넘긴다. NUL 로 갈라 읽어 값의 줄바꿈 · 공백을 그대로 둔다
  count=0
  while IFS= read -r -d '' key && IFS= read -r -d '' value; do
    export "${key}=${value}"
    count=$((count + 1))
  done < <(jq -j --arg re "${BUILD_ENV_REGEX}" 'to_entries[] | select(.key | test($re)) | .key, "\\u0000", .value, "\\u0000"' "${VALUES_JSON}")
  rm -rf "${WORK_DIR}"
  echo "빌드 시점 키 ${count}개를 Vault 에서 넘깁니다(이름 규칙: turbo.json build.env)."
fi

pnpm exec turbo run build --filter="${PACKAGE_NAME}"

# standalone 번들 확인 — 서버 진입점 · static 이 없으면 이미지가 뜨지 않는다
APP_DIR="${FS_PATH#frontend/}"
test -s "${APP_DIR}/.next/standalone/${APP_DIR}/server.js"
test -d "${APP_DIR}/.next/static"
# Dockerfile 이 public 을 COPY 한다 — 없으면 빌드가 실패하므로 빈 디렉터리라도 만든다
mkdir -p "${APP_DIR}/public"
uname -m > "${APP_DIR}/${BUILD_ARCH_FILE}"
'''
}

// ── 배포 스크립트 ────────────────────────────────────────────────────────

// 배포 agent 의 sh 하나로 Vault 로그인 → 읽기 → 토큰 폐기 → .env.runtime 작성 → compose → 기동 · SSR 확인 → 정리까지 한다.
// 시크릿(role_id · secret_id · Vault 토큰 · env 값)은 이 스크립트 안에만 있다 — Groovy 변수 · 단계 인자(withEnv · writeFile)를 지나지 않아
// Pipeline Steps 화면 · build.xml 에 남지 않는다. role_id · secret_id 는 withCredentials 가 env 로 넣고 Jenkins 가 로그에서 가린다.
// .env.runtime 은 배포 경로가 아니라 워크스페이스의 700 디렉터리에 둔다 — docker 빌드 컨텍스트(배포 경로)에 들어갈 일이 없다.
// 실패하면 0 이 아닌 값으로 끝나고, compose up 뒤 실패면 컨테이너 로그를 Vault 값을 가려 출력한다.
String deployShellScript() {
    return '''#!/usr/bin/env bash
set +x
set -euo pipefail
umask 077

SERVICE_DIR="${HOME}/${DEPLOY_SUBDIR}"
WORK_DIR="${PWD}/.deploy-secrets"
STAGE_DIR="${PWD}/.deploy-stage"
VALUES_JSON="${WORK_DIR}/values.json"
MASKS_JSON="${WORK_DIR}/masks.json"
ENV_FILE="${WORK_DIR}/.env.runtime"
compose_started=0

# 이전 실행이 SIGKILL 등으로 trap 없이 끝났을 때 남은 시크릿 파일부터 지운다
rm -rf "${WORK_DIR}" "${STAGE_DIR}"
mkdir -m 700 "${WORK_DIR}"
''' + vaultShellFunctions() + '''
# 값을 가린 로그만 낸다. 가릴 목록이 없거나 jq 가 실패하면 로그를 내지 않는다(가리지 않은 줄이 새지 않게)
print_masked_logs() {
  echo "=== ${CONTAINER_NAME} 최근 로그 100줄 (Vault 값은 **** 로 가림) ==="
  if [ -s "${MASKS_JSON}" ]; then
    docker logs --tail 100 "${CONTAINER_NAME}" 2>&1 \
      | jq -Rr --slurpfile m "${MASKS_JSON}" 'reduce $m[0][] as $s (.; split($s) | join("****"))' \
      || echo '(로그를 가리지 못해 출력하지 않습니다)'
  else
    echo '(가릴 값 목록이 없어 로그를 출력하지 않습니다)'
  fi
}

on_exit() {
  status=$?
  set +e
  revoke_token
  if [ "${status}" -ne 0 ] && [ "${compose_started}" = 1 ]; then
    print_masked_logs
  fi
  # .env.runtime 은 성공 · 실패와 무관하게 지운다. 워크스페이스는 Groovy finally 의 deleteDir 도 지운다
  rm -rf "${WORK_DIR}" "${STAGE_DIR}"
  exit "${status}"
}
trap on_exit EXIT

# ── 아키텍처: 빌드 agent 와 배포 호스트가 같아야 standalone 의 네이티브 바이너리(sharp)가 뜬다(deploy.md §1)
# 묶음의 권한을 그대로 푼다(-p). umask 077 로 풀면 파일이 600 이 돼 이미지의 node 사용자가 읽지 못한다
( umask 022; mkdir "${STAGE_DIR}" && tar -xzpf "${BUNDLE_FILE}" -C "${STAGE_DIR}" )
build_arch="$(cat "${STAGE_DIR}/${APP_DIR}/${BUILD_ARCH_FILE}" 2>/dev/null || true)"
if [ -z "${build_arch}" ] || [ "${build_arch}" != "$(uname -m)" ]; then
  echo "빌드 agent 아키텍처(${build_arch:-알 수 없음})와 배포 호스트($(uname -m))가 다릅니다. builder-frontend 를 배포 호스트와 같은 아키텍처로 둡니다."
  exit 1
fi

read_vault_secret

# 실패 로그에서 가릴 값. 시크릿 이름(*_PASSWORD · *_SECRET* · *_KEY · *_TOKEN)은 길이와 무관하게, 그 밖은 8자 이상만.
# 여러 줄 값은 값 전체와 줄마다 가린다. 긴 값부터 바꿔 부분 겹침을 막는다
jq '[to_entries[] | .key as $k | ([.value] + (.value | split("\\n") | map(sub("\\r$"; "")))) as $parts
     | if ($k | test("(_PASSWORD$|_SECRET|_KEY$|_TOKEN$)")) then $parts[] | select(length > 0) else $parts[] | select(length >= 8) end]
     | unique | sort_by(-length)' "${VALUES_JSON}" > "${MASKS_JSON}"

# ── 필수 키 · 값 검사 (키 이름만 출력한다)
missing="$(jq -r --arg req "${REQUIRED_KEYS}" '. as $d | $req | split(" ")[] | select(length > 0)
  | select(($d[.] // "") | test("^[[:space:]]*$"))' "${VALUES_JSON}")"
if [ -n "${missing}" ]; then
  echo "${VAULT_SECRET_PATH} 에 필수 키가 없거나 비어 있습니다(deploy.md §2 · §3): $(printf '%s' "${missing}" | tr '\\n' ' ')"
  exit 1
fi
# 작은따옴표(39) · 줄바꿈(10 · 13)이 든 값은 KEY='value' 로 쓸 수 없다
bad_values="$(jq -r --arg keep "${KEEP_KEYS}" '($keep | split(" ") | map(select(length > 0))) as $k
  | to_entries[] | select(.key | IN($k[])) | select(.value | explode | any(. == 39 or . == 10 or . == 13)) | .key' "${VALUES_JSON}")"
if [ -n "${bad_values}" ]; then
  echo "값에 작은따옴표 · 줄바꿈이 있어 .env.runtime 에 쓸 수 없는 키: $(printf '%s' "${bad_values}" | tr '\\n' ' ')"
  exit 1
fi
own_port="$(jq -r --arg k "${OWN_PORT_KEY}" '.[$k] // ""' "${VALUES_JSON}")"
if ! [[ "${own_port}" =~ ^[1-9][0-9]{0,4}$ ]] || [ "${own_port}" -gt 65535 ]; then
  echo "${OWN_PORT_KEY} 는 1~65535 정수여야 합니다."
  exit 1
fi
DOCKER_NETWORK="$(jq -r '.COMMERCE_DOCKER_NETWORK // ""' "${VALUES_JSON}")"
if ! [[ "${DOCKER_NETWORK}" =~ ^[A-Za-z0-9][A-Za-z0-9_.-]{0,62}$ ]]; then
  echo 'COMMERCE_DOCKER_NETWORK 가 docker 네트워크 이름 규칙에 맞지 않습니다.'
  exit 1
fi

# ── .env.runtime: compose 가 읽는 키만, KEY='value', 키 순, 반대 환경 포트는 '0' 자리값, 권한 600
jq -r --arg keep "${KEEP_KEYS}" --arg ph "${PLACEHOLDER_KEY}" '($keep | split(" ") | map(select(length > 0 and . != $ph))) as $k
  | ([to_entries[] | select(.key | IN($k[]))] + [{key: $ph, value: "0"}]) | sort_by(.key)[]
  | .key + "=" + ([39] | implode) + .value + ([39] | implode)' "${VALUES_JSON}" > "${ENV_FILE}"
chmod 600 "${ENV_FILE}"
echo "필수 키 확인 완료 / .env.runtime 키 $(wc -l < "${ENV_FILE}" | tr -d ' ')개(자리값 ${PLACEHOLDER_KEY}='0' 포함) / Vault 키 $(jq 'length' "${VALUES_JSON}")개 중 compose 가 읽는 키만 씀"

# ── 배포 경로: frontend/ 와 같은 배치(apps/<app>/...)로 묶음을 옮긴다. compose 의 빌드 컨텍스트(../..)가 이 경로다
umask 022
mkdir -p "${SERVICE_DIR}"
rsync -a --delete "${STAGE_DIR}/" "${SERVICE_DIR}/"
cd "${SERVICE_DIR}"
test -s "${APP_DIR}/.next/standalone/${APP_DIR}/server.js"
test -s "${APP_DIR}/${COMPOSE_FILE}"
# Dockerfile 전용 ignore 가 산출물 말고는 빌드 컨텍스트에 보내지 않는다 — 없으면 배포 경로 전체가 docker 데몬으로 간다
test -s "${APP_DIR}/${DOCKERFILE}.dockerignore"

# ── 네트워크는 만들지 않는다. 백엔드 파이프라인이 만들고 게이트웨이가 붙어 있어야 BFF 가 동작한다
if ! docker network inspect "${DOCKER_NETWORK}" >/dev/null 2>&1; then
  echo "네트워크 ${DOCKER_NETWORK} 가 없습니다. 같은 환경의 백엔드(api-gateway)를 먼저 배포합니다(deploy.md §3)."
  exit 1
fi
# dev · prod 는 네트워크를 공유하지 않는다(backend deploy-guide.md §6)
gateway_found=0
for attached in $(docker network inspect -f '{{range .Containers}}{{.Name}} {{end}}' "${DOCKER_NETWORK}"); do
  case "${attached}" in
    commerce-*-"${OPPOSITE_ENV}")
      echo "네트워크 ${DOCKER_NETWORK} 에 반대 환경 컨테이너 ${attached} 가 붙어 있습니다. 환경마다 네트워크를 따로 둡니다."
      exit 1
      ;;
    commerce-api-gateway-"${DEPLOY_ENV}") gateway_found=1 ;;
    commerce-*-"${DEPLOY_ENV}") ;;
    *) echo "경고: 네트워크 ${DOCKER_NETWORK} 에 이 프로젝트 밖 컨테이너 ${attached} 가 붙어 있습니다." ;;
  esac
done
if [ "${gateway_found}" != 1 ]; then
  # SSR 첫 화면은 게이트웨이 없이도 뜬다. 배포는 하되 BFF 요청이 503 이 되는 상태임을 남긴다
  echo "경고: 네트워크 ${DOCKER_NETWORK} 에 commerce-api-gateway-${DEPLOY_ENV} 가 없습니다. BFF 요청은 게이트웨이가 뜰 때까지 503 입니다."
fi

# 같은 이름의 컨테이너가 다른 compose 프로젝트(손으로 띄운 것 등)에 묶여 있으면 compose 가 이름 충돌로 실패한다. 지우지 않고 멈춘다
existing_project="$(docker inspect -f '{{ index .Config.Labels "com.docker.compose.project" }}' "${CONTAINER_NAME}" 2>/dev/null || true)"
if [ -n "${existing_project}" ] && [ "${existing_project}" != "${COMPOSE_PROJECT}" ]; then
  echo "컨테이너 ${CONTAINER_NAME} 가 다른 compose 프로젝트(${existing_project})에 있습니다. 확인한 뒤 손으로 지우고 다시 실행하세요."
  exit 1
fi

compose() { docker compose -p "${COMPOSE_PROJECT}" --env-file "${ENV_FILE}" -f "${APP_DIR}/${COMPOSE_FILE}" "$@"; }
compose config -q

# up 전 컨테이너(있으면)의 ID · 재시작 횟수. compose 가 다시 만들지 않았으면(이미지 · 설정이 같음) 그 횟수와 비교한다
before="$(docker inspect -f '{{.Id}} {{.RestartCount}}' "${CONTAINER_NAME}" 2>/dev/null || true)"
compose_started=1
compose up -d --build --wait --wait-timeout "${WAIT_TIMEOUT}" --remove-orphans "${COMPOSE_SERVICE}"

# --wait 는 healthy 한 번을 본다. 기동 직후 죽거나 restart 가 재시작 루프로 숨기는 것을 막으려고 잠시 뒤 다시 본다
sleep "${SETTLE_SECONDS}"
after="$(docker inspect -f '{{.Id}} {{.RestartCount}} {{.State.Status}} {{if .State.Health}}{{.State.Health.Status}}{{else}}none{{end}}' "${CONTAINER_NAME}" 2>/dev/null || true)"
read -r after_id after_restarts after_status after_health <<< "${after:-}" || true
expected_restarts=0
if [ -n "${before}" ] && [ "${before%% *}" = "${after_id:-}" ]; then
  expected_restarts="${before##* }"
  echo "compose 가 컨테이너를 다시 만들지 않았습니다(이미지 · 설정 같음). 재시작 횟수를 up 전 값(${expected_restarts})과 비교합니다."
fi
if [ "${after_status:-}" != running ] || [ "${after_health:-}" != healthy ] || [ "${after_restarts:-}" != "${expected_restarts}" ]; then
  echo "컨테이너가 기동 뒤 안정되지 않았습니다 (status=${after_status:-<none>} health=${after_health:-<none>} restartCount=${after_restarts:-<none>}, 기대 ${expected_restarts})."
  exit 1
fi

# ── SSR 첫 화면: 컨테이너 안에서 부른다(바인드 주소와 무관하게). 리다이렉트를 따라가지 않고 200 만 통과
smoke_status="$(docker exec -e SMOKE_PATH="${SMOKE_PATH}" "${CONTAINER_NAME}" node -e \
  "fetch('http://127.0.0.1:3000' + process.env.SMOKE_PATH, { redirect: 'manual' }).then((r) => console.log(r.status), () => console.log('fetch-failed'))" \
  2>/dev/null || echo exec-failed)"
if [ "${smoke_status}" != 200 ]; then
  echo "SSR 첫 화면 ${SMOKE_PATH} 가 200 이 아닙니다(${smoke_status})."
  exit 1
fi
echo "SSR 첫 화면 ${SMOKE_PATH} 200"
compose ps "${COMPOSE_SERVICE}"
'''
}

// ── 빌드 설명 · 승인 ─────────────────────────────────────────────────────

// 빌드 설명은 Markup Formatter 설정에 따라 HTML 로 렌더링될 수 있다. 사람이 넣은 문자열은 이스케이프한다.
@NonCPS
String escapeHtml(String text) {
    return (text ?: '').replace('&', '&amp;').replace('<', '&lt;').replace('>', '&gt;').replace('"', '&quot;').replace("'", '&#39;')
}

// main(prod) 의 FORCE_DEPLOY 는 승인자가 앱 이름을 다시 입력해 승인해야 진행한다. executor 를 잡지 않고 기다린다.
// 승인자는 Jenkins 전역 env COMMERCE_PROD_DEPLOY_APPROVERS(백엔드와 같은 값)다. 없으면 실패한다(fail-closed).
// 실행자 본인의 승인은 거부한다. 혼자 운영하는 동안만 전역 env COMMERCE_PROD_DEPLOY_ALLOW_SELF_APPROVAL=true 로 허용한다.
void confirmProdForceDeploy(Map config, Map ctx) {
    String approvers = env.COMMERCE_PROD_DEPLOY_APPROVERS?.trim()
    if (!approvers) {
        error '운영 강제 배포 승인자(Jenkins 전역 env COMMERCE_PROD_DEPLOY_APPROVERS)가 없어 멈춥니다. (#197)'
    }
    boolean allowSelf = env.COMMERCE_PROD_DEPLOY_ALLOW_SELF_APPROVAL?.trim() == 'true'
    def answer
    timeout(time: 30, unit: 'MINUTES') {
        answer = input(
            message: "운영(prod)에 ${config.appName} 를 강제 배포합니다. 커밋 ${ctx.commit.toString().take(12)}. 계속하려면 앱 이름을 그대로 입력하세요.".toString(),
            ok: '운영 배포 승인',
            submitter: approvers,
            submitterParameter: 'APPROVER',
            parameters: [string(name: 'CONFIRM_APP', defaultValue: '', description: "배포할 앱 이름(${config.appName})".toString())]
        )
    }
    String approver = answer['APPROVER']?.toString()?.trim() ?: ''
    String typed = answer['CONFIRM_APP']?.toString()?.trim()
    if (typed != config.appName) {
        error "입력한 앱 이름이 ${config.appName} 와 달라 운영 강제 배포를 멈춥니다."
    }
    if (approver == ctx.triggeredBy && !allowSelf) {
        error "실행자(${approver})가 직접 승인했습니다. 다른 승인자가 승인해야 합니다(혼자 운영이면 COMMERCE_PROD_DEPLOY_ALLOW_SELF_APPROVAL=true)."
    }
    echo "운영 강제 배포 승인: ${approver}${approver == ctx.triggeredBy ? ' (실행자 본인 — 허용 설정)' : ''}"
    currentBuild.description = "${currentBuild.description ?: ''} / 승인 ${escapeHtml(approver)}".toString()
}

// Vault 전역 설정 · credential 을 넣고 body 를 부른다. 값은 sh 안에서만 쓴다.
void withVaultEnv(String deployEnv, Closure body) {
    String vaultAddr = env.VAULT_ADDR?.trim()
    if (!vaultAddr) {
        error 'Jenkins 전역 환경변수 VAULT_ADDR 가 없습니다. (#197 — Manage Jenkins > System > Global properties)'
    }
    Map vaultSpec = resolveVaultSpec(deployEnv)
    Map credentialIds = vaultCredentialIds(deployEnv)
    withCredentials([
        string(credentialsId: credentialIds.roleId as String, variable: 'VAULT_ROLE_ID'),
        string(credentialsId: credentialIds.secretId as String, variable: 'VAULT_SECRET_ID')
    ]) {
        withEnv([
            "VAULT_BASE=${vaultAddr.replaceAll('/+$', '')}",
            "VAULT_AUTH_PATH=${env.VAULT_AUTH_PATH?.trim() ?: 'approle'}",
            "VAULT_API_PATH=${vaultSpec.apiPath}",
            "VAULT_SECRET_PATH=${vaultSpec.path}"
        ]) {
            body()
        }
    }
}

// ── 실행 ─────────────────────────────────────────────────────────────────

void run(Map config) {
    properties([
        buildDiscarder(logRotator(numToKeepStr: '30')),
        disableConcurrentBuilds(),
        parameters([
            booleanParam(
                name: 'RUN_TESTS',
                defaultValue: true,
                description: '빌드 전에 format · lint · typecheck · test 를 돌린다(frontend-ci 와 같다). 끄지 않는다.'
            ),
            booleanParam(
                name: 'SKIP_DEPLOY',
                defaultValue: false,
                description: '배포 브랜치여도 배포하지 않고 빌드만 한다.'
            ),
            booleanParam(
                name: 'FORCE_DEPLOY',
                defaultValue: false,
                description: '변경 경로와 PR 라벨 게이트를 건너뛰고 이 앱을 이 브랜치의 환경(develop → dev, main → prod)으로 배포한다. '
                    + '첫 배포 · 라벨을 빠뜨린 머지 · 이전 커밋으로 되돌리기에 쓴다. main 은 다른 승인자의 입력 승인을 받는다. PR 빌드 · 다른 브랜치에서는 실패한다.'
            ),
            string(
                name: 'FORCE_DEPLOY_REASON',
                defaultValue: '',
                description: 'FORCE_DEPLOY 사유(필수, 200자 이내). 빌드 설명과 로그에 남는다. 시크릿 · 고객 정보를 쓰지 않는다.'
            ),
            string(
                name: 'DEPLOY_COMMIT',
                defaultValue: '',
                description: 'FORCE_DEPLOY 와 함께만 쓴다. 이 브랜치에 이미 머지된 커밋 SHA 로 되돌려 배포한다. 비우면 브랜치 최신.'
            )
        ])
    ])

    List<String> errors = configErrors(config)
    if (errors) {
        error "Jenkinsfile config 가 규칙과 다릅니다:\n - ${errors.join('\n - ')}"
    }

    Map ctx = [:]
    boolean forceDeploy = params.FORCE_DEPLOY == true
    String appDir = (config.fsPath as String).substring('frontend/'.length())

    stage('배포 문맥 확인') {
        node(config.buildAgentLabel) {
            try {
                deleteDir()
                checkoutSource()
                ctx = resolveGitContext()

                // 배포 단위 파일이 없으면(앱을 추가하기 전에 잡을 등록한 경우 등) 빌드 전에 멈춘다
                List<String> missingFiles = []
                for (String unitFile : [config.fsPath + '/package.json', config.fsPath + '/' + config.dockerfile,
                                        config.fsPath + '/' + config.dockerfile + '.dockerignore',
                                        config.fsPath + '/' + config.composeFile, 'frontend/turbo.json']) {
                    if (!fileExists(unitFile)) {
                        missingFiles.add(unitFile)
                    }
                }
                if (missingFiles) {
                    error "배포 단위 파일이 없습니다: ${missingFiles.join(', ')}. 앱 · Dockerfile · compose 를 먼저 추가하고(deploy.md §1) 잡을 등록합니다."
                }

                if (forceDeploy) {
                    if (ctx.isPullRequest == 'true' || !(ctx.deployEnv in ['dev', 'prod'])) {
                        error "FORCE_DEPLOY 는 develop · main 브랜치 잡에서만 씁니다. (현재 ${ctx.isPullRequest == 'true' ? 'PR 빌드' : ctx.branch})"
                    }
                    String user = resolveTriggeringUser()
                    if (!user) {
                        error 'FORCE_DEPLOY 는 사람이 직접 실행한 빌드에서만 씁니다. (실행 사용자를 찾지 못했습니다)'
                    }
                    String reason = params.FORCE_DEPLOY_REASON?.trim()
                    if (!reason) {
                        error 'FORCE_DEPLOY 에는 FORCE_DEPLOY_REASON 이 필요합니다.'
                    }
                    if (reason.length() > 200) {
                        error "FORCE_DEPLOY_REASON 은 200자 이내로 씁니다(지금 ${reason.length()}자)."
                    }
                    ctx.triggeredBy = user
                    if (params.DEPLOY_COMMIT?.trim()) {
                        pinDeployCommit(params.DEPLOY_COMMIT as String)
                    }
                    String now = sh(returnStdout: true, script: 'date -u +%Y-%m-%dT%H:%M:%SZ').trim()
                    echo "FORCE_DEPLOY — 실행 ${user} · ${now} · 환경 ${ctx.deployEnv} · 사유: ${escapeHtml(reason)}"
                    currentBuild.description = "강제 배포(${ctx.deployEnv}) by ${escapeHtml(user)}: ${escapeHtml(reason)}".toString()
                } else if (params.DEPLOY_COMMIT?.trim()) {
                    error 'DEPLOY_COMMIT 은 FORCE_DEPLOY 와 함께만 씁니다.'
                }
                ctx.commit = sh(returnStdout: true, script: 'git rev-parse HEAD').trim()

                // 빌드 시점 키 이름(turbo.json build.env). 값은 빌드 sh 가 Vault 에서 읽는다
                Map buildKeys = buildEnvKeyPattern((readJSON(file: 'frontend/turbo.json')?.tasks?.build?.env ?: []).collect { it.toString() })
                if (buildKeys.errors) {
                    error "빌드 시점 키를 정할 수 없습니다:\n - ${(buildKeys.errors as List<String>).join('\n - ')}"
                }
                ctx.buildEnvRegex = buildKeys.regex

                if (forceDeploy) {
                    ctx.appAffected = 'true'
                    ctx.deployLabelAllowed = 'true'
                } else {
                    // 모노레포라 push 한 번에 잡 전부가 돈다. 이 앱과 무관한 변경이면 빌드 · 배포를 건너뛴다
                    List<String> changedFiles = resolveChangedFiles(ctx)
                    Map affected = classifyAffectedScope(config.fsPath as String, config.appName as String, changedFiles)
                    List<String> matched = affected.matched as List<String>
                    echo "영향 판정: ${affected.scope} — ${affected.reason}${matched ? ': ' + matched.take(10).join(', ') + (matched.size() > 10 ? ' ...' : '') : ''}"
                    String scope = affected.scope as String

                    // 파이프라인 정의 파일'만' 바뀐 PR 은 라벨로 한 번 더 거른다. 다른 앱 · 백엔드 라벨만 붙은 PR 이 공용 groovy 를
                    // 고치면 프론트 잡 전부가 빌드를 돌아 빌더를 점유하는 것을 막는다. 라벨 조회에 실패하면 그대로 돈다(fail-open).
                    if (ctx.isPullRequest == 'true' && scope == 'pipeline') {
                        Map prLabels = resolveDeployLabelContext(ctx)
                        if (prLabels.resolved == 'true') {
                            List<String> scoped = (prLabels.labels as List<String>).findAll {
                                (it.startsWith('backend-') || it.startsWith('frontend-')) && it != 'frontend-web'
                            }
                            String expected = "${deployLabelPrefix()}${config.appName}".toString()
                            if (scoped && !scoped.contains(expected)) {
                                echo "PR 라벨(${scoped.join(', ')})이 다른 배포 단위를 가리켜 파이프라인 정의 변경 CI 를 건너뜁니다."
                                ctx.skipReason = "PR 라벨 범위 밖(${scoped.join(', ')}) - CI 생략".toString()
                                scope = 'none'
                            }
                        }
                    }
                    ctx.appAffected = scope != 'none' ? 'true' : 'false'

                    if (ctx.deployEnv in ['dev', 'prod'] && ctx.appAffected == 'true') {
                        if (!hasUsableBaseline()) {
                            // 첫 빌드 · 잡 재등록 · 브랜치 인덱싱으로 이전 성공 커밋이 없으면 무엇이 바뀌었는지 모른다. 자동 배포하지 않는다(fail-closed).
                            echo '이전 성공 빌드 커밋이 없거나 저장소에서 찾을 수 없어(첫 빌드 · 잡 재등록 · 인덱싱) 자동 배포하지 않습니다. 배포하려면 FORCE_DEPLOY 로 실행하세요.'
                            ctx.deployLabelAllowed = 'false'
                            ctx.skipReason = '이전 성공 커밋 없음 - 자동 배포 안 함(FORCE_DEPLOY 필요)'
                        } else {
                            Map labelContext = resolveMergedDeployLabelContext(config, ctx)
                            ctx.deployLabelAllowed = isAppDeployAllowedByLabels(config, labelContext) ? 'true' : 'false'
                        }
                    } else {
                        // PR 빌드와 배포 브랜치가 아닌 빌드는 어차피 배포하지 않으므로 라벨을 조회하지 않는다
                        ctx.deployLabelAllowed = 'true'
                    }
                }

                currentBuild.displayName = "#${env.BUILD_NUMBER} ${config.appName} ${env.BRANCH_NAME ?: 'n/a'}".toString()
                echo '=== 빌드 문맥 ==='
                echo "브랜치: ${ctx.branch ?: '없음'} / PR 빌드: ${ctx.isPullRequest}${ctx.changeTarget ? ' → ' + ctx.changeTarget : ''}"
                echo "앱: ${config.packageName} / 커밋: ${ctx.commit.toString().take(12)}"
                echo "영향 있음: ${ctx.appAffected} / 라벨 배포 허용: ${ctx.deployLabelAllowed} / 강제 배포: ${forceDeploy}"
                echo "배포 환경: ${ctx.deployEnv}"
                if (ctx.deployEnv in ['dev', 'prod']) {
                    echo "배포 agent: ${resolveDeployAgentLabel(config, ctx)} / 경로: \$HOME/${deployBaseDir()}/${config.deploySubdir}"
                    Map credentialIds = vaultCredentialIds(ctx.deployEnv as String)
                    echo "Vault: ${vaultSecretRoot()}/${ctx.deployEnv}/env (credential ${credentialIds.roleId}) / lock: ${deployLockName()}"
                }
                echo '================='
            } finally {
                deleteDir()
            }
        }
    }

    if (ctx.appAffected == 'false') {
        stage('변경 없음 - 생략') {
            echo "이번 변경은 ${config.appName} 에 영향이 없어 빌드 · 배포를 건너뜁니다."
            currentBuild.description = ctx.skipReason ?: '변경 없음 - 빌드 · 배포 생략'
        }
        return
    }

    boolean shouldDeploy = ctx.deployEnv in ['dev', 'prod'] && ctx.isPullRequest != 'true' && params.SKIP_DEPLOY != true && ctx.deployLabelAllowed == 'true'

    // 배포하지 않는 브랜치 빌드는 빌드도 하지 않는다. CI 는 PR 빌드(GitHub Actions frontend-ci · Jenkins PR 빌드)가 맡는다.
    // SKIP_DEPLOY 는 "배포 없이 빌드만" 이 목적이라 이 생략에서 뺀다.
    if (ctx.isPullRequest != 'true' && params.SKIP_DEPLOY != true && !shouldDeploy) {
        stage('배포 대상 아님 - 생략') {
            echo "배포하지 않는 브랜치 빌드라 빌드를 생략합니다. deployEnv=${ctx.deployEnv}, deployLabelAllowed=${ctx.deployLabelAllowed}"
            echo '이 커밋을 배포하려면 FORCE_DEPLOY 로 실행하세요.'
            if (!currentBuild.description) {
                currentBuild.description = ctx.skipReason ?: (ctx.deployLabelAllowed == 'false' ? '배포 라벨 없음 - 빌드 · 배포 생략' : '배포 브랜치 아님 - 빌드 · 배포 생략')
            }
        }
        return
    }

    if (shouldDeploy && forceDeploy && ctx.deployEnv == 'prod') {
        stage('운영 강제 배포 승인') {
            confirmProdForceDeploy(config, ctx)
        }
    }

    stage('검사 · 빌드') {
        node(config.buildAgentLabel) {
            try {
                deleteDir()
                checkoutSource()
                String built = sh(returnStdout: true, script: 'git rev-parse HEAD').trim()
                if (ctx.isPullRequest != 'true' && built != ctx.commit) {
                    error "빌드 커밋(${built.take(12)})이 판단한 커밋(${ctx.commit.toString().take(12)})과 다릅니다."
                }
                List<String> buildEnv = [
                    "RUN_TESTS=${params.RUN_TESTS != false}",
                    "WITH_VAULT=${shouldDeploy}",
                    "PACKAGE_NAME=${config.packageName}",
                    "FS_PATH=${config.fsPath}",
                    "BUILD_ARCH_FILE=${buildArchFileName()}",
                    "BUILD_ENV_REGEX=${ctx.buildEnvRegex}",
                    "BUILD_REQUIRED_KEYS=${((config.buildRequiredKeys ?: []) as List).join(' ')}",
                    'CI=true',
                    'COREPACK_ENABLE_DOWNLOAD_PROMPT=0'
                ]
                withEnv(buildEnv) {
                    sh toolchainCheckScript()
                    if (shouldDeploy) {
                        // 빌드 시점 키(NEXT_PUBLIC_* 등)는 배포할 환경의 Vault secret 에서 읽는다 — 환경마다 따로 빌드한다(deploy.md §2)
                        withVaultEnv(ctx.deployEnv as String) {
                            sh buildShellScript()
                        }
                    } else {
                        sh buildShellScript()
                    }
                }

                if (shouldDeploy) {
                    // 이미지에 들어갈 산출물 + Dockerfile · ignore · compose · 아키텍처 기록만 묶는다. 경로는 frontend/ 기준
                    withEnv(["APP_DIR=${appDir}", "DOCKERFILE=${config.dockerfile}", "COMPOSE_FILE=${config.composeFile}",
                             "BUILD_ARCH_FILE=${buildArchFileName()}", "BUNDLE_FILE=${bundleFileName()}"]) {
                        sh '''#!/usr/bin/env bash
set -euo pipefail
tar -czf "${BUNDLE_FILE}" -C frontend \
  "${APP_DIR}/.next/standalone" "${APP_DIR}/.next/static" "${APP_DIR}/public" \
  "${APP_DIR}/${DOCKERFILE}" "${APP_DIR}/${DOCKERFILE}.dockerignore" "${APP_DIR}/${COMPOSE_FILE}" "${APP_DIR}/${BUILD_ARCH_FILE}"
ls -l "${BUNDLE_FILE}"
'''
                    }
                    stash(name: "bundle-${config.appName}".toString(), includes: bundleFileName(), useDefaultExcludes: false)
                } else {
                    echo '배포하지 않는 빌드(CI)라 산출물을 넘기지 않습니다.'
                }
            } finally {
                deleteDir()
            }
        }
    }

    if (!shouldDeploy) {
        stage('배포 생략') {
            echo "배포를 생략합니다. deployEnv=${ctx.deployEnv}, skipDeploy=${params.SKIP_DEPLOY == true}, isPullRequest=${ctx.isPullRequest}, deployLabelAllowed=${ctx.deployLabelAllowed}"
        }
        return
    }

    stage("${ctx.deployEnv} 환경 배포") {
        lock(resource: deployLockName()) {
            node(resolveDeployAgentLabel(config, ctx)) {
                try {
                    deleteDir()
                    unstash "bundle-${config.appName}".toString()

                    // 키 이름만 Groovy 에서 정한다. 값은 deployShellScript 안에서만 다룬다. compose 는 묶음 안의 것을 읽는다
                    sh "tar -xzOf '${bundleFileName()}' '${appDir}/${config.composeFile}' > compose-keys.yml"
                    String composeText = readFile(file: 'compose-keys.yml', encoding: 'UTF-8')
                    Map keys = planRuntimeKeys(config.appName as String, ctx.deployEnv as String, composeText)
                    if (keys.errors) {
                        error "배포 env 를 만들 수 없습니다:\n - ${(keys.errors as List<String>).join('\n - ')}"
                    }
                    String containerName = "${config.containerNamePrefix}-${ctx.deployEnv}".toString()

                    int status = 0
                    withVaultEnv(ctx.deployEnv as String) {
                        withEnv([
                            "KEEP_KEYS=${(keys.keep as List<String>).join(' ')}",
                            "REQUIRED_KEYS=${(keys.required as List<String>).join(' ')}",
                            "PLACEHOLDER_KEY=${keys.placeholder}",
                            "OWN_PORT_KEY=${keys.ownPort}",
                            "DEPLOY_SUBDIR=${deployBaseDir()}/${config.deploySubdir}",
                            "APP_DIR=${appDir}",
                            "DOCKERFILE=${config.dockerfile}",
                            "BUNDLE_FILE=${pwd()}/${bundleFileName()}",
                            "BUILD_ARCH_FILE=${buildArchFileName()}",
                            "COMPOSE_PROJECT=${containerName}",
                            "COMPOSE_FILE=${config.composeFile}",
                            "COMPOSE_SERVICE=${config.composeServiceName}-${ctx.deployEnv}",
                            "CONTAINER_NAME=${containerName}",
                            "DEPLOY_ENV=${ctx.deployEnv}",
                            "OPPOSITE_ENV=${ctx.deployEnv == 'prod' ? 'dev' : 'prod'}",
                            "SMOKE_PATH=${config.smokePath}",
                            "WAIT_TIMEOUT=${composeWaitTimeoutSeconds()}",
                            "SETTLE_SECONDS=${postDeploySettleSeconds()}"
                        ]) {
                            status = sh(returnStatus: true, script: deployShellScript())
                        }
                    }
                    if (status != 0) {
                        error "${config.appName} ${ctx.deployEnv} 배포에 실패했습니다(exit ${status}). 대응: frontend/docs/deploy.md §7"
                    }
                    currentBuild.description = "${currentBuild.description ? currentBuild.description + ' / ' : ''}${ctx.deployEnv} 배포 ${ctx.commit.toString().take(8)}".toString()
                } finally {
                    // .env.runtime 은 워크스페이스(.deploy-secrets)에만 있다. 스크립트가 SIGKILL 로 끝났어도 deleteDir 가 지운다
                    deleteDir()
                }
            }
        }
    }
}

return this
