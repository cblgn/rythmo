package fr.rythmo

/** Admission is synchronous: repeated UI events cannot enter the persistence queue. */
class PassageGuard {
    private val pending = mutableSetOf<String>()
    private val blockedUntil = mutableMapOf<String, Long>()
    private val undoUntil = mutableMapOf<String, Long>()
    fun begin(ids: Set<String>, now: Long): Boolean {
        if (ids.isEmpty() || ids.any { it in pending || now < (blockedUntil[it] ?: 0) }) return false
        pending.addAll(ids)
        return true
    }
    fun saved(ids: Set<String>, now: Long) {
        ids.forEach { blockedUntil[it] = now + 1_000; undoUntil[it] = now + 15_000 }
    }
    fun finish(ids: Set<String>) { pending.removeAll(ids) }
    fun canUndo(id: String, now: Long) = id !in pending && now < (undoUntil[id] ?: 0)
    fun consumeUndo(id: String) { undoUntil.remove(id) }
}
