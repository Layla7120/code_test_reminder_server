#!/usr/bin/env bash
# 백틱 게이트 — ADR-0008 / ADR-0009
#
# 현재 층 문서와 .kt 주석에서 세 종류의 식별자가 실재하는지 확인한다:
#   ① `[A-Z]\w*Test`   ② 확장자 있는 파일 경로   ③ `uk_`/`fk_`/`idx_` 식별자
# 마크다운 상대 링크(④)는 층과 무관하게 전부 검사한다.
#
# 층 규칙 — ADR-0007:
#   과거(docs/기록.md, docs/archive/)     : 전부 제외. 그 시점의 사실이라 어긋나는 게 정상
#   미래(PLAN-*.md, 승인됨(미구현) ADR)   : ①②③ 제외. 아직 안 만든 것을 가리키는 게 정상
#                                           구현되면 ADR 상태를 '승인됨' 으로 바꾼다 → 그때부터 검사
#
# 오탐이 늘면 규칙을 넓히지 말고 이 게이트를 지운다. (ADR-0008)
set -uo pipefail
cd "$(dirname "$0")/.."   # 경로를 저장소 루트 기준으로 본다 — 어디서 부르든 같은 답이 나오게
out=$(mktemp)

is_future() {
  case "$1" in PLAN-*.md) return 0;; esac
  grep -q '승인됨(미구현)' "$1" 2>/dev/null
}

present=$( { find docs -name '*.md' -not -path 'docs/archive/*' -not -name '기록.md' -not -path 'docs/learning/*'
             ls README.md AGENTS.md PLAN-*.md 2>/dev/null
             find server/src -name '*.kt'; } | sort -u )

for f in $present; do
  is_future "$f" && continue
  grep -ohE '`[A-Z][A-Za-z0-9]*Test`' "$f" 2>/dev/null | tr -d '`' | sort -u | while read -r t; do
    find server/src/test -name "$t.kt" | grep -q . || echo "  [Test]   $f → $t" >> "$out"
  done
  # 확장자가 있는 것만 파일 경로로 본다 — 엔드포인트(/group/member)와 클래스(global/ServiceZone) 제외
  grep -ohE '`[A-Za-z0-9_./가-힣-]+/[A-Za-z0-9_.가-힣-]+\.[a-z]{1,4}`' "$f" 2>/dev/null | tr -d '`' | sort -u | while read -r p; do
    [ -e "$p" ] || [ -e "$(dirname "$f")/$p" ] || [ -e "server/$p" ] || [ -e "server/src/main/resources/$p" ] || echo "  [경로]   $f → $p" >> "$out"
  done
  grep -ohE '`(uk|fk|idx)_[a-z0-9_]+`' "$f" 2>/dev/null | tr -d '`' | sort -u | while read -r c; do
    grep -rqE "\b$c\b" server/src/main/resources/db/migration server/src/main/kotlin || echo "  [스키마] $f → $c" >> "$out"
  done
done

# ④ 링크는 층과 무관하게 전부 — 깨진 링크는 언제나 결함이다
{ find docs -name '*.md' -not -path 'docs/learning/*'; ls README.md AGENTS.md PLAN-*.md 2>/dev/null; } | while read -r f; do
  d=$(dirname "$f")
  grep -ohE '\]\([^)#][^)]*\)' "$f" 2>/dev/null | sed -E 's/^\]\(//; s/\)$//' | grep -v '^http' | while read -r l; do
    [ -e "$d/$l" ] || echo "  [링크]   $f → $l" >> "$out"
  done
done

if [ -s "$out" ]; then
  echo "❌ 실재하지 않는 참조 $(sort -u "$out" | wc -l | tr -d ' ')건:"; sort -u "$out"; rm -f "$out"; exit 1
fi
echo "✅ 모든 참조가 실재함"; rm -f "$out"
