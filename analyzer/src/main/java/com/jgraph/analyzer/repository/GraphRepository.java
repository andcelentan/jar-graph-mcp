package com.jgraph.analyzer.repository;

import com.jgraph.analyzer.model.AnalysisResult;

public interface GraphRepository extends AutoCloseable {

    /**
     * Unique constraint and indexes
     */
    void initSchema();

    void save(AnalysisResult result);

    /**
     * Delete all nodes and relationship from the graph
     */
    void deleteAll();

    @Override
    void close();
}