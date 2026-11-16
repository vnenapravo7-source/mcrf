package org.mcrf.client

/** A single-use approval of detached bytes. Never put configuration values in presentation or logs. */
internal class ChangePreview private constructor(
    before: Map<String, String>, after: Map<String, String>,
    groups: List<Group>
) {
    enum class Kind(val title: String) { ADDED("Добавлено"), CHANGED("Изменено"), REMOVED("Удалено") }
    data class Group(val kind: Kind, val items: List<String>)
    val groups:List<Group> = java.util.Collections.unmodifiableList(groups.map {
        Group(it.kind,java.util.Collections.unmodifiableList(it.items.toList()))
    })
    private val baseline = before.toMap()
    private val snapshot = after.toMap()
    val hasChanges:Boolean = baseline.any {!ChangeHistoryStore.equivalent(it.value,snapshot.getValue(it.key))}
    private var consumed = false

    @Synchronized fun checkCurrent(current: Map<String, String>) {
        check(!consumed) { "Предпросмотр уже использован. Откройте его заново." }
        require(current == baseline) { "Настройки изменились. Откройте предпросмотр заново." }
    }

    /** Caller holds the same lock as all configuration writers through validation and commit. */
    @Synchronized fun apply(current: () -> Map<String, String>, validate: (Map<String, String>) -> Unit,
                            commit: (Map<String, String>) -> Unit) {
        checkCurrent(current())
        validate(snapshot.toMap())
        checkCurrent(current())
        commit(snapshot.toMap())
        consumed = true
    }
    override fun toString() = "ChangePreview(values hidden)"

    companion object {
        fun preparePolicy(before:Map<String,String>,after:Map<String,String>,targets:List<Map<String,String>>,notes:List<String> = emptyList()):ChangePreview {
            val base=prepare(before,after)
            val alternatives=targets.map {prepare(before,it)}
            val groups=Kind.entries.map {kind->Group(kind,(if(kind==Kind.CHANGED)notes else emptyList())+base.groups.first {it.kind==kind}.items+
                alternatives.flatMapIndexed {index,preview->preview.groups.first {it.kind==kind}.items.map {"Снимок ${index+1} · $it"}})}
            return ChangePreview(before,after,groups)
        }
        fun prepare(before: Map<String, String>, after: Map<String, String>): ChangePreview {
            require(before.keys == ChangeHistoryStore.STORE_NAMES && after.keys == before.keys)
            val left = before.toMap(); val right = after.toMap()
            return ChangePreview(left, right, ChangeHistoryDiff.grouped(left, right))
        }
    }
}
