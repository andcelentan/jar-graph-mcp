package com.jgraph.mcp.fixture;

import org.springframework.data.domain.AuditorAware;
import org.springframework.data.repository.Repository;

import java.util.List;

/**
 * Code analyzed by the analyzer pipeline in mcp-server tests: one case per kind of answer a tool must give
 * (ADR-0009). {@link Repository} and {@link AuditorAware} are deliberately not in {@link #CLASSES}: they stay outside
 * the analysis.
 */
public class McpFixture {

    public static final Class<?>[] CLASSES = {
            PaymentService.class,
            AbstractPaymentService.class,
            CardPaymentService.class,
            PaypalPaymentService.class,
            AuditLog.class,
            Notifier.class,
            Order.class,
            OrderRepository.class,
            OrderAuditor.class,
            Store.class,
            OrderStore.class,
            Checkout.class,
            Legacy.class,
            Legacy.Order.class
    };

    // --- Interface with two implementations, one inheriting a concrete method ---

    public interface PaymentService {
        void pay(String orderId);
    }

    public abstract static class AbstractPaymentService implements PaymentService {
        public void refund(String orderId) {
        }
    }

    public static class CardPaymentService extends AbstractPaymentService {
        @Override
        public void pay(String orderId) {
        }
    }

    public static class PaypalPaymentService extends AbstractPaymentService {
        @Override
        public void pay(String orderId) {
        }

        @Override
        public void refund(String orderId) {
            super.refund(orderId);
        }
    }

    // --- Abstract method with no implementation in the analysis ---

    public interface AuditLog {
        void record(String event);
    }

    // --- Overloads: a method name alone does not identify the method ---

    public interface Notifier {
        void send(String message);

        void send(String message, List<String> recipients);
    }

    // --- Spring Data repository: implemented at run time, never in the jar ---

    public static class Order {
    }

    public interface OrderRepository extends Repository<Order, Long> {
        List<Order> findByCustomerId(String customerId);
    }

    // --- Extends a Spring Data type that is not a repository: nothing is generated for it at run time ---

    public interface OrderAuditor extends AuditorAware<String> {
        String currentTenant();
    }

    // --- Generic interface: javac adds a bridge save(Object) to OrderStore (ADR-0007) ---

    public interface Store<T> {
        void save(T item);
    }

    public static class OrderStore implements Store<Order> {
        @Override
        public void save(Order order) {
        }
    }

    // --- Lambda: javac moves its body to a synthetic method, lambda$checkoutAll$0 ---

    public static class Checkout {
        private final PaymentService payments;

        public Checkout(PaymentService payments) {
            this.payments = payments;
        }

        public void checkoutAll(List<String> orderIds) {
            orderIds.forEach(orderId -> payments.pay(orderId));
        }
    }

    // --- Same simple name as McpFixture.Order: a simple name may match several classes ---

    public static class Legacy {
        public static class Order {
        }
    }
}
