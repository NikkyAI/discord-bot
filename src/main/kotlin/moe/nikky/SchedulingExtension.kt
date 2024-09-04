package moe.nikky

import com.kotlindiscord.kord.extensions.commands.Arguments
import com.kotlindiscord.kord.extensions.commands.application.slash.converters.impl.stringChoice
import com.kotlindiscord.kord.extensions.commands.application.slash.ephemeralSubCommand
import com.kotlindiscord.kord.extensions.commands.converters.impl.long
import com.kotlindiscord.kord.extensions.commands.converters.impl.string
import com.kotlindiscord.kord.extensions.components.buttons.EphemeralInteractionButton
import com.kotlindiscord.kord.extensions.components.components
import com.kotlindiscord.kord.extensions.components.ephemeralButton
import com.kotlindiscord.kord.extensions.components.ephemeralStringSelectMenu
import com.kotlindiscord.kord.extensions.components.forms.ModalForm
import com.kotlindiscord.kord.extensions.components.menus.string.EphemeralStringSelectMenu
import com.kotlindiscord.kord.extensions.extensions.Extension
import com.kotlindiscord.kord.extensions.extensions.ephemeralSlashCommand
import com.kotlindiscord.kord.extensions.storage.Data
import com.kotlindiscord.kord.extensions.storage.StorageType
import com.kotlindiscord.kord.extensions.storage.StorageUnit
import com.kotlindiscord.kord.extensions.time.TimestampType
import com.kotlindiscord.kord.extensions.utils.suggestLongMap
import com.kotlindiscord.kord.extensions.utils.suggestStringMap
import dev.kord.common.entity.ButtonStyle
import dev.kord.common.entity.Permission
import dev.kord.common.entity.Snowflake
import dev.kord.core.behavior.GuildBehavior
import dev.kord.core.behavior.channel.asChannelOf
import dev.kord.core.behavior.channel.createMessage
import dev.kord.core.behavior.edit
import dev.kord.core.behavior.interaction.followup.edit
import dev.kord.core.entity.channel.GuildMessageChannel
import dev.kord.core.entity.interaction.followup.EphemeralFollowupMessage
import dev.kord.rest.builder.message.actionRow
import io.klogging.Klogging
import kotlinx.datetime.Instant
import kotlinx.serialization.Serializable
import org.koin.core.component.inject
import org.koin.dsl.module
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes

class SchedulingExtension() : Extension(), Klogging {
    override val name = "scheduling"


    @Serializable
    data class SchedulingData(
        val events: Map<String, EventData> = emptyMap()
    ) : Data {

        fun update(key: String, event: EventData): SchedulingData {
            return copy(
                events = events + (key to event)
            )
        }

        fun update(key: String, transform: (EventData) -> EventData): SchedulingData {
            return update(
                key, transform(events[key] ?: relayError("unknown event key: $key"))
            )
        }

    }

    @Serializable
    data class EventData(
        val messageId: Snowflake,
        val id: String,
        val name: String,
        val description: String,
        val start: Instant,
        val end: Instant,
        val slotLength: Duration = 30.minutes,
        val signups: List<Signup>
    ) {
        fun addSignup(
            signup: Signup
        ): EventData {
            return copy(
                signups = signups + signup
            )
        }
    }

    @Serializable
    data class Signup(
        val user: Snowflake,
        val slot: Instant,
        val duration: Duration,
    )

    private val dataStorage = StorageUnit(
        storageType = StorageType.Data,
        namespace = name,
        identifier = "scheduling",
        dataType = SchedulingData::class
    )


    private suspend fun GuildBehavior.config() =
        dataStorage
            .withGuild(this.id)


    init {
        bot.getKoin().loadModules(
            listOf(
                module {
                    single { this@SchedulingExtension }
                }
            )
        )
    }

    private val configurationExtension: ConfigurationExtension by inject()


    companion object {
        private val requiredPermissions = arrayOf(
            Permission.ViewChannel,
            Permission.SendMessages,
            Permission.ManageMessages,
        )
    }

    private suspend fun tryParseInstant(value: String): Instant? {
        try {
            return Instant.parse(value)
        } catch (e: IllegalArgumentException) {
            logger.warn { "failed to parse $value as a iso8601 timestamp" }
        }

        try {
            val epochSeconds = value.substringAfter(':').substringBeforeLast(":").toLong()
            return Instant.fromEpochSeconds(epochSeconds)

        } catch (e: Exception) {
            logger.warn { "failed to parse $value as a discord timestamp" }
        }

        return null
    }

