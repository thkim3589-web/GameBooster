#!/bin/bash
# 사용: tools/release.sh 1.8 "커밋 메시지"   (VERSION, RELEASE_NOTES.md는 미리 작성)
set -e
cd "$(dirname "$0")/.."
VER="$1"; MSG="$2"
S=/tmp/claude-0/-home-claude/01ca1cda-c5fd-52dc-9062-d8d951165915/scratchpad
echo "$VER" > VERSION
python3 -c "
import re;s=open('assets/index.html').read();open('$S/app.js','w').write(re.search(r'<script>(.*)</script>',s,re.S).group(1))"
node --check $S/app.js
VERSION_CODE=99 VERSION_NAME=$VER OUT=$S/t.apk ./build.sh 2>&1 | grep -v -e "^Picked up" -e "^Note:" | tail -1
git add -A
git -c user.name="thkim3589-web" -c user.email="thkim3589@gmail.com" commit -q -m "$MSG

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>
Claude-Session: https://claude.ai/code/session_01KqSBgXYUgWgduTKo4tuMvG"
git push -q origin main 2>&1 | grep -v -e moved -e "^remote: *$" || true
SHA=$(git rev-parse --short=7 HEAD); echo "pushed $SHA"
sleep 20
for i in $(seq 1 40); do
  b=$(gh api "repos/thkim3589-web/GameBooster/actions/runs?per_page=6" -q '[.workflow_runs[]|select(.head_sha|startswith("'$SHA'"))]|map(.name+"="+.status+"/"+(.conclusion//""))|join("  ")')
  case "$b" in *in_progress*|*queued*|"") sleep 15;; *) echo "$b"; break;; esac
done
gh api repos/thkim3589-web/GameBooster/releases/latest -q '"release: "+.tag_name+" "+.name'
ID=$(gh api "repos/thkim3589-web/GameBooster/actions/runs?per_page=6" -q '[.workflow_runs[]|select(.name=="Lint check" and (.head_sha|startswith("'$SHA'")))][0].id // empty')
[ -n "$ID" ] && gh run view $ID -R thkim3589-web/GameBooster --log 2>/dev/null | grep -E "errors, [0-9]+ warnings|: Error:" | sed 's/.*Z //' | head -5 || true
