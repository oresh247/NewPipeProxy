/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package org.schabi.newpipe.util

/**
 * Stable key for matching [org.schabi.newpipe.extractor.stream.StreamInfoItem] to local DB rows.
 */
object FeedStreamKeyUtil {

    @JvmStatic
    fun key(serviceId: Int, url: String?): String {
        return "$serviceId|${url ?: ""}"
    }
}
