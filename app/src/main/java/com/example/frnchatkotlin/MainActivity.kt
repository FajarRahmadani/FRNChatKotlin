package com.example.frnchatkotlin

import android.app.Dialog
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.graphics.Outline
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.media.ExifInterface
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.ViewOutlineProvider
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.MediaController
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import android.widget.VideoView
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import java.io.BufferedReader
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.io.PrintWriter
import java.net.Socket
import java.security.SecureRandom
import java.text.SimpleDateFormat
import java.util.Base64
import java.util.Date
import java.util.Locale
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.regex.Pattern
import javax.crypto.Cipher
import javax.crypto.SecretKey
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

// Layar 2: chat lokal lewat socket TCP dengan enkripsi AES-GCM, bisa kirim foto & video.
class MainActivity : AppCompatActivity() {

    private lateinit var serverIp: EditText
    private lateinit var username: EditText
    private lateinit var input: EditText
    private lateinit var btnConnect: Button
    private lateinit var btnSend: Button
    private lateinit var btnAttach: Button
    private lateinit var messages: LinearLayout
    private lateinit var scroll: ScrollView

    private var socket: Socket? = null
    private var writer: PrintWriter? = null
    private var reader: BufferedReader? = null
    private var key: SecretKey? = null
    private var myUsername = ""
    private val io: ExecutorService = Executors.newSingleThreadExecutor()