    suspend fun parseInstant(value: String): Instant {
        return tryParseInstant(value) ?: relayError("failed to parse $value")
    }

    inner class SchedulingCreateArgs : Arguments() {
        val id by string {
            name = "id"
            description = "id of the event"
        }
        val name by string {
            name = "name"
            description = "name of the event"
        }
        val description by string {
            name = "description"
            description = "freeform event description"
//            defaultValue = "a new cool event"
        }
        val startTime by string {
            name = "start"
            description = "supports discord timestamps hammertime"
            validate {
                tryParseInstant(value) ?: fail("failed to parse $value")
            }
        }
        val endTime by string {
            name = "end"
            description = "supports discord timestamps hammertime"
            validate {
                tryParseInstant(value) ?: fail("failed to parse $value")
            }
        }

        val slotLength by long {
            name = "slotlength"
            description = "slot length in minutes"
//            choices(
//                listOf(
//                    15,
//                    30,
//                    45,
//                    60,
//                    90,
//                    120
//                )
//                    .associate {
//                        it.minutes.toString() to it.toLong()
//                    }
//            )

            validate {
                passIf { value in (5..300) }
            }

            autoComplete {
                suggestLongMap(
                    listOf(
                        15,
                        30,
                        45,
                        60,
                        90,
                        120
                    )
                        .associate {
                            it.minutes.toString() to it.toLong()
                        }
                )
            }
        }

//        val endTime by arg("end", "event ends at", object : SingleConverter<Instant>() {
//            override val signatureTypeString: String = "converters.instant.signatureType"
//
//            override suspend fun parse(parser: StringParser?, context: CommandContext, named: String?): Boolean {
//                val arg: String = named ?: parser?.parseNext()?.data ?: return false
//                try {
//                    parsed = Instant.parse(arg)
//                } catch (e: IllegalArgumentException) {
//                    relayError(e.message ?: "failed to parse $arg")
//                }
//                return true
//            }
//
//            override suspend fun parseOption(context: CommandContext, option: OptionValue<*>): Boolean {
//                val optionValue = (option as? StringOptionValue)?.value ?: return false
//                try {
//                    parsed = Instant.parse(optionValue)
//                } catch (e: IllegalArgumentException) {
//                    relayError(e.message ?: "failed to parse $optionValue")
//                }
//                return true
//            }
//
//            override suspend fun toSlashOption(arg: Argument<*>): OptionsBuilder =
//                StringChoiceBuilder(arg.displayName, arg.description).apply { required = true }
//
//        })
    }

    inner class SignupArgs : Arguments() {
        val event by stringChoice {
            name = "event"
            description = "Select a event"
//            validate {
//                val schedulingData = this.context.getGuild()!!.config().get() ?: SchedulingData()
//                failIfNot("unknown event '$value'") {
//                    value !in schedulingData.events.values.map { it.name }
//                }
//            }
            autoComplete { event ->
                val channel = getChannel().asChannelOf<GuildMessageChannel>()
                withLogContext(event, channel.guild) { guild ->
                    val schedulingData = guild.config().get() ?: SchedulingData()
                    schedulingData.events.keys

                    suggestStringMap(
                        schedulingData.events.values.associate { it.name to it.id },
                        suggestInputWithoutMatches = true
                    )
                }
            }
        }
//        val timeslot by string {
//            name = "timeslots"
//
//            autoComplete { event ->
//                val channel = getChannel().asChannelOf<GuildMessageChannel>()
//                withLogContext(event, channel.guild) { guild ->
//                    val schedulingData = guild.config().get() ?: SchedulingData()
//                    this@autoComplete.
//                    schedulingData.events.keys
//
//                    suggestStringMap(
//                        schedulingData.events.mapValues { it.value.name },
//                        suggestInputWithoutMatches = true
//                    )
//                }
//            }
//        }
    }

