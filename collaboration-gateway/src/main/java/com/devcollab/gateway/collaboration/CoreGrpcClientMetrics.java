package com.devcollab.gateway.collaboration;

import io.grpc.ConnectivityState;
import io.grpc.StatusRuntimeException;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.function.Supplier;

@Component
@ConditionalOnProperty(
        prefix = "devcollab.gateway",
        name = "core-transport",
        havingValue = "grpc",
        matchIfMissing = true
)
public class CoreGrpcClientMetrics {

    public static final String REQUESTS_METRIC =
            "devcollab.core.grpc.client.requests";
    public static final String DURATION_METRIC =
            "devcollab.core.grpc.client.duration";
    public static final String CHANNEL_STATE_METRIC =
            "devcollab.core.grpc.channel.state";

    private final MeterRegistry registry;

    public CoreGrpcClientMetrics(
            MeterRegistry registry,
            CoreGrpcChannel channel
    ) {
        this.registry = registry;
        for (ConnectivityState state : ConnectivityState.values()) {
            Gauge.builder(
                            CHANNEL_STATE_METRIC,
                            channel,
                            current -> current.state() == state ? 1.0 : 0.0
                    )
                    .description("Knowledge Core gRPC channel state (one-hot)")
                    .tag("state", state.name())
                    .register(registry);
        }
    }

    public <T> T record(String method, Supplier<T> invocation) {
        Timer.Sample sample = Timer.start(registry);
        String status = "OK";
        try {
            return invocation.get();
        } catch (StatusRuntimeException exception) {
            status = exception.getStatus().getCode().name();
            throw exception;
        } catch (RuntimeException exception) {
            status = "CLIENT_ERROR";
            throw exception;
        } finally {
            registry.counter(
                    REQUESTS_METRIC,
                    "method", method,
                    "status", status
            ).increment();
            sample.stop(registry.timer(
                    DURATION_METRIC,
                    "method", method,
                    "status", status
            ));
        }
    }
}
