package com.droid.crypto

fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it) }

fun String.hexToBytes(): ByteArray {
    val clean = trim()
    require(clean.length % 2 == 0) { "Hex string must have even length" }
    return ByteArray(clean.length / 2) {
        clean.substring(it * 2, it * 2 + 2).toInt(16).toByte()
    }
}