/*
 * SPDX-FileCopyrightText: 2026 NewPipe contributors <https://newpipe.net>
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package org.schabi.newpipe.search

/**
 * Extra YouTube-only search filters (upload date / duration) encoded into InnerTube `params`,
 * matching [Invidious search filters](https://github.com/iv-org/invidious/blob/master/src/invidious/search/filters.cr).
 */
data class YoutubeSearchExtras(
    @JvmField val uploadDateOrdinal: Int,
    @JvmField val durationOrdinal: Int
) {
    /** True when any non-default filter should be sent to YouTube. */
    fun affectsYoutubeParams(): Boolean = uploadDateOrdinal > 0 || durationOrdinal > 0

    companion object {
        @JvmField
        val NONE: YoutubeSearchExtras = YoutubeSearchExtras(0, 0)
    }
}
