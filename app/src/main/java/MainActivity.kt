package com.pangrel.pakaimasker

import android.Manifest
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.location.Criteria
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Bundle
import android.util.Log
import android.view.View
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import kotlinx.android.synthetic.main.activity_main.*
import kotlin.math.roundToInt


class MainActivity : AppCompatActivity() {
    private var isSafeZoneAdded = false

    private val receiver = object: BroadcastReceiver() {
        override fun onReceive(p0: Context?, p1: Intent?) {
            Log.d("MAIN", "receive " + p1?.action)
            when (p1?.action) {
                CamService.ACTION_PREPARED -> startMonitoring()
                CamService.ACTION_STOPPED -> stopMonitoring()
                CamService.ACTION_RESULT -> handleResult(p1)
                CamService.ACTION_LOCATION -> handleSafeZone(p1)

            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        setContentView(R.layout.activity_main)
        initView()
    }

    override fun onResume() {
        super.onResume()

        val filter = IntentFilter()
        filter.addAction(CamService.ACTION_PREPARED)
        filter.addAction(CamService.ACTION_STOPPED)
        filter.addAction(CamService.ACTION_RESULT)
        filter.addAction(CamService.ACTION_LOCATION)
        registerReceiver(receiver, filter)

        updateButtonText(isServiceRunning(this, CamService::class.java))
    }

    override fun onPause() {
        super.onPause()

        unregisterReceiver(receiver)
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)

        when (requestCode) {
            CODE_PERM_CAMERA -> {
                if (grantResults?.firstOrNull() != PackageManager.PERMISSION_GRANTED) {
                    Toast.makeText(this, getString(R.string.err_no_cam_permission), Toast.LENGTH_LONG).show()
                    stopService(Intent(this, CamService::class.java))
                } else {
                    sendBroadcast(Intent(CamService.ACTION_MONITOR))
                }
            }
            CODE_PERM_LOCATION -> {
                if (grantResults?.firstOrNull() != PackageManager.PERMISSION_GRANTED) {
                    Toast.makeText(this, getString(R.string.err_no_loc_permission), Toast.LENGTH_LONG).show()
                } else {
                    buttonLocationMonitoring.visibility = View.GONE
                    buttonSafeZone.visibility = View.VISIBLE
                }
            }
        }
    }

    private fun getCurrentLocation() {
        // Now create a location manager
        val locationManager = getSystemService(Context.LOCATION_SERVICE) as LocationManager

        if ( !locationManager.isProviderEnabled( LocationManager.GPS_PROVIDER ) ) {
            Toast.makeText(this, getString(R.string.err_no_active_gps), Toast.LENGTH_LONG).show()
            return
        }
        buttonSafeZone.setText("ACQUIRE LOCATION...")
        buttonSafeZone.isEnabled = false

        val locationListener: LocationListener = object : LocationListener {
            override fun onLocationChanged(currentLocation: Location) {
                Log.d("Location Changes", currentLocation.toString())

                buttonSafeZone.setText("CLEAR SAFE-ZONE")
                buttonSafeZone.isEnabled = true

                val intent = Intent(CamService.ACTION_ADD_SAFEZONE)

                intent.putExtra("name", "Rumah")
                intent.putExtra("location", currentLocation)

                sendBroadcast(intent)

                isSafeZoneAdded = true
            }

            override fun onStatusChanged(
                provider: String?,
                status: Int,
                extras: Bundle?
            ) {
                Log.d("Status Changed", status.toString())
            }

            override fun onProviderEnabled(provider: String?) {
                Log.d("Provider Enabled", provider)
            }

            override fun onProviderDisabled(provider: String?) {
                Log.d("Provider Disabled", provider)
            }
        }

        val criteria = Criteria()
        criteria.accuracy = Criteria.ACCURACY_FINE
        criteria.powerRequirement = Criteria.POWER_HIGH
        criteria.isAltitudeRequired = false
        criteria.isBearingRequired = false
        criteria.isSpeedRequired = false
        criteria.isCostAllowed = true
        criteria.horizontalAccuracy = Criteria.ACCURACY_HIGH
        criteria.verticalAccuracy = Criteria.ACCURACY_HIGH

        locationManager.requestSingleUpdate(criteria, locationListener, null)
    }

    private fun initView() {
        buttonMonitoring.setOnClickListener {
            buttonMonitoring.isEnabled = false
            if (!isServiceRunning(this, CamService::class.java)) {
                val intent = Intent(this, CamService::class.java)
                startService(intent)
            } else {
                stopService(Intent(this, CamService::class.java))
            }
        }
        buttonSafeZone.setOnClickListener {
            if (isSafeZoneAdded === false) {
                this.getCurrentLocation()
            } else {
                buttonSafeZone.setText("MARK AS SAFE-ZONE")

                val intent = Intent(CamService.ACTION_DEL_SAFEZONE)
                intent.putExtra("name", "Rumah")
                sendBroadcast(intent)

                isSafeZoneAdded = false
                distance.setText("")
                safeZone.setText("")
                distance.setText("")
            }
        }
        buttonLocationMonitoring.setOnClickListener {
            ActivityCompat.requestPermissions(
                this,
                arrayOf(
                    Manifest.permission.ACCESS_COARSE_LOCATION,
                    Manifest.permission.ACCESS_FINE_LOCATION
                ),
                MainActivity.CODE_PERM_LOCATION
            )
        }
    }

    private fun startCameraMonitoring() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            // We don't have camera permission yet. Request it from the user.
            ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.CAMERA),
                MainActivity.CODE_PERM_CAMERA
            )
        } else {
            sendBroadcast(Intent(CamService.ACTION_MONITOR))
        }
    }

    private fun stopMonitoring() {
        isSafeZoneAdded = false
        buttonSafeZone.setText("MARK AS SAFE-ZONE")
        updateButtonText(false)
    }

    private fun startMonitoring() {
        this.startCameraMonitoring()
        updateButtonText(isServiceRunning(this, CamService::class.java))
    }

    private fun updateButtonText(running: Boolean) {
        if (running) {
            buttonMonitoring.setText("STOP MONITORING")

            if (ActivityCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED &&
                ActivityCompat.checkSelfPermission(this, Manifest.permission.ACCESS_COARSE_LOCATION) != PackageManager.PERMISSION_GRANTED
            ) {
                buttonLocationMonitoring.visibility = View.VISIBLE
                buttonSafeZone.visibility = View.GONE
            } else {
                buttonLocationMonitoring.visibility = View.GONE
                buttonSafeZone.visibility = View.VISIBLE
            }

        } else {
            buttonMonitoring.setText("START MONITORING")
            buttonSafeZone.visibility = View.GONE
            buttonLocationMonitoring.visibility = View.GONE
        }

        buttonMonitoring.isEnabled = true
    }

    private fun handleSafeZone(intent: Intent) {
        val zones = intent.getParcelableArrayListExtra<Zone>("zones")
        val safe = intent.getBooleanExtra("safe", false)

        distance.setText("You are " + zones.first().distance.toString() + " meters from safe-zone")

        if (safe) {
            imageView.visibility = View.GONE
            maskAcc.setText("")
            maskStatus.setText("")
            safeZone.setText("Anda Berada Di Zona-Aman")
            safeZone.setTextColor(getResources().getColor(R.color.colorPrimaryDark))
        }
    }

    private fun handleResult(intent: Intent) {
        val byteArray = intent.getByteArrayExtra("image")
        val cls = intent.getIntExtra("class", -1)
        val accuracy = intent.getDoubleExtra("accuracy", 0.0)

        safeZone.setText("")

        if (cls === ImageClassification.UNSURE) {
            maskStatus.setText("INCONSISTENT RESULT")
            maskStatus.setTextColor(getResources().getColor(R.color.colorAccent))
            maskAcc.setText("")
        }
        if (cls === ImageClassification.NOT_FOUND) {
            maskStatus.setText("NO FACE FOUND")
            maskStatus.setTextColor(getResources().getColor(R.color.colorPrimaryDark))
            maskAcc.setText("")
        }
        if (cls === ImageClassification.WITH_MASK) {
            maskStatus.setText("MASK USED !!!")
            maskStatus.setTextColor(getResources().getColor(R.color.colorPrimary))
            maskAcc.setText((accuracy * 100).roundToInt().toString() + "%")
        }
        if (cls === ImageClassification.WITHOUT_MASK) {
            maskStatus.setText("MASK UNUSED !!!")
            maskStatus.setTextColor(getResources().getColor(R.color.colorAccent))
            maskAcc.setText((accuracy * 100).roundToInt().toString() + "%")
        }

        val bmp = BitmapFactory.decodeByteArray(byteArray, 0, byteArray.size)

        imageView.visibility = View.VISIBLE
        imageView.setImageBitmap(
            Bitmap.createScaledBitmap(
                bmp,
                imageView.getWidth(),
                imageView.getHeight(),
                false
            )
        )
    }


    companion object {
        val CODE_PERM_CAMERA = 6112
        val CODE_PERM_LOCATION = 6115
    }
}
