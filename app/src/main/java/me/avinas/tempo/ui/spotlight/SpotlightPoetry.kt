package me.avinas.tempo.ui.spotlight

import me.avinas.tempo.data.stats.TimeRange
import java.time.LocalDate
import java.util.Locale

object SpotlightPoetry {

    fun getHeading(timeRange: TimeRange): String {
        return when (timeRange) {
            TimeRange.THIS_MONTH -> getMonthlyPoetry()
            TimeRange.THIS_YEAR -> getYearlyPoetry()
            TimeRange.ALL_TIME -> getAllTimePoetry()
            else -> getMonthlyPoetry() // Fallback
        }
    }

    private fun getYearlyPoetry(): String {
        val year = LocalDate.now().year
        return "$year unfolds in sound"
    }

    private fun getAllTimePoetry(): String {
        return "A sound that stays"
    }

    private fun getMonthlyPoetry(): String {
        val monthValues = LocalDate.now().monthValue
        
        return if (isTropical()) {
            getTropicalPoetry(monthValues)
        } else if (isSouthernHemisphere()) {
            getSouthernHemispherePoetry(monthValues)
        } else {
            getNorthernHemispherePoetry(monthValues)
        }
    }

    // 🌐 NORTHERN HEMISPHERE
    private fun getNorthernHemispherePoetry(month: Int): String {
        return when (month) {
            1 -> "January arrives quietly"
            2 -> "February keeps its distance"
            3 -> "March begins to loosen its grip"
            4 -> "April opens the year further"
            5 -> "May settles into motion"
            6 -> "June stretches the days"
            7 -> "July holds everything at once"
            8 -> "August lingers a little longer"
            9 -> "September brings edges back"
            10 -> "October sharpens the picture"
            11 -> "November turns inward"
            12 -> "December draws a line under the year"
            else -> "Time moves forward"
        }
    }

    // 🌐 SOUTHERN HEMISPHERE
    private fun getSouthernHemispherePoetry(month: Int): String {
        return when (month) {
            1 -> "January opens wide"
            2 -> "February stays bright"
            3 -> "March begins to slow its pace"
            4 -> "April cools the edges"
            5 -> "May pulls things closer"
            6 -> "June grows quieter"
            7 -> "July holds steady"
            8 -> "August waits patiently"
            9 -> "September starts to open again"
            10 -> "October gathers momentum"
            11 -> "November expands outward"
            12 -> "December closes loudly"
            else -> "Time moves forward"
        }
    }

    // 🌍 EQUATORIAL COUNTRIES
    private fun getTropicalPoetry(month: Int): String {
        return when (month) {
            1 -> "January marks the beginning"
            2 -> "February keeps things moving"
            3 -> "March finds its footing"
            4 -> "April continues forward"
            5 -> "May settles the pace"
            6 -> "June reaches the middle"
            7 -> "July moves past the center"
            8 -> "August carries on"
            9 -> "September looks ahead"
            10 -> "October gains clarity"
            11 -> "November prepares to close"
            12 -> "December brings the year home"
            else -> "Time moves forward"
        }
    }

    // Logic duplicated from SightCardGenerator for self-containment
    private fun isTropical(): Boolean {
        val country = Locale.getDefault().country.uppercase()
        val tropicalCodes = setOf(
            "ID", "SG", "MY", "TH", "VN", "PH", "BR", "CO", "VE", "EC", "PE", 
            "NG", "GH", "KE", "TZ", "LK", "BD", "MX", "JM", "DO", "PR"
        )
        return tropicalCodes.contains(country)
    }

    private fun isSouthernHemisphere(): Boolean {
        val country = Locale.getDefault().country.uppercase()
        val southernCodes = setOf(
            "AR", "AU", "CL", "NZ", "ZA", "UY" 
        )
        return southernCodes.contains(country)
    }
}
