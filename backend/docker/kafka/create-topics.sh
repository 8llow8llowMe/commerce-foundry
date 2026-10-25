#!/usr/bin/env sh
# topics.txt 의 토픽을 환경 namespace 로 풀어 만든다 (#310, 이름 규칙 #304 설계 A-2).
#
#   backend 디렉터리에서:
#     sh docker/kafka/create-topics.sh            로컬 compose Kafka 에 만든다(= local)
#     sh docker/kafka/create-topics.sh local      위와 같다
#     sh docker/kafka/create-topics.sh dev        만들지 않고, dev 에 만들 토픽 목록(이름 · 파티션 · 복제 · 보존)을 출력한다
#     sh docker/kafka/create-topics.sh prod       prod 목록을 출력한다
#
# 토픽 이름 = <namespace>.<topics.txt 의 이름>, namespace = commerce-foundry.<env>(환경변수 KAFKA_NAMESPACE 로 바꿀 수 있다 — 마지막 토막은 env 와 같아야 한다).
# 토픽마다 DLT(<토픽>.dlt)를 같이 만든다. 앱은 토픽을 만들지 않는다(브로커 자동 생성도 꺼 두었다).
# dev · prod 의 실제 생성은 Infra 레포의 선언 스크립트가 한다 — 이 스크립트는 그 목록을 출력할 뿐 원격 클러스터에 붙지 않는다.
# 파티션 수를 줄이거나 이름을 바꾸는 변경은 하지 않는다(--if-not-exists 로 새로 만들 뿐이다).
set -eu

env="${1:-local}"
case "$env" in
    local) partitions=3; replication=1 ;;
    dev) partitions=3; replication=3 ;;
    prod) partitions=6; replication=3 ;;
    *) echo "환경은 local · dev · prod 중 하나입니다: $env" >&2; exit 1 ;;
esac

namespace="${KAFKA_NAMESPACE:-commerce-foundry.$env}"
if ! printf '%s' "$namespace" | grep -Eq '^[a-z0-9]+(-[a-z0-9]+)*\.(local|dev|prod)$' || [ "${namespace##*.}" != "$env" ]; then
    echo "namespace 는 <이름>.<env> 이고 마지막 토막이 환경($env)과 같아야 합니다: $namespace" >&2
    exit 1
fi

script_dir=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
backend_dir=$(CDPATH= cd -- "$script_dir/../.." && pwd)
topics_file="${TOPICS_FILE:-$script_dir/topics.txt}"
compose="docker compose -f $backend_dir/docker-compose-local.yml"
# 컨테이너 안 관리 도구용 리스너(docker-compose-local.yml 의 INTERNAL)
bootstrap="localhost:19092"

create() {
    name="$1"
    retention_ms="$2"
    if [ "$env" = "local" ]; then
        # stdin 을 끊는다 — compose exec 가 표준 입력을 붙이면 while 이 읽는 topics.txt 의 남은 줄을 삼킨다
        $compose exec -T kafka /opt/kafka/bin/kafka-topics.sh --bootstrap-server "$bootstrap" \
            --create --if-not-exists --topic "$name" --partitions "$partitions" --replication-factor "$replication" \
            --config "retention.ms=$retention_ms" < /dev/null
    else
        printf '%s %s %s %s\n' "$name" "$partitions" "$replication" "$retention_ms"
    fi
}

count=0
while read -r topic retention_ms extra; do
    case "$topic" in
        ''|'#'*) continue ;;
    esac
    if [ -n "${extra:-}" ] || [ -z "${retention_ms:-}" ]; then
        echo "topics.txt 줄 형식이 틀렸습니다 — <생산서비스>.<aggregate>-<event> <보존 ms>: $topic" >&2
        exit 1
    fi
    if ! printf '%s' "$topic" | grep -Eq '^(auth|product|order|payment|delivery|operations)\.[a-z0-9]+(-[a-z0-9]+)+$'; then
        echo "토픽 이름은 <생산 서비스>.<aggregate>-<event> 입니다(소문자 · 숫자 · '-', 서비스는 auth · product · order · payment · delivery · operations): $topic" >&2
        exit 1
    fi
    if ! printf '%s' "$retention_ms" | grep -Eq '^[1-9][0-9]*$'; then
        echo "보존 기간은 양의 정수(ms)입니다: $topic" >&2
        exit 1
    fi
    if [ "$env" != "local" ] && [ "$count" -eq 0 ]; then
        echo "# $env 토픽 목록 — <이름> <파티션> <복제> <보존 ms>. Infra 선언 스크립트에 옮긴다"
    fi
    create "$namespace.$topic" "$retention_ms"
    create "$namespace.$topic.dlt" "$retention_ms"
    count=$((count + 1))
done < "$topics_file"

if [ "$env" = "local" ]; then
    echo "topics.txt 의 토픽 $count 개(DLT 포함 $((count * 2)) 개)를 확인했습니다. namespace=$namespace"
    $compose exec -T kafka /opt/kafka/bin/kafka-topics.sh --bootstrap-server "$bootstrap" --list < /dev/null
fi
