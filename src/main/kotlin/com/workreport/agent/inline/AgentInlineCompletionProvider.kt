package com.workreport.agent.inline

import com.intellij.codeInsight.inline.completion.DebouncedInlineCompletionProvider
import com.intellij.codeInsight.inline.completion.InlineCompletionEvent
import com.intellij.codeInsight.inline.completion.InlineCompletionFontUtils
import com.intellij.codeInsight.inline.completion.InlineCompletionProviderID
import com.intellij.codeInsight.inline.completion.InlineCompletionRequest
import com.intellij.codeInsight.inline.completion.elements.InlineCompletionTextElement
import com.intellij.codeInsight.inline.completion.suggestion.InlineCompletionSingleSuggestion
import com.intellij.codeInsight.inline.completion.suggestion.InlineCompletionSuggestion
import com.intellij.openapi.util.TextRange
import com.workreport.plugin.settings.ReportSettingsState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.withContext
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds

/**
 * Editor ghost text: sends the text before/after the caret to the Agent Core,
 * which answers with a fill-in-the-middle completion for the active model.
 * 编辑器内幽灵补全：把光标前后文发给 Agent Core，由 Core 用当前模型做 FIM 补全。
 */
class AgentInlineCompletionProvider : DebouncedInlineCompletionProvider() {

    override val id = InlineCompletionProviderID(AgentInlineCompletionProvider::class.java.name)

    override fun isEnabled(event: InlineCompletionEvent): Boolean {
        if (!ReportSettingsState.getInstance().inlineCompletionEnabled) {
            return false
        }
        val request = event.toRequest() ?: return false
        val file = request.file.virtualFile ?: return false
        return file.isInLocalFileSystem && !file.isDirectory
    }

    override suspend fun getDebounceDelay(request: InlineCompletionRequest): Duration = DEBOUNCE

    override suspend fun getSuggestionDebounced(request: InlineCompletionRequest): InlineCompletionSuggestion {
        val project = request.file.project ?: return InlineCompletionSuggestion.Empty
        val file = request.file.virtualFile ?: return InlineCompletionSuggestion.Empty
        if (file.length > MAX_FILE_BYTES) {
            return InlineCompletionSuggestion.Empty
        }

        val document = request.document
        val offset = request.endOffset.coerceIn(0, document.textLength)
        val prefix = document.getText(TextRange((offset - MAX_PREFIX_CHARS).coerceAtLeast(0), offset))
        if (prefix.isBlank()) {
            return InlineCompletionSuggestion.Empty
        }
        val suffixStart = offset
        val suffix = document.getText(
            TextRange(suffixStart, (suffixStart + MAX_SUFFIX_CHARS).coerceAtMost(document.textLength))
        )

        val text = withContext(Dispatchers.IO) {
            runCatching {
                InlineCompletionClient.complete(
                    project, file.path, file.extension ?: "", prefix, suffix
                )
            }.getOrNull()
        }
        if (text.isNullOrBlank()) {
            return InlineCompletionSuggestion.Empty
        }

        val attributes = InlineCompletionFontUtils.attributes(request.editor)
        return InlineCompletionSingleSuggestion.build(
            elements = flowOf(InlineCompletionTextElement(text, attributes))
        )
    }

    private companion object {
        val DEBOUNCE = 300.milliseconds
        const val MAX_PREFIX_CHARS = 4000
        const val MAX_SUFFIX_CHARS = 1200
        const val MAX_FILE_BYTES = 512L * 1024L
    }
}
