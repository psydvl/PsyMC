package psymc

import groovy.json.JsonSlurper
import org.objectweb.asm.ClassReader
import org.objectweb.asm.Handle
import org.objectweb.asm.commons.ClassRemapper
import org.objectweb.asm.commons.Remapper
import org.objectweb.asm.tree.AnnotationNode
import org.objectweb.asm.tree.ClassNode
import org.objectweb.asm.tree.FieldInsnNode
import org.objectweb.asm.tree.InvokeDynamicInsnNode
import org.objectweb.asm.tree.MethodInsnNode
import java.io.File
import java.util.zip.ZipFile

// NilLoader only. Delete this file together with the nil leaves.
// nilCheck: compare a nil jar with the game jar of each release. No game starts.

private val GAME_PACKAGES = listOf("net/minecraft/", "com/mojang/blaze3d/", "com/mojang/math/", "com/mojang/realmsclient/")

private fun gameClass(name: String) = GAME_PACKAGES.any { name.startsWith(it) }

// One release to check: its id, its client jar with Mojang names, and its mappings (null: named).
class NilRelease(val id: String, val mojangJar: File, val mojangToObf: Mappings?)

// The classes of a jar, read when asked (without code).
private class JarClasses(jar: File) : AutoCloseable {
    private val zip = ZipFile(jar)
    private val cache = hashMapOf<String, ClassNode?>()

    fun get(name: String): ClassNode? = cache.getOrPut(name) {
        zip.getEntry("$name.class")?.let { entry ->
            ClassNode().also { ClassReader(zip.getInputStream(entry).readBytes()).accept(it, ClassReader.SKIP_CODE) }
        }
    }

    override fun close() = zip.close()
}

// Write the report. Return the number of problems.
fun nilCheck(modJar: File, pin: String, releases: List<NilRelease>, report: File): Int {
    val modClasses = mutableListOf<ClassNode>()
    var mappingsJson: Map<*, *> = emptyMap<String, Any>()
    ZipFile(modJar).use { zip ->
        zip.entries().asSequence().forEach { entry ->
            when {
                entry.name.endsWith(".class") ->
                    modClasses.add(ClassNode().also { ClassReader(zip.getInputStream(entry).readBytes()).accept(it, 0) })
                entry.name == "META-INF/nil/mappings.json" ->
                    mappingsJson = JsonSlurper().parse(zip.getInputStream(entry)) as Map<*, *>
            }
        }
    }
    // Patch targets: (class, method name + descriptor, optional).
    val patches = mutableListOf<Triple<String, String, Boolean>>()
    val patchClasses = linkedSetOf<String>()
    val methodRefs = linkedSetOf<Triple<String, String, String>>()
    val fieldRefs = linkedSetOf<Triple<String, String, String>>()
    val classRefs = linkedSetOf<String>()
    val recorder = object : Remapper() {
        override fun map(internalName: String): String {
            if (gameClass(internalName)) classRefs.add(internalName)
            return internalName
        }
    }
    modClasses.forEach { node ->
        node.accept(ClassRemapper(ClassNode(), recorder))
        val target = annotation(node.visibleAnnotations, node.invisibleAnnotations, "Lnilloader/api/lib/mini/annotation/Patch\$Class;")
            ?.let { value(it, "value") }?.replace('.', '/')
        target?.let { patchClasses.add(it) }
        node.methods.forEach { method ->
            if (target != null) {
                annotation(method.visibleAnnotations, method.invisibleAnnotations, "Lnilloader/api/lib/mini/annotation/Patch\$Method;")
                    ?.let { value(it, "value") }
                    ?.let { sig ->
                        val optional = annotation(
                            method.visibleAnnotations,
                            method.invisibleAnnotations,
                            "Lnilloader/api/lib/mini/annotation/Patch\$Method\$Optional;",
                        ) != null
                        patches.add(Triple(target, sig, optional))
                        recorder.mapMethodDesc(sig.substring(sig.indexOf('(')))
                    }
            }
            method.instructions.forEach { insn ->
                when (insn) {
                    is MethodInsnNode -> if (gameClass(insn.owner)) methodRefs.add(Triple(insn.owner, insn.name, insn.desc))
                    is FieldInsnNode -> if (gameClass(insn.owner)) fieldRefs.add(Triple(insn.owner, insn.name, insn.desc))
                    is InvokeDynamicInsnNode -> insn.bsmArgs.filterIsInstance<Handle>().filter { gameClass(it.owner) }.forEach {
                        if (it.tag <= 4) fieldRefs.add(Triple(it.owner, it.name, it.desc)) else methodRefs.add(Triple(it.owner, it.name, it.desc))
                    }
                }
            }
        }
    }
    val text = StringBuilder("# nilCheck\n\nPin `$pin`. Jar `${modJar.name}`.\n\n")
    text.append("Patches: ${patches.size}. Game references: ${classRefs.size} classes, ${methodRefs.size} methods, ${fieldRefs.size} fields.\n")
    var total = 0
    releases.forEach { release ->
        val problems = mutableListOf<String>()
        JarClasses(release.mojangJar).use { game ->
            fun <T> inHierarchy(owner: String, find: (ClassNode) -> T?): T? {
                val seen = hashSetOf<String>()
                val queue = ArrayDeque(listOf(owner))
                while (queue.isNotEmpty()) {
                    val node = game.get(queue.removeFirst()) ?: continue
                    if (!seen.add(node.name)) continue
                    find(node)?.let { return it }
                    queue.addAll(listOfNotNull(node.superName) + node.interfaces)
                }
                return null
            }
            (classRefs + patchClasses).filter { game.get(it) == null }.forEach { problems.add("class `$it` is missing") }
            patches.forEach { (owner, sig, optional) ->
                val node = game.get(owner) ?: return@forEach
                if (node.methods.none { it.name + it.desc == sig }) {
                    problems.add("patch target `$owner.$sig` is missing" + if (optional) " (optional patch)" else "")
                }
            }
            // The class that declares each used member, or null when no class has it.
            val methods = methodRefs.associateWith { (owner, name, desc) ->
                inHierarchy(owner) { node -> node.name.takeIf { node.methods.any { it.name == name && it.desc == desc } } }
            }
            val fields = fieldRefs.associateWith { (owner, name, desc) ->
                inHierarchy(owner) { node -> node.name.takeIf { node.fields.any { it.name == name && it.desc == desc } } }
            }
            methods.filterValues { it == null }.keys.filter { game.get(it.first) != null }.forEach { (owner, name, desc) ->
                problems.add("method `$owner.$name$desc` is missing")
            }
            fields.filterValues { it == null }.keys.filter { game.get(it.first) != null }.forEach { (owner, name, desc) ->
                problems.add("field `$owner.$name:$desc` is missing")
            }
            val mappings = release.mojangToObf
            if (mappings != null) {
                problems.addAll(checkNilMappings(mappingsJson[release.id] as Map<*, *>?, release.id, mappings, classRefs + patchClasses, patches, methods, fields))
            }
        }
        total += problems.size
        text.append("\n## ${release.id}\n\n")
        text.append(if (problems.isEmpty()) "No problems.\n" else problems.joinToString("\n", postfix = "\n") { "- $it." })
    }
    text.append("\nProblems: $total.\n")
    report.parentFile.mkdirs()
    report.writeText(text.toString())
    return total
}

