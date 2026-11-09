package io.github.clarence404.assistant.knowledge;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/**
 * 启动后自动入库一次（{@code assistant.knowledge.ingest-on-startup}，默认开）（第 4 章引入）。
 *
 * <p>所在层：业务层的启动钩子。下游是 {@link KnowledgeIngestionService}。
 *
 * <p>为什么挂在 {@link ApplicationReadyEvent} 上：这个事件在 Web 服务器已经开始监听之后才发布，入库期间页面和对话已经能用；
 * 入库是增量的，文档没变时几乎不花时间（内存版要配了持久化目录才有「上次的结果」可比）。
 *
 * <p><b>失败不影响启动</b>：知识库是辅助功能，Ollama 没启动、向量模型没 pull、pgvector 连不上时，只打一条警告，对话照常可用；
 * 等模型或数据库好了，在页面上点「入库」或调用 {@code POST /api/knowledge/ingest} 即可。
 */
@Component
public class KnowledgeStartupIngest {

    private static final Logger log = LoggerFactory.getLogger(KnowledgeStartupIngest.class);

    private final KnowledgeIngestionService ingestionService;
    private final KnowledgeProperties properties;

    /**
     * 创建。
     *
     * @param ingestionService 入库服务
     * @param properties 知识库配置（开关）
     */
    public KnowledgeStartupIngest(KnowledgeIngestionService ingestionService, KnowledgeProperties properties) {
        this.ingestionService = ingestionService;
        this.properties = properties;
    }

    /** 应用就绪后入库；任何异常都只记警告，不往外抛（抛出去会让 Spring 认为启动失败）。 */
    @EventListener(ApplicationReadyEvent.class)
    public void ingestOnStartup() {
        if (!properties.ingestOnStartup()) {
            log.info("已关闭启动时自动入库（assistant.knowledge.ingest-on-startup=false）");
            return;
        }
        try {
            ingestionService.ingest(false);
        } catch (RuntimeException e) {
            // 只打一行原因，不打堆栈：模型没启动是学习环境里的常态，满屏堆栈反而吓人；要看细节把日志级别调到 DEBUG
            log.warn(
                    "启动时知识入库失败，对话不受影响；模型或向量库就绪后可调用 POST /api/knowledge/ingest 重试。原因：{}（{}）",
                    e.getMessage(),
                    rootCause(e));
            log.debug("启动时知识入库失败的详细信息", e);
        }
    }

    /** 最里层的异常，如 {@code ConnectException: Connection refused}。 */
    private static String rootCause(Throwable e) {
        Throwable current = e;
        for (int depth = 0; current.getCause() != null && depth < 16; depth++) {
            current = current.getCause();
        }
        return current.getClass().getSimpleName() + ": " + current.getMessage();
    }
}
