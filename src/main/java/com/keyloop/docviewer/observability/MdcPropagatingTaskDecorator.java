package com.keyloop.docviewer.observability;

import java.util.Map;
import org.slf4j.MDC;
import org.springframework.core.task.TaskDecorator;
import org.springframework.core.task.support.ContextPropagatingTaskDecorator;

/**
 * Carries the caller's context onto the threads that call source systems: the tracing
 * context (via Micrometer context propagation, so outbound calls become child spans of the
 * request) and the MDC (so their log lines keep the correlation id).
 */
public class MdcPropagatingTaskDecorator implements TaskDecorator {

    private final TaskDecorator tracingContext = new ContextPropagatingTaskDecorator();

    @Override
    public Runnable decorate(Runnable runnable) {
        Runnable withTracing = tracingContext.decorate(runnable);
        Map<String, String> callerMdc = MDC.getCopyOfContextMap();
        return () -> {
            Map<String, String> previous = MDC.getCopyOfContextMap();
            if (callerMdc != null) {
                MDC.setContextMap(callerMdc);
            }
            try {
                withTracing.run();
            } finally {
                if (previous != null) {
                    MDC.setContextMap(previous);
                } else {
                    MDC.clear();
                }
            }
        };
    }
}
