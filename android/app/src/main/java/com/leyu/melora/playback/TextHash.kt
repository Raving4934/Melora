package com.leyu.melora.playback

import java.security.MessageDigest

internal fun String.sha256Hex(byteCount: Int = 32): String = MessageDigest.getInstance("SHA-256")
    .digest(toByteArray(Charsets.UTF_8)).toHexString(endIndex = byteCount)
