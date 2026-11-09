package io.github.clarence404.assistant.chat;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.clarence404.assistant.cli.CliChatModel;
import io.github.clarence404.assistant.cli.CliFlavor;
import io.github.clarence404.assistant.cli.CliProperties;
import io.github.clarence404.assistant.knowledge.KnowledgeEmbedding;
import io.github.clarence404.assistant.knowledge.KnowledgeProperties;
import io.github.clarence404.assistant.tool.ToolProperties;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.ollama.OllamaChatModel;
import org.springframework.ai.ollama.OllamaEmbeddingModel;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.ai.openai.OpenAiEmbeddingModel;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

/**
 * 按 profile 切换模型厂商：默认 ollama，--spring.profiles.active=openai 换成 OpenAI 兼容接口，claude-cli / codex-cli 换成本机的命令行工具。
 * 只启动上下文，不发请求（命令行工具在第一次调用时才查找，没装也能启动）。第 1 章引入（ollama / openai），第 2 章加上两个命令行 profile。
 *
 * <p>手法：每个 {@code @Nested} 内部类带自己的 {@code @SpringBootTest} / {@code @ActiveProfiles}，各起一个上下文，断言容器里的
 * {@link ChatModel} 是哪个实现类。{@code webEnvironment = NONE} 不起 Web 层，只验证装配，更快。
 *
 * <p>第 4 章：同时断言向量模型 {@link EmbeddingModel}：ollama 与两个命令行 profile 用 Ollama 的 bge-m3，openai 用兼容接口的
 * embeddings；知识库默认是内存库。各上下文都关掉启动时自动入库（{@code ingest-on-startup=false}），不去连本机的 Ollama。
 */
class ProviderSwitchTest {

    @Nested
    @SpringBootTest(
            webEnvironment = SpringBootTest.WebEnvironment.NONE,
            properties = "assistant.knowledge.ingest-on-startup=false")
    class DefaultProfile {

        @Autowired
        ChatModel chatModel;

        @Autowired
        ToolProperties tools;

        @Autowired
        EmbeddingModel embeddingModel;

        @Autowired
        KnowledgeEmbedding knowledgeEmbedding;

        @Autowired
        KnowledgeProperties knowledge;

        @Test
        void usesOllama() {
            assertThat(chatModel).isInstanceOf(OllamaChatModel.class);
        }

        /** 第 4 章：向量模型也是 Ollama，默认 bge-m3、不加前缀；知识库默认是内存库。 */
        @Test
        void ollamaEmbeddingDefaultsToBgeM3InMemory() {
            assertThat(embeddingModel).isInstanceOf(OllamaEmbeddingModel.class);
            assertThat(knowledgeEmbedding.modelName()).isEqualTo("bge-m3");
            assertThat(knowledgeEmbedding.profile().queryPrefix()).isEmpty();
            assertThat(knowledgeEmbedding.storeSuffix()).isEqualTo("bge_m3");
            assertThat(knowledge.store()).isEqualTo(KnowledgeProperties.StoreType.MEMORY);
        }

        /** 第 3 章：Ollama 支持原生工具调用，默认两种方式都开。 */
        @Test
        void ollamaUsesNativeToolCallingAndDirectLookup() {
            assertThat(tools.nativeCalling()).isTrue();
            assertThat(tools.directLookup()).isTrue();
        }
    }

    @Nested
    @SpringBootTest(
            webEnvironment = SpringBootTest.WebEnvironment.NONE,
            properties = {
                // application-openai.yml 引用这三个占位符，没有值就启动不了；这里只看装配、不发请求，给假值即可
                "OPENAI_BASE_URL=http://localhost:1/v1",
                "OPENAI_API_KEY=test-placeholder",
                "OPENAI_MODEL=test-model",
                "assistant.knowledge.ingest-on-startup=false"
            })
    @ActiveProfiles("openai")
    class OpenAiProfile {

        @Autowired
        ChatModel chatModel;

        @Autowired
        EmbeddingModel embeddingModel;

        @Autowired
        KnowledgeEmbedding knowledgeEmbedding;

        @Test
        void usesOpenAiCompatibleEndpoint() {
            assertThat(chatModel).isInstanceOf(OpenAiChatModel.class);
        }

        /** 第 4 章：向量模型走同一个兼容接口的 /v1/embeddings。 */
        @Test
        void embeddingUsesOpenAiCompatibleEndpoint() {
            assertThat(embeddingModel).isInstanceOf(OpenAiEmbeddingModel.class);
            assertThat(knowledgeEmbedding.modelName()).isEqualTo("text-embedding-3-small");
        }
    }

    @Nested
    @SpringBootTest(
            webEnvironment = SpringBootTest.WebEnvironment.NONE,
            properties = "assistant.knowledge.ingest-on-startup=false")
    @ActiveProfiles("claude-cli")
    class ClaudeCliProfile {

        @Autowired
        ChatModel chatModel;

        @Autowired
        ObjectProvider<OllamaChatModel> ollama;

        @Autowired
        CliProperties properties;

        @Autowired
        ToolProperties tools;

        @Autowired
        EmbeddingModel embeddingModel;

        @Autowired
        KnowledgeEmbedding knowledgeEmbedding;

        /** 第 4 章：命令行工具没有向量模型，知识库改用 Ollama 的向量模型（对话仍然是命令行模型）。 */
        @Test
        void cliProfileUsesOllamaForEmbedding() {
            assertThat(embeddingModel).isInstanceOf(OllamaEmbeddingModel.class);
            assertThat(knowledgeEmbedding.modelName()).isEqualTo("bge-m3");
        }

        /** 第 3 章：命令行模型不支持原生工具调用，profile 关掉它，只走按意图直接查询。 */
        @Test
        void cliProfileDisablesNativeToolCalling() {
            assertThat(tools.nativeCalling()).isFalse();
            assertThat(tools.directLookup()).isTrue();
        }

        /** 容器里只有 CliChatModel（profile 里 spring.ai.model.chat=none 关掉了 Ollama），并带上了不读 CLAUDE.md 的环境变量。 */
        @Test
        void usesClaudeCodeAsTheOnlyChatModel() {
            assertThat(chatModel).isInstanceOf(CliChatModel.class);
            assertThat(ollama.getIfAvailable()).isNull();
            assertThat(properties.flavor()).isEqualTo(CliFlavor.CLAUDE);
            assertThat(properties.model()).isEqualTo("haiku");
            assertThat(properties.env()).containsEntry("CLAUDE_CODE_DISABLE_CLAUDE_MDS", "1");
        }
    }

    @Nested
    @SpringBootTest(
            webEnvironment = SpringBootTest.WebEnvironment.NONE,
            properties = "assistant.knowledge.ingest-on-startup=false")
    @ActiveProfiles("codex-cli")
    class CodexCliProfile {

        @Autowired
        ChatModel chatModel;

        @Autowired
        CliProperties properties;

        @Test
        void usesCodexCli() {
            assertThat(chatModel).isInstanceOf(CliChatModel.class);
            assertThat(properties.flavor()).isEqualTo(CliFlavor.CODEX);
            assertThat(properties.model()).as("留空用 codex 的默认模型").isNull();
        }
    }
}
