package com.pangrel.pakaimasker

import android.app.*
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Bitmap
import android.graphics.Color
import android.os.*
import android.util.Log
import android.widget.Toast
import androidx.core.app.NotificationCompat
import com.chaquo.python.PyObject
import com.chaquo.python.Python
import com.chaquo.python.android.AndroidPlatform
import java.time.LocalTime

class CamService() : Service() {
    private var classificationModule : PyObject? = null
    private var captureHandler : CaptureHandler? = null
    private var locationTracker : LocationTracker? = null
    private var receiver: BroadcastReceiver? = null
    private var monitorTask: Runnable? = null
    private var mainHandler = Handler(Looper.getMainLooper())

    private var SCHEDULED_TIME_BEGIN : LocalTime? = null
    private var SCHEDULED_TIME_END : LocalTime? = null
    private var TOTAL_CAPTURE = 7                           // 7 images to capture
    private var TOTAL_CAPUTRE_PROCESSED = 2                 // 2 images sample to processed
    private var CAPUTRE_INTERVAL = 15 * 1000L               // 15 seconds capture interval
    private val LOCATION_INTERVAL = 15 * 1000L              // 15 seconds location updates
    private val SMALL_DISPLACEMENT_DISTANCE: Float = 20f    // 20 meters minimum distance to update
    private val SAFEZONE_RADIUS = 50                        // 50 meters radius of safezone
    private val CONFIDENCE_THRESHOLD = 0.8                  // 80 percent minimum confidence

    override fun onBind(p0: Intent?): IBinder? {
        return null
    }
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (! Python.isStarted()) {
            Python.start(AndroidPlatform(this))
        }

        classificationModule = Python.getInstance().getModule("classification")

