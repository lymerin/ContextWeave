package com.devcollab.mcp.cache;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class ReverseDependencyIndexTests {

    @Test
    void symbolExportChangeReturnsFilesDependingOnOldSymbol() {
        ReverseDependencyIndex index = new ReverseDependencyIndex();
        index.updateFile("src/A.java", List.of("A"), List.of());
        index.updateFile("src/B.java", List.of("B"), List.of("A"));

        Set<String> affected = index.updateExportsAndFindAffected(
                "src/A.java", List.of("RenamedA")
        );

        assertThat(affected).containsExactly("src/B.java");
        assertThat(index.exportingFile("RenamedA")).isEqualTo("src/A.java");
    }

    @Test
    void updatingDependentFileRemovesStaleReverseEdges() {
        ReverseDependencyIndex index = new ReverseDependencyIndex();
        index.updateFile("src/B.java", List.of("B"), List.of("A"));
        index.updateFile("src/B.java", List.of("B"), List.of("C"));

        assertThat(index.dependentsOfSymbols(List.of("A"))).isEmpty();
        assertThat(index.dependentsOfSymbols(List.of("C"))).containsExactly("src/B.java");
    }

    @Test
    void removingFileClearsExportAndDependentEdges() {
        ReverseDependencyIndex index = new ReverseDependencyIndex();
        index.updateFile("src/A.java", List.of("A"), List.of());
        index.updateFile("src/B.java", List.of("B"), List.of("A"));

        index.removeFile("src/B.java");
        index.removeFile("src/A.java");

        assertThat(index.dependentsOfSymbols(List.of("A"))).isEmpty();
        assertThat(index.exportingFile("A")).isNull();
    }
}