    private val pickMedia = registerForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri != null) sendMedia(uri)
    }

    // Hasil parsing satu pesan masuk (dikerjakan di thread latar, ditampilkan di UI thread)
    private class Incoming {
        var sender: String? = null
        var text: String? = null
        var kind: String? = null   // null = teks, "img", "vid"
        var time: String = ""
        var me = false
        var system = false
        var thumb: Bitmap? = null
        var file: File? = null     // file video di cache
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        // Latar gelap, jadi ikon status bar dibuat terang
        WindowCompat.getInsetsController(window, window.decorView)
            .isAppearanceLightStatusBars = false

        WindowCompat.setDecorFitsSystemWindows(window, false)
        val root = findViewById<View>(R.id.root)
        ViewCompat.setOnApplyWindowInsetsListener(root) { v, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            val ime = insets.getInsets(WindowInsetsCompat.Type.ime()).bottom
            v.setPadding(0, bars.top, 0, maxOf(ime, bars.bottom))
            insets
        }

        serverIp = findViewById(R.id.serverIp)
        username = findViewById(R.id.username)
        input = findViewById(R.id.input)
        btnConnect = findViewById(R.id.btnConnect)
        btnSend = findViewById(R.id.btnSend)
        btnAttach = findViewById(R.id.btnAttach)
        messages = findViewById(R.id.messages)
        scroll = findViewById(R.id.scroll)

        styleUi()

        username.setText("User_${(Math.random() * 1000).toInt()}")
        input.isEnabled = false
        btnSend.isEnabled = false
        btnAttach.isEnabled = false

        // Bersihkan sisa video dari sesi sebelumnya
        File(cacheDir, "media").listFiles()?.forEach { it.delete() }

        btnAttach.setOnClickListener {
            pickMedia.launch(
                PickVisualMediaRequest.Builder()
                    .setMediaType(ActivityResultContracts.PickVisualMedia.ImageAndVideo)
                    .build()
            )
        }
        btnConnect.setOnClickListener { connect() }
        btnSend.setOnClickListener { send() }
        input.setOnEditorActionListener { _, _, _ -> send(); true }
    }

    private fun styleUi() {
        (serverIp.parent as View).setBackgroundColor(PANEL)
        (input.parent as View).setBackgroundColor(PANEL)
        scroll.setBackgroundColor(BG_CHAT)
        messages.setBackgroundColor(BG_CHAT)
        for (e in listOf(serverIp, username, input)) {
            e.setTextColor(TEXT_CREAM)
            e.setHintTextColor(TIME_C)
            e.setBackgroundColor(FIELD)
            e.setPadding(dp(10), dp(10), dp(10), dp(10))
        }
        for (b in listOf(btnConnect, btnSend, btnAttach)) {
            b.setBackgroundColor(ACCENT)
            b.setTextColor(ACCENT_INK)
        }
    }

    // ====== Koneksi ======
    private fun connect() {
        val ip = serverIp.text.toString().trim()
        val user = username.text.toString().trim()
        if (ip.isEmpty() || user.isEmpty()) {
            Toast.makeText(this, "IP Server dan Username tidak boleh kosong", Toast.LENGTH_SHORT).show()
            return
        }
        myUsername = user
        try {
            key = Crypto.deriveKey(EMBEDDED_SECRET)
        } catch (e: Exception) {
            Toast.makeText(this, "Gagal menyiapkan kunci", Toast.LENGTH_SHORT).show()
            return
        }

        Thread {
            try {
                val s = Socket(ip, PORT)
                socket = s
                val w = PrintWriter(OutputStreamWriter(s.getOutputStream(), Charsets.UTF_8), true)
                writer = w
                val r = BufferedReader(InputStreamReader(s.getInputStream(), Charsets.UTF_8))
                reader = r
                w.println(myUsername)

                runOnUiThread {
                    serverIp.isEnabled = false
                    username.isEnabled = false
                    btnConnect.isEnabled = false
                    input.isEnabled = true
                    btnSend.isEnabled = true
                    btnAttach.isEnabled = true
                }

                while (true) {
                    val msg = r.readLine() ?: break
                    val inc = safeParse(msg)
                    runOnUiThread { showIncoming(inc) }
                }
            } catch (e: Exception) {
                runOnUiThread {
                    Toast.makeText(
                        this,
                        "Gagal terhubung. Pastikan IP benar, server aktif, dan WiFi sama.",
                        Toast.LENGTH_LONG
                    ).show()
                }
            } finally {
                runOnUiThread {
                    input.isEnabled = false
                    btnSend.isEnabled = false
                    btnAttach.isEnabled = false
                }
            }
        }.start()
    }

    private fun send() {
        val text = input.text.toString().trim()
        val w = writer ?: return
        val k = key ?: return
        if (text.isEmpty()) return
        input.setText("")
        io.execute {
            try {
                w.println("enc:" + Crypto.encrypt(k, text))
            } catch (e: Exception) {
                runOnUiThread {
                    Toast.makeText(this, "Gagal mengirim", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    // ====== Kirim foto / video ======
    private fun sendMedia(uri: Uri) {
        val w = writer ?: return
        val k = key ?: return
        val video = contentResolver.getType(uri)?.startsWith("video/") == true
        Toast.makeText(this, if (video) "Mengirim video..." else "Mengirim foto...", Toast.LENGTH_SHORT).show()
        io.execute {
            try {
                val data = if (video) readLimited(uri, MAX_VIDEO_BYTES) else compressImage(uri)
                if (data == null) {
                    runOnUiThread {
                        Toast.makeText(
                            this,
                            "Video terlalu besar (maks ${MAX_VIDEO_BYTES / 1024 / 1024} MB)",
                            Toast.LENGTH_LONG
                        ).show()
                    }
                    return@execute
                }
                val prefix = if (video) VID_PREFIX else IMG_PREFIX
                w.println("enc:" + Crypto.encryptBytes(k, prefix + data))
            } catch (e: Exception) {
                runOnUiThread { Toast.makeText(this, "Gagal mengirim file", Toast.LENGTH_SHORT).show() }
            } catch (e: OutOfMemoryError) {
                runOnUiThread { Toast.makeText(this, "Gagal mengirim file", Toast.LENGTH_SHORT).show() }
            }
        }
    }

    // Baca file apa adanya; null jika melebihi batas
    private fun readLimited(uri: Uri, max: Int): ByteArray? {
        return contentResolver.openInputStream(uri).use { stream ->
            if (stream == null) throw IOException("tidak bisa membuka file")
            val out = ByteArrayOutputStream()
            val buf = ByteArray(16 * 1024)
            var total = 0
            while (true) {
                val n = stream.read(buf)
                if (n == -1) break
                total += n
                if (total > max) return null
                out.write(buf, 0, n)
            }
            out.toByteArray()
        }
    }

    // Perkecil foto (sisi terpanjang 1600 px), putar sesuai EXIF, simpan sebagai JPEG
    private fun compressImage(uri: Uri): ByteArray {
        val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        contentResolver.openInputStream(uri).use { BitmapFactory.decodeStream(it, null, o) }
        var sample = 1
        while (maxOf(o.outWidth, o.outHeight) / sample > MAX_IMAGE_SIDE) sample *= 2
        o.inJustDecodeBounds = false
        o.inSampleSize = sample
        var bmp: Bitmap = contentResolver.openInputStream(uri).use { BitmapFactory.decodeStream(it, null, o) }
            ?: throw IOException("foto tidak bisa dibaca")

        var rot = 0
        try {
            contentResolver.openInputStream(uri).use { stream ->
                if (stream != null) {
                    val ori = ExifInterface(stream).getAttributeInt(
                        ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL
                    )
                    rot = when (ori) {
                        ExifInterface.ORIENTATION_ROTATE_90 -> 90
                        ExifInterface.ORIENTATION_ROTATE_180 -> 180
                        ExifInterface.ORIENTATION_ROTATE_270 -> 270
                        else -> 0
                    }
                }
            }
        } catch (e: Exception) {
            // abaikan: foto tanpa EXIF
        }
        if (rot != 0) {
            val m = Matrix().apply { postRotate(rot.toFloat()) }
            bmp = Bitmap.createBitmap(bmp, 0, 0, bmp.width, bmp.height, m, true)
        }
        val out = ByteArrayOutputStream()
        bmp.compress(Bitmap.CompressFormat.JPEG, 80, out)
        return out.toByteArray()
    }

    // ====== Penerimaan & parsing (sama dengan desktop) ======
    // Berjalan di thread latar: dekripsi dan decode gambar tidak boleh membebani UI.
    private fun safeParse(raw: String): Incoming {
        return try {
            parseIncoming(raw)
        } catch (e: OutOfMemoryError) {
            Incoming().apply {
                system = true
                text = "Media terlalu besar untuk dibuka"
            }
        }
    }

    private fun parseIncoming(raw: String): Incoming {
        val inc = Incoming()
        var rest = raw
        var time: String? = null
        val tm = TIME_P.matcher(rest)
        if (tm.matches()) {
            time = tm.group(1)
            rest = tm.group(2) ?: ""
        }
        inc.time = time ?: TF.format(Date())

        val mm = MSG_P.matcher(rest)
        if (!mm.matches()) {
            inc.system = true
            inc.text = rest
            return inc
        }

        val sender = mm.group(1) ?: ""
        val body = mm.group(2) ?: ""
        if (sender.equals("SYSTEM", ignoreCase = true)) {
            inc.system = true
            inc.text = body
            return inc
        }

        inc.sender = sender
        inc.me = sender == myUsername
        if (!body.startsWith("enc:")) {
            inc.text = body
            return inc
        }

        try {
            val plain = Crypto.decryptBytes(key!!, body.substring(4))
            when {
                startsWith(plain, IMG_PREFIX) -> {
                    inc.kind = "img"
                    inc.thumb = decodeBitmap(plain, IMG_PREFIX.size)
                    if (inc.thumb == null) {
                        inc.kind = null
                        inc.text = "[foto tidak bisa dibuka]"
                    }
                }
                startsWith(plain, VID_PREFIX) -> {
                    inc.kind = "vid"
                    val dir = File(cacheDir, "media")
                    dir.mkdirs()
                    val f = File(dir, "vid_${System.nanoTime()}.mp4")
                    FileOutputStream(f).use { it.write(plain, VID_PREFIX.size, plain.size - VID_PREFIX.size) }
                    inc.file = f
                    inc.thumb = videoFrame(f)
                }
                else -> inc.text = String(plain, Charsets.UTF_8)
            }
        } catch (e: Exception) {
            inc.kind = null
            inc.text = "🔒 [pesan terenkripsi - aplikasi/kunci berbeda]"
        }
        return inc
    }

    private fun startsWith(data: ByteArray, prefix: ByteArray): Boolean {
        if (data.size < prefix.size) return false
        for (i in prefix.indices) if (data[i] != prefix[i]) return false
        return true
    }

    private fun decodeBitmap(data: ByteArray, offset: Int): Bitmap? {
        val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(data, offset, data.size - offset, o)
        var sample = 1
        while (maxOf(o.outWidth, o.outHeight) / sample > 1024) sample *= 2
        o.inJustDecodeBounds = false
        o.inSampleSize = sample
        return BitmapFactory.decodeByteArray(data, offset, data.size - offset, o)
    }

    private fun videoFrame(f: File): Bitmap? {
        val r = MediaMetadataRetriever()
        return try {
            r.setDataSource(f.absolutePath)
            r.getFrameAtTime(0)
        } catch (e: Exception) {
            null
        } finally {
            try { r.release() } catch (e: Exception) { }
        }
    }

    // Berjalan di UI thread
    private fun showIncoming(inc: Incoming) {
        when {
            inc.system -> addSystem(inc.text.orEmpty())
            inc.kind == null -> addBubble(
                if (inc.me) null else inc.sender, inc.text.orEmpty(), inc.time, inc.me, null
            )
            else -> addBubble(
                if (inc.me) null else inc.sender, null, inc.time, inc.me, mediaView(inc)
            )
        }
    }

    // ====== Tampilan foto / video di bubble ======
    private fun mediaView(inc: Incoming): View {
        val w = minOf((resources.displayMetrics.widthPixels * 0.65).toInt(), dp(260))
        var h = dp(146)
        val thumb = inc.thumb
        if (thumb != null) {
            h = minOf((w * thumb.height.toFloat() / thumb.width).toInt(), dp(340))
        }
        val video = inc.kind == "vid"

        val frame = FrameLayout(this)
        frame.clipToOutline = true
        frame.outlineProvider = object : ViewOutlineProvider() {
            override fun getOutline(view: View, outline: Outline) {
                outline.setRoundRect(0, 0, view.width, view.height, dp(12).toFloat())
            }
        }

        val iv = ImageView(this).apply {
            setBackgroundColor(FIELD)
            scaleType = ImageView.ScaleType.CENTER_CROP
            if (thumb != null) setImageBitmap(thumb)
        }
        frame.addView(iv, FrameLayout.LayoutParams(w, h))

        if (video) {
            val play = TextView(this).apply {
                text = "▶"
                setTextColor(0xFFFFFFFF.toInt())
                textSize = 36f
            }
            frame.addView(
                play,
                FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    Gravity.CENTER
                )
            )
        }
        frame.setOnClickListener {
            if (video) inc.file?.let { showVideo(it) } else inc.thumb?.let { showImage(it) }
        }
        return frame
    }

    private fun showImage(bmp: Bitmap) {
        val d = Dialog(this, android.R.style.Theme_Black_NoTitleBar_Fullscreen)
        val iv = ImageView(this).apply {
            setBackgroundColor(0xFF000000.toInt())
            scaleType = ImageView.ScaleType.FIT_CENTER
            setImageBitmap(bmp)
            setOnClickListener { d.dismiss() }
        }
        d.setContentView(iv)
        d.show()
    }

    private fun showVideo(file: File) {
        val d = Dialog(this, android.R.style.Theme_Black_NoTitleBar_Fullscreen)
        val box = FrameLayout(this).apply { setBackgroundColor(0xFF000000.toInt()) }
        val vv = VideoView(this)
        val mc = MediaController(this)
        mc.setAnchorView(vv)
        vv.setMediaController(mc)
        vv.setVideoPath(file.absolutePath)
        vv.setOnPreparedListener { vv.start() }
        box.addView(
            vv,
            FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT,
                Gravity.CENTER
            )
        )
        d.setContentView(box)
        d.setOnDismissListener { vv.stopPlayback() }
        d.show()
    }

    // ====== Bubble ======
    private fun addBubble(sender: String?, text: String?, time: String, me: Boolean, media: View?) {
        val bubble = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = GradientDrawable().apply {
                setColor(if (me) BUBBLE_ME else BUBBLE_THEM)
                cornerRadius = dp(16).toFloat()
            }
            setPadding(dp(12), dp(7), dp(12), dp(6))
        }

        val maxW = (resources.displayMetrics.widthPixels * 0.75).toInt()

        if (sender != null) {
            bubble.addView(TextView(this).apply {
                this.text = sender
                setTextColor(SENDER_C)
                textSize = 12f
                typeface = Typeface.DEFAULT_BOLD
            })
        }
        if (media != null) {
            val mp = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                topMargin = dp(2); bottomMargin = dp(2)
            }
            bubble.addView(media, mp)
        }
        if (text != null) {
            bubble.addView(TextView(this).apply {
                this.text = text
                setTextColor(if (me) TEXT_ME else TEXT_THEM)
                textSize = 15f
                maxWidth = maxW
            })
        }
        bubble.addView(TextView(this).apply {
            this.text = time
            setTextColor(TIME_C)
            textSize = 10f
        })

        val lp = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply {
            gravity = if (me) Gravity.END else Gravity.START
            topMargin = dp(4); bottomMargin = dp(4); leftMargin = dp(8); rightMargin = dp(8)
        }
        messages.addView(bubble, lp)
        scrollDown()
    }

    private fun addSystem(text: String) {
        val v = TextView(this).apply {
            this.text = text
            setTextColor(SYSTEM_C)
            textSize = 12f
            typeface = Typeface.defaultFromStyle(Typeface.ITALIC)
        }
        val lp = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply {
            gravity = Gravity.CENTER_HORIZONTAL
            topMargin = dp(5); bottomMargin = dp(5)
        }
        messages.addView(v, lp)
        scrollDown()
    }

    private fun scrollDown() {
        scroll.post { scroll.fullScroll(View.FOCUS_DOWN) }
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    // ====== Enkripsi end-to-end (identik dengan ChatClient desktop) ======
    private object Crypto {
        private val SALT = "ChatAppLocal-salt-v1".toByteArray(Charsets.UTF_8)

        fun deriveKey(passphrase: String): SecretKey {
            val f = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
            val spec = PBEKeySpec(passphrase.toCharArray(), SALT, 65536, 256)
            return SecretKeySpec(f.generateSecret(spec).encoded, "AES")
        }

        fun encrypt(key: SecretKey, plaintext: String): String =
            encryptBytes(key, plaintext.toByteArray(Charsets.UTF_8))

        fun decrypt(key: SecretKey, b64: String): String =
            String(decryptBytes(key, b64), Charsets.UTF_8)

        fun encryptBytes(key: SecretKey, plain: ByteArray): String {
            val iv = ByteArray(12).also { SecureRandom().nextBytes(it) }
            val c = Cipher.getInstance("AES/GCM/NoPadding")
            c.init(Cipher.ENCRYPT_MODE, key, GCMParameterSpec(128, iv))
            val ct = c.doFinal(plain)
            return Base64.getEncoder().encodeToString(iv + ct)
        }

        fun decryptBytes(key: SecretKey, b64: String): ByteArray {
            val data = Base64.getDecoder().decode(b64)
            val iv = data.copyOfRange(0, 12)
            val ct = data.copyOfRange(12, data.size)
            val c = Cipher.getInstance("AES/GCM/NoPadding")
            c.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(128, iv))
            return c.doFinal(ct)
        }
    }

    companion object {
        // ====== HARUS SAMA PERSIS dengan ChatClient desktop agar pesan bisa saling dibaca ======
        private const val EMBEDDED_SECRET = "fajar ganteng baik hati dan tidak sombong"
        private const val PORT = 2106

        // ====== Pengiriman foto & video (format sama dengan Java dan desktop) ======
        private const val MAX_VIDEO_BYTES = 10 * 1024 * 1024   // batas video 10 MB
        private const val MAX_IMAGE_SIDE = 1600                // sisi terpanjang foto (px)
        private val IMG_PREFIX = "MEDIA:img:".toByteArray(Charsets.UTF_8)
        private val VID_PREFIX = "MEDIA:vid:".toByteArray(Charsets.UTF_8)

        // ====== Warna tema coklat ======
        private const val FIELD = 0xFF403026.toInt()
        private const val PANEL = 0xFF33261E.toInt()
        private const val BG_CHAT = 0xFF241A14.toInt()
        private const val ACCENT = 0xFFC8803C.toInt()
        private const val ACCENT_INK = 0xFF2A1C12.toInt()
        private const val BUBBLE_ME = 0xFFA66A33.toInt()
        private const val BUBBLE_THEM = 0xFF3E3027.toInt()
        private const val TEXT_ME = 0xFFFFF4E8.toInt()
        private const val TEXT_THEM = 0xFFECE0D4.toInt()
        private const val TEXT_CREAM = 0xFFE8D9C9.toInt()
        private const val SENDER_C = 0xFFE0A867.toInt()
        private const val TIME_C = 0xFF9C8675.toInt()
        private const val SYSTEM_C = 0xFFB89B86.toInt()

        private val TF = SimpleDateFormat("HH:mm", Locale.getDefault())
        private val TIME_P: Pattern =
            Pattern.compile("^\\[(\\d{1,2}:\\d{2}(?::\\d{2})?)\\]\\s*(.*)$", Pattern.DOTALL)
        private val MSG_P: Pattern = Pattern.compile("^\\[(.*?)\\]:\\s?(.*)$", Pattern.DOTALL)
    }
}