        sendBroadcast(Intent(ACTION_PREPARED))
        return super.onStartCommand(intent, flags, startId)
    }
    override fun onCreate() {
        super.onCreate()

        init()

        val filter = IntentFilter()
        filter.addAction(ACTION_MONITOR)
        filter.addAction(ACTION_ADD_SAFEZONE)
        filter.addAction(ACTION_DEL_SAFEZONE)
        registerReceiver(receiver, filter)

        startForeground()


//        this.SCHEDULED_TIME_BEGIN = LocalTime.parse("06:00:00")
//        this.SCHEDULED_TIME_END = LocalTime.parse("15:00:00")
    }
    override fun onDestroy() {
        super.onDestroy()

        mainHandler.removeCallbacks(monitorTask)
        locationTracker?.stopMonitoring()
        captureHandler?.destroy()
        unregisterReceiver(receiver)
        sendBroadcast(Intent(ACTION_STOPPED))
    }


    private fun init() {
        locationTracker = LocationTracker(applicationContext, LOCATION_INTERVAL, SAFEZONE_RADIUS, SMALL_DISPLACEMENT_DISTANCE)
        captureHandler = CaptureHandler(applicationContext, TOTAL_CAPTURE, TOTAL_CAPUTRE_PROCESSED)
        receiver = object : BroadcastReceiver() {
            override fun onReceive(p0: Context?, p1: Intent?) {
                Log.d(TAG, "Receive: " + p1?.action)
                when (p1?.action) {
                    ACTION_MONITOR -> startMonitoring()
                    ACTION_ADD_SAFEZONE -> {
                        val notificationManager =
                            applicationContext.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
                        notificationManager.cancel(REMINDER_NOTIFICATION_ID)
                        locationTracker?.addSafeZone(
                            p1.getStringExtra("name"),
                            p1.getParcelableExtra("location")
                        )
                    }
                    ACTION_DEL_SAFEZONE -> locationTracker?.removeSafeZone(p1.getStringExtra("name"))
                }
            }
        }
        monitorTask = object: Runnable  {
            override fun run() {
                mainHandler.postDelayed(monitorTask, CAPUTRE_INTERVAL)

                if (isReady()) {
                    captureHandler?.captureRequest()
                }
            }
        }

        locationTracker?.setListener(object: OnLocationListener {
            override fun onUpdated(safeZones: ArrayList<Zone>, isSafe: Boolean) {
                updateLocations(safeZones, isSafe)
            }
        })
        captureHandler?.setListener(object: OnCapturedListener {
            override fun onCapturedDone(bitmapList: ArrayList<Bitmap>) {
                executeClassification(bitmapList)
            }
        })
    }
    private fun isReady(): Boolean {
        if (SCHEDULED_TIME_BEGIN !== null && SCHEDULED_TIME_END != null) {
            val now = LocalTime.now()
            if (!(SCHEDULED_TIME_BEGIN!!.isBefore(now) && SCHEDULED_TIME_END!!.isAfter(now))) {
                Log.d(TAG, "Out of scheduled time-range")
                return false
            }
        }

        if (locationTracker?.isSafe() === true) {
            Log.d(TAG, "User is in safe-zone")
            return false
        }

        if ((getSystemService(POWER_SERVICE) as PowerManager).isInteractive == false) {
            Log.d(TAG, "Screen device is off")
            return false
        }

        return true
    }
    private fun startMonitoring() {
        mainHandler.post(monitorTask)
    }
    private fun startForeground() {
        val pendingIntent: PendingIntent =
            Intent(this, MainActivity::class.java).let { notificationIntent ->
                PendingIntent.getActivity(this, 0, notificationIntent, 0)
            }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                CHANNEL_NAME,
                NotificationManager.IMPORTANCE_NONE
            )
            channel.lightColor = Color.BLUE
            channel.lockscreenVisibility = Notification.VISIBILITY_PRIVATE
            val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            nm.createNotificationChannel(channel)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                REMINDER_CHANNEL_ID,
                REMINDER_CHANNEL_NAME,
                NotificationManager.IMPORTANCE_HIGH
            )
            channel.lightColor = Color.BLUE
            channel.lockscreenVisibility = Notification.VISIBILITY_PRIVATE
            val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            nm.createNotificationChannel(channel)
        }

        val notification: Notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Mask Monitoring")
            .setContentText("Service is running in background...")
            .setSmallIcon(R.mipmap.ic_launcher_foreground)
            .setContentIntent(pendingIntent)
            .setTicker(getText(R.string.app_name))
            .build()

        startForeground(ONGOING_NOTIFICATION_ID, notification)
    }
    private fun updateLocations(safeZones: ArrayList<Zone>, isSafe: Boolean) {
        val intent = Intent(ACTION_LOCATION)
        intent.putExtra("zones", safeZones)
        intent.putExtra("safe", isSafe)
        sendBroadcast(intent)
    }
    private fun executeClassification(images: MutableList<Bitmap>) {
        val classification = ImageClassification()
        classification.setModule(classificationModule!!)
        classification.setCallback(object : OnEventListener<ClassificationResult> {
            override fun onFailure(e: java.lang.Exception?) {
                Toast.makeText(
                    applicationContext,
                    "ERROR CLASSIFICATION: " + e!!.message,
                    Toast.LENGTH_LONG
                )
                    .show()
            }

            override fun onSuccess(result: ClassificationResult) {
                if (!isReady()) {
                    return
                }

                val intent = Intent(ACTION_RESULT)
                intent.putExtra("image", result.image)
                intent.putExtra("class", result.classification)
                intent.putExtra("accuracy", result.accuracy)
                intent.putExtra("passed", result.accuracy >= CONFIDENCE_THRESHOLD)
                sendBroadcast(intent)

                val notificationManager =
                    applicationContext.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

                val cls = intent.getIntExtra("class", -1)
                if (cls === 1) {
                    val notificationIntent = Intent(applicationContext, MainActivity::class.java)
                    notificationIntent.addCategory(Intent.CATEGORY_LAUNCHER)
                    notificationIntent.action = Intent.ACTION_MAIN
                    notificationIntent.flags =
                        Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
                    val resultIntent =
                        PendingIntent.getActivity(applicationContext, 0, notificationIntent, 0)

                    val notificationBuilder =
                        NotificationCompat.Builder(applicationContext, REMINDER_CHANNEL_ID)
                            .setDefaults(Notification.DEFAULT_SOUND)
                            .setSmallIcon(R.mipmap.ic_launcher_foreground)
                            .setContentIntent(resultIntent)
                            .setContentTitle("MASK REMINDER !!!")
                            .setContentText("Please Wear a Mask to Help Protect You Againts Coronavirus")
                            .setPriority(NotificationCompat.PRIORITY_HIGH)
                            .setCategory(NotificationCompat.CATEGORY_REMINDER)

                    notificationManager.notify(
                        REMINDER_NOTIFICATION_ID,
                        notificationBuilder.build()
                    )
                } else {
                    notificationManager.cancel(REMINDER_NOTIFICATION_ID)
                }
            }
        })
        classification.execute(
            images.subList(
                Math.max(images.size - TOTAL_CAPUTRE_PROCESSED, 0),
                images.size
            )
        )
    }

    companion object {
        val TAG = "CamService"

        val ACTION_PREPARED = "pakaimasker.action.PREPARED"
        val ACTION_STOPPED = "pakaimasker.action.STOPPED"
        val ACTION_RESULT = "pakaimasker.action.RESULT"
        val ACTION_LOCATION = "pakaimasker.action.LOCATION"
        val ACTION_MONITOR = "pakaimasker.action.MONITOR"
        val ACTION_ADD_SAFEZONE = "pakaimasker.action.ADD_SAFEZONE"
        val ACTION_DEL_SAFEZONE = "pakaimasker.action.DEL_SAFEZONE"

        val CHANNEL_ID = "cam_service_channel_id"
        val CHANNEL_NAME = "cam_service_channel_name"
        val ONGOING_NOTIFICATION_ID = 6660

        val REMINDER_CHANNEL_ID = "cam_service_reminder_channel_id"
        val REMINDER_CHANNEL_NAME = "cam_service_reminder_channel_name"
        val REMINDER_NOTIFICATION_ID = 6665
    }
}