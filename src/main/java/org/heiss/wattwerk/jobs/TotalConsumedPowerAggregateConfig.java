package org.heiss.wattwerk.jobs;

import io.smallrye.config.ConfigMapping;
import io.smallrye.config.WithDefault;

import java.time.Duration;

@ConfigMapping(prefix = "wattwerk.jobs.total-consumed-power-aggregate")
public interface TotalConsumedPowerAggregateConfig {
    Source source();

    Output output();

    @WithDefault("12H")
    Duration lookback();

    @WithDefault("5S")
    Duration sourceBucket();

    @WithDefault("5M")
    Duration outputBucket();

    interface Source {
        String solarItem();

        String gridItem();

        String batteryItem();

        @WithDefault("W")
        String unit();
    }

    interface Output {
        String series();

        @WithDefault("W")
        String unit();
    }
}
