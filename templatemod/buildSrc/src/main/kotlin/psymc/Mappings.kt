package psymc

import org.objectweb.asm.ClassReader
import org.objectweb.asm.ClassWriter
import org.objectweb.asm.Type
import org.objectweb.asm.commons.ClassRemapper
import org.objectweb.asm.commons.SimpleRemapper
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream

// Names of one class in two namespaces. Members are keyed by the source name and the source descriptor.
class ClassNames(val source: String, val target: String) {
    val methods = linkedMapOf<String, String>()
    val fields = linkedMapOf<String, String>()
}

// A mapping from one namespace to another. Class names are internal names (a/b/C).
class Mappings(val classes: Map<String, ClassNames>) {
    fun mapType(name: String): String = classes[name]?.target ?: name

    fun mapDesc(desc: String): String = Type.getType(desc).let { mapDescType(it) }

    private fun mapDescType(type: Type): String = when (type.sort) {
        Type.ARRAY -> "[".repeat(type.dimensions) + mapDescType(type.elementType)
        Type.OBJECT -> "L" + mapType(type.internalName) + ";"
        Type.METHOD -> type.argumentTypes.joinToString("", "(", ")") { mapDescType(it) } + mapDescType(type.returnType)
        else -> type.descriptor
    }

    // The same mapping in the other direction.
    fun reverse(): Mappings {
        val out = linkedMapOf<String, ClassNames>()
        classes.values.forEach { names ->
            val back = ClassNames(names.target, names.source)
            names.methods.forEach { (key, target) ->
                val paren = key.indexOf('(')
                back.methods[target + mapDesc(key.substring(paren))] = key.substring(0, paren)
            }
            names.fields.forEach { (key, target) ->
                val colon = key.indexOf(':')
                back.fields[target + ":" + mapDesc(key.substring(colon + 1))] = key.substring(0, colon)
            }
            out[back.source] = back
        }
        return Mappings(out)
    }

    // This mapping, then the next one. The next one starts in the target namespace of this one.
    // The next one can name a member only on the class that declares it first (Tiny does so).
    // middleParents gives the super class and the interfaces in the middle namespace.
    fun then(next: Mappings, middleParents: (String) -> List<String>): Mappings {
        fun <T> inHierarchy(start: String, find: (ClassNames) -> T?): T? {
            val seen = hashSetOf<String>()
            val queue = ArrayDeque(listOf(start))
            while (queue.isNotEmpty()) {
                val name = queue.removeFirst()
                if (!seen.add(name)) {
                    continue
                }
                next.classes[name]?.let(find)?.let { return it }
                queue.addAll(middleParents(name))
            }
            return null
        }
        val out = linkedMapOf<String, ClassNames>()
        classes.values.forEach { names ->
            val joined = ClassNames(names.source, next.mapType(names.target))
            names.methods.forEach { (key, middle) ->
                val middleKey = middle + mapDesc(key.substring(key.indexOf('(')))
                joined.methods[key] = inHierarchy(names.target) { it.methods[middleKey] } ?: middle
            }
            names.fields.forEach { (key, middle) ->
                val middleKey = middle + ":" + mapDesc(key.substring(key.indexOf(':') + 1))
                joined.fields[key] = inHierarchy(names.target) { it.fields[middleKey] } ?: middle
            }
            out[names.source] = joined
        }
        return Mappings(out)
    }

    // ASM keys: owner.name+desc for a method, owner.name for a field. The keys use source names.
    fun asmMap(): Map<String, String> {
        val map = hashMapOf<String, String>()
        classes.values.forEach { names ->
            map[names.source] = names.target
            names.methods.forEach { (key, target) -> map[names.source + "." + key] = target }
            names.fields.forEach { (key, target) -> map[names.source + "." + key.substringBefore(':')] = target }
        }
        return map
    }

