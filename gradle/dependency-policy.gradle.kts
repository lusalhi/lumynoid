// Dependency policy support (AD-17). Applied from :app.
// `releaseClasspathReport` writes every module on the resolved release runtime classpath, transitive ones
// included, with the licences its POM (or the nearest parent POM) declares. `checkDependencyPolicy` feeds
// that report to .github/scripts/check_dependency_policy.py, which owns the rules and has its own self-test.

import org.gradle.api.artifacts.component.ModuleComponentIdentifier
import org.gradle.api.artifacts.component.ProjectComponentIdentifier
import org.gradle.api.artifacts.result.ResolvedDependencyResult
import org.gradle.api.artifacts.result.UnresolvedDependencyResult
import org.w3c.dom.Element
import javax.xml.parsers.DocumentBuilderFactory

val reportFile = layout.buildDirectory.file("reports/dependency-policy/release-runtime-classpath.json")

data class PomInfo(val licenses: List<Pair<String, String>>, val parent: Triple<String, String, String>?)

fun Element.child(tag: String): Element? =
    (0 until childNodes.length).map { childNodes.item(it) }.filterIsInstance<Element>().firstOrNull { it.tagName == tag }

fun Element.childText(tag: String): String = child(tag)?.textContent?.trim().orEmpty()

fun readPom(group: String, name: String, version: String): PomInfo? {
    val detached = configurations.detachedConfiguration(dependencies.create("$group:$name:$version@pom"))
    detached.isTransitive = false
    val file = detached.incoming.artifactView { isLenient = true }.files.files.firstOrNull() ?: return null
    val factory = DocumentBuilderFactory.newInstance()
    factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true)
    val project = factory.newDocumentBuilder().parse(file).documentElement
    val licenses = project.child("licenses")?.let { node ->
        (0 until node.childNodes.length).map { node.childNodes.item(it) }.filterIsInstance<Element>()
            .filter { it.tagName == "license" }.map { it.childText("name") to it.childText("url") }
    }.orEmpty()
    val parent = project.child("parent")?.let {
        Triple(it.childText("groupId"), it.childText("artifactId"), it.childText("version"))
    }
    return PomInfo(licenses, parent)
}

/** Licences declared by the module's POM, falling back to the nearest parent POM that declares any. */
fun licensesOf(group: String, name: String, version: String): List<Pair<String, String>> {
    var current: Triple<String, String, String>? = Triple(group, name, version)
    var depth = 0
    while (current != null && depth < 6) {
        val pom = readPom(current.first, current.second, current.third) ?: return emptyList()
        if (pom.licenses.isNotEmpty()) return pom.licenses
        current = pom.parent
        depth++
    }
    return emptyList()
}

val releaseClasspathReport = tasks.register("releaseClasspathReport") {
    group = "verification"
    description = "Lists the release runtime classpath with each module's declared licences."
    outputs.file(reportFile)
    // The classpath is the input and Gradle cannot see it here; a stale report would let a bad dependency through.
    outputs.upToDateWhen { false }
    notCompatibleWithConfigurationCache("resolves POM files through detached configurations at execution time")
    doLast {
        val classpath = configurations.getByName("releaseRuntimeClasspath")
        val root = classpath.incoming.resolutionResult.root
        val seen = linkedSetOf<ModuleComponentIdentifier>()
        val stack = ArrayDeque(listOf(root))
        val visited = mutableSetOf<Any>()
        while (stack.isNotEmpty()) {
            val component = stack.removeLast()
            if (!visited.add(component.id)) continue
            (component.id as? ModuleComponentIdentifier)?.let(seen::add)
            component.dependencies.forEach { dependency ->
                when (dependency) {
                    is ResolvedDependencyResult -> stack.add(dependency.selected)
                    // A dependency that cannot be resolved must never vanish from the report.
                    is UnresolvedDependencyResult -> throw GradleException("Unresolved release dependency: ${dependency.requested.displayName}")
                }
            }
        }
        // Jars and AARs that did not come from a Maven module (files(...), fileTree(...)) have no POM and no licence.
        val localFiles = classpath.incoming.artifactView {
            componentFilter { it !is ModuleComponentIdentifier && it !is ProjectComponentIdentifier }
        }.files.files.map { it.name }.distinct().sorted()
        val modules = seen.sortedBy { "${it.group}:${it.module}" }.map { id ->
            mapOf(
                "group" to id.group,
                "name" to id.module,
                "version" to id.version,
                "licenses" to licensesOf(id.group, id.module, id.version).map { (n, u) -> mapOf("name" to n, "url" to u) },
            )
        }
        val unmanaged = localFiles.map { file ->
            mapOf("group" to "(local file)", "name" to file, "version" to "-", "licenses" to emptyList<Map<String, String>>())
        }
        val out = reportFile.get().asFile
        out.parentFile.mkdirs()
        out.writeText(groovy.json.JsonOutput.prettyPrint(groovy.json.JsonOutput.toJson(mapOf("modules" to modules + unmanaged))))
    }
}

tasks.register<Exec>("checkDependencyPolicy") {
    group = "verification"
    description = "Fails on forbidden Google groups and on dependencies without an OSI licence (AD-17)."
    dependsOn(releaseClasspathReport)
    val script = rootProject.file(".github/scripts/check_dependency_policy.py")
    val allowlist = rootProject.file("config/license-allowlist.txt")
    outputs.upToDateWhen { false }
    commandLine("python3", script.absolutePath, "--report", reportFile.get().asFile.absolutePath, "--allowlist", allowlist.absolutePath)
}
