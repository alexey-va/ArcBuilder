package ru.arc.buildertools

import java.math.BigDecimal
import java.math.RoundingMode

/** Money is represented as integer minor units everywhere inside ArcBuilder. */
internal object BuilderMoney {
    const val SCALE = 2
    const val MAX_MINOR = 100_000_000_000L

    fun parseMinor(raw: String, rounding: RoundingMode = RoundingMode.UNNECESSARY): Long =
        BigDecimal(raw.trim().replace(',', '.'))
            .setScale(SCALE, rounding)
            .movePointRight(SCALE)
            .longValueExact()
            .also { require(it in 0..MAX_MINOR) { "Money value is outside its safety bound" } }

    /** One-way boundary for legacy providers that expose prices as Double. */
    fun priceFromProvider(raw: Double): Long {
        require(raw.isFinite() && raw > 0.0) { "Provider price is invalid" }
        return BigDecimal.valueOf(raw)
            .setScale(SCALE, RoundingMode.CEILING)
            .movePointRight(SCALE)
            .longValueExact()
            .also { require(it in 1..MAX_MINOR) { "Provider price is outside its safety bound" } }
    }

    /** One-way boundary for legacy providers that expose balances as Double. */
    fun balanceFromProvider(raw: Double): Long {
        require(raw.isFinite() && raw >= 0.0) { "Provider balance is invalid" }
        return BigDecimal.valueOf(raw)
            .setScale(SCALE, RoundingMode.HALF_UP)
            .movePointRight(SCALE)
            .longValueExact()
            .also { require(it in 0..MAX_MINOR) { "Provider balance is outside its safety bound" } }
    }

    fun signedFromProvider(raw: Double): Long {
        require(raw.isFinite()) { "Provider transaction amount is invalid" }
        return BigDecimal.valueOf(raw)
            .setScale(SCALE, RoundingMode.HALF_UP)
            .movePointRight(SCALE)
            .longValueExact()
            .also { require(it in -MAX_MINOR..MAX_MINOR) { "Provider amount is outside its safety bound" } }
    }

    /** Used only when an external API has no exact-money overload. */
    fun toProviderDouble(minor: Long): Double {
        require(minor in 0..MAX_MINOR) { "Money value is outside its safety bound" }
        return BigDecimal.valueOf(minor, SCALE).toDouble()
    }

    fun decimal(minor: Long): BigDecimal = BigDecimal.valueOf(minor, SCALE)
}
