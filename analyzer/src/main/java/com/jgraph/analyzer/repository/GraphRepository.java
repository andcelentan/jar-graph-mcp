package com.jgraph.analyzer.repository;

import com.jgraph.analyzer.model.AnalysisResult;

public interface GraphRepository extends AutoCloseable {

    /**
     * Checks that the database can be reached and accepts the credentials, without reading or writing the graph. Called
     * before the archive is read, so a wrong address or password fails in seconds, not after the whole analysis.
     *
     * @throws RuntimeException the adapter's own exception when the database cannot be used
     */
    void verifyConnectivity();

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