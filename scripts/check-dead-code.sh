#!/usr/bin/env bash
# 죽은 코드 게이트 — ADR-0008
#
# 선언됐지만 호출이 0회인 함수를 찾는다. 판정 근거는 사람이 읽고 센 값이 아니라
# server/src 전체에서 센 호출 횟수다.
#
# 왜 함수만 보는가 —
#   측정으로 찾은 죽은 코드 4건이 전부 함수였고(Spring Data 리포지토리 메서드 3건 +
#   포트 인터페이스 메서드 1건), detekt 의 미사용 코드 규칙은 전부 private 전용이라
#   그중 하나도 못 잡는다. 클래스·프로퍼티까지 넓히면 오탐이 늘고, 오탐이 늘면
#   규칙을 넓히지 말고 게이트를 지운다는 게 ADR-0008 의 규칙이다.
#
# 제외 — 프레임워크가 부르는 것은 소스에 호출부가 없다:
#   @Bean / @ExceptionHandler / 요청 매핑(@GetMapping 등) / JPA projection getter /
#   override (선언은 인터페이스 쪽에서 이미 센다) / main
#
# 브로드 규칙(제외 없음)은 24건을 뱉었고 그중 참이 4건이었다 — 오탐 83%.
# 위 제외를 적용하면 참 4건만 남는다. 이 목록을 늘려야 하는 상황이 오면
# 그때가 게이트를 지울 때다.
set -uo pipefail
cd "$(dirname "$0")/.."

declarations=$(
  find server/src/main/kotlin -name '*.kt' | while read -r f; do
    awk -v file="$f" '
      # 어노테이션은 다음 선언까지 쌓아둔다.
      # @ExceptionHandler(A::class, B::class) 처럼 여러 줄에 걸치므로 괄호 깊이를 센다 —
      # 깊이만 안 세면 두 번째 줄에서 ann 이 초기화돼 handleBadRequest 가 오탐으로 나온다
      depth > 0 { ann = ann " " $0; depth += gsub(/\(/, "(") - gsub(/\)/, ")"); next }
      /^[[:space:]]*@/ { ann = ann " " $0; depth = gsub(/\(/, "(") - gsub(/\)/, ")"); next }
      /^[[:space:]]*(\/\/|\*|\/\*)/ { next }
      /^[[:space:]]*$/ { next }

      /(^|[[:space:]])fun[[:space:]]/ {
        line = $0
        # override 는 인터페이스 선언 쪽에서 이미 센다
        if (line ~ /(^|[[:space:]])override[[:space:]]/) { ann = ""; next }
        if (ann ~ /@Bean|@ExceptionHandler|@(Get|Post|Put|Patch|Delete|Request)Mapping/) { ann = ""; next }

        # "fun " 뒤 ~ 첫 "(" 앞의 마지막 식별자가 이름이다.
        # 확장 함수(fun List<...>.toDenseRankEntries()) 에서 수신 타입을 집지 않으려는 것
        sub(/.*(^|[[:space:]])fun[[:space:]]+/, "", line)
        sub(/\(.*/, "", line)
        if (match(line, /[A-Za-z_][A-Za-z0-9_]*$/)) {
          name = substr(line, RSTART, RLENGTH)
          # JPA projection getter: 본문 없는 getXxx(). 프레임워크가 프록시로 호출한다
          if (name ~ /^get[A-Z]/ && $0 !~ /[={]/) { ann = ""; next }
          if (name != "main") print name "\t" file
        }
        ann = ""
        next
      }
      { ann = "" }
    ' "$f"
  done | sort -u
)

dead=""
while IFS=$'\t' read -r name file; do
  [ -z "$name" ] && continue
  calls=$(grep -rE "(\b$name[[:space:]]*\(|::$name\b)" server/src --include='*.kt' \
          | grep -vcE "(^|[[:space:]])fun[[:space:]]+([A-Za-z0-9_<>,?[:space:]]*\.)?$name[[:space:]]*\(")
  [ "$calls" = "0" ] && dead="$dead  $name — $file"$'\n'
done <<< "$declarations"

if [ -n "$dead" ]; then
  echo "❌ 선언됐지만 호출이 0회인 함수 $(printf '%s' "$dead" | grep -c .)건:"
  printf '%s' "$dead"
  exit 1
fi
echo "✅ 호출되지 않는 함수 없음"
