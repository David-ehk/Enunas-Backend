package com.enunas.backend.order;

import com.enunas.backend.user.User;
import jakarta.persistence.*;
import lombok.*;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

@Entity
@Table(name = "returns")
@Getter
@Setter
@Builder(toBuilder = true)
@NoArgsConstructor
@AllArgsConstructor
public class ReturnOrder {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, unique = true)
    private String returnNumber;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "order_id", nullable = false)
    private Order order;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id", nullable = false)
    private User user;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    @Builder.Default
    private ReturnStatus status = ReturnStatus.REQUESTED;

    @Enumerated(EnumType.STRING)
    private ReturnReason reason;

    @Column(length = 500)
    private String description;

    @OneToMany(mappedBy = "returnOrder", cascade = CascadeType.ALL, orphanRemoval = true)
    @Builder.Default
    @Getter(AccessLevel.NONE)
    @Setter(AccessLevel.NONE)
    private List<ReturnItem> items = new ArrayList<>();

    private String returnLabel;

    @Column(precision = 10, scale = 2)
    private BigDecimal refundAmount;

    @Column(updatable = false)
    private LocalDateTime requestedAt;

    private LocalDateTime approvedAt;
    private LocalDateTime receivedAt;
    private LocalDateTime refundedAt;

    @PrePersist
    protected void onCreate() {
        requestedAt = LocalDateTime.now();
    }

    // ===== Collection access =====

    public List<ReturnItem> getItems() {
        return items == null ? List.of() : Collections.unmodifiableList(items);
    }

    public void addItem(ReturnItem item) {
        if (items == null) items = new ArrayList<>();
        items.add(item);
        item.setReturnOrder(this);
    }

    // ===== equals / hashCode =====

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof ReturnOrder other)) return false;
        return id != null && id.equals(other.id);
    }

    @Override
    public int hashCode() {
        return getClass().hashCode();
    }
}
