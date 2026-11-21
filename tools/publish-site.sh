#!/usr/bin/env bash
# 把 site/ 发布到 gh-pages 分支（GitHub Pages：https://languang666.github.io/geoshift/）
set -euo pipefail
cd "$(dirname "$0")/.."
tmp=$(mktemp -d)
trap 'git worktree remove --force "$tmp" 2>/dev/null || true; rm -rf "$tmp"' EXIT
if git ls-remote --exit-code --heads origin gh-pages >/dev/null; then
  git fetch -q origin gh-pages
  git worktree add -q -B gh-pages "$tmp" origin/gh-pages
else
  git worktree add -q --detach "$tmp"
  git -C "$tmp" checkout -q --orphan gh-pages
fi
git -C "$tmp" rm -rqf --ignore-unmatch .
cp -r site/. "$tmp"/
touch "$tmp/.nojekyll"
git -C "$tmp" add -A
if git -C "$tmp" diff --cached --quiet; then echo "site 无变化"; exit 0; fi
git -C "$tmp" commit -qm "Publish site from $(git rev-parse --short HEAD)"
git -C "$tmp" push -q origin gh-pages
echo "已发布到 gh-pages"
