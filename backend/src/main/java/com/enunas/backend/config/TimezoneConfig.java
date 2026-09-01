package com.enunas.backend.config;

import jakarta.annotation.PostConstruct;
import org.springframework.context.annotation.Configuration;

import java.util.TimeZone;

/**
 * Pins the JVM's default time zone to UTC.
 *
 * <p>Every {@code createdAt}/{@code updatedAt} in this codebase is a {@link java.time.LocalDateTime}
 * written with {@code LocalDateTime.now()} — i.e. in whatever zone the JVM happens to default to.
 * The reporting queries that slice by month ({@code Vat22fExportService},
 * {@code SettlementAccountingReportService}, {@code SettlementService}) build their period bounds as
 * Berlin midnights converted to UTC, because a German tax month is a Berlin month but storage is
 * UTC. Writers and readers therefore only agree when the JVM default zone is UTC.
 *
 * <p>Containers default to UTC, so production and CI agreed by accident; a developer machine on
 * Europe/Berlin shifted every window by the Berlin offset. That is invisible except in the final
 * hours of a month, where sales silently fell out of the §22f export and both settlement reports.
 * Making the assumption explicit here is what keeps every environment on the same clock — see also
 * the {@code -Duser.timezone=UTC} surefire argLine, which covers tests that never build this context.
 */
@Configuration
public class TimezoneConfig {

    @PostConstruct
    public void useUtc() {
        TimeZone.setDefault(TimeZone.getTimeZone("UTC"));
    }
}
