package moe.nikky

import dev.kordex.core.DiscordRelayedException
import dev.kordex.core.i18n.toKey

fun relayError(messageKey: dev.kordex.i18n.Key): Nothing {
    throw DiscordRelayedException(messageKey) as Throwable
}

@Deprecated("replace with key")
fun relayError(message: String): Nothing {
    throw DiscordRelayedException(message.toKey())
}
