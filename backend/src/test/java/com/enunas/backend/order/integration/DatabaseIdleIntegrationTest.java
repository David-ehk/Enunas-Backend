package com.enunas.backend.order.integration;

import com.enunas.backend.discount.integration.AbstractDiscountIntegrationTest;
import com.enunas.backend.order.OrderExpiryScheduler;
import com.enunas.backend.user.EmailService;
import com.zaxxer.hikari.HikariDataSource;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;

import javax.sql.DataSource;
import java.sql.SQLException;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.verify;

/** The two wiring points that let the database suspend while the shop is idle. */
class DatabaseIdleIntegrationTest extends AbstractDiscountIntegrationTest {

    @Autowired DataSource dataSource;
    @MockitoBean EmailService emailService;
    @MockitoSpyBean OrderExpiryScheduler orderExpiryScheduler;

    @Test
    void placingAnOrder_opensTheExpiryWindow() {
        seedCustomer();
        BrandFixture brand = seedBrand("BrandA", "brand-a", "0.18");
        long listing = seedListing(brand.brand(), brand.user(), "119.00", 5);
        String token = login("customer@it.local", "Customer123!");
        clearInvocations(orderExpiryScheduler);

        postOrder(token, null, List.of(item(listing, 1)));

        verify(orderExpiryScheduler).orderCreated();
    }

    @Test
    void connectionPool_keepsNoIdleConnectionsOrKeepalives() throws SQLException {
        HikariDataSource hikari = dataSource.unwrap(HikariDataSource.class);

        assertThat(hikari.getMinimumIdle()).isZero();
        assertThat(hikari.getIdleTimeout()).isEqualTo(60_000);
        assertThat(hikari.getKeepaliveTime()).isZero();
    }
}
