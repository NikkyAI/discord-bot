package moe.nikky

import kotlin.time.Clock
import kotlinx.datetime.DateTimePeriod
import kotlin.time.Instant
import kotlinx.datetime.TimeZone
import kotlinx.datetime.plus
import kotlin.time.Duration

/**
 * Converts this [DateTimePeriod] to a [Duration].
 */
public fun DateTimePeriod.toDuration(): Duration {
    val now = Clock.System.now()
    val applied = now.plus(this, TimeZone.UTC)

    return applied - now
}
public fun DateTimePeriod.fromNow(): Instant {
    val now = kotlin.time.Clock.System.now()
    val applied = now.plus(this, TimeZone.UTC)

    return applied
}