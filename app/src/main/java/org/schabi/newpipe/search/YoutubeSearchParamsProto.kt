/*
 * SPDX-FileCopyrightText: 2026 NewPipe contributors <https://newpipe.net>
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package org.schabi.newpipe.search

import android.util.Base64
import java.io.ByteArrayOutputStream
import org.schabi.newpipe.extractor.services.youtube.linkHandler.YoutubeSearchQueryHandlerFactory

/**
 * Builds InnerTube `params` (URL-safe base64 protobuf) for YouTube search, aligned with
 * Invidious [Filters#to_yt_params](https://github.com/iv-org/invidious/blob/master/src/invidious/search/filters.cr).
 */
object YoutubeSearchParamsProto {

    private fun writeVarint(value: Long, out: ByteArrayOutputStream) {
        var v = value
        while (v >= 128) {
            out.write(((v.toInt() and 0x7F) or 0x80))
            v = v ushr 7
        }
        out.write(v.toInt())
    }

    private fun writeFieldVarint(fieldNumber: Int, value: Long, out: ByteArrayOutputStream) {
        writeVarint(((fieldNumber shl 3) or 0).toLong(), out)
        writeVarint(value, out)
    }

    private fun writeFieldLengthDelimited(fieldNumber: Int, payload: ByteArray, out: ByteArrayOutputStream) {
        writeVarint(((fieldNumber shl 3) or 2).toLong(), out)
        writeVarint(payload.size.toLong(), out)
        out.write(payload, 0, payload.size)
    }

    private fun invidiousTypeFromContentFilter(contentType: String?): Int = when (contentType) {
        null, "", YoutubeSearchQueryHandlerFactory.ALL -> 0
        YoutubeSearchQueryHandlerFactory.VIDEOS -> 1
        YoutubeSearchQueryHandlerFactory.CHANNELS -> 2
        YoutubeSearchQueryHandlerFactory.PLAYLISTS -> 3
        else -> 0
    }

    /**
     * @param uploadDateOrdinal Invidious Date: 0 none, 1 hour, 2 today, 3 week, 4 month, 5 year
     * @param durationOrdinal Invidious Duration: 0 none, 1 short, 2 long, 3 medium
     * @return `params` string for the search JSON body (with `=` as `%3D` like NewPipe constants).
     */
    @JvmStatic
    fun buildParams(
        contentType: String?,
        uploadDateOrdinal: Int,
        durationOrdinal: Int
    ): String {
        val embedded = ByteArrayOutputStream()
        if (uploadDateOrdinal > 0) {
            writeFieldVarint(1, uploadDateOrdinal.toLong(), embedded)
        }
        val type = invidiousTypeFromContentFilter(contentType)
        if (type > 0) {
            writeFieldVarint(2, type.toLong(), embedded)
        }
        if (durationOrdinal > 0) {
            writeFieldVarint(3, durationOrdinal.toLong(), embedded)
        }
        val embeddedBytes = embedded.toByteArray()

        val outer = ByteArrayOutputStream()
        if (embeddedBytes.isNotEmpty()) {
            writeFieldLengthDelimited(2, embeddedBytes, outer)
        }
        writeFieldVarint(30, 1L, outer)

        val raw = Base64.encodeToString(outer.toByteArray(), Base64.NO_WRAP)
        return raw.replace("=", "%3D")
    }
}
