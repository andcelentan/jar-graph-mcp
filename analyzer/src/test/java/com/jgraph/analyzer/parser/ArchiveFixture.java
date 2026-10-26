package com.jgraph.analyzer.parser;

import org.thirdparty.library.ThirdPartyClient;

/**
 * Classes packed into fat jars, WARs and EARs by archive tests: the application, an internal library under the same
 * root package, and a call to a third-party library ({@link ThirdPartyClient}, in another package).
 */
public class ArchiveFixture {

    /** Application code, under {@code BOOT-INF/classes/} or {@code WEB-INF/classes/}. */
    public static class OrderService {
        private final PaymentGateway gateway;
        private final ThirdPartyClient client;

        public OrderService(PaymentGateway gateway, ThirdPartyClient client) {
            this.gateway = gateway;
            this.client = client;
        }

        public void placeOrder(String orderId) {
            gateway.charge(client.fetch(orderId));
        }
    }

    /** An internal library, in a nested jar: same root package as the application. */
    public interface PaymentGateway {
        void charge(String orderId);
    }
}
