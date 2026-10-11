package cn.xiao.xyaax.importing

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.util.Log
import com.google.android.gms.tasks.Task
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.chinese.ChineseTextRecognizerOptions
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.text.PDFTextStripper
import com.tom_roush.pdfbox.text.TextPosition
import kotlinx.coroutines.suspendCancellableCoroutine
import java.io.InputStream
import java.util.zip.ZipInputStream
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** 文件解析结果：统一成文本块 + 原文，供 CourseTableImporter 使用。 */
data class ExtractedDocument(
    val blocks: List<TextBlock>,
    val rawText: String,
    val note: String
)

/**
 * 文档提取：PDF 优先取文本层（带坐标），无文本层回退渲染 + 离线 OCR；
 * 图片走离线 OCR；txt/md 按行；csv/tsv 按分隔符；docx/xlsx 直接解析 OOXML。
 */
object DocumentTextExtractor {
    private const val TAG = "DocumentTextExtractor"
    private const val MIN_PDF_TEXT_LENGTH = 40
    private const val OCR_MAX_SIDE = 2400

    suspend fun extract(context: Context, uri: Uri, displayName: String, mimeType: String?): ExtractedDocument {
        val name = displayName.lowercase()
        val mime = mimeType.orEmpty().lowercase()

        return when {
            mime.startsWith("image/") || name.endsWith(".png") || name.endsWith(".jpg") ||
                name.endsWith(".jpeg") || name.endsWith(".webp") -> extractImage(context, uri)

            mime == "application/pdf" || name.endsWith(".pdf") -> extractPdf(context, uri)

            name.endsWith(".docx") -> extractDocx(context, uri)
            name.endsWith(".xlsx") -> extractXlsx(context, uri)
            name.endsWith(".csv") || mime == "text/csv" -> extractDelimited(context, uri, ',')
            name.endsWith(".tsv") || mime.contains("tab-separated") -> extractDelimited(context, uri, '\t')
            name.endsWith(".html") || name.endsWith(".htm") || mime == "text/html" -> extractHtml(context, uri)
            name.endsWith(".doc") || name.endsWith(".xls") -> throw IllegalArgumentException("暂不支持 doc/xls 老格式，请另存为 docx/xlsx 后重试")

            else -> extractPlainText(context, uri)
        }
    }

    // ---------- 纯文本 / 分隔符 ----------

    private fun readText(context: Context, uri: Uri): String =
        context.contentResolver.openInputStream(uri)?.use { it.readBytes().toString(Charsets.UTF_8) }
            ?: throw IllegalStateException("无法读取文件")

    private fun extractPlainText(context: Context, uri: Uri): ExtractedDocument {
        val text = readText(context, uri)
        return ExtractedDocument(PlainTextLayout.toBlocks(text), text, "文本文件")
    }

    private fun extractDelimited(context: Context, uri: Uri, delimiter: Char): ExtractedDocument {
        val text = readText(context, uri)
        val rows = text.lines().filter { it.isNotBlank() }.map { it.split(delimiter).map { cell -> cell.trim() } }
        val blocks = rows.flatMapIndexed { rowIndex, row ->
            row.mapIndexedNotNull { columnIndex, cell ->
                cell.takeIf { it.isNotEmpty() }?.let { TextBlock(it, columnIndex * 300f, rowIndex * 40f, 280f, 36f, columnIndex, rowIndex) }
            }
        }
        return ExtractedDocument(blocks, text, "表格文件(${rows.size} 行)")
    }

    // ---------- HTML ----------

    private fun extractHtml(context: Context, uri: Uri): ExtractedDocument {
        val text = readText(context, uri)
        val grids = HtmlTableParser.parseTables(text)
        if (grids.isEmpty()) {
            // 无 <table>：退回纯文本行解析
            return ExtractedDocument(PlainTextLayout.toBlocks(text), text, "HTML（无表格，按文本解析）")
        }
        val blocks = grids.flatMap { HtmlTableParser.gridToBlocks(it) }
        return ExtractedDocument(blocks, text, "HTML 表格（${grids.size} 个表）")
    }

    // ---------- 图片 OCR ----------

    private suspend fun extractImage(context: Context, uri: Uri): ExtractedDocument {
        val bitmap = context.contentResolver.openInputStream(uri)?.use { stream ->
            android.graphics.BitmapFactory.decodeStream(stream)
        } ?: throw IllegalStateException("图片读取失败")
        val blocks = recognize(bitmap)
        return ExtractedDocument(blocks, blocks.joinToString("\n") { it.text }, "图片 OCR（离线识别）")
    }

    // ---------- PDF ----------

    private suspend fun extractPdf(context: Context, uri: Uri): ExtractedDocument {
        val textBlocks = runCatching { pdfTextBlocks(context, uri) }
            .onFailure { Log.w(TAG, "pdf text layer failed", it) }
            .getOrDefault(emptyList())
        val textLength = textBlocks.sumOf { it.text.length }
        if (textLength >= MIN_PDF_TEXT_LENGTH) {
            return ExtractedDocument(
                blocks = textBlocks,
                rawText = textBlocks.joinToString("\n") { it.text },
                note = "PDF 文本层（${textBlocks.size} 段）"
            )
        }

        val bitmapBlocks = runCatching { pdfOcr(context, uri) }
            .onFailure { Log.w(TAG, "pdf ocr failed", it) }
            .getOrDefault(emptyList())
        return ExtractedDocument(
            blocks = bitmapBlocks,
            rawText = bitmapBlocks.joinToString("\n") { it.text },
            note = "PDF 无文本层，已用离线 OCR 识别（${bitmapBlocks.size} 段）"
        )
    }

