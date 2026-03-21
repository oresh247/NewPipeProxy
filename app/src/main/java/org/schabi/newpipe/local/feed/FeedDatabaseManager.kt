package org.schabi.newpipe.local.feed

import android.content.Context
import android.util.Log
import io.reactivex.rxjava3.android.schedulers.AndroidSchedulers
import io.reactivex.rxjava3.core.Completable
import io.reactivex.rxjava3.core.Flowable
import io.reactivex.rxjava3.core.Maybe
import io.reactivex.rxjava3.core.Single
import io.reactivex.rxjava3.schedulers.Schedulers
import java.time.LocalDate
import java.time.OffsetDateTime
import java.time.ZoneOffset
import org.schabi.newpipe.MainActivity.DEBUG
import org.schabi.newpipe.NewPipeDatabase
import org.schabi.newpipe.database.feed.model.FeedEntity
import org.schabi.newpipe.database.feed.model.FeedGroupEntity
import org.schabi.newpipe.database.feed.model.FeedLastUpdatedEntity
import org.schabi.newpipe.database.stream.StreamWithState
import org.schabi.newpipe.database.stream.model.StreamEntity
import org.schabi.newpipe.database.stream.model.StreamStateEntity
import org.schabi.newpipe.database.subscription.NotificationMode
import org.schabi.newpipe.extractor.stream.StreamInfoItem
import org.schabi.newpipe.extractor.stream.StreamType
import org.schabi.newpipe.local.subscription.FeedGroupIcon
import org.schabi.newpipe.util.FeedStreamKeyUtil
import org.schabi.newpipe.util.StreamTypeUtil

class FeedDatabaseManager(context: Context) {
    private val database = NewPipeDatabase.getInstance(context)
    private val feedTable = database.feedDAO()
    private val feedGroupTable = database.feedGroupDAO()
    private val streamTable = database.streamDAO()

    companion object {
        /**
         * Only items that are newer than this will be saved.
         */
        val FEED_OLDEST_ALLOWED_DATE: OffsetDateTime = LocalDate.now().minusWeeks(13)
            .atStartOfDay().atOffset(ZoneOffset.UTC)

        /**
         * Progress (ms) stored for streams with unknown duration so subscription "new" dot logic
         * treats them as no longer "unstarted" (see FeedDAO threshold).
         */
        private const val UNKNOWN_DURATION_FINISHED_PROGRESS_MS: Long = 86_400_000L
    }

    fun groups() = feedGroupTable.getAll()

    /**
     * Emits subscription ids that still have at least one not-fully-played stream in the local feed.
     */
    fun subscriptionIdsWithNotFullyPlayedFeedStreams(): Flowable<List<Long>> {
        return feedTable.getSubscriptionIdsWithNotFullyPlayedFeedStreams()
    }

    /**
     * Keys ([FeedStreamKeyUtil.key]) for streams in the local feed for this subscription that still
     * count as not fully played (same rules as [subscriptionIdsWithNotFullyPlayedFeedStreams]).
     */
    fun newInFeedStreamKeysForSubscription(subscriptionId: Long): Flowable<Set<String>> {
        return feedTable.getNewInFeedStreamKeysForSubscription(subscriptionId)
            .map { rows ->
                rows.map { row -> FeedStreamKeyUtil.key(row.serviceId, row.url) }.toSet()
            }
            .subscribeOn(Schedulers.io())
    }

    fun database() = database

    fun getStreams(
        groupId: Long,
        includePlayedStreams: Boolean,
        includePartiallyPlayedStreams: Boolean,
        includeFutureStreams: Boolean
    ): Maybe<List<StreamWithState>> {
        return feedTable.getStreams(
            groupId,
            includePlayedStreams,
            includePartiallyPlayedStreams,
            if (includeFutureStreams) null else OffsetDateTime.now()
        )
    }

    fun outdatedSubscriptions(outdatedThreshold: OffsetDateTime) = feedTable.getAllOutdated(outdatedThreshold)

    fun outdatedSubscriptionsWithNotificationMode(
        outdatedThreshold: OffsetDateTime,
        @NotificationMode notificationMode: Int
    ) = feedTable.getOutdatedWithNotificationMode(outdatedThreshold, notificationMode)

    fun notLoadedCount(groupId: Long = FeedGroupEntity.GROUP_ALL_ID): Flowable<Long> {
        return when (groupId) {
            FeedGroupEntity.GROUP_ALL_ID -> feedTable.notLoadedCount()
            else -> feedTable.notLoadedCountForGroup(groupId)
        }
    }

    fun outdatedSubscriptionsForGroup(
        groupId: Long = FeedGroupEntity.GROUP_ALL_ID,
        outdatedThreshold: OffsetDateTime
    ) = feedTable.getAllOutdatedForGroup(groupId, outdatedThreshold)

    fun markAsOutdated(subscriptionId: Long) = feedTable
        .setLastUpdatedForSubscription(FeedLastUpdatedEntity(subscriptionId, null))

    fun doesStreamExist(stream: StreamInfoItem): Boolean {
        return streamTable.exists(stream.serviceId, stream.url)
    }