// The mapping set of an obfuscated release must rename every used name that the release renames.
private fun checkNilMappings(
    set: Map<*, *>?,
    release: String,
    mappings: Mappings,
    classes: Set<String>,
    patches: List<Triple<String, String, Boolean>>,
    methods: Map<Triple<String, String, String>, String?>,
    fields: Map<Triple<String, String, String>, String?>,
): List<String> {
    if (set == null) {
        return listOf("mappings.json has no set `$release`: NilLoader keeps the Mojang names on this release")
    }
    // Full class name to its entry. Inner classes sit in inner-classes of the outer entry.
    val entries = hashMapOf<String, Map<*, *>>()
    fun walk(list: List<*>?, prefix: String) {
        list.orEmpty().map { it as Map<*, *> }.forEach { entry ->
            val name = prefix + entry["from"]
            entries[name] = entry
            walk(entry["inner-classes"] as List<*>?, "$name$")
        }
    }
    walk(set["classes"] as List<*>?, "")
    val problems = mutableListOf<String>()
    classes.filter { mappings.mapType(it) != it && it !in entries }.forEach {
        problems.add("set `$release` has no class `$it`")
    }
    fun memberMapped(owner: String, key: String, section: String, renamed: Boolean) {
        if (!renamed) return
        val members = entries[owner]?.get(section) as Map<*, *>?
        if (members?.containsKey(key) != true) problems.add("set `$release` has no $section entry `$owner.$key`")
    }
    patches.forEach { (owner, sig, _) ->
        val renamed = mappings.classes[owner]?.methods?.get(sig)?.let { it != sig.substringBefore('(') } ?: false
        memberMapped(owner, sig, "methods", renamed)
    }
    methods.forEach { (ref, declaring) ->
        val (owner, name, desc) = ref
        val renamed = declaring?.let { mappings.classes[it]?.methods?.get(name + desc) }?.let { it != name } ?: false
        memberMapped(owner, name + desc, "methods", renamed)
    }
    fields.forEach { (ref, declaring) ->
        val (owner, name, desc) = ref
        val renamed = declaring?.let { mappings.classes[it]?.fields?.get("$name:$desc") }?.let { it != name } ?: false
        memberMapped(owner, "$name:$desc", "fields", renamed)
    }
    return problems
}

private fun annotation(visible: List<AnnotationNode>?, invisible: List<AnnotationNode>?, desc: String): AnnotationNode? = (visible.orEmpty() + invisible.orEmpty()).firstOrNull { it.desc == desc }

private fun value(node: AnnotationNode, key: String): String? = node.values?.chunked(2)?.firstOrNull { it[0] == key }?.get(1)?.toString()
