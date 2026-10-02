package com.jgraph.analyzer.parser;

import jakarta.transaction.Transactional;

public class JarReaderTestFixture {

    public interface TestInterface {
        void interfaceMethod();
    }

    public static class TestImplementation implements TestInterface {
        
        @Override
        public void interfaceMethod() {
            System.out.println("External call");
        }

        @Transactional
        public void transactionalMethod(String arg) {
            privateMethod();
        }

        public void overloadedMethod(int i) {
        }

        public void overloadedMethod(String s) {
        }

        private void privateMethod() {
        }
    }
}
