package com.hanaki.ecom.agent;

import com.hanaki.ecom.store.EcommerceStore;
import org.springframework.beans.factory.annotation.Autowired;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.messaging.support.MessageBuilder;
import org.springframework.statemachine.StateMachine;
import org.springframework.statemachine.config.StateMachineBuilder;
import org.springframework.statemachine.config.StateMachineFactory;
import org.springframework.statemachine.support.DefaultStateMachineContext;
import org.springframework.stereotype.Service;
import org.springframework.scheduling.annotation.Scheduled;
import reactor.core.publisher.Mono;

import java.util.Map;

/**
 * 业务任务状态机与 Agent Graph 生命周期解耦。Spring StateMachine 负责声明和验证迁移拓扑，
 * 数据库 CAS 负责持久状态与幂等；Graph 重跑不会重复执行已完成的业务写操作。
 */
@Service
public class BusinessTaskStateMachine {
    public enum TaskState {
        WAITING_CONFIRMATION, WAITING_STORE_APPROVAL, WAITING_OFFICIAL_APPROVAL,
        APPROVED, EXECUTING, REFUNDED, COMPLETED, FAILED, REJECTED, CANCELLED, TIMED_OUT
    }

    public enum TaskEvent {
        USER_CANCELLED, USER_CONFIRMED, AUTO_APPROVED, STORE_REJECTED, STORE_APPROVED,
        OFFICIAL_REJECTED, OFFICIAL_APPROVED, LEGACY_STORE_APPROVAL_MIGRATED, EXECUTION_STARTED,
        REFUND_SUCCEEDED, EXECUTION_SUCCEEDED, EXECUTION_FAILED, DEADLINE_EXPIRED
    }

    private static final Map<TransitionKey, TaskEvent> EVENTS = Map.ofEntries(
            Map.entry(key(TaskState.WAITING_CONFIRMATION, TaskState.CANCELLED), TaskEvent.USER_CANCELLED),
            Map.entry(key(TaskState.WAITING_CONFIRMATION, TaskState.WAITING_STORE_APPROVAL), TaskEvent.USER_CONFIRMED),
            Map.entry(key(TaskState.WAITING_CONFIRMATION, TaskState.APPROVED), TaskEvent.AUTO_APPROVED),
            Map.entry(key(TaskState.WAITING_CONFIRMATION, TaskState.TIMED_OUT), TaskEvent.DEADLINE_EXPIRED),
            Map.entry(key(TaskState.WAITING_STORE_APPROVAL, TaskState.REJECTED), TaskEvent.STORE_REJECTED),
            Map.entry(key(TaskState.WAITING_STORE_APPROVAL, TaskState.APPROVED), TaskEvent.STORE_APPROVED),
            Map.entry(key(TaskState.WAITING_STORE_APPROVAL, TaskState.TIMED_OUT), TaskEvent.DEADLINE_EXPIRED),
            Map.entry(key(TaskState.WAITING_OFFICIAL_APPROVAL, TaskState.REJECTED), TaskEvent.OFFICIAL_REJECTED),
            Map.entry(key(TaskState.WAITING_OFFICIAL_APPROVAL, TaskState.APPROVED), TaskEvent.LEGACY_STORE_APPROVAL_MIGRATED),
            Map.entry(key(TaskState.WAITING_OFFICIAL_APPROVAL, TaskState.TIMED_OUT), TaskEvent.DEADLINE_EXPIRED),
            Map.entry(key(TaskState.APPROVED, TaskState.EXECUTING), TaskEvent.EXECUTION_STARTED),
            Map.entry(key(TaskState.EXECUTING, TaskState.REFUNDED), TaskEvent.REFUND_SUCCEEDED),
            Map.entry(key(TaskState.EXECUTING, TaskState.COMPLETED), TaskEvent.EXECUTION_SUCCEEDED),
            Map.entry(key(TaskState.EXECUTING, TaskState.FAILED), TaskEvent.EXECUTION_FAILED));

