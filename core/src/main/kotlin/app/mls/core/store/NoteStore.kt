package app.mls.core.store

/** Persistence for the local cache snapshot. Implementations must keep note content encrypted at rest. */
interface NoteStore {
    fun read(): CacheSnapshot
    fun write(snapshot: CacheSnapshot)
}

/** Non-persistent store for tests and ephemeral use. */
class InMemoryNoteStore(initial: CacheSnapshot = CacheSnapshot()) : NoteStore {
    @Volatile
    private var snapshot = initial
    override fun read(): CacheSnapshot = snapshot
    override fun write(snapshot: CacheSnapshot) { this.snapshot = snapshot }
}
