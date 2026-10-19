package kiwi.lorenz.pdfreader.info

import java.security.MessageDigest
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * Decrypts strings and streams of a PDF encrypted with the standard security handler (revisions
 * 2 to 6: RC4, AES-128 and AES-256). A file with only a permissions password, which opens without
 * asking, decrypts with the empty password.
 */
internal class PdfSecurity private constructor(
    private val fileKey: ByteArray,
    private val strings: Method,
    private val streams: Method,
) {
    enum class Method { NONE, RC4, AES128, AES256 }

    fun decryptString(bytes: ByteArray, number: Int, generation: Int) = decrypt(strings, bytes, number, generation)

    fun decryptStream(bytes: ByteArray, number: Int, generation: Int) = decrypt(streams, bytes, number, generation)

    /** [value] as read from indirect object [number], with its strings decrypted. */
    fun decryptStrings(value: Any?, number: Int, generation: Int): Any? = when (value) {
        is PdfString -> PdfString(decryptString(value.bytes, number, generation))
        is List<*> -> value.map { decryptStrings(it, number, generation) }
        is Map<*, *> -> value.mapValues { decryptStrings(it.value, number, generation) }
        is PdfStream -> PdfStream(
            @Suppress("UNCHECKED_CAST")
            (decryptStrings(value.dict, number, generation) as Map<String, Any?>),
            value.dataOffset, value.number, value.generation,
        )
        else -> value
    }

    private fun decrypt(method: Method, data: ByteArray, number: Int, generation: Int): ByteArray = when (method) {
        Method.NONE -> data
        Method.RC4 -> rc4(objectKey(number, generation, isAes = false), data)
        Method.AES128 -> aesDecrypt(objectKey(number, generation, isAes = true), data)
        Method.AES256 -> aesDecrypt(fileKey, data)
    }

    /** Revisions 2 to 4 have a key of their own for each object. */
    private fun objectKey(number: Int, generation: Int, isAes: Boolean): ByteArray {
        val md5 = MessageDigest.getInstance("MD5")
        md5.update(fileKey)
        md5.update(byteArrayOf(number.toByte(), (number shr 8).toByte(), (number shr 16).toByte()))
        md5.update(byteArrayOf(generation.toByte(), (generation shr 8).toByte()))
        if (isAes) md5.update("sAlT".toByteArray())
        return md5.digest().copyOf(minOf(fileKey.size + 5, 16))
    }

    companion object {
        /**
         * Null if the file uses another handler or neither [password] (the one to open it, or the
         * permissions one) nor the empty password fit. [resolve] follows references.
         */
        fun open(encrypt: Map<String, Any?>, id: ByteArray, password: String?, resolve: (Any?) -> Any?): PdfSecurity? {
            if ((resolve(encrypt["Filter"]) as? PdfName)?.value != "Standard") return null
            fun number(key: String) = (resolve(encrypt[key]) as? Number)?.toInt()
            fun bytes(key: String) = (resolve(encrypt[key]) as? PdfString)?.bytes
            val version = number("V") ?: 0
            val revision = number("R") ?: return null
            val owner = bytes("O") ?: return null
            val user = bytes("U") ?: return null

            val (strings, streams) = when (version) {
                1, 2 -> Method.RC4 to Method.RC4
                4 -> {
                    val filters = resolve(encrypt["CF"]) as? Map<*, *>
                    fun method(key: String): Method {
                        val name = (resolve(encrypt[key]) as? PdfName)?.value ?: return Method.NONE
                        val filter = resolve(filters?.get(name)) as? Map<*, *> ?: return Method.NONE
                        return when ((resolve(filter["CFM"]) as? PdfName)?.value) {
                            "V2" -> Method.RC4
                            "AESV2" -> Method.AES128
                            "AESV3" -> Method.AES256
                            else -> Method.NONE
                        }
                    }
                    method("StrF") to method("StmF")
                }
                5 -> Method.AES256 to Method.AES256
                else -> return null
            }

            val candidates = listOfNotNull(password, "").distinct()
            val key = if (revision >= 5) {
                val ownerKey = bytes("OE") ?: return null
                val userKey = bytes("UE") ?: return null
                candidates.firstNotNullOfOrNull { modernKey(it, revision, owner, user, ownerKey, userKey) }
            } else {
                val length = ((number("Length") ?: 40) / 8).coerceIn(5, 16).let { if (revision == 2) 5 else it }
                val permissions = number("P") ?: return null
                val encryptMetadata = resolve(encrypt["EncryptMetadata"]) as? Boolean ?: true
                val legacy = LegacyHandler(revision, length, owner, user, permissions, id, encryptMetadata)
                candidates.firstNotNullOfOrNull { legacy.key(it) }
            } ?: return null
            return PdfSecurity(key, strings, streams)
        }

        // Revisions 5 and 6 (AES-256)

        private fun modernKey(
            password: String, revision: Int, owner: ByteArray, user: ByteArray, ownerKey: ByteArray, userKey: ByteArray,
        ): ByteArray? {
            if (owner.size < 48 || user.size < 48) return null
            val pw = password.toByteArray(Charsets.UTF_8).let { it.copyOf(minOf(it.size, 127)) }
            val user48 = user.copyOf(48)
            fun hash(salt: ByteArray, extra: ByteArray) = modernHash(revision, pw, salt, extra)
            // The password to open it.
            if (hash(user.copyOfRange(32, 40), ByteArray(0)).contentEquals(user.copyOf(32))) {
                return aesNoPadding(hash(user.copyOfRange(40, 48), ByteArray(0)), userKey)
            }
            // The permissions password.
            if (hash(owner.copyOfRange(32, 40), user48).contentEquals(owner.copyOf(32))) {
                return aesNoPadding(hash(owner.copyOfRange(40, 48), user48), ownerKey)
            }
            return null
        }

        /** Revision 5 hashes once; revision 6 runs the rounds of ISO 32000-2's algorithm 2.B. */
        private fun modernHash(revision: Int, password: ByteArray, salt: ByteArray, extra: ByteArray): ByteArray {
            var k = MessageDigest.getInstance("SHA-256").digest(password + salt + extra)
            if (revision == 5) return k
            var round = 0
            while (true) {
                val block = password + k + extra
                val repeated = ByteArray(block.size * 64).also { r -> repeat(64) { block.copyInto(r, it * block.size) } }
                val cipher = Cipher.getInstance("AES/CBC/NoPadding")
                cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(k.copyOf(16), "AES"), IvParameterSpec(k.copyOfRange(16, 32)))
                val e = cipher.doFinal(repeated)
                val digest = when (e.copyOf(16).sumOf { it.toInt() and 0xFF } % 3) {
                    0 -> "SHA-256"
                    1 -> "SHA-384"
                    else -> "SHA-512"
                }
                k = MessageDigest.getInstance(digest).digest(e)
                round++
                if (round >= 64 && (e.last().toInt() and 0xFF) <= round - 32) break
            }
            return k.copyOf(32)
        }

        private fun aesNoPadding(key: ByteArray, data: ByteArray): ByteArray {
            val cipher = Cipher.getInstance("AES/CBC/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), IvParameterSpec(ByteArray(16)))
            return cipher.doFinal(data.copyOf(32))
        }
    }

    // Revisions 2 to 4 (RC4 and AES-128)

    private class LegacyHandler(
        val revision: Int,
        val length: Int,
        val owner: ByteArray,
        val user: ByteArray,
        val permissions: Int,
        val id: ByteArray,
        val encryptMetadata: Boolean,
    ) {
        /** The file key if [password] is the one to open it or the permissions one. */
        fun key(password: String): ByteArray? {
            val padded = pad(password.toByteArray(Charsets.ISO_8859_1))
            return keyIfUser(padded) ?: keyIfUser(userPasswordFromOwner(padded))
        }

        private fun keyIfUser(padded: ByteArray): ByteArray? {
            val key = fileKey(padded)
            val expected = if (revision == 2) {
                rc4(key, PADDING)
            } else {
                var x = MessageDigest.getInstance("MD5").digest(PADDING + id)
                for (i in 0..19) x = rc4(xorKey(key, i), x)
                x
            }
            val compared = if (revision == 2) 32 else 16
            return key.takeIf { user.size >= compared && expected.copyOf(compared).contentEquals(user.copyOf(compared)) }
        }

        private fun fileKey(padded: ByteArray): ByteArray {
            val md5 = MessageDigest.getInstance("MD5")
            md5.update(padded)
            md5.update(owner.copyOf(32))
            md5.update(byteArrayOf(permissions.toByte(), (permissions shr 8).toByte(), (permissions shr 16).toByte(), (permissions shr 24).toByte()))
            md5.update(id)
            if (revision >= 4 && !encryptMetadata) md5.update(byteArrayOf(-1, -1, -1, -1))
            var hash = md5.digest()
            if (revision >= 3) repeat(50) { hash = MessageDigest.getInstance("MD5").digest(hash.copyOf(length)) }
            return hash.copyOf(length)
        }

        /** The permissions password unlocks the password to open it, stored encrypted in /O. */
        private fun userPasswordFromOwner(padded: ByteArray): ByteArray {
            var hash = MessageDigest.getInstance("MD5").digest(padded)
            if (revision >= 3) repeat(50) { hash = MessageDigest.getInstance("MD5").digest(hash) }
            val key = hash.copyOf(length)
            var result = owner.copyOf(32)
            if (revision == 2) return rc4(key, result)
            for (i in 19 downTo 0) result = rc4(xorKey(key, i), result)
            return result
        }

        private fun xorKey(key: ByteArray, value: Int) = ByteArray(key.size) { (key[it].toInt() xor value).toByte() }

        private fun pad(password: ByteArray) = password.copyOf(minOf(password.size, 32)) + PADDING.copyOf(32 - minOf(password.size, 32))
    }
}

