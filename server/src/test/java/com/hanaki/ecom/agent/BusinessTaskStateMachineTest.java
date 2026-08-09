package com.hanaki.ecom.agent;

import com.hanaki.ecom.store.EcommerceStore;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.*;

class BusinessTaskStateMachineTest {
    private final EcommerceStore store = mock(EcommerceStore.class);
    private final BusinessTaskStateMachine machine = new BusinessTaskStateMachine(store);

    @Test
    void frameworkAcceptsDeclaredTransitionThenDatabaseCasCommitsIt() {
        when(store.transitionTask("BT-1", "tenant", "WAITING_CONFIRMATION", "WAITING_STORE_APPROVAL"))
                .thenReturn(true);

        assertThat(machine.tryTransition("BT-1", "tenant", "WAITING_CONFIRMATION", "WAITING_STORE_APPROVAL"))
                .isTrue();
        verify(store).transitionTask("BT-1", "tenant", "WAITING_CONFIRMATION", "WAITING_STORE_APPROVAL");
    }

    @Test
    void frameworkRejectsUndeclaredTransitionBeforeDatabaseWrite() {
        assertThatThrownBy(() -> machine.tryTransition("BT-1", "tenant", "WAITING_CONFIRMATION", "REFUNDED"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("非法业务状态迁移");
        verify(store, never()).transitionTask(anyString(), anyString(), anyString(), anyString());
    }

    @Test
    void timeoutScannerUsesSameFrameworkAndCasPath() {
        when(store.overdueBusinessTasks()).thenReturn(List.of(
                new EcommerceStore.OverdueBusinessTask("BT-2", "tenant", "WAITING_OFFICIAL_APPROVAL")));
        when(store.transitionTask("BT-2", "tenant", "WAITING_OFFICIAL_APPROVAL", "TIMED_OUT"))
                .thenReturn(true);

        machine.expireOverdueTasks();

        verify(store).transitionTask("BT-2", "tenant", "WAITING_OFFICIAL_APPROVAL", "TIMED_OUT");
    }
}
