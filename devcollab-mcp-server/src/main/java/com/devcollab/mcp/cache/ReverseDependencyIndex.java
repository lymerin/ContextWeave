package com.devcollab.mcp.cache;

import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

public class ReverseDependencyIndex {

    private final Map<String, Set<String>> exportsByFile = new LinkedHashMap<>();
    private final Map<String, Set<String>> referencesByFile = new LinkedHashMap<>();
    private final Map<String, String> exportingFileBySymbol = new LinkedHashMap<>();
    private final Map<String, Set<String>> dependentsBySymbol = new LinkedHashMap<>();

    public synchronized void updateFile(
            String filePath,
            Collection<String> exportedSymbols,
            Collection<String> referencedSymbols
    ) {
        removeFile(filePath);
        Set<String> exports = normalizedSet(exportedSymbols);
        Set<String> references = normalizedSet(referencedSymbols);
        exportsByFile.put(filePath, exports);
        referencesByFile.put(filePath, references);
        for (String symbol : exports) {
            exportingFileBySymbol.put(symbol, filePath);
        }
        for (String symbol : references) {
            dependentsBySymbol
                    .computeIfAbsent(symbol, _symbol -> new LinkedHashSet<>())
                    .add(filePath);
        }
    }

    public synchronized Set<String> updateExportsAndFindAffected(
            String filePath,
            Collection<String> newExportedSymbols
    ) {
        Set<String> current = exportsByFile.getOrDefault(filePath, Set.of());
        Set<String> next = normalizedSet(newExportedSymbols);
        Set<String> changedSymbols = symmetricDifference(current, next);
        Set<String> affected = dependentsOfSymbols(changedSymbols);

        for (String symbol : current) {
            exportingFileBySymbol.remove(symbol, filePath);
        }
        exportsByFile.put(filePath, next);
        for (String symbol : next) {
            exportingFileBySymbol.put(symbol, filePath);
        }
        return affected;
    }

    public synchronized Set<String> dependentsOfSymbols(Collection<String> symbols) {
        Set<String> dependents = new LinkedHashSet<>();
        for (String symbol : normalizedSet(symbols)) {
            dependents.addAll(dependentsBySymbol.getOrDefault(symbol, Set.of()));
        }
        return Collections.unmodifiableSet(dependents);
    }

    public synchronized Set<String> directDependentsOfFile(String filePath) {
        Set<String> exports = exportsByFile.getOrDefault(filePath, Set.of());
        return dependentsOfSymbols(exports);
    }

    public synchronized void removeFile(String filePath) {
        Set<String> exports = exportsByFile.remove(filePath);
        if (exports != null) {
            for (String symbol : exports) {
                exportingFileBySymbol.remove(symbol, filePath);
            }
        }
        Set<String> references = referencesByFile.remove(filePath);
        if (references != null) {
            for (String symbol : references) {
                Set<String> dependents = dependentsBySymbol.get(symbol);
                if (dependents == null) {
                    continue;
                }
                dependents.remove(filePath);
                if (dependents.isEmpty()) {
                    dependentsBySymbol.remove(symbol);
                }
            }
        }
    }

    public synchronized String exportingFile(String symbol) {
        return exportingFileBySymbol.get(symbol);
    }

    private Set<String> normalizedSet(Collection<String> values) {
        if (values == null || values.isEmpty()) {
            return Set.of();
        }
        Set<String> normalized = new LinkedHashSet<>();
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                normalized.add(value.trim());
            }
        }
        return Collections.unmodifiableSet(normalized);
    }

    private Set<String> symmetricDifference(Set<String> left, Set<String> right) {
        Set<String> changed = new LinkedHashSet<>(left);
        for (String value : right) {
            if (!changed.add(value)) {
                changed.remove(value);
            }
        }
        return changed;
    }
}