    fun upsertAll(
        subscriptionId: Long,
        items: List<StreamInfoItem>,
        oldestAllowedDate: OffsetDateTime = FEED_OLDEST_ALLOWED_DATE
    ) {
        val itemsToInsert = items.mapNotNull { stream ->
            val uploadDate = stream.uploadDate

            when {
                uploadDate == null && stream.streamType == StreamType.LIVE_STREAM -> stream
                uploadDate != null && uploadDate.offsetDateTime() >= oldestAllowedDate -> stream
                else -> null
            }
        }

        feedTable.unlinkOldLivestreams(subscriptionId)

        if (itemsToInsert.isNotEmpty()) {
            val streamEntities = itemsToInsert.map { StreamEntity(it) }
            val streamIds = streamTable.upsertAll(streamEntities)
            val feedEntities = streamIds.map { FeedEntity(it, subscriptionId) }

            feedTable.insertAll(feedEntities)
        }

        feedTable.setLastUpdatedForSubscription(
            FeedLastUpdatedEntity(subscriptionId, OffsetDateTime.now(ZoneOffset.UTC))
        )
    }

    fun removeOrphansOrOlderStreams(oldestAllowedDate: OffsetDateTime = FEED_OLDEST_ALLOWED_DATE) {
        feedTable.unlinkStreamsOlderThan(oldestAllowedDate)
        streamTable.deleteOrphans()
    }

    fun clear() {
        feedTable.deleteAll()
        val deletedOrphans = streamTable.deleteOrphans()
        if (DEBUG) {
            Log.d(
                this::class.java.simpleName,
                "clear() → streamTable.deleteOrphans() → $deletedOrphans"
            )
        }
    }

    /**
     * Sets playback state to "finished" for all non-live streams linked in the local feed for this
     * subscription. Live streams are skipped (they still match the subscription-dot query). Runs
     * even when watch history is disabled so the subscriptions indicator can clear.
     *
     * @return count of streams updated
     */
    fun markAllFeedStreamsPlayedForSubscription(subscriptionId: Long): Single<Int> {
        return Single.fromCallable {
            var updated = 0
            database.runInTransaction {
                val streamStateDao = database.streamStateDAO()
                val streams = feedTable.getStreamsForSubscriptionFeed(subscriptionId)
                for (stream in streams) {
                    if (StreamTypeUtil.isLiveStream(stream.streamType)) {
                        continue
                    }
                    val progressMillis = if (stream.duration >= 1) {
                        stream.duration * 1000
                    } else {
                        UNKNOWN_DURATION_FINISHED_PROGRESS_MS
                    }
                    streamStateDao.upsert(StreamStateEntity(stream.uid, progressMillis))
                    updated++
                }
            }
            updated
        }.subscribeOn(Schedulers.io())
    }

    // /////////////////////////////////////////////////////////////////////////
    // Feed Groups
    // /////////////////////////////////////////////////////////////////////////

    fun subscriptionIdsForGroup(groupId: Long): Flowable<List<Long>> {
        return feedGroupTable.getSubscriptionIdsFor(groupId)
            .subscribeOn(Schedulers.io())
            .observeOn(AndroidSchedulers.mainThread())
    }

    fun updateSubscriptionsForGroup(groupId: Long, subscriptionIds: List<Long>): Completable {
        return Completable
            .fromCallable { feedGroupTable.updateSubscriptionsForGroup(groupId, subscriptionIds) }
            .subscribeOn(Schedulers.io())
            .observeOn(AndroidSchedulers.mainThread())
    }

    fun createGroup(name: String, icon: FeedGroupIcon): Maybe<Long> {
        return Maybe.fromCallable { feedGroupTable.insert(FeedGroupEntity(0, name, icon)) }
            .subscribeOn(Schedulers.io())
            .observeOn(AndroidSchedulers.mainThread())
    }

    fun getGroup(groupId: Long): Maybe<FeedGroupEntity> {
        return feedGroupTable.getGroup(groupId)
            .subscribeOn(Schedulers.io())
            .observeOn(AndroidSchedulers.mainThread())
    }

    fun updateGroup(feedGroupEntity: FeedGroupEntity): Completable {
        return Completable.fromCallable { feedGroupTable.update(feedGroupEntity) }
            .subscribeOn(Schedulers.io())
            .observeOn(AndroidSchedulers.mainThread())
    }

    fun deleteGroup(groupId: Long): Completable {
        return Completable.fromCallable { feedGroupTable.delete(groupId) }
            .subscribeOn(Schedulers.io())
            .observeOn(AndroidSchedulers.mainThread())
    }

    fun updateGroupsOrder(groupIdList: List<Long>): Completable {
        var index = 0L
        val orderMap = groupIdList.associateBy({ it }, { index++ })

        return Completable.fromCallable { feedGroupTable.updateOrder(orderMap) }
            .subscribeOn(Schedulers.io())
            .observeOn(AndroidSchedulers.mainThread())
    }

    fun oldestSubscriptionUpdate(groupId: Long): Flowable<List<OffsetDateTime?>> {
        return when (groupId) {
            FeedGroupEntity.GROUP_ALL_ID -> feedTable.oldestSubscriptionUpdateFromAll()
            else -> feedTable.oldestSubscriptionUpdate(groupId)
        }
    }
}
