package com.enunas.backend.order.integration;

import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationVersion;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * V27's shipment_backfill_review INSERT is pure SQL logic that never runs through the app's normal
 * Spring context (migrations apply to an empty schema before any JPA seeding happens) — verified
 * here directly against Postgres via raw JDBC + Flyway's Java API: migrate to V26, seed rows that
 * simulate pre-migration historical orders (bypassing all app code, exactly like real production
 * data would look), migrate the rest of the way to V27, then assert on what the review table
 * actually contains.
 */
@Testcontainers
class ShipmentBackfillReviewMigrationTest {

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16");

    @Test
    void reviewTable_flagsMultiBrandOrderAsAmbiguous_singleBrandOrderAsNotAmbiguous() throws Exception {
        Flyway.configure()
                .dataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())
                .locations("classpath:db/migration")
                .target(MigrationVersion.fromVersion("26"))
                .load()
                .migrate();

        try (Connection c = DriverManager.getConnection(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
             Statement st = c.createStatement()) {

            // Two brands, two users, two products/variants/listings.
            st.execute("INSERT INTO users (id, email, password, role, enabled, admin_approved) VALUES " +
                    "(1, 'a@it.local', 'x', 'BRAND_PARTNER', true, true), " +
                    "(2, 'b@it.local', 'x', 'BRAND_PARTNER', true, true), " +
                    "(3, 'c@it.local', 'x', 'BRAND_PARTNER', true, true), " +
                    "(4, 'buyer@it.local', 'x', 'CUSTOMER', true, true)");
            st.execute("INSERT INTO brand_partners (id, user_id, brand_name, slug, status, approved) VALUES " +
                    "(1, 1, 'Alpha', 'alpha', 'ACTIVE', true), " +
                    "(2, 2, 'Beta', 'beta', 'ACTIVE', true), " +
                    "(3, 3, 'Gamma', 'gamma', 'ACTIVE', true)");
            st.execute("INSERT INTO products (id, name, slug, brand_id, creator_id, category, gender, " +
                    "outfit_slot, product_type, status, complete_the_look_enabled, return_period_days) VALUES " +
                    "(1, 'Alpha Hoodie', 'alpha-hoodie', 1, 1, 'CLOTHING', 'UNISEX', 'TOP', 'HOODIE', 'ACTIVE', false, 14), " +
                    "(2, 'Beta Tee', 'beta-tee', 2, 2, 'CLOTHING', 'UNISEX', 'TOP', 'T_SHIRT', 'ACTIVE', false, 14), " +
                    "(3, 'Gamma Cap', 'gamma-cap', 3, 3, 'ACCESSORIES', 'UNISEX', 'ACCESSORY', 'CAP', 'ACTIVE', false, 14)");
            st.execute("INSERT INTO product_colors (id, product_id, sku, color, color_family) VALUES " +
                    "(1, 1, 'SKU00001', 'Black', 'BLACK'), (2, 2, 'SKU00002', 'White', 'WHITE'), " +
                    "(3, 3, 'SKU00003', 'Red', 'RED')");
            st.execute("INSERT INTO product_variants (id, product_id, product_color_id, size, stock_quantity) VALUES " +
                    "(1, 1, 1, 'M', 5), (2, 2, 2, 'M', 5), (3, 3, 3, 'ONE', 5)");

            // Order 100: two brands (Alpha + Beta) -> ambiguous. Order 200: one brand (Gamma) -> not ambiguous.
            st.execute("INSERT INTO orders (id, order_number, buyer_id, status, subtotal, shipping_total, total, " +
                    "currency, shipping_carrier, tracking_number, shipped_at, created_at, updated_at) VALUES " +
                    "(100, 'ENS-LEGACY-1', 4, 'SHIPPED', 100.00, 5.00, 105.00, 'EUR', 'DHL', 'OLD-TRACK-1', now(), now(), now()), " +
                    "(200, 'ENS-LEGACY-2', 4, 'DELIVERED', 20.00, 5.00, 25.00, 'EUR', 'UPS', 'OLD-TRACK-2', now(), now(), now())");
            st.execute("INSERT INTO order_items (id, order_id, variant_id, product_snapshot_name, " +
                    "variant_snapshot_sku, variant_snapshot_color, variant_snapshot_size, price_at_purchase, " +
                    "quantity, line_total) VALUES " +
                    "(1, 100, 1, 'Alpha Hoodie', 'SKU00001', 'Black', 'M', 50.00, 1, 50.00), " +
                    "(2, 100, 2, 'Beta Tee', 'SKU00002', 'White', 'M', 50.00, 1, 50.00), " +
                    "(3, 200, 3, 'Gamma Cap', 'SKU00003', 'Red', 'ONE', 20.00, 1, 20.00)");
        }

        Flyway.configure()
                .dataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())
                .locations("classpath:db/migration")
                .target(MigrationVersion.LATEST)
                .load()
                .migrate();

        try (Connection c = DriverManager.getConnection(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
             Statement st = c.createStatement();
             ResultSet rs = st.executeQuery(
                     "SELECT order_id, brand_name, is_ambiguous, legacy_tracking_number " +
                     "FROM shipment_backfill_review ORDER BY order_id, brand_name")) {

            java.util.List<String> rows = new java.util.ArrayList<>();
            while (rs.next()) {
                rows.add(rs.getLong("order_id") + "|" + rs.getString("brand_name") + "|"
                        + rs.getBoolean("is_ambiguous") + "|" + rs.getString("legacy_tracking_number"));
            }

            assertThat(rows).containsExactlyInAnyOrder(
                    "100|Alpha|true|OLD-TRACK-1",
                    "100|Beta|true|OLD-TRACK-1",
                    "200|Gamma|false|OLD-TRACK-2");
        }
    }
}