    override suspend fun setup() {

        ephemeralSlashCommand {
            name = "scheduling"
            description = "create and edit open signup events"
            allowInDms = false

            ephemeralSubCommand(::SchedulingCreateArgs) {
                name = "create"
                description = "register a new event"

                requireBotPermissions(
                    Permission.SendMessages,
                )
                check {
                    with(configurationExtension) { requiresBotControl() }
                }
                action {
                    withLogContext(event, guild) { guild ->
                        val config = guild.config()
                        val schedulingData = config.get() ?: SchedulingData()

                        val existingEvent = schedulingData.events[arguments.id]
                        if (existingEvent != null) {
                            relayError("event ${existingEvent.id} ${existingEvent.name} exists already")
                        }
                        val startTime = parseInstant(arguments.startTime)
                        val endTime = parseInstant(arguments.endTime)
                        val slotLength = arguments.slotLength.minutes

                        val messageChannel = event.interaction.channel.asChannelOf<GuildMessageChannel>()

                        val message = messageChannel.createMessage(
                            """
                                new event placeholder
                            """.trimIndent()
                        )


                        val newEvent = EventData(
                            messageId = message.id,
                            id = arguments.id,
                            name = arguments.name,
                            description = arguments.description,
                            start = startTime,
                            end = endTime,
                            slotLength = slotLength,
                            signups = emptyList()
                        )

                        config.save(
                            (config.get() ?: SchedulingData()).update(arguments.id, newEvent)
                        )

                        message.edit {
                            content = """
                                id: `${newEvent.id}`
                                event: ${newEvent.name}
                                start: ${TimestampType.LongDateTime.format(newEvent.start.epochSeconds)} ${
                                TimestampType.RelativeTime.format(
                                    newEvent.start.epochSeconds
                                )
                            }
                                slots: ${newEvent.slotLength}

                                ${newEvent.description}

                                signup with
                                ```
                                /signup event:${newEvent.id}
                                ```
                            """.trimIndent()
                        }
                        respond {
                            content = "event created"
                        }
                    }
                }
            }
            ephemeralSubCommand {
                name = "list"
                description = "list events"

                action {
                    withLogContext(event, guild) { guild ->
                        logger.infoF { "loading config" }
                        val config = guild.config()
                        val schedulingData = config.get() ?: SchedulingData()

                        logger.info { "got ${schedulingData.events.size} events" }
                        val response = schedulingData.events.map {
                            "${it.key} ${it.value.name} ${TimestampType.RelativeTime.format(it.value.start.epochSeconds)}"
                        }.joinToString("\n")
                        logger.infoF { response }

                        respond {
                            content = response
                        }
                    }
                }
            }
        }

        ephemeralSlashCommand(::SignupArgs) {
            name = "signup"
            description = "signup for events"
            allowInDms = false

            action {
                withLogContext(event, guild) { guild ->
                    val config = guild.config()
                    val schedulingData = config.get() ?: SchedulingData()
                    logger.info { "loading event ${arguments.event}" }
                    val scheduledEvent = schedulingData.events[arguments.event]
                        ?: relayError("could not find event for key ${arguments.event}")

//                    val messageChannel = event.interaction.channel.asChannelOf<GuildMessageChannel>()
                    try {
                        var selectedTimeslot: Instant? = null
                        var response: EphemeralFollowupMessage? = null
//                        var button: EphemeralInteractionButton<ModalForm>? = null
//                        var timeslotSelection: EphemeralStringSelectMenu<ModalForm>? = null

                        suspend fun updateResponse(
                            buttonEnabled: Boolean = false,
                        ) {
//                            button = null
                            response?.edit {
                                content = "please select a timeslot and submit"
                                components {
                                    removeAll()
                                    ephemeralStringSelectMenu(
                                        row = 0
                                    ) {
                                        minimumChoices = 1
                                        maximumChoices = 1 // TODO: allow signing up for multiple timeslots ?
                                        placeholder = "timeslot start"

                                        val timeslots =
                                            scheduledEvent.start.epochSeconds..scheduledEvent.end.epochSeconds step scheduledEvent.slotLength.inWholeSeconds
                                        timeslots.forEachIndexed() { index, epochSeconds ->
                                            val instant = Instant.fromEpochSeconds(epochSeconds)
                                            val label = TimestampType.LongDateTime.format(epochSeconds)
                                            val value = instant.toString()

                                            option(label, value) {
                                                if(instant == selectedTimeslot) {
                                                    default = true
                                                }
                                                val end = instant + scheduledEvent.slotLength
                                                description =
                                                    "slot: $index, until ${TimestampType.LongDateTime.format(end.epochSeconds)}"
                                            }
                                        }


                                        action { modal ->
                                            selectedTimeslot = selected.firstOrNull() ?.let {
                                                Instant.parse(it)
                                            }


//                                        validateValues()
                                            val instant = selectedTimeslot
                                            if (instant != null && instant >= scheduledEvent.start && instant < scheduledEvent.end) {
                                                if(!buttonEnabled) {
                                                    updateResponse(buttonEnabled = true)
                                                }
                                            } else {
                                                if(buttonEnabled) {
                                                    updateResponse(buttonEnabled = false)
                                                }
                                            }
                                        }
                                    }
                                    ephemeralButton(
                                        row = 1
                                    ) {
                                        if(buttonEnabled) {
                                            enable()
                                        } else {
                                            disable()
                                        }
//                                    disable()
                                        style = ButtonStyle.Success
                                        label = "Submit"

                                        action { modal ->
                                            try {
                                                val instant = selectedTimeslot ?: relayError("no timeslot was selected")

                                                val signup = Signup(
                                                    user = user.id,
                                                    slot = instant,
                                                    duration = scheduledEvent.slotLength
                                                )

                                                //TODO: check for duplication

                                                config.save(
                                                    (config.get() ?: SchedulingData()).update(
                                                        arguments.event
                                                    ) { event ->
                                                        event.addSignup(signup)
                                                    }
                                                )

                                                response?.edit {
                                                    content = "registered ${user.mention} for ${TimestampType.ShortDateTime.format(instant.epochSeconds)}"

                                                    suppressEmbeds = true
                                                    logger.info { "components: ${components?.size}" }
                                                    logger.info { "embeds: ${embeds?.size}" }
                                                    components {
                                                        removeAll()
                                                    }
                                                    embeds?.clear()
                                                    components?.clear()
                                                } ?: relayError("failed up update response")
                                            } catch (e: Exception) {
                                                logger.error(e) { "something exploded" }
                                                relayError(e.message ?: "unknown error")
                                            }
                                        }
                                    }
                                }
                            } ?: relayError("failed up update response")
                        }

                        response = respond {
                            content = "please select a timeslot and submit"
                          /*  components {
                                button = ephemeralButton(
                                    row = 1
                                ) {
//                                    disable()
                                    style = ButtonStyle.Success
                                    label = "Submit"

                                    action { modal ->
                                        try {
                                            val instant = selectedTimeslot ?: relayError("no timeslot was selected")

                                            val signup = Signup(
                                                user = user.id,
                                                slot = instant,
                                                duration = scheduledEvent.slotLength
                                            )

                                            //TODO: check for duplication

                                            config.save(
                                                (config.get() ?: SchedulingData()).update(
                                                    arguments.event
                                                ) { event ->
                                                    event.addSignup(signup)
                                                }
                                            )

                                            response!!.edit {
                                                content = " registered ${user.mention} for ${TimestampType.ShortDateTime.format(instant.epochSeconds)}"
                                            }
                                        } catch (e: Exception) {
                                            logger.error(e) { "something exploded" }
                                            relayError(e.message ?: "unknown error")
                                        }
                                    }
                                }
                                val timeslotSelection = ephemeralStringSelectMenu(
                                    row = 0
                                ) {
                                    minimumChoices = 1
                                    maximumChoices = 1 // TODO: allow signing up for multiple timeslots ?
                                    placeholder = "timeslot start"

//                                    body = { modal ->
//                                        selectedTimeslot = selected.firstOrNull()
//                                    }


                                    val timeslots =
                                        scheduledEvent.start.epochSeconds..scheduledEvent.end.epochSeconds step scheduledEvent.slotLength.inWholeSeconds
                                    timeslots.forEachIndexed() { index, epochSeconds ->
                                        val instant = Instant.fromEpochSeconds(epochSeconds)
                                        val label = TimestampType.LongDateTime.format(epochSeconds)
                                        val value = instant.toString()

                                        option(label, value) {
                                            val end = instant + scheduledEvent.slotLength
                                            description =
                                                "slot: $index, until ${TimestampType.LongDateTime.format(end.epochSeconds)}"
                                        }
                                    }


                                    action { modal ->
                                        selectedTimeslot = selected.firstOrNull() ?.let {
                                            Instant.parse(it)
                                        }
//                                        validateValues()
                                        val instant = selectedTimeslot?.let {
                                            Instant.parse(it)
                                        }
                                        selectedTimeslot = instant
//                                        if (instant != null && instant >= scheduledEvent.start && instant < scheduledEvent.end) {
//                                            button.enable()
//                                        } else {
//                                            button.disable()
//                                        }
                                    }
                                }


                            }*/
                        }
                        updateResponse()
                    } catch (e: Exception) {
                        logger.error(e) { "something exploded" }
                        relayError(e.message ?: "unknown error")
                    }
                }
            }
        }
    }
}