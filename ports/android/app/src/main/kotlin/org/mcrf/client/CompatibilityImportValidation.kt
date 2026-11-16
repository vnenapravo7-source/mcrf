package org.mcrf.client

/** Registration is explicit: installed-primary mode never falls back to bundled validation.
 * The validator receives a defensive model; it must validate effective settings in its worker,
 * throw on cancellation/unsupported fields/missing modules, and never mutate catalogue state. */
class CompatibilityImportValidation {
    fun interface Validator { fun validate(candidate: ServerImport.Server) }
    private data class Policy(val installed: Boolean, val validator: Validator?)
    @Volatile private var policy = Policy(false, null)

    /** null deliberately blocks saves until an installed-primary validator is available. */
    @Synchronized fun useInstalledPrimary(validator: Validator?) { policy = Policy(true, validator) }

    fun validateAndCommit(candidate: ServerImport.Server, legacy: Validator, commit: () -> Unit) {
        val captured = policy
        val validator = if (captured.installed) requireNotNull(captured.validator) {
            "Проверка установленного основного ядра недоступна. Настройки не сохранены."
        } else legacy
        val defensive = ConnectionEdits.apply(candidate, ConnectionEdits.snapshot(candidate))
        validator.validate(defensive)
        synchronized(this) {
            check(policy === captured) { "Основное ядро изменилось во время проверки. Повтори сохранение." }
            commit()
        }
    }

    companion object { @JvmField val editor = CompatibilityImportValidation() }
}
