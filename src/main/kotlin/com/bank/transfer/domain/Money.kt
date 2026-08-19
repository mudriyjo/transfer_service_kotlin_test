package com.bank.transfer.domain

import java.math.BigDecimal
import java.math.RoundingMode
import java.util.Currency

/**
 * Monetary value used by all three transfer flows.
 *
 * Values retain the caller-provided scale (up to the database scale) rather
 * than silently rounding.  Arithmetic helpers require matching currencies.
 */
data class Money(
    val amount: BigDecimal,
    val currency: String,
) : Comparable<Money> {

    init {
        require(amount.precision() <= MAX_PRECISION) {
            "Money precision must not exceed $MAX_PRECISION digits"
        }
        require(amount.scale() <= DATABASE_SCALE) {
            "Money scale must not exceed $DATABASE_SCALE decimal places"
        }
        require(currency == currency.uppercase() && currency.length == 3) {
            "Currency must be an uppercase ISO-4217 code"
        }
        Currency.getInstance(currency)
    }

    val isPositive: Boolean
        get() = amount.signum() > 0

    val isZero: Boolean
        get() = amount.signum() == 0

    fun negate(): Money = Money(amount.negate(), currency)

    operator fun plus(other: Money): Money {
        requireSameCurrency(other)
        return Money(amount.add(other.amount), currency)
    }

    operator fun minus(other: Money): Money {
        requireSameCurrency(other)
        return Money(amount.subtract(other.amount), currency)
    }

    fun isGreaterThan(other: Money): Boolean = compareTo(other) > 0

    fun toMinorUnits(): Long {
        val fractionDigits = Currency.getInstance(currency).defaultFractionDigits
        return amount
            .movePointRight(fractionDigits)
            .setScale(0, RoundingMode.UNNECESSARY)
            .longValueExact()
    }

    /** Stable representation used when deriving an idempotency fingerprint. */
    fun canonicalAmount(): String = amount.stripTrailingZeros().toPlainString()

    override fun compareTo(other: Money): Int {
        requireSameCurrency(other)
        return amount.compareTo(other.amount)
    }

    private fun requireSameCurrency(other: Money) {
        require(currency == other.currency) {
            "Currency mismatch: $currency and ${other.currency}"
        }
    }

    companion object {
        const val MAX_PRECISION: Int = 19
        const val DATABASE_SCALE: Int = 4

        fun of(amount: BigDecimal, currency: String): Money {
            val normalizedCurrency = currency.trim().uppercase()
            require(normalizedCurrency.length == 3) { "Currency must be an ISO-4217 code" }
            return Money(amount, normalizedCurrency)
        }

        fun of(amount: String, currency: String): Money = of(BigDecimal(amount), currency)

        fun zero(currency: String): Money = of(BigDecimal.ZERO, currency)
    }
}
