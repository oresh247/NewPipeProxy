package org.schabi.newpipe.fragments.list.channel;

import android.os.Bundle;
import android.text.TextUtils;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.evernote.android.state.State;

import org.schabi.newpipe.R;
import org.schabi.newpipe.databinding.PlaylistControlBinding;
import org.schabi.newpipe.error.UserAction;
import org.schabi.newpipe.extractor.InfoItem;
import org.schabi.newpipe.extractor.ListExtractor;
import org.schabi.newpipe.extractor.channel.tabs.ChannelTabInfo;
import org.schabi.newpipe.extractor.exceptions.ParsingException;
import org.schabi.newpipe.extractor.linkhandler.ListLinkHandler;
import org.schabi.newpipe.extractor.linkhandler.ListLinkHandlerFactory;
import org.schabi.newpipe.extractor.linkhandler.ReadyChannelTabListLinkHandler;
import org.schabi.newpipe.extractor.stream.StreamInfoItem;
import org.schabi.newpipe.fragments.list.BaseListInfoFragment;
import org.schabi.newpipe.fragments.list.playlist.PlaylistControlViewHolder;
import org.schabi.newpipe.player.playqueue.ChannelTabPlayQueue;
import org.schabi.newpipe.player.playqueue.PlayQueue;
import org.schabi.newpipe.database.subscription.SubscriptionEntity;
import org.schabi.newpipe.local.feed.FeedDatabaseManager;
import org.schabi.newpipe.local.subscription.SubscriptionManager;
import org.schabi.newpipe.util.ChannelTabHelper;
import org.schabi.newpipe.util.ExtractorHelper;
import org.schabi.newpipe.util.PlayButtonHelper;

import java.util.Collections;
import java.util.List;
import java.util.function.Supplier;
import java.util.stream.Collectors;

import io.reactivex.rxjava3.android.schedulers.AndroidSchedulers;
import io.reactivex.rxjava3.core.Flowable;
import io.reactivex.rxjava3.core.Single;
import io.reactivex.rxjava3.disposables.CompositeDisposable;
import io.reactivex.rxjava3.schedulers.Schedulers;