    private fun pdfTextBlocks(context: Context, uri: Uri): List<TextBlock> {
        PDFBoxResourceLoader.init(context.applicationContext)
        val blocks = mutableListOf<TextBlock>()
        context.contentResolver.openInputStream(uri)?.use { stream ->
            PDDocument.load(stream).use { document ->
                val stripper = object : PDFTextStripper() {
                    override fun writeString(text: String, textPositions: List<TextPosition>) {
                        val first = textPositions.firstOrNull() ?: return
                        val last = textPositions.lastOrNull() ?: first
                        blocks += TextBlock(
                            text = text.trim(),
                            x = first.xDirAdj,
                            y = first.yDirAdj,
                            width = (last.xDirAdj + last.widthDirAdj) - first.xDirAdj,
                            height = first.heightDir
                        )
                    }
                }
                stripper.sortByPosition = true
                stripper.startPage = 1
                stripper.endPage = document.numberOfPages
                stripper.getText(document)
            }
        }
        return blocks.filter { it.text.isNotBlank() }
    }

    private suspend fun pdfOcr(context: Context, uri: Uri): List<TextBlock> {
        val result = mutableListOf<TextBlock>()
        val descriptor = context.contentResolver.openFileDescriptor(uri, "r") ?: return result
        ParcelFileDescriptor.AutoCloseInputStream(descriptor).use { stream ->
            val bytes = stream.readBytes()
            val tmp = java.io.File.createTempFile("import-", ".pdf", context.cacheDir)
            tmp.writeBytes(bytes)
            runCatching {
                PdfRenderer(ParcelFileDescriptor.open(tmp, ParcelFileDescriptor.MODE_READ_ONLY)).use { renderer ->
                    for (index in 0 until renderer.pageCount) {
                        renderer.openPage(index).use { page ->
                            val scale = (OCR_MAX_SIDE.toFloat() / maxOf(page.width, page.height)).coerceAtLeast(1f)
                            val bitmap = Bitmap.createBitmap(
                                (page.width * scale).toInt().coerceAtLeast(1),
                                (page.height * scale).toInt().coerceAtLeast(1),
                                Bitmap.Config.ARGB_8888
                            )
                            bitmap.eraseColor(Color.WHITE)
                            page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                            result += recognize(bitmap)
                            bitmap.recycle()
                        }
                    }
                }
            }.also { tmp.delete() }
        }
        return result
    }

    private suspend fun recognize(bitmap: Bitmap): List<TextBlock> {
        val recognizer = TextRecognition.getClient(ChineseTextRecognizerOptions.Builder().build())
        val image = InputImage.fromBitmap(bitmap, 0)
        val text = runCatching { recognizer.process(image).await() }.getOrElse {
            recognizer.close()
            throw it
        }
        recognizer.close()
        return text.textBlocks.flatMap { block ->
            block.lines.map { line ->
                val box = line.boundingBox
                TextBlock(
                    text = line.text.trim(),
                    x = box?.left?.toFloat() ?: 0f,
                    y = box?.top?.toFloat() ?: 0f,
                    width = box?.width()?.toFloat() ?: 0f,
                    height = box?.height()?.toFloat() ?: 0f
                )
            }
        }.filter { it.text.isNotBlank() }
    }

    private suspend fun <T> Task<T>.await(): T = suspendCancellableCoroutine { continuation ->
        addOnSuccessListener { value -> if (continuation.isActive) continuation.resume(value) }
        addOnFailureListener { error -> if (continuation.isActive) continuation.resumeWithException(error) }
        addOnCanceledListener { if (continuation.isActive) continuation.cancel() }
    }

    // ---------- docx ----------

    private fun extractDocx(context: Context, uri: Uri): ExtractedDocument {
        val xml = readZipEntry(context, uri, "word/document.xml") ?: throw IllegalStateException("docx 内容缺失")
        val blocks = OoxmlParser.parseDocx(xml)
        return ExtractedDocument(blocks, blocks.joinToString("\n") { it.text }, "Word 文档（${blocks.size} 段）")
    }

    // ---------- xlsx ----------

    private fun extractXlsx(context: Context, uri: Uri): ExtractedDocument {
        val shared = readZipEntry(context, uri, "xl/sharedStrings.xml")?.let { OoxmlParser.parseSharedStrings(it) }.orEmpty()
        val sheet = readZipEntry(context, uri, "xl/worksheets/sheet1.xml")
            ?: throw IllegalStateException("xlsx 缺少工作表")
        val blocks = OoxmlParser.parseSheet(sheet, shared)
        return ExtractedDocument(blocks, blocks.joinToString("\n") { it.text }, "Excel 表格（${blocks.size} 个单元格）")
    }

    private fun readZipEntry(context: Context, uri: Uri, entryName: String): String? {
        context.contentResolver.openInputStream(uri)?.use { raw: InputStream ->
            ZipInputStream(raw).use { zip ->
                var entry = zip.nextEntry
                while (entry != null) {
                    if (entry.name.equals(entryName, ignoreCase = true)) {
                        return zip.readBytes().toString(Charsets.UTF_8)
                    }
                    entry = zip.nextEntry
                }
            }
        }
        return null
    }
}
