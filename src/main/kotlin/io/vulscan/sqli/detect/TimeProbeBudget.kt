package io.vulscan.sqli.detect

import java.util.concurrent.atomic.AtomicInteger

/**
 * Лимит на суммарное число проб с реальной задержкой за всё задание (`maxTimeProbesPerJob`, Q-1).
 * Делится между вызовами [TimeBasedDetector.detect] для разных параметров одного задания: когда
 * свободного места не остаётся, оставшиеся параметры не получают ни одной probe-с-задержкой пробы.
 */
class TimeProbeBudget(private val max: Int) {
    private val used = AtomicInteger(0)

    /** Резервирует [n] проб атомарно; при нехватке лимита не резервирует ничего и возвращает false. */
    fun reserve(n: Int): Boolean {
        while (true) {
            val current = used.get()
            if (current + n > max) return false
            if (used.compareAndSet(current, current + n)) return true
        }
    }

    fun usedCount(): Int = used.get()
}