public class ChannelTabFragment extends BaseListInfoFragment<InfoItem, ChannelTabInfo>
        implements PlaylistControlViewHolder {

    // states must be protected and not private for State being able to access them
    @State
    protected ListLinkHandler tabHandler;
    @State
    protected String channelName;
    /**
     * Alternate channel URL for DB lookup when {@link #url} does not match
     * {@link SubscriptionEntity} (same channel, different URL form).
     */
    @State
    @Nullable
    protected String subscriptionUrlAlternate;

    private PlaylistControlBinding playlistControlBinding;

    private CompositeDisposable newInFeedDisposable;
    private SubscriptionManager subscriptionManager;
    private FeedDatabaseManager feedDatabaseManager;

    @NonNull
    public static ChannelTabFragment getInstance(final int serviceId,
                                                 final ListLinkHandler tabHandler,
                                                 final String channelName,
                                                 final String channelUrl,
                                                 @Nullable final String channelUrlAlternate) {
        final ChannelTabFragment instance = new ChannelTabFragment();
        instance.serviceId = serviceId;
        instance.tabHandler = tabHandler;
        instance.channelName = channelName;
        instance.url = channelUrl;
        instance.subscriptionUrlAlternate = channelUrlAlternate;
        return instance;
    }

    public ChannelTabFragment() {
        super(UserAction.REQUESTED_CHANNEL);
    }

    /**
     * Resolves the subscription row using {@link #url}, then optionally
     * {@link #subscriptionUrlAlternate}, so the channel page matches the URL stored in DB
     * (e.g. handle vs /channel/id).
     *
     * @return flowable list of matching subscriptions (at most one row in practice)
     */
    private Flowable<List<SubscriptionEntity>> subscriptionListFlowableForChannel() {
        return subscriptionManager.subscriptionTable()
                .getSubscriptionFlowable(serviceId, url)
                .switchMap(subscriptions -> {
                    if (!subscriptions.isEmpty()) {
                        return Flowable.just(subscriptions);
                    }
                    if (TextUtils.isEmpty(subscriptionUrlAlternate)
                            || subscriptionUrlAlternate.equals(url)) {
                        return Flowable.just(Collections.emptyList());
                    }
                    return subscriptionManager.subscriptionTable()
                            .getSubscriptionFlowable(serviceId, subscriptionUrlAlternate);
                });
    }

    /*//////////////////////////////////////////////////////////////////////////
    // LifeCycle
    //////////////////////////////////////////////////////////////////////////*/

    @Override
    public void onCreate(final Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setHasOptionsMenu(false);
        subscriptionManager = new SubscriptionManager(requireContext());
        feedDatabaseManager = new FeedDatabaseManager(requireContext());
        newInFeedDisposable = new CompositeDisposable();
    }

    @Override
    public void onStart() {
        super.onStart();
        if (ChannelTabHelper.isStreamsTab(tabHandler) && !TextUtils.isEmpty(url)) {
            newInFeedDisposable.clear();
            newInFeedDisposable.add(subscriptionListFlowableForChannel()
                    .subscribeOn(Schedulers.io())
                    .switchMap(subscriptions -> {
                        if (subscriptions.isEmpty()) {
                            return Flowable.just(Collections.<String>emptySet());
                        }
                        return feedDatabaseManager.newInFeedStreamKeysForSubscription(
                                subscriptions.get(0).getUid());
                    })
                    .observeOn(AndroidSchedulers.mainThread())
                    .subscribe(
                            keys -> infoListAdapter.setNewInFeedStreamKeys(keys),
                            throwable -> Log.e(TAG, "observe new-in-feed stream keys", throwable)
                    ));
        }
    }

    @Override
    public void onStop() {
        newInFeedDisposable.clear();
        if (ChannelTabHelper.isStreamsTab(tabHandler)) {
            infoListAdapter.setNewInFeedStreamKeys(null);
        }
        super.onStop();
    }

    @Override
    public View onCreateView(@NonNull final LayoutInflater inflater,
                             @Nullable final ViewGroup container,
                             @Nullable final Bundle savedInstanceState) {
        return inflater.inflate(R.layout.fragment_channel_tab, container, false);
    }

    @Override
    public void onDestroyView() {
        super.onDestroyView();
        playlistControlBinding = null;
    }

    @Override
    protected Supplier<View> getListHeaderSupplier() {
        if (ChannelTabHelper.isStreamsTab(tabHandler)) {
            playlistControlBinding = PlaylistControlBinding
                    .inflate(activity.getLayoutInflater(), itemsList, false);
            return playlistControlBinding::getRoot;
        }
        return null;
    }

    @Override
    protected Single<ChannelTabInfo> loadResult(final boolean forceLoad) {
        return ExtractorHelper.getChannelTab(serviceId, tabHandler, forceLoad);
    }

    @Override
    protected Single<ListExtractor.InfoItemsPage<InfoItem>> loadMoreItemsLogic() {
        return ExtractorHelper.getMoreChannelTabItems(serviceId, tabHandler, currentNextPage);
    }

    @Override
    public void setTitle(final String title) {
        // The channel name is displayed as title in the toolbar.
        // The title is always a description of the content of the tab fragment.
        // It should be unique for each channel because multiple channel tabs
        // can be added to the main page. Therefore, the channel name is used.
        // Using the title variable would cause the title to be the same for all channel tabs.
        super.setTitle(channelName);
    }

    @Override
    public void handleResult(@NonNull final ChannelTabInfo result) {
        super.handleResult(result);

        // FIXME this is a really hacky workaround, to avoid storing useless data in the fragment
        //  state. The problem is, `ReadyChannelTabListLinkHandler` might contain raw JSON data that
        //  uses a lot of memory (e.g. ~800KB for YouTube). While 800KB doesn't seem much, if
        //  you combine just a couple of channel tab fragments you easily go over the 1MB
        //  save&restore transaction limit, and get `TransactionTooLargeException`s. A proper
        //  solution would require rethinking about `ReadyChannelTabListLinkHandler`s.
        if (tabHandler instanceof ReadyChannelTabListLinkHandler) {
            try {
                // once `handleResult` is called, the parsed data was already saved to cache, so
                // we can discard any raw data in ReadyChannelTabListLinkHandler and create a
                // link handler with identical properties, but without any raw data
                final ListLinkHandlerFactory channelTabLHFactory = result.getService()
                        .getChannelTabLHFactory();
                if (channelTabLHFactory != null) {
                    // some services do not not have a ChannelTabLHFactory
                    tabHandler = channelTabLHFactory.fromQuery(tabHandler.getId(),
                            tabHandler.getContentFilters(), tabHandler.getSortFilter());
                }
            } catch (final ParsingException e) {
                // silently ignore the error, as the app can continue to function normally
                Log.w(TAG, "Could not recreate channel tab handler", e);
            }
        }

        if (playlistControlBinding != null) {
            // PlaylistControls should be visible only if there is some item in
            // infoListAdapter other than header
            if (infoListAdapter.getItemCount() > 1) {
                playlistControlBinding.getRoot().setVisibility(View.VISIBLE);
            } else {
                playlistControlBinding.getRoot().setVisibility(View.GONE);
            }

            PlayButtonHelper.initPlaylistControlClickListener(
                    activity, playlistControlBinding, this);
        }
    }

    @Override
    public PlayQueue getPlayQueue() {
        final List<StreamInfoItem> streamItems = infoListAdapter.getItemsList().stream()
                .filter(StreamInfoItem.class::isInstance)
                .map(StreamInfoItem.class::cast)
                .collect(Collectors.toList());

        return new ChannelTabPlayQueue(currentInfo.getServiceId(), tabHandler,
                currentInfo.getNextPage(), streamItems, 0);
    }
}