    private final EcommerceStore store;
    private final StateMachineFactory<TaskState, TaskEvent> factory;
    private final MeterRegistry meters;

    @Autowired
    public BusinessTaskStateMachine(EcommerceStore store,
                                    StateMachineFactory<TaskState, TaskEvent> factory,
                                    MeterRegistry meters) {
        this.store = store;
        this.factory = factory;
        this.meters = meters;
    }

    /** 仅供不启动 Spring 容器的单元测试使用；生产环境始终注入 StateMachineFactory。 */
    public BusinessTaskStateMachine(EcommerceStore store) {
        this.store = store;
        this.factory = null;
        this.meters = null;
    }

    public boolean tryTransition(String taskId, String tenantId, String expected, String target) {
        if (!acceptedByFramework(expected, target))
            throw new IllegalStateException("非法业务状态迁移：" + expected + " -> " + target);
        boolean changed = store.transitionTask(taskId, tenantId, expected, target);
        if (meters != null) meters.counter("agent.business.task.transitions", "source", expected.toLowerCase(),
                "target", target.toLowerCase(), "result", changed ? "committed" : "cas_rejected").increment();
        return changed;
    }

    public void transitionRequired(String taskId, String tenantId, String expected, String target) {
        if (!tryTransition(taskId, tenantId, expected, target))
            throw new IllegalArgumentException("任务状态已变化，请刷新后重试");
    }

    public String status(String taskId, String tenantId) { return store.taskStatus(taskId, tenantId); }

    /** 等待用户或人工审批超过截止时间后，通过同一状态机和 CAS 路径进入 TIMED_OUT。 */
    @Scheduled(fixedDelayString = "${agent.business-task.timeout-scan-ms:60000}")
    public void expireOverdueTasks() {
        for (EcommerceStore.OverdueBusinessTask task : store.overdueBusinessTasks()) {
            tryTransition(task.taskId(), task.tenantId(), task.status(), "TIMED_OUT");
        }
    }

    /**
     * 每次迁移创建隔离的状态机实例，先恢复到数据库给出的 expected，再发送唯一事件。
     * 状态机不承担持久化，避免内存状态成为业务事实源。
     */
    private boolean acceptedByFramework(String expected, String target) {
        try {
            TaskState source = TaskState.valueOf(expected);
            TaskState destination = TaskState.valueOf(target);
            TaskEvent event = EVENTS.get(key(source, destination));
            if (event == null) return false;
            StateMachine<TaskState, TaskEvent> machine = factory == null ? buildTestMachine() : factory.getStateMachine();
            machine.stopReactively().block();
            machine.getStateMachineAccessor().doWithAllRegions(access ->
                    access.resetStateMachineReactively(
                            new DefaultStateMachineContext<>(source, null, null, null)).block());
            machine.startReactively().block();
            machine.sendEvent(Mono.just(MessageBuilder.withPayload(event).build())).blockLast();
            boolean accepted = machine.getState() != null && destination == machine.getState().getId();
            machine.stopReactively().block();
            return accepted;
        } catch (Exception error) {
            throw new IllegalStateException("Spring StateMachine 迁移验证失败", error);
        }
    }

    private StateMachine<TaskState, TaskEvent> buildTestMachine() throws Exception {
        StateMachineBuilder.Builder<TaskState, TaskEvent> builder = StateMachineBuilder.builder();
        builder.configureConfiguration().withConfiguration().autoStartup(false);
        builder.configureStates().withStates().initial(TaskState.WAITING_CONFIRMATION)
                .states(java.util.EnumSet.allOf(TaskState.class));
        var transitions = builder.configureTransitions();
        for (var entry : EVENTS.entrySet()) {
            transitions.withExternal().source(entry.getKey().source()).target(entry.getKey().target())
                    .event(entry.getValue()).and();
        }
        return builder.build();
    }

    private static TransitionKey key(TaskState source, TaskState target) {
        return new TransitionKey(source, target);
    }

    private record TransitionKey(TaskState source, TaskState target) {}
}
