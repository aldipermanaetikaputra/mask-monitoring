package com.pangrel.pakaimasker

import android.location.Location
import android.os.Parcel
import android.os.Parcelable

class Zone() : Parcelable {
    var name: String = ""
    var latitude: Double = 0.0
    var longitude: Double = 0.0
    var distance: Int = 0
    var isSafe: Boolean = false

    constructor(parcel: Parcel) : this() {
        name = parcel.readString()
        latitude = parcel.readDouble()
        longitude = parcel.readDouble()
        distance = parcel.readInt()
        isSafe = parcel.readByte() != 0.toByte()
    }

    constructor(name: String, latitude: Double, longitude: Double) : this() {
        this.name = name
        this.latitude = latitude
        this.longitude = longitude
    }

    fun calculateDistance(location: Location): Int {
        val results = FloatArray(1)
        Location.distanceBetween(location.latitude, location.longitude, this.latitude, this.longitude, results)
        distance = results.first().toInt()

        return distance
    }

    override fun writeToParcel(parcel: Parcel, flags: Int) {
        parcel.writeString(name)
        parcel.writeDouble(latitude)
        parcel.writeDouble(longitude)
        parcel.writeInt(distance)
        parcel.writeByte(if (isSafe) 1 else 0)
    }

    override fun describeContents(): Int {
        return 0
    }

    companion object CREATOR : Parcelable.Creator<Zone> {
        override fun createFromParcel(parcel: Parcel): Zone {
            return Zone(parcel)
        }

        override fun newArray(size: Int): Array<Zone?> {
            return arrayOfNulls(size)
        }
    }
}