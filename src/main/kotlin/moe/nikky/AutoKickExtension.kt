package moe.nikky

import dev.kord.common.entity.Permission
import dev.kord.common.entity.Permissions
import dev.kord.common.entity.Snowflake
import dev.kord.core.behavior.GuildBehavior
import dev.kord.core.behavior.channel.ChannelBehavior
import dev.kord.core.entity.Guild
import dev.kord.core.entity.Role
import dev.kord.core.event.guild.MemberUpdateEvent
import dev.kordex.core.commands.Arguments
import dev.kordex.core.commands.application.slash.ephemeralSubCommand
import dev.kordex.core.commands.converters.impl.role
import dev.kordex.core.commands.converters.impl.string
import dev.kordex.core.extensions.Extension
import dev.kordex.core.extensions.ephemeralSlashCommand
import dev.kordex.core.extensions.event
import dev.kordex.core.extensions.publicSlashCommand
import dev.kordex.core.i18n.toKey
import dev.kordex.core.storage.Data
import dev.kordex.core.storage.StorageType
import dev.kordex.core.storage.StorageUnit
import io.klogging.Klogging
import kotlinx.coroutines.flow.toList
import kotlinx.serialization.Serializable
import moe.nikky.AutoKickExtension.AutoKickArgs
import moe.nikky.RoleManagementExtension.AddRoleArg
import org.koin.core.component.inject
import org.koin.dsl.module
import kotlin.getValue

class AutoKickExtension() : Extension(), Klogging {
    override val name: String = "autokick"

    private val config: ConfigurationExtension by inject()

    private val guildConfig = StorageUnit(
        storageType = StorageType.Config,
        namespace = name,
        identifier = "autokick",
        dataType = AutokickConfig::class
    )

    private fun GuildBehavior.config() =
        guildConfig
            .withGuild(id)

    init {
        bot.getKoin().loadModules(
            listOf(
                module {
                    single { this@AutoKickExtension }
                }
            )
        )
    }


    companion object {
        private val requiredPermissions = arrayOf(
            Permission.KickMembers,
        )
    }

    inner class AutoKickArgs : Arguments() {
        val role by role {
            name = "role".toKey()
            description = "Role".toKey()
        }
    }


    override suspend fun setup() {

        ephemeralSlashCommand {
            name = "autokick".toKey()
            description = "sets the autokick role".toKey()
            allowInDms = false
            requireBotPermissions(*requiredPermissions)

            ephemeralSubCommand {
                name = "disable".toKey()
                description = "disables autokick".toKey()

                requireBotPermissions(*requiredPermissions)

                check {
                    with(config) { requiresBotControl() }
                }

                action {
                    withLogContext(event, guild) { guild ->
                        val responseMessage = disableAutokick(
                            guild,
                            arguments,
                            event.interaction.channel
                        )

                        respond {
                            content = responseMessage
                        }
                    }
                }
            }
            ephemeralSubCommand(::AutoKickArgs) {
                name = "setup".toKey()
                description = "disables autokick".toKey()

                requireBotPermissions(*requiredPermissions)

                check {
                    with(config) { requiresBotControl() }
                }

                action {
                    withLogContext(event, guild) { guild ->
                        val responseMessage = setupAutokick(
                            guild,
                            arguments,
                            event.interaction.channel
                        )

                        respond {
                            content = responseMessage
                        }
                    }
                }
            }
        }

        event<MemberUpdateEvent> {
            this.check {
//                this.failIf {
//                    val oldRoles = event.old?.roles?.toList()?.toSet()
//                    val newRoles = event.member.roles.toList().toSet()
//                    oldRoles == newRoles
//                }
                failIf("autokick config is not found".toKey()) {
                    event.guild.config().get() == null
                }
            }
            action {
                withLogContext(event, event.guild) { guild ->
                    val config = guild.config().get()
                    if(config == null) {
                        return@withLogContext
                    }

                    val oldRoles = event.old?.roles?.toList()?.toSet()
                    val newRoles = event.member.roles.toList().toSet()
                    if (oldRoles != newRoles) {
//                        logger.info { "old roles: ${oldRoles}" }
//                        logger.info { "new roles: ${newRoles}" }

                        val autoKickRole = guild.config().get()?.getRole(guild)

                        if(autoKickRole != null && autoKickRole in newRoles) {

                            logger.warn { "kicking ${event.member.effectiveName} for having role ${autoKickRole.name}" }
                            event.member.kick(reason = "autokicked due to being assigned ${autoKickRole.mention}")
                        }
                    }
                }
            }
        }
    }


    private suspend fun setupAutokick(
        guild: Guild,
        arguments: AutoKickArgs,
        currentChannel: ChannelBehavior,
    ): String {
        val configUnit = guild.config()

        configUnit.save(
            AutokickConfig(
                arguments.role.id
            )
        )
        return "setup autokick config"
    }

    private suspend fun disableAutokick(
        guild: Guild,
        arguments: Arguments,
        currentChannel: ChannelBehavior,
    ): String {
        val configUnit = guild.config()
        configUnit.delete()
        return "deleted autokick config"
    }

}

@Serializable
data class AutokickConfig(
    val role: Snowflake,
) : Data {
    suspend fun getRole(guildBehavior: GuildBehavior): Role {
        return guildBehavior.getRole(role)
    }
}
