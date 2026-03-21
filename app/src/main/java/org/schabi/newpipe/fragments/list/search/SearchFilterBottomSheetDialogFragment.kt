/*
 * SPDX-FileCopyrightText: 2026 NewPipe contributors <https://newpipe.net>
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package org.schabi.newpipe.fragments.list.search

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import androidx.appcompat.widget.AppCompatRadioButton
import androidx.core.os.bundleOf
import androidx.core.view.isVisible
import androidx.fragment.app.FragmentManager
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import org.schabi.newpipe.R
import org.schabi.newpipe.extractor.ServiceList
import org.schabi.newpipe.extractor.services.peertube.linkHandler.PeertubeSearchQueryHandlerFactory
import org.schabi.newpipe.extractor.services.youtube.linkHandler.YoutubeSearchQueryHandlerFactory
import org.schabi.newpipe.util.ServiceHelper
import org.schabi.newpipe.views.NewPipeTextView

/**
 * Bottom sheet for search content type plus optional YouTube upload date / duration (InnerTube params).
 */
class SearchFilterBottomSheetDialogFragment : BottomSheetDialogFragment() {

    private val typeRadios = mutableListOf<AppCompatRadioButton>()
    private val uploadRadios = mutableListOf<AppCompatRadioButton>()
    private val durationRadios = mutableListOf<AppCompatRadioButton>()

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View = inflater.inflate(R.layout.dialog_search_filters, container, false)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        val filters = requireArguments().getStringArray(ARG_FILTERS) ?: emptyArray()
        val selectedId = requireArguments().getString(ARG_SELECTED).orEmpty()
        val serviceId = requireArguments().getInt(ARG_SERVICE_ID)
        val initialUpload = requireArguments().getInt(ARG_UPLOAD_ORDINAL)
        val initialDuration = requireArguments().getInt(ARG_DURATION_ORDINAL)

        val typeContainer = view.findViewById<LinearLayout>(R.id.search_filters_type_container)
        val youtubeExtra = view.findViewById<LinearLayout>(R.id.search_filters_youtube_extra)
        val uploadContainer = view.findViewById<LinearLayout>(R.id.search_filters_upload_container)
        val durationContainer = view.findViewById<LinearLayout>(R.id.search_filters_duration_container)
        val ctx = requireContext()
        val initialSelection = resolveInitialSelection(filters, selectedId)

        for (filter in filters) {
            when (filter) {
                YoutubeSearchQueryHandlerFactory.MUSIC_SONGS -> {
                    typeContainer.addView(buildSectionLabel(ctx.getString(R.string.youtube_music)))
                }

                PeertubeSearchQueryHandlerFactory.SEPIA_VIDEOS -> {
                    typeContainer.addView(buildSectionLabel(ctx.getString(R.string.sepia_search)))
                }
            }
            val label = ServiceHelper.getTranslatedFilterString(filter, ctx)
            typeContainer.addView(
                buildTypeRadio(filter, label, filter == initialSelection)
            )
        }

        if (serviceId == ServiceList.YouTube.getServiceId()) {
            youtubeExtra.isVisible = true
            addOrdinalRadios(
                uploadContainer,
                uploadRadios,
                listOf(
                    0 to ctx.getString(R.string.search_upload_any_time),
                    1 to ctx.getString(R.string.search_upload_last_hour),
                    2 to ctx.getString(R.string.search_upload_today),
                    3 to ctx.getString(R.string.search_upload_week),
                    4 to ctx.getString(R.string.search_upload_month),
                    5 to ctx.getString(R.string.search_upload_year)
                ),
                initialUpload
            ) { uploadRadios }
            addOrdinalRadios(
                durationContainer,
                durationRadios,
                listOf(
                    0 to ctx.getString(R.string.search_duration_any),
                    1 to ctx.getString(R.string.search_duration_short),
                    3 to ctx.getString(R.string.search_duration_medium),
                    2 to ctx.getString(R.string.search_duration_long)
                ),
                initialDuration
            ) { durationRadios }
        }

