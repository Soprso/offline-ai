package com.soprso.dhurandharai.document

import android.net.Uri
import com.soprso.dhurandharai.document.extractors.*

/** Function type for document extractors. */
typealias DocumentExtractFn = suspend (Uri?, java.io.File?, DocumentAttachment) -> DocumentAttachment

/** Routes attachments to the correct extractor based on MIME type. */
class DocumentRouter(
    private val textExtractor: TextExtractor,
    private val csvExtractor: CsvExtractor,
    private val pdfExtractor: PdfExtractor,
    private val docxExtractor: DocxExtractor,
    private val pptxExtractor: PptxExtractor,
) {

    fun route(mimeType: String): DocumentExtractFn? {
        return when (AttachmentMime.category(mimeType)) {
            AttachmentCategory.Text -> textExtractor::extract
            AttachmentCategory.Spreadsheet -> csvExtractor::extract
            AttachmentCategory.Document -> when (mimeType.lowercase()) {
                AttachmentMime.PDF -> pdfExtractor::extract
                AttachmentMime.DOCX -> docxExtractor::extract
                AttachmentMime.PPTX -> pptxExtractor::extract
                else -> null
            }
            AttachmentCategory.Image -> null // handled by ImageExtractor
            else -> null
        }
    }
}
