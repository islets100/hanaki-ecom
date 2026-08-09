package com.hanaki.ecom.memory.infrastructure.graph;

import com.alibaba.cloud.ai.graph.CompileConfig;
import com.alibaba.cloud.ai.graph.checkpoint.config.SaverConfig;
import com.alibaba.cloud.ai.graph.checkpoint.savers.file.FileSystemSaver;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Spring AI Alibaba Graph Checkpointer 的唯一基础设施适配器。
 *
 * <p>Memory/业务代码不再直接依赖 FileSystemSaver。生产切换 RedisSaver 时只替换本适配器，Graph
 * 拓扑和 Memory 领域模型无需改动。当前本地演示仍使用文件实现；它只负责恢复执行位置，恢复后的
 * 订单、退款、补偿状态仍必须由业务节点重新查询，绝不能把 Checkpoint 当作实时事实快照。</p>
 */
@Component
public final class GraphCheckpointAdapter {
    private final Path root;

    public GraphCheckpointAdapter(
            @Value("${agent.checkpoint.directory:./data/graph-checkpoints}") String checkpointDirectory) {
        this.root = Path.of(checkpointDirectory).toAbsolutePath().normalize();
    }

    /**
     * 每个主图/业务子图使用独立目录。namespace 只允许固定安全字符，并在 resolve 后再次验证没有
     * 逃出配置根目录，避免错误配置或未来动态 Agent 名称造成路径穿越。
     */
    public CompileConfig compileConfig(String namespace) {
        if (namespace == null || !namespace.matches("[a-z0-9/_-]{1,120}"))
            throw new IllegalArgumentException("不安全的 Checkpoint namespace");
        Path folder = root.resolve(namespace).normalize();
        if (!folder.startsWith(root)) throw new IllegalArgumentException("Checkpoint namespace 逃出配置根目录");
        try {
            Files.createDirectories(folder);
            FileSystemSaver saver = FileSystemSaver.builder().targetFolder(folder).build();
            return CompileConfig.builder().saverConfig(SaverConfig.builder().register(saver).build()).build();
        } catch (java.io.IOException error) {
            throw new IllegalStateException("无法创建 Graph Checkpoint 目录：" + namespace, error);
        }
    }
}
