#!/usr/bin/env bash
# 把 verify 产出的测试报告与覆盖率汇总到一个目录（默认 site/），供 GitHub Pages 发布到 reports/<ref>/
#   tests/surefire.html   单元测试（surefire）
#   tests/failsafe.html   集成测试（failsafe，Testcontainers Ollama）
#   coverage/             JaCoCo 覆盖率
#   index.html            入口页
# 用法：bash .github/scripts/collect-reports.sh [输出目录] [标题]
set -uo pipefail

out="${1:-site}"
title="${2:-mall-assistant}"
rm -rf "$out"
mkdir -p "$out/tests"

# surefire-report 插件把报告写在 target/reports（带 css / images）
if [ -d target/reports ]; then
    cp -r target/reports/. "$out/tests/"
fi
if [ -f target/site/jacoco/index.html ]; then
    cp -r target/site/jacoco "$out/coverage"
fi

summary() {
    # 从 surefire XML 统计：用例数 失败 错误 跳过
    local pattern="$1"
    local tests=0 failures=0 errors=0 skipped=0
    for f in $pattern; do
        [ -f "$f" ] || continue
        line=$(grep -m1 -o '<testsuite [^>]*>' "$f") || continue
        for k in tests failures errors skipped; do
            v=$(echo "$line" | grep -o " $k=\"[0-9]*\"" | grep -o '[0-9]*')
            v=${v:-0}
            eval "$k=\$(( $k + v ))"
        done
    done
    echo "用例 $tests，失败 $failures，错误 $errors，跳过 $skipped"
}

unit=$(summary "target/surefire-reports/TEST-*.xml")
it=$(summary "target/failsafe-reports/TEST-*.xml")

{
    echo '<!doctype html><html lang="zh-CN"><head><meta charset="utf-8">'
    echo '<meta name="viewport" content="width=device-width, initial-scale=1">'
    echo "<title>${title} 测试报告</title>"
    echo '<style>body{font-family:system-ui,sans-serif;max-width:760px;margin:2rem auto;padding:0 1rem;line-height:1.6}'
    echo 'code{background:#f3f4f6;padding:0 .3em;border-radius:3px}</style></head><body>'
    echo "<h1>${title} 测试报告</h1>"
    echo "<p>提交 <code>${GITHUB_SHA:-local}</code>，生成于 $(date -u '+%Y-%m-%d %H:%M UTC')</p>"
    case "${GITHUB_REF_NAME:-}" in
        *-start) echo '<p><strong>start 标签：测试未通过是预期的</strong>，这是练习起点，补全 TODO 后验收测试才会通过。</p>' ;;
    esac
    echo '<h2>测试</h2><ul>'
    if [ -f "$out/tests/surefire.html" ]; then
        echo "<li><a href=\"tests/surefire.html\">单元测试（surefire）</a>：${unit}</li>"
    fi
    if [ -f "$out/tests/failsafe.html" ]; then
        echo "<li><a href=\"tests/failsafe.html\">集成测试（failsafe，Ollama）</a>：${it}</li>"
    fi
    echo '</ul><h2>覆盖率</h2><ul>'
    if [ -d "$out/coverage" ]; then
        echo '<li><a href="coverage/index.html">JaCoCo</a></li>'
    fi
    echo '</ul></body></html>'
} > "$out/index.html"

echo "报告已汇总到 $out：单元测试 ${unit}；集成测试 ${it}"
