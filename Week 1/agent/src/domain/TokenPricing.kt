package agent.domain

// Примерные расценки DeepSeek (USD за 1M токенов), только для демо Task 3.
// deepseek-chat (legacy, routes to v4-flash): вход ~0.14, выход ~0.28.
// Точный биллинг — в личном кабинете, здесь считаем только порядок стоимости.
object TokenPricing {
    const val INPUT_USD_PER_MILLION: Double = 0.14
    const val OUTPUT_USD_PER_MILLION: Double = 0.28

    fun inputCostUsd(tokens: Int): Double = tokens * INPUT_USD_PER_MILLION / 1_000_000.0

    fun outputCostUsd(tokens: Int): Double = tokens * OUTPUT_USD_PER_MILLION / 1_000_000.0
}

// Формат стоимости: до 10 знаков после запятой, как требуется в Task 3.
// Locale.US чтобы в русской локали не вылезала запятая (USD всегда с точкой).
fun formatCostUsd(cost: Double): String = String.format(java.util.Locale.US, "%.10f", cost)
