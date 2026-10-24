package com.jgraph.mcp.graph;

/**
 * A query written by an assistant that is not run, or not run to the end: not read-only, forbidden, invalid Cypher,
 * or too slow. The message says what to change, since the assistant reads it and retries.
 */
public class QueryRejectedException extends IllegalArgumentException {

    public QueryRejectedException(String message) {
        super(message);
    }

    public QueryRejectedException(String message, Throwable cause) {
        super(message, cause);
    }
}
