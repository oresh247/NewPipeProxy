/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package org.schabi.newpipe.database.feed.model

import androidx.room.ColumnInfo
import org.schabi.newpipe.database.stream.model.StreamEntity

/**
 * Row from [org.schabi.newpipe.database.feed.dao.FeedDAO] listing streams still "new" in the
 * local feed for a subscription.
 */
data class FeedStreamNewInFeedRow(
    @ColumnInfo(name = StreamEntity.STREAM_SERVICE_ID)
    val serviceId: Int,
    @ColumnInfo(name = StreamEntity.STREAM_URL)
    val url: String
)
