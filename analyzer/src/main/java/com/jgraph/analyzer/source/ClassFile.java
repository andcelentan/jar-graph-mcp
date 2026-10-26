package com.jgraph.analyzer.source;

import java.util.Objects;

/**
 * The bytes of one {@code .class} file, and where it was found.
 *
 * @param origin where the file is, archives separated by {@code !/}, e.g.
 *               {@code app.jar!/BOOT-INF/lib/core.jar!/com/acme/Foo.class}: for messages only, the class name comes
 *               from the bytecode
 * @param bytes  the content of the file
 */
public record ClassFile(String origin, byte[] bytes) {

    public ClassFile {
        Objects.requireNonNull(origin, "origin");
        Objects.requireNonNull(bytes, "bytes");
    }
}
