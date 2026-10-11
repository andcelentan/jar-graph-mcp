package com.jgraph.analyzer.parser;

/**
 * Class hierarchy compiled by javac and analyzed in tests: interface inheritance, an abstract base class,
 * inherited and overridden methods, and methods that look like overrides but are not (private, static).
 * No generics and no covariant return types, so javac generates no bridge methods.
 */
public class HierarchyFixture {

    public static final Class<?>[] CLASSES = {
            PaymentService.class,
            AuditedPaymentService.class,
            AbstractPaymentService.class,
            CardPaymentService.class,
            Checkout.class
    };

    public interface PaymentService {
        void pay(String orderId);

        void refund(String orderId);
    }

    public interface AuditedPaymentService extends PaymentService {
        void audit(String orderId);
    }

    public abstract static class AbstractPaymentService implements AuditedPaymentService {

        // pay(String) is not declared here: implemented by subclasses

        @Override
        public void refund(String orderId) {
            log(orderId);
        }

        @Override
        public void audit(String orderId) {
        }

        protected void log(String message) {
        }

        private void secret() {
        }

        static void utility() {
        }
    }

    public static class CardPaymentService extends AbstractPaymentService {

        @Override
        public void pay(String orderId) {
        }

        @Override
        protected void log(String message) {
        }

        // Same name and descriptor as in the superclass, but private: not an override
        private void secret() {
        }

        // Static: hides the superclass method, does not override it
        static void utility() {
        }
    }

    public static class Checkout {
        private final PaymentService paymentService = new CardPaymentService();

        public void checkout(String orderId) {
            paymentService.pay(orderId);
        }
    }
}
