package psymc

import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream

// A small NBT reader and writer: a named root compound. level.dat is gzip; hotbar.nbt is not.
// A tag is a NbtTag: its type id and its value. A compound is a LinkedHashMap.

class NbtTag(val type: Int, var value: Any)

fun readNbtFile(file: File): Pair<String, NbtTag> = DataInputStream(GZIPInputStream(file.inputStream()).buffered()).use { input ->
    val type = input.readByte().toInt()
    val name = input.readUTF()
    name to NbtTag(type, readNbtPayload(input, type))
}

fun writeNbtFile(file: File, name: String, root: NbtTag, gzip: Boolean = true) {
    val stream = if (gzip) GZIPOutputStream(file.outputStream()) else file.outputStream()
    DataOutputStream(stream.buffered()).use { output ->
        output.writeByte(root.type)
        output.writeUTF(name)
        writeNbtPayload(output, root)
    }
}

@Suppress("UNCHECKED_CAST")
fun NbtTag.compound(): LinkedHashMap<String, NbtTag> = value as LinkedHashMap<String, NbtTag>

private fun readNbtPayload(input: DataInputStream, type: Int): Any = when (type) {
    1 -> input.readByte()
    2 -> input.readShort()
    3 -> input.readInt()
    4 -> input.readLong()
    5 -> input.readFloat()
    6 -> input.readDouble()
    7 -> ByteArray(input.readInt()).also { input.readFully(it) }
    8 -> input.readUTF()
    9 -> {
        val itemType = input.readByte().toInt()
        val size = input.readInt()
        itemType to MutableList(size) { NbtTag(itemType, readNbtPayload(input, itemType)) }
    }
    10 -> {
        val map = linkedMapOf<String, NbtTag>()
        while (true) {
            val childType = input.readByte().toInt()
            if (childType == 0) {
                break
            }
            val name = input.readUTF()
            map[name] = NbtTag(childType, readNbtPayload(input, childType))
        }
        map
    }
    11 -> IntArray(input.readInt()) { input.readInt() }
    12 -> LongArray(input.readInt()) { input.readLong() }
    else -> throw IllegalArgumentException("Unknown NBT tag type $type")
}

private fun writeNbtPayload(output: DataOutputStream, tag: NbtTag) {
    when (tag.type) {
        1 -> output.writeByte((tag.value as Byte).toInt())
        2 -> output.writeShort((tag.value as Short).toInt())
        3 -> output.writeInt(tag.value as Int)
        4 -> output.writeLong(tag.value as Long)
        5 -> output.writeFloat(tag.value as Float)
        6 -> output.writeDouble(tag.value as Double)
        7 -> (tag.value as ByteArray).let {
            output.writeInt(it.size)
            output.write(it)
        }
        8 -> output.writeUTF(tag.value as String)
        9 -> {
            @Suppress("UNCHECKED_CAST")
            val list = tag.value as Pair<Int, List<NbtTag>>
            val (itemType, items) = list
            output.writeByte(itemType)
            output.writeInt(items.size)
            items.forEach { writeNbtPayload(output, it) }
        }
        10 -> {
            tag.compound().forEach { (name, child) ->
                output.writeByte(child.type)
                output.writeUTF(name)
                writeNbtPayload(output, child)
            }
            output.writeByte(0)
        }
        11 -> (tag.value as IntArray).let { array ->
            output.writeInt(array.size)
            array.forEach { output.writeInt(it) }
        }
        12 -> (tag.value as LongArray).let { array ->
            output.writeInt(array.size)
            array.forEach { output.writeLong(it) }
        }
        else -> throw IllegalArgumentException("Unknown NBT tag type ${tag.type}")
    }
}
