package com.jgraph.analyzer.parser;

import com.jgraph.analyzer.model.AnalysisResult;

/**
 * What {@link BytecodeReader} produced: the model, and how many class files were left out of it and why.
 *
 * @param model             the classes, methods and calls read
 * @param classFilesRead    every class file the source handed over
 * @param excludedByFilter  class files outside the package filter
 * @param duplicatesSkipped class files skipped because a class with the same name was read before
 */
public record ReadResult(
        AnalysisResult model,
        int classFilesRead,
        int excludedByFilter,
        int duplicatesSkipped
) {
}
