package org.mcrf.client

import android.os.Parcel

/** Custom private IPC has no generated AIDL schema checks. Never let an absent
 * fixed-width field look like an explicit zero/WAIT/false/release decision. */
internal object OpenFluxParcel {
    fun readInt(parcel: Parcel): Int { check(parcel.dataAvail() >= 4); return parcel.readInt() }
    fun readLong(parcel: Parcel): Long { check(parcel.dataAvail() >= 8); return parcel.readLong() }
    /** Check embedded length AND padded payload before allocation. Future HTTP
     * endpoint must first reject oversized parcels/FDs/UIDs/descriptors.
     * Returned bytes belong to caller and must be wiped on every terminal path. */
    fun readBytes(parcel: Parcel, maximum: Int): ByteArray {
        require(maximum in 0..8192)
        val position = parcel.dataPosition()
        val length = readInt(parcel)
        check(length in 0..maximum)
        val padded = (length + 3) and -4
        check(parcel.dataAvail() >= padded)
        val bytes = ByteArray(length)
        try {
            parcel.setDataPosition(position); parcel.readByteArray(bytes)
            return bytes
        } catch (failure: Exception) { bytes.fill(0); throw failure }
    }
}