private val PADDING = intArrayOf(
    0x28, 0xBF, 0x4E, 0x5E, 0x4E, 0x75, 0x8A, 0x41, 0x64, 0x00, 0x4E, 0x56, 0xFF, 0xFA, 0x01, 0x08,
    0x2E, 0x2E, 0x00, 0xB6, 0xD0, 0x68, 0x3E, 0x80, 0x2F, 0x0C, 0xA9, 0xFE, 0x64, 0x53, 0x69, 0x7A,
).let { bytes -> ByteArray(bytes.size) { bytes[it].toByte() } }

private fun rc4(key: ByteArray, data: ByteArray): ByteArray {
    val s = IntArray(256) { it }
    var j = 0
    for (i in 0 until 256) {
        j = (j + s[i] + (key[i % key.size].toInt() and 0xFF)) and 0xFF
        s[i] = s[j].also { s[j] = s[i] }
    }
    var i = 0
    j = 0
    return ByteArray(data.size) { n ->
        i = (i + 1) and 0xFF
        j = (j + s[i]) and 0xFF
        s[i] = s[j].also { s[j] = s[i] }
        (data[n].toInt() xor s[(s[i] + s[j]) and 0xFF]).toByte()
    }
}

/** The first 16 bytes are the IV. Damaged padding is tolerated, as the text is still readable. */
private fun aesDecrypt(key: ByteArray, data: ByteArray): ByteArray {
    if (data.size < 32 || data.size % 16 != 0) return ByteArray(0)
    val cipher = Cipher.getInstance("AES/CBC/NoPadding")
    cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), IvParameterSpec(data.copyOf(16)))
    val plain = cipher.doFinal(data, 16, data.size - 16)
    val padding = plain.last().toInt() and 0xFF
    return if (padding in 1..16) plain.copyOf(plain.size - padding) else plain
}
