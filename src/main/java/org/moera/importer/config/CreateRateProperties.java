package org.moera.importer.config;

import java.time.Duration;
import jakarta.annotation.PostConstruct;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Component
@ConfigurationProperties("moera.delivery.create-rate")
public class CreateRateProperties {

    private int count = 10;
    private Duration period = Duration.ofMinutes(1);

    public int getCount() {
        return count;
    }

    public void setCount(int count) {
        this.count = count;
    }

    public Duration getPeriod() {
        return period;
    }

    public void setPeriod(Duration period) {
        this.period = period;
    }

    public Duration interval() {
        Duration interval = period.dividedBy(count);
        if (interval.multipliedBy(count).compareTo(period) < 0) {
            interval = interval.plusNanos(1);
        }
        return interval;
    }

    @PostConstruct
    void validate() {
        if (count <= 0 || period == null || period.isZero() || period.isNegative()) {
            throw new IllegalStateException("Destination create rate count and period must be positive");
        }
    }

}
