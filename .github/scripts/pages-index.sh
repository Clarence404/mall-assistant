#!/usr/bin/env bash
# 生成 gh-pages 根目录的 index.html：列出 reports/ 下已有的各 ref 报告，再加上本次的 ref
# 用法：bash .github/scripts/pages-index.sh <输出目录> <本次 ref>
set -euo pipefail

out="$1"
current="$2"
mkdir -p "$out"

existing=""
if git fetch --depth=1 origin gh-pages 2>/dev/null; then
    existing=$(git ls-tree -d --name-only FETCH_HEAD reports/ 2>/dev/null | sed 's#^reports/##')
fi
refs=$(printf '%s\n%s\n' "$existing" "$current" | grep -v '^$' | sort -uV)

{
    echo '<!doctype html><html lang="zh-CN"><head><meta charset="utf-8">'
    echo '<meta name="viewport" content="width=device-width, initial-scale=1">'
    echo '<title>mall-assistant 测试报告</title>'
    echo '<style>body{font-family:system-ui,sans-serif;max-width:760px;margin:2rem auto;padding:0 1rem;line-height:1.6}</style>'
    echo '</head><body><h1>mall-assistant 测试报告</h1>'
    echo '<p>每次推送 main 或章节 tag（chNN-start / chNN-done）都会跑一遍 <code>./mvnw -B verify</code>（含 Testcontainers Ollama 集成测试；start 标签的测试失败是预期的），报告按 ref 存放在 <code>reports/&lt;ref&gt;/</code>。</p><ul>'
    for r in $refs; do
        echo "<li><a href=\"reports/${r}/\">${r}</a></li>"
    done
    echo '</ul></body></html>'
} > "$out/index.html"

echo "$refs"
