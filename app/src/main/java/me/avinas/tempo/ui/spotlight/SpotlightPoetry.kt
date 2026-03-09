package me.avinas.tempo.ui.spotlight

import android.content.Context
import me.avinas.tempo.R
import me.avinas.tempo.data.stats.TimeRange
import java.time.LocalDate
import java.util.Locale

object SpotlightPoetry {

    fun getHeading(context: Context, timeRange: TimeRange): String {
        return when (timeRange) {
            TimeRange.THIS_MONTH -> getMonthlyPoetry(context)
            TimeRange.THIS_YEAR -> getYearlyPoetry(context)
            TimeRange.ALL_TIME -> getAllTimePoetry(context)
            else -> getMonthlyPoetry(context) // Fallback
        }
    }

    private fun getYearlyPoetry(context: Context): String {
        val year = LocalDate.now().year
        return context.getString(R.string.poetry_year_unfolds, year)
    }

    private fun getAllTimePoetry(context: Context): String {
        return context.getString(R.string.poetry_all_time)
    }

    private fun getMonthlyPoetry(context: Context): String {
        val monthValues = LocalDate.now().monthValue
        
        return if (isTropical()) {
            getTropicalPoetry(context, monthValues)
        } else if (isSouthernHemisphere()) {
            getSouthernHemispherePoetry(context, monthValues)
        } else {
            getNorthernHemispherePoetry(context, monthValues)
        }
    }

    // 🌐 NORTHERN HEMISPHERE
    private fun getNorthernHemispherePoetry(context: Context, month: Int): String {
        val resId = when (month) {
            1 -> R.string.poetry_nh_1
            2 -> R.string.poetry_nh_2
            3 -> R.string.poetry_nh_3
            4 -> R.string.poetry_nh_4
            5 -> R.string.poetry_nh_5
            6 -> R.string.poetry_nh_6
            7 -> R.string.poetry_nh_7
            8 -> R.string.poetry_nh_8
            9 -> R.string.poetry_nh_9
            10 -> R.string.poetry_nh_10
            11 -> R.string.poetry_nh_11
            12 -> R.string.poetry_nh_12
            else -> R.string.poetry_time_forward
        }
        return context.getString(resId)
    }

    // 🌐 SOUTHERN HEMISPHERE
    private fun getSouthernHemispherePoetry(context: Context, month: Int): String {
        val resId = when (month) {
            1 -> R.string.poetry_sh_1
            2 -> R.string.poetry_sh_2
            3 -> R.string.poetry_sh_3
            4 -> R.string.poetry_sh_4
            5 -> R.string.poetry_sh_5
            6 -> R.string.poetry_sh_6
            7 -> R.string.poetry_sh_7
            8 -> R.string.poetry_sh_8
            9 -> R.string.poetry_sh_9
            10 -> R.string.poetry_sh_10
            11 -> R.string.poetry_sh_11
            12 -> R.string.poetry_sh_12
            else -> R.string.poetry_time_forward
        }
        return context.getString(resId)
    }

    // 🌍 EQUATORIAL COUNTRIES
    private fun getTropicalPoetry(context: Context, month: Int): String {
        val resId = when (month) {
            1 -> R.string.poetry_tr_1
            2 -> R.string.poetry_tr_2
            3 -> R.string.poetry_tr_3
            4 -> R.string.poetry_tr_4
            5 -> R.string.poetry_tr_5
            6 -> R.string.poetry_tr_6
            7 -> R.string.poetry_tr_7
            8 -> R.string.poetry_tr_8
            9 -> R.string.poetry_tr_9
            10 -> R.string.poetry_tr_10
            11 -> R.string.poetry_tr_11
            12 -> R.string.poetry_tr_12
            else -> R.string.poetry_time_forward
        }
        return context.getString(resId)
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