    companion object {
        // ProGuard mappings of Mojang: Mojang names to the obfuscated names.
        fun proguard(file: File): Mappings {
            val classes = linkedMapOf<String, ClassNames>()
            var current: ClassNames? = null
            val members = mutableListOf<Pair<ClassNames, String>>()
            file.forEachLine { raw ->
                if (raw.startsWith("#") || raw.isBlank()) {
                    return@forEachLine
                }
                if (!raw.startsWith(" ")) {
                    val (from, to) = raw.removeSuffix(":").split(" -> ")
                    current = ClassNames(from.replace('.', '/'), to.replace('.', '/')).also { classes[it.source] = it }
                } else {
                    members.add(current!! to raw.trim())
                }
            }
            // The member lines use Java type names. Make the descriptors after all class names are known.
            val method = Regex("""^(?:\d+:\d+:)?(\S+) ([^ (]+)\((.*)\)(?::\d+)*(?::\d+:\d+)? -> (\S+)$""")
            val field = Regex("""^(\S+) (\S+) -> (\S+)$""")
            members.forEach { (owner, line) ->
                val m = method.matchEntire(line)
                if (m != null) {
                    val (ret, name, args, target) = m.destructured
                    // An inlined frame names a method of another class. It is not a member of this class.
                    if (!name.contains('.')) {
                        val desc = args.split(",").filter { it.isNotBlank() }.joinToString("", "(", ")") { javaDesc(it) } + javaDesc(ret)
                        owner.methods[name + desc] = target
                    }
                    return@forEach
                }
                val f = field.matchEntire(line) ?: return@forEach
                owner.fields[f.groupValues[2] + ":" + javaDesc(f.groupValues[1])] = f.groupValues[3]
            }
            return Mappings(classes)
        }

        // Tiny v2 mappings (net.fabricmc:intermediary): official names to intermediary names.
        fun tinyV2(text: String): Mappings {
            val classes = linkedMapOf<String, ClassNames>()
            var current: ClassNames? = null
            text.lineSequence().drop(1).forEach { line ->
                val parts = line.split('\t')
                when {
                    parts[0] == "c" -> current = ClassNames(parts[1], parts[2]).also { classes[it.source] = it }
                    parts.size >= 5 && parts[0] == "" && parts[1] == "m" -> current!!.methods[parts[3] + parts[2]] = parts[4]
                    parts.size >= 5 && parts[0] == "" && parts[1] == "f" -> current!!.fields[parts[3] + ":" + parts[2]] = parts[4]
                }
            }
            return Mappings(classes)
        }

        private fun javaDesc(type: String): String {
            var name = type
            var dims = 0
            while (name.endsWith("[]")) {
                name = name.removeSuffix("[]")
                dims++
            }
            val base = when (name) {
                "void" -> "V"
                "boolean" -> "Z"
                "byte" -> "B"
                "char" -> "C"
                "short" -> "S"
                "int" -> "I"
                "long" -> "J"
                "float" -> "F"
                "double" -> "D"
                else -> "L" + name.replace('.', '/') + ";"
            }
            return "[".repeat(dims) + base
        }
    }
}

// Write a copy of the game jar with Mojang names. Only the class files are copied.
// A call or a field use can name a subclass as the owner of an inherited member. The mappings
// name the member only on the class that declares it. So the remapper looks through the super
// classes and the interfaces (in obfuscated names) for the member.
fun remapGameJar(obfuscated: File, mojangToObf: Mappings, out: File) {
    val hierarchy = GameHierarchy(obfuscated)
    val map = mojangToObf.reverse().asmMap()
    fun <T> inHierarchy(owner: String, find: (String) -> T?): T? {
        val seen = hashSetOf<String>()
        val queue = ArrayDeque(listOf(owner))
        while (queue.isNotEmpty()) {
            val name = queue.removeFirst()
            if (!seen.add(name)) {
                continue
            }
            find(name)?.let { return it }
            queue.addAll(hierarchy.parents(name))
        }
        return null
    }
    val remapper = object : SimpleRemapper(map) {
        override fun mapMethodName(owner: String, name: String, descriptor: String): String = inHierarchy(owner) { map["$it.$name$descriptor"] } ?: name

        override fun mapFieldName(owner: String, name: String, descriptor: String): String = inHierarchy(owner) { map["$it.$name"] } ?: name
    }
    val temp = File(out.parentFile, out.name + ".part")
    out.parentFile.mkdirs()
    ZipFile(obfuscated).use { zip ->
        ZipOutputStream(temp.outputStream()).use { output ->
            zip.entries().asSequence().filter { it.name.endsWith(".class") }.forEach { entry ->
                val reader = ClassReader(zip.getInputStream(entry).readBytes())
                val writer = ClassWriter(0)
                reader.accept(ClassRemapper(writer, remapper), 0)
                val name = remapper.map(reader.className) ?: reader.className
                output.putNextEntry(ZipEntry("$name.class"))
                output.write(writer.toByteArray())
                output.closeEntry()
            }
        }
    }
    temp.renameTo(out)
}

// The super class and the interfaces of each class in a jar.
// rename gives the names to use: for example Mojang names for an obfuscated jar.
class GameHierarchy(jar: File, rename: (String) -> String = { it }) {
    private val parents = hashMapOf<String, List<String>>()

    init {
        ZipFile(jar).use { zip ->
            zip.entries().asSequence().filter { it.name.endsWith(".class") }.forEach { entry ->
                val reader = ClassReader(zip.getInputStream(entry).readBytes())
                parents[rename(reader.className)] = (listOfNotNull(reader.superName) + reader.interfaces).map(rename)
            }
        }
    }

    fun parents(name: String): List<String> = parents[name].orEmpty()
}
