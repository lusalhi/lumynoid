package dev.lusalhi.lumynoid.core

import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerialName
import kotlinx.serialization.descriptors.PolymorphicKind
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.descriptors.SerialKind
import kotlinx.serialization.descriptors.elementDescriptors
import kotlinx.serialization.descriptors.elementNames
import kotlinx.serialization.serializer
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * AD-19 / Consistency Conventions: every persisted subtype and enum entry carries an explicit, never-changing
 * snake_case `@SerialName`. Without one the stored name is the class's fully qualified name, so a rename or a
 * package move silently orphans old rows (they would decode to `Unknown`).
 *
 * A new persisted sealed root or enum must be added to [persistedRoots]; the test cannot discover it.
 */
@OptIn(ExperimentalSerializationApi::class)
class SerialNamePolicyTest {
    private val snakeCase = Regex("[a-z][a-z0-9]*(_[a-z0-9]+)*")

    private val persistedRoots: List<SerialDescriptor> = listOf(
        serializer<Origin>().descriptor,
        serializer<TaskEvent>().descriptor,
        serializer<FailureReason>().descriptor,
    )

    /** Names that reach storage under [root]: sealed subtype names and enum entry names. Returns the offenders. */
    private fun violations(root: SerialDescriptor, seen: MutableSet<String> = mutableSetOf()): List<String> {
        if (!seen.add(root.serialName)) return emptyList()
        val own = when {
            root.kind == SerialKind.ENUM ->
                root.elementNames.filterNot(snakeCase::matches).map { "enum ${root.serialName} entry '$it'" }
            root.kind == PolymorphicKind.SEALED -> subtypes(root).filterNot { snakeCase.matches(it.serialName) }
                .map { "sealed subtype '${it.serialName}' of ${root.serialName}" }
            else -> emptyList()
        }
        val children = (if (root.kind == PolymorphicKind.SEALED) subtypes(root) else root.elementDescriptors.toList())
        return own + children.flatMap { violations(it, seen) }
    }

    private fun subtypes(sealed: SerialDescriptor): List<SerialDescriptor> =
        sealed.getElementDescriptor(1).elementDescriptors.toList()

    @Test
    fun everyPersistedTypeHasAnExplicitSnakeCaseSerialName() {
        persistedRoots.forEach { root ->
            assertEquals(emptyList(), violations(root), "persisted names under ${root.serialName} need an explicit @SerialName")
        }
    }

    @Test
    fun theCheckRejectsAnUnnamedSubtypeAndAMisCasedEnumEntry() {
        val found = violations(serializer<UnnamedFixture>().descriptor) + violations(serializer<MisCasedFixture>().descriptor)
        assertTrue(found.any { "Unnamed" in it }, "unnamed subtype not detected: $found")
        assertTrue(found.any { "MisCased" in it || "Camel" in it }, "mis-cased enum entry not detected: $found")
    }

    @Serializable
    private sealed interface UnnamedFixture {
        @Serializable
        data object Unnamed : UnnamedFixture
    }

    @Serializable
    private enum class MisCasedFixture {
        @SerialName("CamelCase") ENTRY,
    }
}
