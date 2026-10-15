package com.jgraph.analyzer.parser;

/**
 * Calls whose bytecode names the receiver's static type instead of the declaring class, and calls that do not dispatch
 * (ADR-0008). No generics and no covariant return types, so javac generates no bridge methods.
 */
public class CallFixture {

    public static final Class<?>[] CLASSES = {
            PaymentService.class,
            AbstractPaymentService.class,
            CardPaymentService.class,
            PaypalPaymentService.class,
            Status.class,
            Checkout.class
    };

    public interface PaymentService {
        void pay(String orderId);
    }

    public abstract static class AbstractPaymentService implements PaymentService {

        // pay(String) is declared only in PaymentService

        public void refund(String orderId) {
            log(orderId);
        }

        protected void log(String message) {
        }

        public static String currency() {
            return "EUR";
        }
    }

    public static class CardPaymentService extends AbstractPaymentService {
        @Override
        public void pay(String orderId) {
            log(orderId);                       // inherited helper: invokevirtual CardPaymentService.log
        }
    }

    public static class PaypalPaymentService extends AbstractPaymentService {
        @Override
        public void pay(String orderId) {
        }

        @Override
        public void refund(String orderId) {
            super.refund(orderId);              // invokespecial AbstractPaymentService.refund: no dispatch
        }
    }

    public enum Status {
        ACTIVE
    }

    public static class Checkout {
        public void checkout(CardPaymentService card, AbstractPaymentService any, PaymentService api, String id) {
            card.refund(id);                    // invokevirtual CardPaymentService.refund: inherited
            any.pay(id);                        // invokevirtual AbstractPaymentService.pay: declared in the interface
            api.pay(id);                        // invokeinterface PaymentService.pay: declared
            CardPaymentService.currency();      // invokestatic CardPaymentService.currency: inherited static
        }

        public String describe(Status status) {
            return status.name();               // invokevirtual Status.name: declared in java/lang/Enum
        }
    }
}
