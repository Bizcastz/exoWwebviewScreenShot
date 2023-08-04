package com.amkorllc.exowwebviewscreenshot

import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Bundle
import android.os.Environment
import android.webkit.WebView
import androidx.appcompat.app.AppCompatActivity
import com.google.android.exoplayer2.MediaItem
import com.google.android.exoplayer2.SimpleExoPlayer
import com.google.android.exoplayer2.ui.PlayerView
import java.io.File
import java.io.FileOutputStream
import java.util.HashMap
import android.content.Context
import java.io.FileInputStream

import android.app.DownloadManager

import android.content.BroadcastReceiver

import android.content.Intent

import android.content.IntentFilter
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.util.Log
import android.view.View

import com.arthenica.mobileffmpeg.Config
import com.arthenica.mobileffmpeg.FFmpeg


import android.util.Base64
import java.io.ByteArrayOutputStream
import org.ksoap2.SoapEnvelope
import org.ksoap2.serialization.SoapObject
import org.ksoap2.serialization.SoapSerializationEnvelope
import org.ksoap2.transport.HttpTransportSE
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

import androidx.lifecycle.lifecycleScope








class MainActivity : AppCompatActivity() {

    private lateinit var playerView: PlayerView
    private lateinit var player: SimpleExoPlayer
    private lateinit var webView: WebView

    private var isWebViewScreenshotTaken = false
    private var isExoPlayerScreenshotTaken = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        playerView = findViewById(R.id.player_view)
        webView = findViewById(R.id.web_view)

        // Load a webpage
        webView.loadUrl("https://www.google.com") // Replace with your website URL

        // Create a player instance
        player = SimpleExoPlayer.Builder(this).build()

        // Bind the player to the view
        playerView.player = player

        // Build the media item and set it to player
        val mediaItem = MediaItem.fromUri(Uri.parse("https://commondatastorage.googleapis.com/gtv-videos-bucket/sample/BigBuckBunny.mp4")) // Replace with your video URL
        player.setMediaItem(mediaItem)

        // Prepare the player and start playing
        player.prepare()
        player.playWhenReady = true

