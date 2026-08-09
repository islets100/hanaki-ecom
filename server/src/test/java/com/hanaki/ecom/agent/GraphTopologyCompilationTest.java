package com.hanaki.ecom.agent;

import com.hanaki.ecom.memory.infrastructure.graph.GraphCheckpointAdapter;
import com.hanaki.ecom.observability.AgentTelemetryService;
import com.hanaki.ecom.support.SupportService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

/**
 * 拓扑契约测试。它不调用模型，只要求 StateGraph 能把固定节点和条件边编译为 CompiledGraph；
 * 节点遗漏、条件边目标不存在或形成框架不允许的结构时，会在这里而不是生产启动时暴露。
 */
class GraphTopologyCompilationTest {
    @Test
    void mainGraphWithFourDomainSubgraphsAndSharedHandoffCompiles(@TempDir Path temp) throws Exception {
        GraphConfiguration configuration = new GraphConfiguration();

        var graph = configuration.customerServiceGraph(
                mock(GuardrailService.class), mock(IntentRouter.class),
                mock(BusinessAgentGraphs.class), mock(LogisticsAgentService.class),
                mock(SupportService.class), mock(AgentTelemetryService.class),
                new GraphCheckpointAdapter(temp.toString()));

        assertThat(graph).isNotNull();
    }
}
