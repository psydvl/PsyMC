package psymc

import groovy.json.JsonOutput
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

// NilLoader only. Delete this file together with the nil leaves.

// One mapping set of a nilmod: Mojang names to the names of one game. parents gives the super class
// and the interfaces of a game class, in Mojang names, for that game.
class NilTarget(val mappings: Mappings, val parents: (String) -> List<String>)

// Write META-INF/nil/mappings.json for the mod classes in classesDir.
// targets: the mapping sets by NilLoader id.
// Each set has the game classes and the members that the mod code uses. A member that the mod
// reaches through a subclass gets an entry on that subclass too: NilLoader cannot read the game
// class hierarchy in Mojang names at run time.
fun writeNilMappings(classesDir: File, targets: Map<String, NilTarget>, out: File) {
    val modClasses = classesDir.walkTopDown().filter { it.name.endsWith(".class") }.map { file ->
        ClassNode().also { ClassReader(file.readBytes()).accept(it, ClassReader.SKIP_FRAMES) }
    }.toList()
    val modByName = modClasses.associateBy { it.name }
    val methodRefs = linkedSetOf<Triple<String, String, String>>()
    val fieldRefs = linkedSetOf<Triple<String, String, String>>()
    // Every class name in the mod bytecode: owners, descriptors, signatures, casts, constants.
    val classRefs = linkedSetOf<String>()
    val recorder = object : Remapper() {
        override fun map(internalName: String): String {
            classRefs.add(internalName)
            return internalName
        }
    }
    modClasses.forEach { node ->
        node.accept(ClassRemapper(ClassNode(), recorder))
        // A Mini patch names its target class and methods in annotations.
        val patchClass = annotationValue(node.visibleAnnotations, node.invisibleAnnotations, "Lnilloader/api/lib/mini/annotation/Patch\$Class;")
            ?.replace('.', '/')
        patchClass?.let { classRefs.add(it) }
        node.methods.forEach { method ->
            if (patchClass != null) {
                annotationValue(method.visibleAnnotations, method.invisibleAnnotations, "Lnilloader/api/lib/mini/annotation/Patch\$Method;")?.let { sig ->
                    val desc = sig.substring(sig.indexOf('('))
                    methodRefs.add(Triple(patchClass, sig.substringBefore('('), desc))
                    recorder.mapMethodDesc(desc)
                }
            }
            // A method that overrides a game method keeps the game name.
            if (node.superName != null) {
                methodRefs.add(Triple(node.name, method.name, method.desc))
            }
            method.instructions.forEach { insn ->
                when (insn) {
                    is MethodInsnNode -> methodRefs.add(Triple(insn.owner, insn.name, insn.desc))
                    is FieldInsnNode -> fieldRefs.add(Triple(insn.owner, insn.name, insn.desc))
                    is InvokeDynamicInsnNode -> insn.bsmArgs.filterIsInstance<Handle>().forEach { handle ->
                        if (handle.tag <= 4) {
                            fieldRefs.add(Triple(handle.owner, handle.name, handle.desc))
                        } else {
                            methodRefs.add(Triple(handle.owner, handle.name, handle.desc))
                        }
                    }
                }
            }
        }
    }
    val json = linkedMapOf<String, Any>()
    targets.forEach { (id, target) ->
        val mappings = target.mappings
        fun parents(name: String): List<String> {
            val mod = modByName[name]
            return if (mod != null) listOfNotNull(mod.superName) + mod.interfaces else target.parents(name)
        }
        // Owner name to the member entries of that owner.
        val methods = hashMapOf<String, MutableMap<String, String>>()
        val fields = hashMapOf<String, MutableMap<String, String>>()
        fun resolve(owner: String, find: (ClassNames) -> String?): String? {
            val seen = hashSetOf<String>()
            val queue = ArrayDeque(listOf(owner))
            while (queue.isNotEmpty()) {
                val name = queue.removeFirst()
                if (!seen.add(name)) {
                    continue
                }
                mappings.classes[name]?.let(find)?.let { return it }
                queue.addAll(parents(name))
            }
            return null
        }
        methodRefs.forEach { (owner, name, desc) ->
            resolve(owner) { it.methods[name + desc] }?.takeIf { it != name }?.let {
                methods.getOrPut(owner) { linkedMapOf() }[name + desc] = it + mappings.mapDesc(desc)
            }
        }
        fieldRefs.forEach { (owner, name, desc) ->
            resolve(owner) { it.fields["$name:$desc"] }?.takeIf { it != name }?.let {
                fields.getOrPut(owner) { linkedMapOf() }["$name:$desc"] = it + ":" + mappings.mapDesc(desc)
            }
        }
        // An inner class needs its outer classes in the set too.
        val used = classRefs.filter { it in mappings.classes }.flatMap { name ->
            val parts = name.split('$')
            parts.indices.map { parts.subList(0, it + 1).joinToString("$") }
        }.filter { it in mappings.classes }
        // A mod class gets entries only for its overrides. Its own name stays.
        val owners = (used + methods.keys + fields.keys).toSortedSet()
        json[id] = mapOf("classes" to nest(owners, mappings, methods, fields))
    }
    out.parentFile.mkdirs()
    out.writeText(JsonOutput.prettyPrint(JsonOutput.toJson(json)))
}

// NilLoader reads an inner class from the inner-classes list of its outer class, by simple names.
private fun nest(
    owners: Set<String>,
    mappings: Mappings,
    methods: Map<String, Map<String, String>>,
    fields: Map<String, Map<String, String>>,
): List<Map<String, Any>> {
    class Node(val from: String, val to: String) {
        val inner = linkedMapOf<String, Node>()
        var ownMethods: Map<String, String> = emptyMap()
        var ownFields: Map<String, String> = emptyMap()

        fun toJson(): Map<String, Any> {
            val map = linkedMapOf<String, Any>("from" to from, "to" to to)
            if (inner.isNotEmpty()) map["inner-classes"] = inner.values.map { it.toJson() }
            if (ownMethods.isNotEmpty()) map["methods"] = ownMethods
            if (ownFields.isNotEmpty()) map["fields"] = ownFields
            return map
        }
    }
    val top = linkedMapOf<String, Node>()
    owners.forEach { owner ->
        val target = mappings.mapType(owner)
        val fromParts = owner.split('$')
        val toParts = target.split('$')
        // Without the same depth on both sides, keep the full names on the top level.
        val (fromPath, toPath) = if (fromParts.size == toParts.size) fromParts to toParts else listOf(owner) to listOf(target)
        var node = top.getOrPut(fromPath[0]) { Node(fromPath[0], toPath[0]) }
        for (index in 1 until fromPath.size) {
            node = node.inner.getOrPut(fromPath[index]) { Node(fromPath[index], toPath[index]) }
        }
        node.ownMethods = methods[owner].orEmpty()
        node.ownFields = fields[owner].orEmpty()
    }
    return top.values.map { it.toJson() }
}

private fun annotationValue(visible: List<AnnotationNode>?, invisible: List<AnnotationNode>?, desc: String): String? {
    val node = (visible.orEmpty() + invisible.orEmpty()).firstOrNull { it.desc == desc } ?: return null
    val values = node.values ?: return null
    return values.chunked(2).firstOrNull { it[0] == "value" }?.get(1)?.toString()
}
