package com.hanaki.ecom.config;

import com.hanaki.ecom.agent.BusinessTaskStateMachine.TaskEvent;
import com.hanaki.ecom.agent.BusinessTaskStateMachine.TaskState;
import org.springframework.context.annotation.Configuration;
import org.springframework.statemachine.config.EnableStateMachineFactory;
import org.springframework.statemachine.config.EnumStateMachineConfigurerAdapter;
import org.springframework.statemachine.config.builders.StateMachineConfigurationConfigurer;
import org.springframework.statemachine.config.builders.StateMachineStateConfigurer;
import org.springframework.statemachine.config.builders.StateMachineTransitionConfigurer;

import java.util.EnumSet;

/**
 * 业务任务状态机的唯一框架配置。
 *
 * <p>使用 StateMachineFactory 复用已经解析的状态拓扑；每个业务任务仍取得隔离的状态机实例，
 * 并在发送事件前从数据库中的期望状态恢复。关闭 Spring ApplicationEvent 广播可以避免在高并发
 * 审批场景中把状态迁移变成全局事件总线压力。</p>
 */
@Configuration
@EnableStateMachineFactory(contextEvents = false)
public class BusinessTaskStateMachineConfiguration
        extends EnumStateMachineConfigurerAdapter<TaskState, TaskEvent> {

    @Override
    public void configure(StateMachineConfigurationConfigurer<TaskState, TaskEvent> config) throws Exception {
        config.withConfiguration().autoStartup(false);
    }

    @Override
    public void configure(StateMachineStateConfigurer<TaskState, TaskEvent> states) throws Exception {
        states.withStates()
                .initial(TaskState.WAITING_CONFIRMATION)
                .states(EnumSet.allOf(TaskState.class));
    }

    @Override
    public void configure(StateMachineTransitionConfigurer<TaskState, TaskEvent> transitions) throws Exception {
        transitions
                .withExternal().source(TaskState.WAITING_CONFIRMATION)
                    .target(TaskState.CANCELLED).event(TaskEvent.USER_CANCELLED).and()
                .withExternal().source(TaskState.WAITING_CONFIRMATION)
                    .target(TaskState.WAITING_STORE_APPROVAL).event(TaskEvent.USER_CONFIRMED).and()
                .withExternal().source(TaskState.WAITING_CONFIRMATION)
                    .target(TaskState.APPROVED).event(TaskEvent.AUTO_APPROVED).and()
                .withExternal().source(TaskState.WAITING_CONFIRMATION)
                    .target(TaskState.TIMED_OUT).event(TaskEvent.DEADLINE_EXPIRED).and()
                .withExternal().source(TaskState.WAITING_STORE_APPROVAL)
                    .target(TaskState.REJECTED).event(TaskEvent.STORE_REJECTED).and()
                .withExternal().source(TaskState.WAITING_STORE_APPROVAL)
                    .target(TaskState.APPROVED).event(TaskEvent.STORE_APPROVED).and()
                .withExternal().source(TaskState.WAITING_STORE_APPROVAL)
                    .target(TaskState.TIMED_OUT).event(TaskEvent.DEADLINE_EXPIRED).and()
                .withExternal().source(TaskState.WAITING_OFFICIAL_APPROVAL)
                    .target(TaskState.REJECTED).event(TaskEvent.OFFICIAL_REJECTED).and()
                .withExternal().source(TaskState.WAITING_OFFICIAL_APPROVAL)
                    .target(TaskState.APPROVED).event(TaskEvent.LEGACY_STORE_APPROVAL_MIGRATED).and()
                .withExternal().source(TaskState.WAITING_OFFICIAL_APPROVAL)
                    .target(TaskState.TIMED_OUT).event(TaskEvent.DEADLINE_EXPIRED).and()
                .withExternal().source(TaskState.APPROVED)
                    .target(TaskState.EXECUTING).event(TaskEvent.EXECUTION_STARTED).and()
                .withExternal().source(TaskState.EXECUTING)
                    .target(TaskState.REFUNDED).event(TaskEvent.REFUND_SUCCEEDED).and()
                .withExternal().source(TaskState.EXECUTING)
                    .target(TaskState.COMPLETED).event(TaskEvent.EXECUTION_SUCCEEDED).and()
                .withExternal().source(TaskState.EXECUTING)
                    .target(TaskState.FAILED).event(TaskEvent.EXECUTION_FAILED);
    }
}
