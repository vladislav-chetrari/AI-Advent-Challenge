package agent.domain

// Task 5: стратегии управления контекстом (без summary — summary остался
// отдельным legacy-режимом из Task 4 для честного сравнения).
enum class StrategyType(val id: String) {
    SLIDING("sliding"),
    FACTS("facts"),
    BRANCHING("branching"),
    SUMMARY_LEGACY("summary_legacy"),
    ;

    companion object {
        fun byId(id: String?): StrategyType =
            entries.firstOrNull { it.id == id } ?: SLIDING
    }
}
