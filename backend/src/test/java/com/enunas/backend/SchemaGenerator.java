package com.enunas.backend;

import org.hibernate.boot.Metadata;
import org.hibernate.boot.MetadataSources;
import org.hibernate.boot.registry.StandardServiceRegistry;
import org.hibernate.boot.registry.StandardServiceRegistryBuilder;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;

/**
 * One-off developer tool: generates the full PostgreSQL DDL from the entity model
 * using the same dialect and naming strategies as the running app, so the output
 * can be captured as a Flyway baseline migration. Script-only — no DB connection
 * (JDBC metadata access is disabled and no connection provider is configured).
 *
 * Disabled by default so it does not run in the normal build. Re-run on demand
 * after entity changes to regenerate the baseline:
 *   ./mvnw test -Dtest=SchemaGenerator -DfailIfNoTests=false
 * Output: target/generated-schema.sql
 */
@Disabled("Run manually to regenerate the Flyway baseline after entity changes")
class SchemaGenerator {

    @Test
    void generate() {
        StandardServiceRegistry registry = new StandardServiceRegistryBuilder()
                .applySetting("hibernate.dialect", "org.hibernate.dialect.PostgreSQLDialect")
                .applySetting("hibernate.physical_naming_strategy",
                        "org.hibernate.boot.model.naming.CamelCaseToUnderscoresNamingStrategy")
                .applySetting("hibernate.implicit_naming_strategy",
                        "org.springframework.boot.hibernate.SpringImplicitNamingStrategy")
                .applySetting("jakarta.persistence.schema-generation.scripts.action", "create")
                .applySetting("jakarta.persistence.schema-generation.scripts.create-target",
                        "target/generated-schema.sql")
                .applySetting("hibernate.connection.provider_class",
                        "org.hibernate.engine.jdbc.connections.internal.UserSuppliedConnectionProviderImpl")
                .applySetting("hibernate.boot.allow_jdbc_metadata_access", "false")
                .applySetting("hibernate.temp.use_jdbc_metadata_defaults", "false")
                .build();

        MetadataSources sources = new MetadataSources(registry)
                .addAnnotatedClass(com.enunas.backend.user.User.class)
                .addAnnotatedClass(com.enunas.backend.customer.Customer.class)
                .addAnnotatedClass(com.enunas.backend.wardrobe.WardrobeItem.class)
                .addAnnotatedClass(com.enunas.backend.brandpartner.BrandPartner.class)
                .addAnnotatedClass(com.enunas.backend.brandpartner.brandeconomics.BrandEconomics.class)
                .addAnnotatedClass(com.enunas.backend.brandpartner.brandanalytics.BrandAnalytics.class)
                .addAnnotatedClass(com.enunas.backend.brandpartner.brandpayoutprofile.BrandPayoutProfile.class)
                .addAnnotatedClass(com.enunas.backend.brandpartner.brandshippingprofile.BrandShippingProfile.class)
                .addAnnotatedClass(com.enunas.backend.product.Product.class)
                .addAnnotatedClass(com.enunas.backend.product.productvariant.ProductColor.class)
                .addAnnotatedClass(com.enunas.backend.product.productvariant.ProductVariant.class)
                .addAnnotatedClass(com.enunas.backend.product.productlisting.ProductListing.class)
                .addAnnotatedClass(com.enunas.backend.product.producteconomics.ProductEconomics.class)
                .addAnnotatedClass(com.enunas.backend.product.productanalytics.ProductAnalytics.class)
                .addAnnotatedClass(com.enunas.backend.media.ProductImage.class)
                .addAnnotatedClass(com.enunas.backend.media.ProductVideo.class)
                .addAnnotatedClass(com.enunas.backend.order.Order.class)
                .addAnnotatedClass(com.enunas.backend.order.OrderItem.class)
                .addAnnotatedClass(com.enunas.backend.order.ReturnOrder.class)
                .addAnnotatedClass(com.enunas.backend.order.ReturnItem.class)
                .addAnnotatedClass(com.enunas.backend.payment.Payment.class)
                .addAnnotatedClass(com.enunas.backend.ledger.LedgerEntry.class)
                .addAnnotatedClass(com.enunas.backend.payout.Payout.class);

        Metadata metadata = sources.buildMetadata();
        // Building the SessionFactory triggers the schema-generation script output.
        metadata.buildSessionFactory().close();

        StandardServiceRegistryBuilder.destroy(registry);
    }
}
