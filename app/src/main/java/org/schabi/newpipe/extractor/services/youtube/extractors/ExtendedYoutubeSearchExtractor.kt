/*
 * SPDX-FileCopyrightText: 2026 NewPipe contributors <https://newpipe.net>
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package org.schabi.newpipe.extractor.services.youtube.extractors

import com.grack.nanojson.JsonObject
import com.grack.nanojson.JsonWriter
import java.io.IOException
import java.nio.charset.StandardCharsets
import org.schabi.newpipe.extractor.StreamingService
import org.schabi.newpipe.extractor.downloader.Downloader
import org.schabi.newpipe.extractor.exceptions.ExtractionException
import org.schabi.newpipe.extractor.linkhandler.ListLinkHandler
import org.schabi.newpipe.extractor.linkhandler.SearchQueryHandler
import org.schabi.newpipe.extractor.services.youtube.YoutubeParsingHelper.getJsonPostResponse
import org.schabi.newpipe.extractor.services.youtube.YoutubeParsingHelper.prepareDesktopJsonBuilder
import org.schabi.newpipe.extractor.utils.Utils.isNullOrEmpty
import org.schabi.newpipe.search.YoutubeSearchExtras
import org.schabi.newpipe.search.YoutubeSearchParamsProto

/**
 * YouTube search with upload-date / duration filters by building InnerTube `params` protobuf
 * (see [YoutubeSearchParamsProto]). The upstream [YoutubeSearchExtractor] only reads the content
 * type; this subclass fills [YoutubeSearchExtractor.initialData] via reflection after a custom POST.
 */
class ExtendedYoutubeSearchExtractor(
    service: StreamingService,
    query: SearchQueryHandler,
    private val extras: YoutubeSearchExtras
) : YoutubeSearchExtractor(service, query) {

    @Throws(IOException::class, ExtractionException::class)
    override fun onFetchPage(downloader: Downloader) {
        val q = searchString
        val localization = extractorLocalization
        val filters = (getLinkHandler() as ListLinkHandler).contentFilters
        val searchType = if (filters.isNullOrEmpty()) null else filters[0] as? String

        val params = YoutubeSearchParamsProto.buildParams(
            searchType,
            extras.uploadDateOrdinal,
            extras.durationOrdinal
        )

        val jsonBody = prepareDesktopJsonBuilder(localization, extractorContentCountry)
            .value("query", q)
        if (!isNullOrEmpty(params)) {
            jsonBody.value("params", params)
        }
        val body = JsonWriter.string(jsonBody.done()).toByteArray(StandardCharsets.UTF_8)
        val initial = getJsonPostResponse("search", body, localization)
        setInitialDataField(this, initial)
    }

    companion object {
        private val initialDataField: java.lang.reflect.Field? = try {
            YoutubeSearchExtractor::class.java.getDeclaredField("initialData").also { it.isAccessible = true }
        } catch (_: Exception) {
            null
        }

        @Throws(ExtractionException::class)
        private fun setInitialDataField(target: YoutubeSearchExtractor, data: JsonObject?) {
            val f = initialDataField
                ?: throw ExtractionException("YoutubeSearchExtractor.initialData field not accessible")
            try {
                f.set(target, data)
            } catch (e: Exception) {
                throw ExtractionException("Could not assign search initialData", e)
            }
        }
    }
}
