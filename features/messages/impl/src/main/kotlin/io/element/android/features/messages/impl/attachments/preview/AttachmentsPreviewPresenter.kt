/*
 * Copyright (c) 2025 Element Creations Ltd.
 * Copyright 2023-2025 New Vector Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.features.messages.impl.attachments.preview

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import dev.zacsweers.metro.Assisted
import dev.zacsweers.metro.AssistedFactory
import dev.zacsweers.metro.AssistedInject
import io.element.android.features.messages.impl.attachments.Attachment
import io.element.android.features.messages.impl.attachments.preview.imageeditor.AttachmentImageEditor
import io.element.android.features.messages.impl.attachments.preview.imageeditor.AttachmentImageEditorState
import io.element.android.features.messages.impl.attachments.preview.imageeditor.AttachmentImageEdits
import io.element.android.features.messages.impl.attachments.video.MediaOptimizationSelectorPresenter
import io.element.android.features.messages.impl.attachments.video.MediaOptimizationSelectorState
import io.element.android.features.messages.impl.attachments.video.VideoCompressionPresetSelector
import io.element.android.features.messages.impl.attachments.video.VideoUploadEstimation
import io.element.android.libraries.androidutils.file.TemporaryUriDeleter
import io.element.android.libraries.androidutils.file.safeDelete
import io.element.android.libraries.androidutils.hash.hash
import io.element.android.libraries.architecture.AsyncData
import io.element.android.libraries.architecture.Presenter
import io.element.android.libraries.core.coroutine.CoroutineDispatchers
import io.element.android.libraries.core.coroutine.firstInstanceOf
import io.element.android.libraries.core.extensions.runCatchingExceptions
import io.element.android.libraries.core.mimetype.MimeTypes.isMimeTypeVideo
import io.element.android.libraries.di.annotations.SessionCoroutineScope
import io.element.android.libraries.matrix.api.core.EventId
import io.element.android.libraries.matrix.api.permalink.PermalinkBuilder
import io.element.android.libraries.matrix.api.timeline.Timeline
import io.element.android.libraries.mediaupload.api.MediaOptimizationConfig
import io.element.android.libraries.mediaupload.api.MediaOptimizationConfigProvider
import io.element.android.libraries.mediaupload.api.MediaSenderFactory
import io.element.android.libraries.mediaupload.api.MediaUploadInfo
import io.element.android.libraries.mediaupload.api.allFiles
import io.element.android.libraries.preferences.api.store.VideoCompressionPreset
import io.element.android.libraries.textcomposer.model.TextEditorState
import io.element.android.libraries.textcomposer.model.rememberMarkdownTextEditorState
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.toImmutableList
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import timber.log.Timber
import java.io.File

@AssistedInject
class AttachmentsPreviewPresenter(
    @Assisted private val attachments: ImmutableList<Attachment>,
    @Assisted private val onDoneListener: OnDoneListener,
    @Assisted private val timelineMode: Timeline.Mode,
    @Assisted private val inReplyToEventId: EventId?,
    mediaSenderFactory: MediaSenderFactory,
    private val permalinkBuilder: PermalinkBuilder,
    private val temporaryUriDeleter: TemporaryUriDeleter,
    private val attachmentImageEditor: AttachmentImageEditor,
    private val mediaOptimizationSelectorPresenterFactory: MediaOptimizationSelectorPresenter.Factory,
    private val videoCompressionPresetSelector: VideoCompressionPresetSelector,
    @SessionCoroutineScope private val sessionCoroutineScope: CoroutineScope,
    private val dispatchers: CoroutineDispatchers,
    private val mediaOptimizationConfigProvider: MediaOptimizationConfigProvider,
) : Presenter<AttachmentsPreviewState> {
    @AssistedFactory
    interface Factory {
        fun create(
            attachments: ImmutableList<Attachment>,
            timelineMode: Timeline.Mode,
            onDoneListener: OnDoneListener,
            inReplyToEventId: EventId?,
        ): AttachmentsPreviewPresenter
    }

    private val mediaSender = mediaSenderFactory.create(timelineMode)

    // Decided once, from the initial selection: a batch that's whittled down to one item via
    // removal still uses the batch state machine (no per-item image editing support), rather than
    // switching composable branches mid-lifetime.
    private val isBatch = attachments.size > 1

    @Composable
    override fun present(): AttachmentsPreviewState {
        return if (isBatch) {
            presentBatch()
        } else {
            presentSingle(attachments.first())
        }
    }

    @Composable
    private fun presentSingle(attachment: Attachment): AttachmentsPreviewState {
        val coroutineScope = rememberCoroutineScope()

        val sendActionState = remember {
            mutableStateOf<SendActionState>(SendActionState.Idle)
        }
        val originalLocalMedia = remember { (attachment as Attachment.Media).localMedia }
        var currentAttachment by remember { mutableStateOf(attachment) }
        var canEditImage by remember { mutableStateOf(originalLocalMedia.info.canEditImage()) }
        var imageEditorState by remember { mutableStateOf<AttachmentImageEditorState?>(null) }
        var appliedImageEdits by remember { mutableStateOf(AttachmentImageEdits()) }
        var isApplyingImageEdits by remember { mutableStateOf(false) }
        var displayImageEditError by remember { mutableStateOf(false) }
        var editedTempFile by remember { mutableStateOf<File?>(null) }

        val markdownTextEditorState = rememberMarkdownTextEditorState(initialText = null, initialFocus = false)
        val textEditorState by rememberUpdatedState(
            TextEditorState.Markdown(markdownTextEditorState, isRoomEncrypted = null)
        )

        val ongoingSendAttachmentJob = remember { mutableStateOf<Job?>(null) }

        var preprocessMediaJob by remember { mutableStateOf<Job?>(null) }

        val mediaAttachment = currentAttachment as Attachment.Media
        val mediaOptimizationSelectorPresenter = remember {
            mediaOptimizationSelectorPresenterFactory.create(
                localMedia = mediaAttachment.localMedia,
                sendAsFile = mediaAttachment.sendAsFile,
            )
        }
        val mediaOptimizationSelectorState by rememberUpdatedState(mediaOptimizationSelectorPresenter.present())

        val observableSendState = snapshotFlow { sendActionState.value }

        var displayFileTooLargeError by remember { mutableStateOf(false) }

        LaunchedEffect(
            mediaOptimizationSelectorState.displayMediaSelectorViews,
            mediaOptimizationSelectorState.videoSizeEstimations,
            currentAttachment,
            imageEditorState,
            isApplyingImageEdits,
        ) {
            // If the media optimization selector is not displayed, we can pre-process the media
            // to prepare it for sending. This is done to avoid blocking the UI thread when the
            // user clicks on the send button.
            @Suppress("ComplexCondition")
            if (mediaOptimizationSelectorState.displayMediaSelectorViews == false &&
                preprocessMediaJob == null &&
                imageEditorState == null &&
                !isApplyingImageEdits) {
                if (mediaAttachment.localMedia.info.mimeType.isMimeTypeVideo() && mediaOptimizationSelectorState.videoSizeEstimations.dataOrNull() == null) {
                    Timber.d("Waiting for video size estimations to be able to select the best video compression preset before pre-processing the media")
                    return@LaunchedEffect
                }
                val config = getAutoPreprocessMediaOptimizationConfig(
                    mediaAttachment = mediaAttachment,
                    videoSizeEstimations = mediaOptimizationSelectorState.videoSizeEstimations,
                )
                preprocessMediaJob = coroutineScope.preProcessAttachment(
                    attachment = currentAttachment,
                    mediaOptimizationConfig = config,
                    displayProgress = false,
                    sendActionState = sendActionState,
                )
            }
        }

        LaunchedEffect(originalLocalMedia) {
            canEditImage = originalLocalMedia.info.canEditImage() || attachmentImageEditor.canEdit(originalLocalMedia)
        }

        val maxUploadSize = mediaOptimizationSelectorState.maxUploadSize.dataOrNull()
        LaunchedEffect(maxUploadSize) {
            // Check file upload size if the media won't be processed for upload
            val isImageFile = mediaAttachment.localMedia.info.isImageAttachment()
            val isVideoFile = mediaAttachment.localMedia.info.mimeType.isMimeTypeVideo()
            if (maxUploadSize != null && !(isImageFile || isVideoFile)) {
                // If file size is not known, we're permissive and allow sending. The SDK will cancel the upload if needed.
                val fileSize = mediaAttachment.localMedia.info.fileSize ?: 0L
                if (maxUploadSize < fileSize) {
                    displayFileTooLargeError = true
                }
            }
        }

        val videoSizeEstimations = mediaOptimizationSelectorState.videoSizeEstimations.dataOrNull()
        LaunchedEffect(videoSizeEstimations) {
            if (videoSizeEstimations != null) {
                // Check if the video size estimations are too large for the max upload size
                displayFileTooLargeError = videoSizeEstimations.none { it.canUpload }
            }
        }

        fun handleEvent(event: AttachmentsPreviewEvent) {
            when (event) {
                is AttachmentsPreviewEvent.SendAttachment -> {
                    ongoingSendAttachmentJob.value = coroutineScope.launch {
                        // If the media optimization selector is displayed, we need to wait for the user to select the options
                        // before we can pre-process the media.
                        if (mediaOptimizationSelectorState.displayMediaSelectorViews == true) {
                            val config = MediaOptimizationConfig(
                                compressImages = mediaOptimizationSelectorState.isImageOptimizationEnabled == true,
                                videoCompressionPreset = mediaOptimizationSelectorState.selectedVideoPreset ?: VideoCompressionPreset.STANDARD,
                            )
                            preprocessMediaJob = preProcessAttachment(
                                attachment = currentAttachment,
                                mediaOptimizationConfig = config,
                                displayProgress = true,
                                sendActionState = sendActionState,
                            )
                        }

                        // If the processing was hidden before, make it visible now
                        if (sendActionState.value is SendActionState.Sending.Processing) {
                            sendActionState.value = SendActionState.Sending.Processing(displayProgress = true)
                        }

                        // Wait until the media is ready to be uploaded
                        val mediaUploadInfo = observableSendState.firstInstanceOf<SendActionState.Sending.ReadyToUpload>().mediaInfo

                        // Pre-processing is done, send the attachment
                        val caption = markdownTextEditorState.getMessageMarkdown(permalinkBuilder)
                            .takeIf { it.isNotEmpty() }

                        val editedTempFileToDelete = editedTempFile
                        editedTempFile = null

                        // If we're supposed to send the media as a background job, we can dismiss this screen already
                        if (coroutineContext.isActive) {
                            onDoneListener()
                        }

                        // Send the media using the session coroutine scope so it doesn't matter if this screen or the chat one are closed
                        sessionCoroutineScope.launch(dispatchers.io) {
                            try {
                                sendPreProcessedMedia(
                                    mediaUploadInfo = mediaUploadInfo,
                                    caption = caption,
                                    sendActionState = sendActionState,
                                    dismissAfterSend = false,
                                    inReplyToEventId = inReplyToEventId,
                                )
                            } finally {
                                editedTempFileToDelete?.safeDelete()
                                // Clean up the pre-processed media after it's been sent
                                mediaSender.cleanUp()
                            }
                        }
                    }
                }
                AttachmentsPreviewEvent.CancelAndDismiss -> {
                    displayFileTooLargeError = false
                    displayImageEditError = false
                    isApplyingImageEdits = false

                    // Cancel media preprocessing and sending
                    preprocessMediaJob?.cancel()
                    preprocessMediaJob = null
                    // If we couldn't send the pre-processed media, remove it
                    mediaSender.cleanUp()
                    ongoingSendAttachmentJob.value?.cancel()

                    // Dismiss the screen
                    dismissSingle(attachment, sendActionState, editedTempFile)
                }
                AttachmentsPreviewEvent.CancelAndClearSendState -> {
                    // Cancel media sending
                    ongoingSendAttachmentJob.value?.let {
                        it.cancel()
                        ongoingSendAttachmentJob.value = null
                    }

                    val mediaUploadInfo = sendActionState.value.mediaUploadInfo()
                    sendActionState.value = if (mediaUploadInfo != null) {
                        SendActionState.Sending.ReadyToUpload(mediaUploadInfo)
                    } else {
                        SendActionState.Idle
                    }
                }
                AttachmentsPreviewEvent.OpenImageEditor -> {
                    val resolvedCanEditImage = canEditImage || originalLocalMedia.info.canEditImage()
                    if (resolvedCanEditImage) {
                        preprocessMediaJob?.cancel()
                        preprocessMediaJob = null
                        resetPreparedMedia(sendActionState)
                        imageEditorState = AttachmentImageEditorState(
                            localMedia = originalLocalMedia,
                            edits = appliedImageEdits,
                            previewDebug = false,
                        )
                    }
                }
                AttachmentsPreviewEvent.CloseImageEditor -> {
                    imageEditorState = null
                }
                is AttachmentsPreviewEvent.UpdateImageCropRect -> {
                    val pendingState = imageEditorState ?: return
                    imageEditorState = pendingState.copy(
                        edits = pendingState.edits.copy(cropRect = event.cropRect)
                    )
                }
                AttachmentsPreviewEvent.RotateImageToTheLeft -> {
                    val pendingState = imageEditorState ?: return
                    imageEditorState = pendingState.copy(
                        edits = pendingState.edits.rotateAntiClockwise()
                    )
                }
                AttachmentsPreviewEvent.FlipImageHorizontally -> {
                    val pendingState = imageEditorState ?: return
                    imageEditorState = pendingState.copy(
                        edits = pendingState.edits.flipHorizontally()
                    )
                }
                AttachmentsPreviewEvent.FlipImageVertically -> {
                    val pendingState = imageEditorState ?: return
                    imageEditorState = pendingState.copy(
                        edits = pendingState.edits.flipVertically()
                    )
                }
                AttachmentsPreviewEvent.ResetImageEdits -> {
                    imageEditorState = imageEditorState?.copy(
                        edits = AttachmentImageEdits()
                    )
                }
                AttachmentsPreviewEvent.ApplyImageEdits -> {
                    val pendingState = imageEditorState ?: return
                    if (!pendingState.edits.hasChanges) {
                        editedTempFile?.safeDelete()
                        editedTempFile = null
                        appliedImageEdits = pendingState.edits
                        currentAttachment = Attachment.Media(originalLocalMedia)
                        imageEditorState = null
                        resetPreparedMedia(sendActionState)
                        return
                    }
                    isApplyingImageEdits = true
                    displayImageEditError = false
                    coroutineScope.launch {
                        val result = withContext(dispatchers.io) {
                            attachmentImageEditor.exportEdits(
                                localMedia = originalLocalMedia,
                                edits = pendingState.edits,
                            )
                        }
                        result.fold(
                            onSuccess = { editedMedia ->
                                editedTempFile?.safeDelete()
                                editedTempFile = editedMedia.file
                                appliedImageEdits = pendingState.edits
                                currentAttachment = Attachment.Media(editedMedia.localMedia)
                                imageEditorState = null
                                resetPreparedMedia(sendActionState)
                            },
                            onFailure = {
                                Timber.e(it, "Failed to apply image edits")
                                displayImageEditError = true
                            }
                        )
                        isApplyingImageEdits = false
                    }
                }
                AttachmentsPreviewEvent.ClearImageEditError -> {
                    displayImageEditError = false
                }
                is AttachmentsPreviewEvent.RemoveAttachment, is AttachmentsPreviewEvent.FocusAttachment -> {
                    // Not applicable to a single-attachment preview.
                }
            }
        }

        return AttachmentsPreviewState(
            attachment = currentAttachment,
            attachments = persistentListOf(currentAttachment),
            focusedIndex = 0,
            imageEditorState = imageEditorState,
            canEditImage = canEditImage,
            isApplyingImageEdits = isApplyingImageEdits,
            displayImageEditError = displayImageEditError,
            sendActionState = sendActionState.value,
            sendProgress = null,
            textEditorState = textEditorState,
            mediaOptimizationSelectorState = mediaOptimizationSelectorState,
            displayFileTooLargeError = displayFileTooLargeError,
            eventSink = ::handleEvent,
        )
    }

    /**
     * Batch preview/send flow for 2+ selected attachments. Image/video editing isn't supported here
     * (see plan notes): no existing precedent in this codebase for keying per-item edit state across
     * a variable-size batch, so the crop/rotate/flip UI is simply hidden ([canEditImage] forced false).
     *
     * Media optimization is also simplified relative to the single-item flow: instead of running one
     * live [MediaOptimizationSelectorPresenter] per item and broadcasting user choices to all of them,
     * a single presenter tracks the *focused* item (driving the shared toggle/preset UI and its size
     * estimate), and the resulting choice is applied uniformly to every item in the batch at send time.
     *
     * Unlike the single-item flow (which dismisses the screen once the one item is pre-processed, then
     * uploads in the background), batch pre-processing runs, with visible progress and retry-on-failure,
     * entirely on this screen; the screen only dismisses once every item has been pre-processed
     * successfully, and the uploads then proceed sequentially in the background.
     */
    @Composable
    private fun presentBatch(): AttachmentsPreviewState {
        val coroutineScope = rememberCoroutineScope()

        val sendActionState = remember { mutableStateOf<SendActionState>(SendActionState.Idle) }
        val attachmentsList = remember { mutableStateListOf<Attachment>().apply { addAll(attachments) } }
        var focusedIndex by remember { mutableStateOf(0) }
        var batchCursor by remember { mutableStateOf(0) }
        var sendProgress by remember { mutableStateOf<SendProgress?>(null) }
        val readyMediaList = remember { mutableStateListOf<MediaUploadInfo>() }
        val ongoingSendAttachmentJob = remember { mutableStateOf<Job?>(null) }

        val markdownTextEditorState = rememberMarkdownTextEditorState(initialText = null, initialFocus = false)
        val textEditorState by rememberUpdatedState(
            TextEditorState.Markdown(markdownTextEditorState, isRoomEncrypted = null)
        )

        val safeFocusedIndex = focusedIndex.coerceIn(0, (attachmentsList.size - 1).coerceAtLeast(0))
        val focusedAttachment = attachmentsList.getOrNull(safeFocusedIndex)
        val focusedMediaAttachment = focusedAttachment as? Attachment.Media

        val mediaOptimizationSelectorPresenter = focusedMediaAttachment?.let { media ->
            remember(media.localMedia.uri) {
                mediaOptimizationSelectorPresenterFactory.create(
                    localMedia = media.localMedia,
                    sendAsFile = media.sendAsFile,
                )
            }
        }
        val mediaOptimizationSelectorState = mediaOptimizationSelectorPresenter?.present() ?: emptyMediaOptimizationSelectorState()

        val maxUploadSize = mediaOptimizationSelectorState.maxUploadSize.dataOrNull()
        val videoSizeEstimations = mediaOptimizationSelectorState.videoSizeEstimations.dataOrNull()
        val displayFileTooLargeError = when {
            videoSizeEstimations != null -> videoSizeEstimations.none { it.canUpload }
            maxUploadSize != null && focusedMediaAttachment != null -> {
                val isImageFile = focusedMediaAttachment.localMedia.info.isImageAttachment()
                val isVideoFile = focusedMediaAttachment.localMedia.info.mimeType.isMimeTypeVideo()
                if (isImageFile || isVideoFile) {
                    false
                } else {
                    val fileSize = focusedMediaAttachment.localMedia.info.fileSize ?: 0L
                    maxUploadSize < fileSize
                }
            }
            else -> false
        }

        suspend fun preProcessBatch(caption: String?) {
            val total = attachmentsList.size
            val sharedConfig = if (mediaOptimizationSelectorState.displayMediaSelectorViews == true) {
                MediaOptimizationConfig(
                    compressImages = mediaOptimizationSelectorState.isImageOptimizationEnabled == true,
                    videoCompressionPreset = mediaOptimizationSelectorState.selectedVideoPreset ?: VideoCompressionPreset.STANDARD,
                )
            } else {
                null
            }
            for (index in batchCursor until total) {
                val mediaAttachment = attachmentsList[index] as? Attachment.Media ?: continue
                sendProgress = SendProgress(currentIndex = index, total = total)
                sendActionState.value = SendActionState.Sending.Processing(displayProgress = true)
                val config = sharedConfig ?: getAutoPreprocessMediaOptimizationConfig(
                    mediaAttachment = mediaAttachment,
                    videoSizeEstimations = AsyncData.Uninitialized,
                )
                val result = mediaSender.preProcessMedia(
                    uri = mediaAttachment.localMedia.uri,
                    mimeType = mediaAttachment.localMedia.info.mimeType,
                    mediaOptimizationConfig = config,
                )
                result.fold(
                    onSuccess = { mediaUploadInfo ->
                        readyMediaList.add(mediaUploadInfo)
                        batchCursor = index + 1
                    },
                    onFailure = { error ->
                        Timber.e(error, "Failed to pre-process media in batch")
                        if (error is CancellationException) throw error
                        sendActionState.value = SendActionState.Failure(error, null)
                    }
                )
                if (sendActionState.value is SendActionState.Failure) return
            }

            // The whole batch is pre-processed: dismiss the screen and upload in the background,
            // exactly like the single-item flow does once its one item is ready.
            if (currentCoroutineContext().isActive) {
                onDoneListener()
            }
            val mediaToUpload = readyMediaList.toList()
            sessionCoroutineScope.launch(dispatchers.io) {
                mediaToUpload.forEachIndexed { index, mediaUploadInfo ->
                    sendActionState.value = SendActionState.Sending.Uploading(mediaUploadInfo)
                    val itemCaption = caption.takeIf { index == mediaToUpload.lastIndex }
                    runCatchingExceptions {
                        mediaSender.sendPreProcessedMedia(
                            mediaUploadInfo = mediaUploadInfo,
                            caption = itemCaption,
                            formattedCaption = null,
                            inReplyToEventId = inReplyToEventId,
                        ).getOrThrow()
                    }.onFailure { error ->
                        if (error is CancellationException) throw error
                        Timber.e(error, "Failed to send attachment in batch")
                    }
                    // Whether it succeeded or failed, this screen is gone and there's no retry UI left
                    // for it here (a failed send surfaces as a normal failed-event row in the room
                    // timeline instead), so always release this item's temp files.
                    cleanUp(mediaUploadInfo)
                }
                // Backstop sweep of the whole pre-processing cache, mirroring the single-item flow.
                mediaSender.cleanUp()
                sendActionState.value = SendActionState.Done
            }
        }

        fun handleEvent(event: AttachmentsPreviewEvent) {
            when (event) {
                AttachmentsPreviewEvent.SendAttachment -> {
                    val caption = markdownTextEditorState.getMessageMarkdown(permalinkBuilder)
                        .takeIf { it.isNotEmpty() }
                    ongoingSendAttachmentJob.value = coroutineScope.launch {
                        preProcessBatch(caption)
                    }
                }
                AttachmentsPreviewEvent.CancelAndDismiss -> {
                    ongoingSendAttachmentJob.value?.cancel()
                    ongoingSendAttachmentJob.value = null
                    sendProgress = null
                    // Nothing has been uploaded yet at this point (dismissal only happens post-preprocess),
                    // so it's safe to sweep away every pending pre-processed file for the whole batch.
                    mediaSender.cleanUp()
                    attachmentsList.forEach { (it as? Attachment.Media)?.let { media -> temporaryUriDeleter.delete(media.localMedia.uri) } }
                    sendActionState.value = SendActionState.Done
                    onDoneListener()
                }
                AttachmentsPreviewEvent.CancelAndClearSendState -> {
                    ongoingSendAttachmentJob.value?.let {
                        it.cancel()
                        ongoingSendAttachmentJob.value = null
                    }
                    sendActionState.value = SendActionState.Idle
                }
                is AttachmentsPreviewEvent.RemoveAttachment -> {
                    if (sendActionState.value == SendActionState.Idle && event.index in attachmentsList.indices) {
                        val removed = attachmentsList.removeAt(event.index)
                        (removed as? Attachment.Media)?.let { temporaryUriDeleter.delete(it.localMedia.uri) }
                        if (attachmentsList.isEmpty()) {
                            onDoneListener()
                        } else {
                            focusedIndex = focusedIndex.coerceIn(0, attachmentsList.lastIndex)
                            if (batchCursor > attachmentsList.size) {
                                batchCursor = attachmentsList.size
                            }
                        }
                    }
                }
                is AttachmentsPreviewEvent.FocusAttachment -> {
                    if (event.index in attachmentsList.indices) {
                        focusedIndex = event.index
                    }
                }
                AttachmentsPreviewEvent.OpenImageEditor,
                AttachmentsPreviewEvent.CloseImageEditor,
                is AttachmentsPreviewEvent.UpdateImageCropRect,
                AttachmentsPreviewEvent.RotateImageToTheLeft,
                AttachmentsPreviewEvent.FlipImageHorizontally,
                AttachmentsPreviewEvent.FlipImageVertically,
                AttachmentsPreviewEvent.ResetImageEdits,
                AttachmentsPreviewEvent.ApplyImageEdits,
                AttachmentsPreviewEvent.ClearImageEditError -> {
                    // Editing isn't supported for multi-attachment batches.
                }
            }
        }

        return AttachmentsPreviewState(
            attachment = focusedAttachment ?: attachments.first(),
            attachments = attachmentsList.toImmutableList(),
            focusedIndex = safeFocusedIndex,
            imageEditorState = null,
            canEditImage = false,
            isApplyingImageEdits = false,
            displayImageEditError = false,
            sendActionState = sendActionState.value,
            sendProgress = sendProgress,
            textEditorState = textEditorState,
            mediaOptimizationSelectorState = mediaOptimizationSelectorState,
            displayFileTooLargeError = displayFileTooLargeError,
            eventSink = ::handleEvent,
        )
    }

    private suspend fun getAutoPreprocessMediaOptimizationConfig(
        mediaAttachment: Attachment.Media,
        videoSizeEstimations: AsyncData<ImmutableList<VideoUploadEstimation>>,
    ): MediaOptimizationConfig {
        return if (mediaAttachment.sendAsFile) {
            // If we're sending the media as a file, we can skip image compression and we should select the highest video compression preset that still fits
            // the upload limit (if the estimations are available)
            val videoCompressionPreset = videoCompressionPresetSelector.selectBestVideoPreset(
                expectedVideoPreset = VideoCompressionPreset.HIGH,
                videoSizeEstimations = videoSizeEstimations,
            ).dataOrNull() ?: VideoCompressionPreset.HIGH

            MediaOptimizationConfig(
                compressImages = false,
                videoCompressionPreset = videoCompressionPreset,
            )
        } else {
            // Otherwise, we just rely on the user preferences for media optimization
            mediaOptimizationConfigProvider.get()
        }
    }

    private fun CoroutineScope.preProcessAttachment(
        attachment: Attachment,
        mediaOptimizationConfig: MediaOptimizationConfig,
        displayProgress: Boolean,
        sendActionState: MutableState<SendActionState>,
    ) = launch(dispatchers.io) {
        when (attachment) {
            is Attachment.Media -> {
                preProcessMedia(
                    mediaAttachment = attachment,
                    mediaOptimizationConfig = mediaOptimizationConfig,
                    displayProgress = displayProgress,
                    sendActionState = sendActionState,
                )
            }
        }
    }

    private suspend fun preProcessMedia(
        mediaAttachment: Attachment.Media,
        mediaOptimizationConfig: MediaOptimizationConfig,
        displayProgress: Boolean,
        sendActionState: MutableState<SendActionState>,
    ) {
        sendActionState.value = SendActionState.Sending.Processing(displayProgress = displayProgress)
        mediaSender.preProcessMedia(
            uri = mediaAttachment.localMedia.uri,
            mimeType = mediaAttachment.localMedia.info.mimeType,
            mediaOptimizationConfig = mediaOptimizationConfig,
        ).fold(
            onSuccess = { mediaUploadInfo ->
                Timber.d("Media ${mediaUploadInfo.file.path.orEmpty().hash()} finished processing, it's now ready to upload")
                sendActionState.value = SendActionState.Sending.ReadyToUpload(mediaUploadInfo)
            },
            onFailure = {
                Timber.e(it, "Failed to pre-process media")
                if (it is CancellationException) {
                    throw it
                } else {
                    sendActionState.value = SendActionState.Failure(it, null)
                }
            }
        )
    }

    private fun dismissSingle(
        attachment: Attachment,
        sendActionState: MutableState<SendActionState>,
        editedTempFile: File?,
    ) {
        // Delete the temporary file
        when (attachment) {
            is Attachment.Media -> {
                temporaryUriDeleter.delete(attachment.localMedia.uri)
                sendActionState.value.mediaUploadInfo()?.let { data ->
                    cleanUp(data)
                }
            }
        }
        editedTempFile?.safeDelete()
        // Reset the sendActionState to ensure that dialog is closed before the screen
        sendActionState.value = SendActionState.Done
        onDoneListener()
    }

    private fun cleanUp(
        mediaUploadInfo: MediaUploadInfo,
    ) {
        mediaUploadInfo.allFiles().forEach { file ->
            file.safeDelete()
        }
    }

    private fun resetPreparedMedia(sendActionState: MutableState<SendActionState>) {
        sendActionState.value.mediaUploadInfo()?.let(::cleanUp)
        mediaSender.cleanUp()
        sendActionState.value = SendActionState.Idle
    }

    private suspend fun sendPreProcessedMedia(
        mediaUploadInfo: MediaUploadInfo,
        caption: String?,
        sendActionState: MutableState<SendActionState>,
        dismissAfterSend: Boolean,
        inReplyToEventId: EventId?,
    ) = runCatchingExceptions {
        sendActionState.value = SendActionState.Sending.Uploading(mediaUploadInfo)
        mediaSender.sendPreProcessedMedia(
            mediaUploadInfo = mediaUploadInfo,
            caption = caption,
            formattedCaption = null,
            inReplyToEventId = inReplyToEventId,
        ).getOrThrow()
    }.fold(
        onSuccess = {
            cleanUp(mediaUploadInfo)
            // Reset the sendActionState to ensure that dialog is closed before the screen
            sendActionState.value = SendActionState.Done

            if (dismissAfterSend) {
                onDoneListener()
            }
        },
        onFailure = { error ->
            Timber.e(error, "Failed to send attachment")
            if (error is CancellationException) {
                throw error
            } else {
                sendActionState.value = SendActionState.Failure(error, mediaUploadInfo)
            }
        }
    )
}

private fun emptyMediaOptimizationSelectorState() = MediaOptimizationSelectorState(
    maxUploadSize = AsyncData.Uninitialized,
    videoSizeEstimations = AsyncData.Uninitialized,
    isImageOptimizationEnabled = null,
    selectedVideoPreset = null,
    displayMediaSelectorViews = false,
    displayVideoPresetSelectorDialog = false,
    eventSink = {},
)
