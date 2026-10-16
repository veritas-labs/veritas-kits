package uk.veritaslabs.kits.sync

import app.cash.sqldelight.async.coroutines.awaitAsOneOrNull
import kotlin.jvm.optionals.getOrNull
import kotlin.time.Clock
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.first
import org.automerge.AmValue
import org.automerge.Document
import org.automerge.ObjectId
import uk.veritaslabs.kits.docs.AutomergeDocumentCore
import uk.veritaslabs.kits.docs.Scope
import uk.veritaslabs.kits.docs.SpaceDocument
import uk.veritaslabs.kits.docs.SpaceDocumentStore
import uk.veritaslabs.kits.docs.SpaceDocumentType
import uk.veritaslabs.kits.docs.spaceDocumentId
import uk.veritaslabs.kits.spaces.SpaceSeeding
import uk.veritaslabs.kits.store.KitsDatabase

/** The name the tests' devices give themselves; the kit does not know the platform's device name. */
internal fun getDeviceName(): String = "Test device"

/** The module ids the nine test document types file their Sync activity under: the names the engine's first app uses, which are persisted. */
internal object TestModules {
    const val Pantry = "pantry"
    const val Shopping = "shopping"
    const val Cookbook = "cookbook"
    const val MealPlan = "mealplan"
    const val Calendar = "calendar"
    const val Tasks = "tasks"
    const val Merchants = "merchants"
}

/**
 * A Space document made of notes (a text under a key in the root map), so the engine can be tested with documents of its own
 * instead of an app's. It is the smallest thing that is a [SpaceDocument] over a real Automerge document.
 */
internal class NoteDocument private constructor(
    private val state: MutableStateFlow<List<String>>,
    private val core: AutomergeDocumentCore,
) : SpaceDocument by core {

    constructor(
        document: Document,
        persist: suspend (ByteArray) -> Unit = {},
        onLocalChange: () -> Unit = {},
    ) : this(MutableStateFlow(document.notes()), document, persist, onLocalChange)

    private constructor(
        state: MutableStateFlow<List<String>>,
        document: Document,
        persist: suspend (ByteArray) -> Unit,
        onLocalChange: () -> Unit,
    ) : this(state, AutomergeDocumentCore(document, { state.value = document.notes() }, persist, onLocalChange))

    /** Every note, sorted. */
    val notes: Flow<List<String>> = state

    /** Adds [text] as a note under its own key. */
    suspend fun add(text: String) = core.edit {
        val tx = startTransaction()
        tx.set(ObjectId.ROOT, "note:$text", text)
        tx.commit()
    }

    /** Removes the note [text], if there is one. */
    suspend fun remove(text: String) = core.edit {
        val tx = startTransaction()
        tx.delete(ObjectId.ROOT, "note:$text")
        tx.commit()
    }

    /** Republishes and persists after the document changed outside [add]/[remove], e.g. by applying a sync message. */
    suspend fun refresh() = core.refresh()
}

private fun Document.notes(): List<String> =
    mapEntries(ObjectId.ROOT).getOrNull()?.mapNotNull { (it.value as? AmValue.Str)?.value }?.sorted().orEmpty()

/** One [NoteDocument] per Space, shared by everything on one database (the sync and the test must edit the same document). */
internal class NoteSources(val database: KitsDatabase, val feature: String) {

    private val store = SpaceDocumentStore(
        database, feature, { Clock.System.now().toEpochMilliseconds() },
        refreshNew = { it.refresh() },
        create = { _, document, persist, changed -> NoteDocument(document, persist, changed) },
    )

    val localChanges: SharedFlow<String> = store.localChanges

    suspend fun forSpace(spaceId: String): NoteDocument? = store.forSpace(spaceId)

    /** The notes of [spaceId]'s document, sorted; fails if there is no such Space. */
    suspend fun titles(spaceId: String): List<String> = checkNotNull(forSpace(spaceId)).notes.first()
}

/**
 * The nine document types of the first app on this engine, as synthetic note documents with the same features, modules and scopes
 * (the engine's send order names the features), in registration order with the private ones after the shared ones.
 */
internal class TestSources(val database: KitsDatabase) {
    val pantry = NoteSources(database, "pantry")
    val shopping = NoteSources(database, "shopping-list")
    val cookbook = NoteSources(database, "cookbook")
    val merchants = NoteSources(database, "merchants")
    val mealPlan = NoteSources(database, "mealplan")
    val calendar = NoteSources(database, "calendar")
    val tasks = NoteSources(database, "tasks")
    val privateCalendar = NoteSources(database, "private-calendar")
    val privateTasks = NoteSources(database, "private-tasks")

    fun documentTypes(): List<SpaceDocumentType> = listOf(
        type(pantry, TestModules.Pantry, Scope.Shared),
        type(shopping, TestModules.Shopping, Scope.Shared),
        type(cookbook, TestModules.Cookbook, Scope.Shared),
        type(merchants, TestModules.Merchants, Scope.Shared),
        type(mealPlan, TestModules.MealPlan, Scope.Shared),
        type(calendar, TestModules.Calendar, Scope.Shared),
        type(tasks, TestModules.Tasks, Scope.Shared),
        type(privateCalendar, TestModules.Calendar, Scope.Private),
        type(privateTasks, TestModules.Tasks, Scope.Private),
    )

    private fun type(sources: NoteSources, module: String, scope: Scope) =
        SpaceDocumentType(sources.feature, module, scope, sources::forSpace, sources.localChanges)
}

/** The seeding the first app uses: a Space made here gets its first document; one that arrives gets empty placeholders for two. */
internal class TestSeeding(private val sources: TestSources, private val database: KitsDatabase) : SpaceSeeding {

    override suspend fun seedCreated(spaceId: String) {
        sources.pantry.forSpace(spaceId)
    }

    override suspend fun placeholdersForArrived(spaceId: String) {
        listOf("pantry", "shopping-list").forEach { feature ->
            val id = spaceDocumentId(spaceId, feature)
            if (database.automergeDocumentQueries.selectById(id).awaitAsOneOrNull() == null) {
                database.automergeDocumentQueries.upsert(id, spaceId, feature, Document().save(), Clock.System.now().toEpochMilliseconds())
            }
        }
    }
}

/** A Space repository over [database] that tells [changes] and has its own Profile, as an app's screens would. */
internal fun spaceRepository(
    database: KitsDatabase,
    changes: uk.veritaslabs.kits.spaces.SpaceChangeListener? = null,
): uk.veritaslabs.kits.spaces.SpaceRepository = uk.veritaslabs.kits.spaces.SpaceRepository(
    database, changes, uk.veritaslabs.kits.spaces.ProfileRepository(database, deviceName = ::getDeviceName),
)
