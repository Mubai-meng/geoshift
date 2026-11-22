#!/usr/bin/env bash
# 把 site/ 发布到 gh-pages 分支（GitHub Pages：https://languang666.github.io/geoshift/）
# 发布前用 gh 读取最新 Release，把版本号、下载链接、大小、日期、SHA-256 写进发布的页面副本，
# 这样国内访问不到 api.github.com 时也能下到最新版。发新版 Release 后重新运行本脚本即可。
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

gh release view --json tagName,publishedAt,body,assets > "$tmp/.release.json"
python3 -I - "$tmp/index.html" "$tmp/.release.json" <<'PY'
import json, re, sys
page, rel_path = sys.argv[1], sys.argv[2]
rel = json.load(open(rel_path))
tag = rel["tagName"]
apk = next(a for a in rel["assets"] if re.fullmatch(r"Geoshift-[\d.]+\.apk", a["name"]))
motion = next((a for a in rel["assets"] if re.fullmatch(r"GeoshiftMotion-[\d.]+\.apk", a["name"])), None)
m = re.search(r"Geoshift APK SHA-256[^`]*`([0-9a-f]{64})`", rel.get("body") or "", re.I)
sha = m.group(1) if m else (apk.get("digest") or "").removeprefix("sha256:")
meta = f'{apk["size"] / 1048576:.1f} MB · {rel["publishedAt"][:10]}'
s = open(page, encoding="utf-8").read()
subs = [
    (r"/download/v[\d.]+/Geoshift-[\d.]+\.apk", f"/download/{tag}/{apk['name']}"),
    (r"/download/v[\d.]+/GeoProbe\.apk", f"/download/{tag}/GeoProbe.apk"),
    (r'(class="js-ver">)v[\d.]+(<)', rf"\g<1>{tag}\g<2>"),
    (r'(class="js-meta">)[^<]*(<)', rf"\g<1>{meta}\g<2>"),
    (r'(tag: ")v[\d.]+(")', rf"\g<1>{tag}\g<2>"),
]
if motion:
    subs.append((r"/download/v[\d.]+/GeoshiftMotion-[\d.]+\.apk", f"/download/{tag}/{motion['name']}"))
if sha:
    subs.append((r'(class="js-sha">)[0-9a-f]{64}(<)', rf"\g<1>{sha}\g<2>"))
else:  # 没有校验值就不显示旧版本的
    subs.append((r'class="sha js-sha-box"', 'class="sha js-sha-box" hidden'))
for pat, rep in subs:
    s, n = re.subn(pat, rep, s)
    if n == 0:
        sys.exit(f"页面里找不到要替换的内容：{pat}")
open(page, "w", encoding="utf-8").write(s)
print(f"页面写入 {tag} · {apk['name']} · {meta}" + ("" if sha else "（发布说明里没有 SHA-256）"))
PY
rm "$tmp/.release.json"

git -C "$tmp" add -A
if git -C "$tmp" diff --cached --quiet; then echo "site 无变化"; exit 0; fi
git -C "$tmp" commit -qm "Publish site from $(git rev-parse --short HEAD)"
git -C "$tmp" push -q origin gh-pages
echo "已发布到 gh-pages"
