package com.jgraph.analyzer.parser;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;

/**
 * One type per {@link com.jgraph.analyzer.model.ClassKind}, each with a supertype added implicitly by the compiler
 * (java/lang/Object, java/lang/Enum, java/lang/Record, java/lang/annotation/Annotation).
 */
public class TypeKindFixture {

    public static final Class<?>[] CLASSES = {
            PlainClass.class,
            Marker.class,
            Status.class,
            Point.class,
            Audited.class
    };

    public static class PlainClass {
    }

    public interface Marker {
    }

    public enum Status {
        ACTIVE,
        INACTIVE
    }

    public record Point(int x, int y) {
    }

    @Retention(RetentionPolicy.RUNTIME)
    public @interface Audited {
    }
}
