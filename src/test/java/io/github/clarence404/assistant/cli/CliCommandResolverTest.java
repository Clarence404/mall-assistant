package io.github.clarence404.assistant.cli;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** 解析命令：PATH 查找、Windows 下拆开 npm 的 .cmd 包装，以及 Windows 参数转义。和当前操作系统无关，Windows 规则用参数指定。 */
class CliCommandResolverTest {

    /** npm 为 bin 生成的 claude.cmd（节选）。 */
    private static final String NPM_SHIM = """
            @ECHO off
            GOTO start
            :find_dp0
            SET dp0=%~dp0
            EXIT /b
            :start
            SETLOCAL
            CALL :find_dp0
            IF EXIST "%dp0%\\node.exe" (
              SET "_prog=%dp0%\\node.exe"
            ) ELSE (
              SET "_prog=node"
              SET PATHEXT=%PATHEXT:;.JS;=;%
            )
            endLocal & goto #_undefined_# 2>NUL || title %COMSPEC% & "%_prog%"  "%dp0%\\node_modules\\@anthropic-ai\\claude-code\\cli.js" %*
            """;

    @TempDir
    Path dir;

    @Test
    void windowsNpmShimIsUnwrappedToNodeAndScript() throws Exception {
        Files.writeString(dir.resolve("claude.cmd"), NPM_SHIM);
        Files.writeString(dir.resolve("claude"), "#!/bin/sh\n"); // 给 Git Bash 用的 sh 脚本，Windows 下不能直接执行
        Files.writeString(dir.resolve("node.exe"), "");
        Path script = dir.resolve("node_modules/@anthropic-ai/claude-code/cli.js");
        Files.createDirectories(script.getParent());
        Files.writeString(script, "");

        assertThat(CliCommandResolver.resolve("claude", true, dir.toString(), ".COM;.EXE;.BAT;.CMD"))
                .containsExactly(dir.resolve("node.exe").toString(), script.toString());
    }

    @Test
    void windowsPrefersExeOverShim() throws Exception {
        Files.writeString(dir.resolve("claude.exe"), "");
        Files.writeString(dir.resolve("claude.cmd"), NPM_SHIM);

        assertThat(CliCommandResolver.resolve("claude", true, "C:\\nowhere;" + dir, null))
                .containsExactly(dir.resolve("claude.exe").toString());
    }

    @Test
    void nonNpmBatchFileIsRejected() throws Exception {
        Files.writeString(dir.resolve("claude.cmd"), "@echo off\r\nsomething.exe %*\r\n");

        assertThatThrownBy(() -> CliCommandResolver.resolve("claude", true, dir.toString(), null))
                .isInstanceOf(CliExecutionException.class)
                .hasMessageContaining("npm");
    }

    @Test
    void missingCommandIsExecutionFailure() {
        assertThatThrownBy(() -> CliCommandResolver.resolve("no-such-cli-xyz", false, dir.toString(), null))
                .isInstanceOf(CliExecutionException.class)
                .hasMessageContaining("no-such-cli-xyz");
    }

    @Test
    void explicitPathIsUsedAsIs() throws Exception {
        Path exe = Files.writeString(dir.resolve("my-claude.exe"), "");

        assertThat(CliCommandResolver.resolve(exe.toString(), true, "", null)).containsExactly(exe.toString());
    }

    @Test
    void windowsArgumentsAreQuotedForTheCRuntime() {
        assertThat(CliCommandResolver.quoteWindowsArg("plain")).isEqualTo("plain");
        assertThat(CliCommandResolver.quoteWindowsArg("")).isEqualTo("\"\"");
        assertThat(CliCommandResolver.quoteWindowsArg("a b")).isEqualTo("\"a b\"");
        assertThat(CliCommandResolver.quoteWindowsArg("{\"a\":\"b\"}")).isEqualTo("\"{\\\"a\\\":\\\"b\\\"}\"");
        assertThat(CliCommandResolver.quoteWindowsArg("C:\\dir with space\\"))
                .as("结尾反斜杠成对写，不吃掉收尾引号")
                .isEqualTo("\"C:\\dir with space\\\\\"");
        assertThat(CliCommandResolver.quoteWindowsArg("x\\\"y")).isEqualTo("\"x\\\\\\\"y\"");
    }
}
