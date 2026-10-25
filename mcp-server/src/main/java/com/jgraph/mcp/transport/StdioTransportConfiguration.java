package com.jgraph.mcp.transport;

import org.springframework.ai.mcp.customizer.McpSyncServerCustomizer;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Makes the STDIO transport of the MCP SDK 2.0.0 safe for parallel tool calls (ADR-0011).
 * <p>
 * That transport queues every outgoing message in a Reactor {@code Sinks.many().unicast()}, which accepts one producer
 * at a time: when two threads emit at the same instant the second gets {@code FAIL_NON_SERIALIZED}, logged as "Failed to
 * enqueue message", and the response is dropped. Worse, that error ends the inbound pipeline of the transport (it is
 * subscribed without an error handler): from then on the server answers nothing, and the client waits forever. By
 * default a {@code SYNC} server runs each tool call on its own {@code boundedElastic} thread, so two calls finishing
 * together are two producers.
 * <p>
 * With immediate execution the SDK runs the tool on the thread that delivers the request, which for this transport is
 * its single thread reading standard input: calls are served one at a time, in arrival order, and every response is
 * emitted by that one thread. Spring AI turns it on by itself for servlet applications only, with a customizer of its
 * own: it accepts a single {@link McpSyncServerCustomizer}, and the two never coexist, since this one only exists with
 * STDIO, which runs without a web server. The price is that a slow
 * call delays the next ones, at most by the {@code query_graph} timeout. {@code StdioTransportIT} fails if responses
 * are lost again, after an upgrade for instance.
 */
@Configuration(proxyBeanMethods = false)
class StdioTransportConfiguration {

    @Bean
    @ConditionalOnProperty(prefix = "spring.ai.mcp.server", name = "stdio", havingValue = "true")
    McpSyncServerCustomizer serialToolCallsOverStdio() {
        return serverBuilder -> serverBuilder.immediateExecution(true);
    }
}
