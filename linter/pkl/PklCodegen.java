// Copyright 2026 The Project Contributors
// SPDX-License-Identifier: MIT

package dev.groknull.bpmner.tools.pkl;

import java.nio.file.Files;
import java.nio.file.Path;
import org.pkl.codegen.java.JavaCodeGenerator;
import org.pkl.codegen.java.JavaCodeGeneratorOptions;
import org.pkl.core.ModuleSource;
import org.pkl.core.Evaluator;

/** Minimal hermetic bridge for Pkl's Java API, which exposes Spring generation but no Bazel rule. */
public final class PklCodegen {
    private PklCodegen() {}

    public static void main(String[] args) throws Exception {
        if (args.length != 2) {
            throw new IllegalArgumentException("Expected Pkl module and generated Java output paths");
        }

        var output = Path.of(args[1]);
        try (var evaluator = Evaluator.preconfigured()) {
            var schema = evaluator.evaluateSchema(ModuleSource.path(Path.of(args[0])));
            var generator = new JavaCodeGenerator(
                schema,
                new JavaCodeGeneratorOptions("  ", false, true, false, true, null, null, false, java.util.Map.of())
            );
            var source = generator.getOutput().entrySet().stream()
                .filter(entry -> entry.getKey().endsWith(".java"))
                .findFirst()
                .orElseThrow()
                .getValue();
            Files.createDirectories(output.getParent());
            Files.writeString(output, source);
        }
    }
}
