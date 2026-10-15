package psymc

import org.objectweb.asm.ClassReader
import org.objectweb.asm.Handle
import org.objectweb.asm.Opcodes
import org.objectweb.asm.tree.ClassNode
import org.objectweb.asm.tree.FieldInsnNode
import org.objectweb.asm.tree.InvokeDynamicInsnNode
import org.objectweb.asm.tree.MethodInsnNode
import org.objectweb.asm.tree.MethodNode
import org.objectweb.asm.util.Textifier
import org.objectweb.asm.util.TraceMethodVisitor
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.util.zip.ZipFile

// Read one class of a game jar. owner: dotted or internal name. Null when the jar has no such class.
fun readGameClass(jar: File, owner: String): ClassNode? = ZipFile(jar).use { zip ->
    val entry = zip.getEntry(owner.replace('.', '/') + ".class") ?: return null
    ClassNode().also { ClassReader(zip.getInputStream(entry).readBytes()).accept(it, 0) }
}

// The methods of a class that match "name" or "name(desc)".
fun matchMethods(node: ClassNode, method: String): List<MethodNode> {
    val paren = method.indexOf('(')
    val name = if (paren < 0) method else method.substring(0, paren)
    val desc = if (paren < 0) null else method.substring(paren)
    return node.methods.filter { it.name == name && (desc == null || it.desc == desc) }
}

// Markdown for the matched methods: access, descriptor, the calls and field uses in the body, and
// the bytecode when bytecode is true.
fun describeMethods(node: ClassNode, methods: List<MethodNode>, bytecode: Boolean): String {
    val out = StringBuilder()
    methods.forEach { method ->
        out.append("- `").append(method.name).append(method.desc).append("` access 0x")
            .append(Integer.toHexString(method.access))
        if (method.access and Opcodes.ACC_STATIC != 0) out.append(" static")
        if (method.access and Opcodes.ACC_SYNTHETIC != 0) out.append(" synthetic")
        out.append('\n')
        // Each call with its ordinal among the same calls: the numbers for @At ordinal.
        val seen = linkedMapOf<String, Int>()
        method.instructions.forEach { insn ->
            val text = when (insn) {
                is MethodInsnNode -> "INVOKE L${insn.owner};${insn.name}${insn.desc}"
                is FieldInsnNode -> "FIELD L${insn.owner};${insn.name}:${insn.desc}"
                is InvokeDynamicInsnNode -> {
                    val handle = (insn.bsmArgs.getOrNull(1) as? Handle)
                    if (handle != null) "LAMBDA ${handle.owner}.${handle.name}${handle.desc}" else "INDY ${insn.name}${insn.desc}"
                }
                else -> null
            } ?: return@forEach
            seen[text] = (seen[text] ?: -1) + 1
            out.append("  - ").append(text).append(" ordinal ").append(seen[text]).append('\n')
        }
        if (bytecode) {
            val printer = Textifier()
            method.accept(TraceMethodVisitor(printer))
            val text = StringWriter().also { printer.print(PrintWriter(it)) }.toString()
            out.append("\n```\n").append(text).append("```\n")
        }
    }
    if (methods.isEmpty()) {
        out.append("- No method with this name. Methods of the class: ")
            .append(node.methods.map { it.name }.distinct().sorted().joinToString(", ")).append('\n')
    }
    return out.toString()
}

// The synthetic lambda methods that a method makes, nested lambdas included, in bytecode order.
fun lambdasOf(node: ClassNode, methods: List<MethodNode>): List<String> {
    val byKey = node.methods.associateBy { it.name + it.desc }
    val found = linkedSetOf<String>()
    val queue = ArrayDeque(methods)
    while (queue.isNotEmpty()) {
        val method = queue.removeFirst()
        method.instructions.forEach { insn ->
            if (insn is InvokeDynamicInsnNode && insn.bsm.owner == "java/lang/invoke/LambdaMetafactory") {
                val impl = (insn.bsmArgs.getOrNull(1) as? Handle) ?: return@forEach
                val target = byKey[impl.name + impl.desc]
                if (impl.owner == node.name &&
                    target != null &&
                    target.access and Opcodes.ACC_SYNTHETIC != 0 &&
                    found.add(impl.name)
                ) {
                    queue.add(target)
                }
            }
        }
    }
    return found.toList()
}