        // Let the video play for 10 seconds, then take a screenshot
        playerView.postDelayed({
            downloadVideoAndCaptureFrame(MainActivity@this, "https://commondatastorage.googleapis.com/gtv-videos-bucket/sample/BigBuckBunny.mp4") // Replace with your video URL
            //captureScreenshotAndSave(playerView, "player_screenshot")
            captureScreenshotAndSave(webView, "web_screenshot")

            mergeImages()
        }, 10000) // 20 seconds delay
    }

    override fun onDestroy() {
        super.onDestroy()
        // Release the player when it's not needed
        player.release()
    }


    private fun downloadVideoAndCaptureFrame(context: Context, videoUrl: String) {
        val videoUri = Uri.parse(videoUrl)
        val request = DownloadManager.Request(videoUri)
        request.setNotificationVisibility(DownloadManager.Request.VISIBILITY_HIDDEN) // To avoid a notification after download
        request.setDestinationInExternalFilesDir(context, null, "temp.mp4") // set destination

        val manager = context.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager

        try {
            val downloadId = manager.enqueue(request)

            val br = object : BroadcastReceiver() {
                override fun onReceive(context: Context, intent: Intent) {
                    val id = intent.getLongExtra(DownloadManager.EXTRA_DOWNLOAD_ID, -1)
                    if (downloadId == id) {
                        val file = File(context.getExternalFilesDir(null), "temp.mp4")
                        getVideoFrameAndSaveToFile(context, Uri.fromFile(file))

                        // If you want to delete the file after capturing frame
                        // file.delete()
                    }
                }
            }

            // register receiver to get notified when download is complete
            context.registerReceiver(br, IntentFilter(DownloadManager.ACTION_DOWNLOAD_COMPLETE))
        } catch (e: Exception) {
            e.printStackTrace()
        }

    }



    private fun getVideoFrameAndSaveToFile(context: Context, uri: Uri) {
        val retriever = MediaMetadataRetriever()
        var inputStream: FileInputStream? = null
        var outputStream: FileOutputStream? = null

        try {
            retriever.setDataSource(context, uri)
            val frame = retriever.getFrameAtTime(10 * 1000000, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)

            // Save the bitmap to a file
            val file = File(context.getExternalFilesDir(null), "exo_screenshot.png")
            outputStream = FileOutputStream(file)
            frame?.compress(Bitmap.CompressFormat.PNG, 100, outputStream)
        } catch (e: Exception) {
            e.printStackTrace()
        } finally {
            retriever.release()
            inputStream?.close()
            outputStream?.close()

            // After the screenshot is saved:
            isExoPlayerScreenshotTaken = true

            if (isWebViewScreenshotTaken) {
                mergeImages()
            }
        }
    }


    private fun captureScreenshotAndSave(view: View, fileName: String) {
        val bitmap = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        view.draw(canvas)

        val file = File(getExternalFilesDir(null), "$fileName.png")
        val outputStream = FileOutputStream(file)
        bitmap.compress(Bitmap.CompressFormat.PNG, 100, outputStream)
        outputStream.close()
        // After the screenshot is saved:
        isWebViewScreenshotTaken = true

        if (isExoPlayerScreenshotTaken) {
            mergeImages()
        }
    }


    private fun mergeImages() {
        val image1Path = "${getExternalFilesDir(null)}/web_screenshot.png"
        val image2Path = "${getExternalFilesDir(null)}/exo_screenshot.png"
        val outputPath = "${getExternalFilesDir(null)}/merged_output.png"

        val ffmpegCommand = "-y -i $image1Path -i $image2Path -filter_complex \"[1] format = yuva444p, colorchannelmixer = aa = 0.5[web];[0][web] overlay \" $outputPath"

        val rc = FFmpeg.execute(ffmpegCommand)

        if (rc == Config.RETURN_CODE_SUCCESS) {
            Log.i(Config.TAG, "Command execution completed successfully.")
            val bitmap: Bitmap = convertFileToBitmap(outputPath)!!
            val base64Str = bitmapToBase64(bitmap)

            lifecycleScope.launch {
                val response = uploadBitmapToServer(base64Str)
                // Do something with the response on the main thread
            }
        } else if (rc == Config.RETURN_CODE_CANCEL) {
            Log.i(Config.TAG, "Command execution cancelled by user.")
        } else {
            Log.i(Config.TAG, "Command execution failed with rc=$rc and the output below.")
            Config.printLastCommandOutput(Log.INFO)
        }
    }

    fun bitmapToBase64(bitmap: Bitmap): String {
        val outputStream = ByteArrayOutputStream()
        bitmap.compress(Bitmap.CompressFormat.PNG, 100, outputStream)
        val byteArray = outputStream.toByteArray()
        return Base64.encodeToString(byteArray, Base64.DEFAULT)
    }

    suspend fun uploadBitmapToServer(base64Str: String) = withContext(Dispatchers.IO) {
        val NAMESPACE = "http://demo.stors.net/system_comm_tablet/"
        val METHOD_NAME = "SystemSnapshot"
        val SOAP_ACTION = "$NAMESPACE$METHOD_NAME"
        val URL = "https://www.bizcastzcloud.com/system_comm_tablet.asmx"

        val request = SoapObject(NAMESPACE, METHOD_NAME)
        request.addProperty("system_guid", "58cf0a90-38be-4b5f-a775-455efbc10621")
        request.addProperty("screenShot", base64Str)

        val envelope = SoapSerializationEnvelope(SoapEnvelope.VER11)
        envelope.dotNet = true
        envelope.setOutputSoapObject(request)

        val httpTransport = HttpTransportSE(URL)

        try {
            Log.i(Config.TAG, "About to call SOAP")
            httpTransport.call(SOAP_ACTION, envelope)
            Log.i(Config.TAG, "After call SOAP")
            val response = envelope.response
        } catch (e: Exception) {
            Log.i(Config.TAG, "BROKEN SOAP")
            e.printStackTrace()
        }
    }

    fun convertFileToBitmap(filePath: String): Bitmap? {
        val file = File(filePath)
        return if (file.exists()) {
            BitmapFactory.decodeFile(file.absolutePath)
        } else {
            null
        }
    }


}