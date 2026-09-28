package com.enunas.backend.order;

import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;

class OrderItemCancellationStateTest {

    @Test
    void activeItem_isNeitherCancelledNorSettled() {
        OrderItem item = new OrderItem();
        assertThat(item.isCancelled()).isFalse();
        assertThat(item.isCancellationSettled()).isFalse();
    }

    @Test
    void claimedItem_isCancelledButNotSettled() {
        OrderItem item = new OrderItem();
        item.setCancelledAt(LocalDateTime.now());
        assertThat(item.isCancelled()).isTrue();
        assertThat(item.isCancellationSettled()).isFalse();
    }

    @Test
    void settledItem_isBoth() {
        OrderItem item = new OrderItem();
        item.setCancelledAt(LocalDateTime.now());
        item.setRefundTransactionId("re_1");
        assertThat(item.isCancelled()).isTrue();
        assertThat(item.isCancellationSettled()).isTrue();
    }
}
