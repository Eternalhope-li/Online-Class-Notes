package com.lecture.notes.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.graphics.Matrix
import android.os.Bundle
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.FileProvider
import androidx.lifecycle.lifecycleScope
import com.lecture.notes.R
import com.lecture.notes.data.Entry
import com.lecture.notes.data.Note
import com.lecture.notes.data.NoteStore
import com.lecture.notes.databinding.ActivityShotBinding
import com.lecture.notes.net.LlmDigest
import com.lecture.notes.util.Formats
import com.lecture.notes.util.ImageUtil
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 看大图。
 *
 * 从笔记列表里点一张截图进来：双指缩放、拖动、双击复位，菜谱里可以
 * 让 AI 重新分析、编辑说明、复制、分享、删除。
 */
class ShotActivity : AppCompatActivity() {

    private lateinit var binding: ActivityShotBinding
    private var note: Note? = null
    private var entry: Entry? = null
    private var rel: String = ""
    private var atMs: Long = 0L
    private var busy = false

    private val matrix = Matrix()
    private var scaleFactor = 1f
    private var baseScale = 1f
    private var lastX = 0f
    private var lastY = 0f
    private var dragging = false

    private val scaleDetector by lazy {
        ScaleGestureDetector(this, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
            override fun onScale(detector: ScaleGestureDetector): Boolean {
                val f = detector.scaleFactor.coerceIn(0.5f, 2f)
                val next = (scaleFactor * f).coerceIn(1f, 8f)
                val real = next / scaleFactor
                scaleFactor = next
                matrix.postScale(real, real, detector.focusX, detector.focusY)
                clamp()
                binding.image.imageMatrix = matrix
                return true
            }
        })
    }

    private val tapDetector by lazy {
        GestureDetector(this, object : GestureDetector.SimpleOnGestureListener() {
            override fun onDoubleTap(e: MotionEvent): Boolean {
                if (scaleFactor > 1.2f) {
                    fit()
                } else {
                    scaleFactor = 2.5f
                    matrix.postScale(2.5f, 2.5f, e.x, e.y)
                    clamp()
                    binding.image.imageMatrix = matrix
                }
                return true
            }
        })
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityShotBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.toolbar.setNavigationOnClickListener { finish() }
        binding.toolbar.setOnMenuItemClickListener { handleMenu(it.itemId) }
        // 这一页是黑底，溢出菜单的图标得跟着变白
        binding.toolbar.overflowIcon?.setTint(android.graphics.Color.WHITE)

        rel = intent.getStringExtra(EXTRA_REL).orEmpty()
        atMs = intent.getLongExtra(EXTRA_AT, 0L)
        val id = intent.getStringExtra(EXTRA_ID).orEmpty()

        val file = NoteStore.shotFile(id, rel)
        if (!file.exists()) {
            toast(getString(R.string.shot_missing))
            finish()
            return
        }
        val bitmap = ImageUtil.decode(file, 2048)
        if (bitmap == null) {
            toast(getString(R.string.shot_missing))
            finish()
            return
        }
        binding.image.setImageBitmap(bitmap)
        binding.toolbar.title = getString(R.string.shot_title) + " · " + Formats.mmss(atMs)
        binding.image.post { fit() }
        binding.image.setOnTouchListener { _, e -> onImageTouch(e) }

        lifecycleScope.launch {
            val n = withContext(Dispatchers.IO) { NoteStore.load(id) }
            if (n == null) {
                finish()
                return@launch
            }
            note = n
            entry = n.entries.firstOrNull { it.image == rel && it.atMs == atMs }
            showCaption(entry?.caption.orEmpty())
        }
    }

    override fun onPrepareOptionsMenu(menu: android.view.Menu): Boolean {
        val hasCaption = !entry?.caption.isNullOrBlank()
        menu.findItem(R.id.action_shot_analyze)?.apply {
            isEnabled = !busy
            title = getString(
                when {
                    busy -> R.string.entry_shot_analyzing
                    hasCaption -> R.string.entry_shot_reanalyze
                    else -> R.string.shot_menu_analyze
                }
            )
        }
        return super.onPrepareOptionsMenu(menu)
    }

    // ------------------------------------------------------------ 手势

    private fun onImageTouch(e: MotionEvent): Boolean {
        scaleDetector.onTouchEvent(e)
        tapDetector.onTouchEvent(e)
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                lastX = e.x
                lastY = e.y
                dragging = true
            }

            MotionEvent.ACTION_MOVE -> {
                if (dragging && !scaleDetector.isInProgress) {
                    val dx = e.x - lastX
                    val dy = e.y - lastY
                    lastX = e.x
                    lastY = e.y
                    matrix.postTranslate(dx, dy)
                    clamp()
                    binding.image.imageMatrix = matrix
                }
            }

            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> dragging = false
        }
        return true
    }

    private fun fit() {
        val d = binding.image.drawable ?: return
        val vw = binding.image.width.toFloat()
        val vh = binding.image.height.toFloat()
        val dw = d.intrinsicWidth.toFloat()
        val dh = d.intrinsicHeight.toFloat()
        if (vw <= 0f || vh <= 0f || dw <= 0f || dh <= 0f) return
        val s = minOf(vw / dw, vh / dh)
        baseScale = s
        scaleFactor = 1f
        matrix.reset()
        matrix.postScale(s, s)
        matrix.postTranslate((vw - dw * s) / 2f, (vh - dh * s) / 2f)
        binding.image.imageMatrix = matrix
    }

    /** 别让图被拖出屏幕。 */
    private fun clamp() {
        val d = binding.image.drawable ?: return
        val v = FloatArray(9)
        matrix.getValues(v)
        val sx = v[Matrix.MSCALE_X]
        val sy = v[Matrix.MSCALE_Y]
        val tx = v[Matrix.MTRANS_X]
        val ty = v[Matrix.MTRANS_Y]
        val w = d.intrinsicWidth * sx
        val h = d.intrinsicHeight * sy
        val vw = binding.image.width.toFloat()
        val vh = binding.image.height.toFloat()
        val ntx = if (w <= vw) (vw - w) / 2f else tx.coerceIn(vw - w, 0f)
        val nty = if (h <= vh) (vh - h) / 2f else ty.coerceIn(vh - h, 0f)
        matrix.postTranslate(ntx - tx, nty - ty)
    }

    // ------------------------------------------------------------ 菜单

    private fun handleMenu(id: Int): Boolean {
        when (id) {
            R.id.action_shot_analyze -> analyze()
            R.id.action_shot_edit -> editCaption()
            R.id.action_shot_copy -> {
                val text = tidyShotCaption(entry?.caption.orEmpty())
                if (text.isBlank()) {
                    toast(getString(R.string.entry_shot_no_caption))
                } else {
                    getSystemService(ClipboardManager::class.java)
                        .setPrimaryClip(ClipData.newPlainText(getString(R.string.shot_title), text))
                    toast(getString(R.string.shot_copied))
                }
            }

            R.id.action_shot_share -> shareImage()
            R.id.action_shot_delete -> confirmDelete()
            else -> return false
        }
        return true
    }

    private fun analyze() {
        val n = note ?: return
        val e = entry ?: return
        if (busy) return
        if (!LlmDigest.isReady()) {
            toast(getString(R.string.digest_ai_need_key))
            return
        }
        busy = true
        invalidateOptionsMenu()
        showCaption(getString(R.string.entry_shot_analyzing))
        lifecycleScope.launch {
            var err: String? = null
            val caption = withContext(Dispatchers.IO) {
                try {
                    val b64 = ImageUtil.visionBase64(NoteStore.shotFile(n.id, rel))
                        ?: throw IllegalStateException(getString(R.string.shot_missing))
                    LlmDigest.analyzeImage(b64, n.title)
                } catch (t: Throwable) {
                    err = t.message ?: t.javaClass.simpleName
                    null
                }
            }
            busy = false
            if (caption.isNullOrBlank()) {
                toast(getString(R.string.shot_analyze_failed, err ?: "未知错误"))
                showCaption(entry?.caption.orEmpty())
            } else {
                NoteStore.setImageCaption(n.id, e.atMs, rel, caption, true)
                entry = entry?.copy(caption = caption, analyzed = true)
                showCaption(caption)
                toast(getString(R.string.shot_analyzed))
            }
            invalidateOptionsMenu()
        }
    }

    private fun editCaption() {
        val n = note ?: return
        val e = entry ?: return
        val input = EditText(this).apply {
            setText(e.caption)
            setSelection(text.length)
            gravity = android.view.Gravity.TOP
            inputType = android.text.InputType.TYPE_CLASS_TEXT or
                    android.text.InputType.TYPE_TEXT_FLAG_MULTI_LINE
            minLines = 6
        }
        val pad = (18 * resources.displayMetrics.density).toInt()
        val box = FrameLayout(this)
        box.addView(
            input,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.WRAP_CONTENT
            ).apply { setMargins(pad, pad / 2, pad, 0) }
        )
        AlertDialog.Builder(this)
            .setTitle(R.string.shot_menu_edit)
            .setView(box)
            .setPositiveButton(R.string.common_ok) { _, _ ->
                val text = input.text.toString().trim()
                NoteStore.setImageCaption(n.id, e.atMs, rel, text, text.isNotBlank())
                entry = entry?.copy(caption = text, analyzed = text.isNotBlank())
                showCaption(text)
                toast(getString(R.string.shot_caption_saved))
            }
            .setNegativeButton(R.string.common_cancel, null)
            .show()
    }

    private fun shareImage() {
        val n = note ?: return
        val f = NoteStore.shotFile(n.id, rel)
        if (!f.exists()) {
            toast(getString(R.string.shot_missing))
            return
        }
        try {
            val uri = FileProvider.getUriForFile(this, "$packageName.files", f)
            val i = Intent(Intent.ACTION_SEND).setType("image/jpeg")
                .putExtra(Intent.EXTRA_STREAM, uri)
                .putExtra(Intent.EXTRA_TEXT, tidyShotCaption(entry?.caption.orEmpty()))
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            startActivity(Intent.createChooser(i, getString(R.string.shot_menu_share)))
        } catch (t: Throwable) {
            toast(getString(R.string.shot_analyze_failed, t.message ?: "未知错误"))
        }
    }

    private fun confirmDelete() {
        val n = note ?: return
        AlertDialog.Builder(this)
            .setMessage(R.string.shot_delete_msg)
            .setPositiveButton(R.string.common_ok) { _, _ ->
                lifecycleScope.launch {
                    withContext(Dispatchers.IO) { NoteStore.removeImage(n.id, atMs, rel) }
                    toast(getString(R.string.shot_deleted))
                    finish()
                }
            }
            .setNegativeButton(R.string.common_cancel, null)
            .show()
    }

    private fun showCaption(text: String) {
        val tidy = tidyShotCaption(text)
        if (tidy.isBlank()) {
            binding.caption.visibility = View.GONE
        } else {
            binding.caption.visibility = View.VISIBLE
            binding.caption.text = tidy
        }
    }

    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()

    companion object {
        const val EXTRA_ID = "note_id"
        const val EXTRA_REL = "shot_rel"
        const val EXTRA_AT = "shot_at"
    }
}
