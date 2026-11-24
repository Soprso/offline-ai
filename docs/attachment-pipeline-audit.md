# Attachment Pipeline Audit

## Scope
Audited: `ChatScreen.kt`, `ChatViewModel.kt`, `AttachmentComposer.kt`, `AttachmentViewModel.kt`, `DocumentProcessor.kt`, `DocumentRouter.kt`, `DocumentStorage.kt`, extractors, vision classes, `ContextBuilder.kt`, `DocumentChunker.kt`, `DocumentAttachmentEntity/Dao`, `AppDatabase`, `ModelEngine`, `NativeBridge`, build files.

## Lifecycle Trace

| Stage | Status | Evidence / Gap |
|---|---|---|
| File selection | Partial | SAF `OpenDocument` + Photo Picker exist. MIME filter is broad but not all supported types guaranteed; no size/type validation before copy. |
| Content URI | Partial | URI persisted in entity; `takePersistableUriPermission` attempted in ChatScreen and DocumentStorage but not consistently for all paths. |
| Local file copy | Partial | `DocumentStorage.copyUriToLocal` copies docs to `files/documents/originals`; images to `files/images/originals`. Uses temp+rename. No size cap, no free-space check, no cancellation. |
| Attachment metadata | Partial | Room entity exists (v5). `AttachmentViewModel.addAttachments` inserts after full processing - no intermediate "Saved locally/Processing" states visible in DB. |
| Document extraction | Broken/Incomplete | PDF extractor OCRs every page via PdfRenderer render; no selectable-text extraction path, no mixed PDF handling, no page-by-page persistence. DOCX/PPTX use DOM parsing (full XML in memory), no ZIP bomb guard, no embedded image extraction (count only), rough table/list handling. CSV split by comma, no quoted-field handling. |
| OCR/image processing | Broken | `OcrProcessor` uses ML Kit Play Services unbundled model (`play-services-mlkit-text-recognition`). `isAvailable` only checks client creation, not model readiness. Failures return empty string - cannot distinguish no-text vs model-missing. No confidence/blocks/lines returned. No explicit offline-model-unavailable state. |
| Context construction | Broken | `ContextBuilder` fixed 2048-token budget, no model-context awareness, no relevance selection, no follow-up retrieval over conversation attachments. `AttachmentViewModel.buildContextFromAttachments` only uses `extractedText`/`ocrText`, ignores page structure/image refs. |
| llama.cpp prompt | Broken | `ChatViewModel.generateReply` rebuilds history from DB (which already includes current user message) then appends user message again - duplicate user turn. Document context prepended only for current message attachments; follow-ups lose prior document context. |
| AI response | Partial | ModelEngine/NativeBridge streaming path looks functional; no evidence of document-grounded response on device. |
| Conversation persistence | Partial | Messages persisted; attachments linked via `messageId`. `MessageAttachmentChip` opens `Uri.fromFile(File(path))` which breaks for relative image paths. No thumbnails in chat history, no processing status, no retry. Conversation delete cleans files with ref counting - good. |

## Key Root Causes

1. **No end-to-end attachment state machine**: processing is synchronous inside `addAttachments`; DB gets final state only. UI cannot show processing/OCR/failed/partial states reliably.
2. **Duplicate user message in prompt**: `sendInternal` inserts user message, `generateReply` loads history and appends same user content again.
3. **Document context not conversation-scoped**: only current message attachments are used; follow-up questions cannot access earlier attachments.
4. **PDF extraction is OCR-only**: no selectable text extraction. Task requires selectable-text path, scanned path, mixed handling, page refs. PdfRenderer cannot extract text.
5. **OCR stub-like behavior**: ML Kit unbundled model may be unavailable offline; failures collapse to empty text; no state distinction.
6. **Attachment UI incomplete**: composer preview has no image thumbnail/filename resolution; message chips have no thumbnail/status/retry; relative image paths not resolved.
7. **Extraction quality gaps**: CSV naive split; DOCX/PPTX DOM parsing with no ZIP bomb/size guards; no embedded image extraction; no page/slide structured metadata preserved for context.
8. **Context builder not budget-aware/retrieval-aware**: fixed 2048 token cap, no model context size, no chunk selection by query.
9. **Missing tests/fixtures**: no document extraction/OCR/context tests.

## Files Requiring Change (planned)

- `ChatViewModel.kt` - fix prompt duplication, conversation-scoped doc context, status/error propagation.
- `AttachmentViewModel.kt` - observable processing states, DB updates during processing, retry, conversation/message attachment queries, context building.
- `DocumentProcessor.kt` - staged processing, status persistence callbacks, OCR availability handling.
- `DocumentStorage.kt` - path resolution helpers, safe cleanup, size checks.
- `PdfExtractor.kt` - selectable text path (library evaluation), scanned/mixed handling, page-aware output.
- `DocxExtractor.kt`, `PptxExtractor.kt` - bounded/safer parsing, embedded image extraction, structure.
- `CsvExtractor.kt` - proper CSV parsing.
- `TextExtractor.kt` - encoding fallback.
- `OcrProcessor.kt` - real result model, model-unavailable/error distinction.
- `ImageExtractor.kt`/`ImagePreprocessor.kt` - reuse for OCR/page rendering.
- `ContextBuilder.kt`/`DocumentChunker.kt` - structured, budget-aware, query-aware retrieval.
- `ChatScreen.kt`/`AttachmentComposer.kt` - previews, message attachment rendering, status/retry, open image.
- `DocumentAttachmentEntity/Dao/Metadata` - status/error fields as needed.
- `build.gradle.kts/libs.versions.toml` - dependencies only if justified.
- Tests/fixtures under `app/src/test` and/or `app/src/androidTest`.
