package org.schabi.newpipe.settings;

import android.content.SharedPreferences;
import android.os.Bundle;
import android.view.View;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.StringRes;
import androidx.preference.Preference;
import androidx.preference.PreferenceFragmentCompat;
import androidx.preference.PreferenceManager;

import org.schabi.newpipe.MainActivity;
import org.schabi.newpipe.util.ThemeHelper;

import java.util.Objects;

public abstract class BasePreferenceFragment extends PreferenceFragmentCompat {
    protected final String TAG = getClass().getSimpleName() + "@" + Integer.toHexString(hashCode());
    protected static final boolean DEBUG = MainActivity.DEBUG;

    SharedPreferences defaultPreferences;

    @Override
    public void onCreate(@Nullable final Bundle savedInstanceState) {
        defaultPreferences = PreferenceManager.getDefaultSharedPreferences(requireActivity());
        super.onCreate(savedInstanceState);
    }

    protected void addPreferencesFromResourceRegistry() {
        addPreferencesFromResource(
                SettingsResourceRegistry.getInstance().getPreferencesResId(this.getClass()));
    }

    /**
     * For nested {@link androidx.preference.PreferenceScreen} fragments: root XML
     * {@link androidx.preference.PreferenceScreen} must use the same {@code android:key} as the
     * parent screen that opens this fragment.
     *
     * @param rootKey key passed to {@link PreferenceFragmentCompat#onCreatePreferences}; must match
     *                the opening {@link PreferenceScreen}'s {@code android:key}
     */
    protected void setPreferencesFromResourceRegistry(@Nullable final String rootKey) {
        final int resId = SettingsResourceRegistry.getInstance().getPreferencesResId(getClass());
        setPreferencesFromResource(resId, rootKey);
    }

    @Override
    public void onViewCreated(@NonNull final View rootView,
                              @Nullable final Bundle savedInstanceState) {
        super.onViewCreated(rootView, savedInstanceState);
        setDivider(null);
        ThemeHelper.setTitleToAppCompatActivity(getActivity(), getPreferenceScreen().getTitle());
    }

    @Override
    public void onResume() {
        super.onResume();
        ThemeHelper.setTitleToAppCompatActivity(getActivity(), getPreferenceScreen().getTitle());
    }

    @NonNull
    public final <T extends Preference> T requirePreference(@StringRes final int resId) {
        final T preference = findPreference(getString(resId));
        Objects.requireNonNull(preference);
        return preference;
    }
}