        view.findViewById<View>(R.id.search_filters_cancel).setOnClickListener { dismiss() }
        view.findViewById<View>(R.id.search_filters_apply).setOnClickListener {
            val chosenType = typeRadios.firstOrNull { it.isChecked }?.tag as? String
                ?: filters.firstOrNull()
                ?: return@setOnClickListener
            val bundle = bundleOf(RESULT_FILTER to chosenType)
            if (serviceId == ServiceList.YouTube.getServiceId()) {
                bundle.putInt(RESULT_YT_UPLOAD, ordinalFrom(uploadRadios))
                bundle.putInt(RESULT_YT_DURATION, ordinalFrom(durationRadios))
            }
            parentFragmentManager.setFragmentResult(REQUEST_KEY, bundle)
            dismiss()
        }
    }

    private fun ordinalFrom(radios: List<AppCompatRadioButton>): Int {
        val checked = radios.firstOrNull { it.isChecked } ?: return 0
        return (checked.tag as? String)?.toIntOrNull() ?: 0
    }

    private fun addOrdinalRadios(
        container: LinearLayout,
        track: MutableList<AppCompatRadioButton>,
        options: List<Pair<Int, String>>,
        initialOrdinal: Int,
        group: () -> List<AppCompatRadioButton>
    ) {
        for ((ord, label) in options) {
            container.addView(
                buildOrdinalRadio(track, ord, label, ord == initialOrdinal, group)
            )
        }
    }

    private fun buildOrdinalRadio(
        track: MutableList<AppCompatRadioButton>,
        ordinal: Int,
        label: String,
        checked: Boolean,
        group: () -> List<AppCompatRadioButton>
    ): AppCompatRadioButton {
        val rb = AppCompatRadioButton(requireContext()).apply {
            text = label
            tag = ordinal.toString()
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
            isChecked = checked
            setOnClickListener {
                selectOnlyIn(group.invoke(), this)
            }
        }
        track.add(rb)
        return rb
    }

    private fun buildTypeRadio(filterId: String, label: String, checked: Boolean): AppCompatRadioButton {
        val rb = AppCompatRadioButton(requireContext()).apply {
            text = label
            tag = filterId
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
            isChecked = checked
            setOnClickListener { selectOnlyIn(typeRadios, this) }
        }
        typeRadios.add(rb)
        return rb
    }

    private fun selectOnlyIn(group: List<AppCompatRadioButton>, selected: AppCompatRadioButton) {
        group.forEach { it.isChecked = it == selected }
    }

    private fun buildSectionLabel(text: String): NewPipeTextView {
        val density = resources.displayMetrics.density
        return NewPipeTextView(requireContext()).apply {
            this.text = text
            isEnabled = false
            alpha = 0.75f
            setPadding(0, (12 * density).toInt(), 0, (4 * density).toInt())
        }
    }

    companion object {
        const val REQUEST_KEY: String = "search_content_filter_request"
        const val RESULT_FILTER: String = "result_content_filter"
        const val RESULT_YT_UPLOAD: String = "result_yt_upload_ordinal"
        const val RESULT_YT_DURATION: String = "result_yt_duration_ordinal"

        private const val ARG_FILTERS = "arg_filters"
        private const val ARG_SELECTED = "arg_selected"
        private const val ARG_SERVICE_ID = "arg_service_id"
        private const val ARG_UPLOAD_ORDINAL = "arg_upload_ordinal"
        private const val ARG_DURATION_ORDINAL = "arg_duration_ordinal"

        @JvmStatic
        fun newInstance(
            filters: Array<String>,
            selectedFilter: String?,
            serviceId: Int,
            uploadOrdinal: Int,
            durationOrdinal: Int
        ): SearchFilterBottomSheetDialogFragment {
            return SearchFilterBottomSheetDialogFragment().apply {
                arguments = bundleOf(
                    ARG_FILTERS to filters,
                    ARG_SELECTED to (selectedFilter ?: ""),
                    ARG_SERVICE_ID to serviceId,
                    ARG_UPLOAD_ORDINAL to uploadOrdinal,
                    ARG_DURATION_ORDINAL to durationOrdinal
                )
            }
        }

        @JvmStatic
        fun show(
            fragmentManager: FragmentManager,
            filters: Array<String>,
            selectedFilter: String?,
            serviceId: Int,
            uploadOrdinal: Int,
            durationOrdinal: Int
        ) {
            newInstance(filters, selectedFilter, serviceId, uploadOrdinal, durationOrdinal)
                .show(fragmentManager, "search_filters")
        }

        private fun resolveInitialSelection(filters: Array<String>, selectedId: String): String {
            if (filters.isEmpty()) {
                return selectedId
            }
            if (selectedId.isEmpty() || filters.contains(selectedId)) {
                return if (selectedId.isEmpty()) filters[0] else selectedId
            }
            return filters[0]
        }
    }
}
