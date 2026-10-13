// SPDX-License-Identifier: GPL-3.0-or-later
package org.morlune.engine

object Geohash {
    private const val BASE32 = "0123456789bcdefghjkmnpqrstuvwxyz"

    fun encode(lat: Double, lon: Double, precision: Int): String {
        require(precision in 1..12) { "geohash precision must be 1..12" }
        require(lat in -90.0..90.0 && lon in -180.0..180.0) { "lat/lon out of range" }
        var latLo = -90.0; var latHi = 90.0
        var lonLo = -180.0; var lonHi = 180.0
        val sb = StringBuilder()
        var bit = 0; var ch = 0; var evenBit = true
        while (sb.length < precision) {
            if (evenBit) {
                val mid = (lonLo + lonHi) / 2
                if (lon >= mid) { ch = ch * 2 + 1; lonLo = mid } else { ch *= 2; lonHi = mid }
            } else {
                val mid = (latLo + latHi) / 2
                if (lat >= mid) { ch = ch * 2 + 1; latLo = mid } else { ch *= 2; latHi = mid }
            }
            evenBit = !evenBit
            if (++bit == 5) { sb.append(BASE32[ch]); bit = 0; ch = 0 }
        }
        return sb.toString()
    }
}
