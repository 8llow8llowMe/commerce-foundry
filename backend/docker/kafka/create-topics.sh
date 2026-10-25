#!/usr/bin/env sh
# 로컬 Kafka(docker-compose-local.yml)에 topics.txt 의 토픽을 만든다 (#310). 이미 있는 토픽은 건너뛴다 — 여러 번 돌려도 된다.
#
#   backend 디렉터리에서: sh docker/kafka/create-topics.sh
#
# 앱은 토픽을 만들지 않는다(브로커 자동 생성도 꺼 두었다). dev 공유 클러스터의 토픽은 Infra 와 정한 방식으로 만든다(deploy-guide.md) —
# 이 스크립트는 로컬 전용이다. 파티션 수를 줄이거나 이름을 바꾸는 변경은 하지 않는다(새로 만들 뿐이다).
set -eu

script_dir=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
backend_dir=$(CDPATH= cd -- "$script_dir/../.." && pwd)
topics_file="$script_dir/topics.txt"
compose="docker compose -f $backend_dir/docker-compose-local.yml"
# 컨테이너 안 관리 도구용 리스너(docker-compose-local.yml 의 INTERNAL)
bootstrap="localhost:19092"

created=0
while read -r topic partitions retention_ms extra; do
    case "$topic" in
        ''|'#'*) continue ;;
    esac
    if [ -n "${extra:-}" ] || [ -z "${partitions:-}" ] || [ -z "${retention_ms:-}" ]; then
        echo "topics.txt 줄 형식이 틀렸습니다 — <토픽> <파티션> <보존 ms>: $topic" >&2
        exit 1
    fi
    if ! printf '%s' "$topic" | grep -Eq '^[a-z0-9][a-z0-9._-]{0,248}$'; then
        echo "토픽 이름은 소문자 · 숫자 · '.' · '_' · '-' 만 씁니다: $topic" >&2
        exit 1
    fi
    # stdin 을 끊는다 — compose exec 가 표준 입력을 붙이면 while 이 읽는 topics.txt 의 남은 줄을 삼킨다
    $compose exec -T kafka /opt/kafka/bin/kafka-topics.sh --bootstrap-server "$bootstrap" \
        --create --if-not-exists --topic "$topic" --partitions "$partitions" --replication-factor 1 \
        --config "retention.ms=$retention_ms" < /dev/null
    created=$((created + 1))
done < "$topics_file"

echo "topics.txt 의 토픽 $created 개를 확인했습니다."
$compose exec -T kafka /opt/kafka/bin/kafka-topics.sh --bootstrap-server "$bootstrap" --list
