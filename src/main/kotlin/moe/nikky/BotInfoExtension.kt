package moe.nikky

import dev.kordex.core.DISCORD_FUCHSIA
import dev.kordex.core.DISCORD_GREEN
import dev.kordex.core.commands.Arguments
import dev.kordex.core.commands.application.slash.ephemeralSubCommand
import dev.kordex.core.commands.converters.impl.role
import dev.kordex.core.extensions.Extension
import dev.kordex.core.extensions.ephemeralSlashCommand
import dev.kord.common.entity.Permission
import dev.kord.common.entity.Permissions
import dev.kord.rest.builder.message.embed
import dev.kordex.core.i18n.toKey
import io.klogging.Klogging
import io.ktor.http.*
import kotlinx.coroutines.flow.count
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import moe.nikky.twitch.TwitchExtension
import org.koin.core.component.inject

class BotInfoExtension : Extension(), Klogging {
    override val name: String = "bot-info-extension"

    private val configurationExtension: ConfigurationExtension by inject()
    private val roleManagementExtension: RoleManagementExtension by inject()
    private val twitchExtensions: TwitchExtension by inject()

    private val inviteUrl: String = runBlocking {
        val permission = Permissions(
            Permission.ViewChannel,
            Permission.SendMessages,
            Permission.ManageMessages,
            Permission.ManageRoles,
            Permission.ManageWebhooks,
            Permission.ReadMessageHistory,
            Permission.ManageEvents,
            Permission.KickMembers,
        )
        val scopes = listOf(
            "bot",
            "applications.commands"
        )
        URLBuilder("https://discord.com/api/oauth2/authorize").apply {
            parameters.append("client_id", kord.resources.applicationId.toString())
            parameters.append("permissions", permission.code.value)
            parameters.append("scope", scopes.joinToString(" "))
        }.build().toString().also { inviteUrl ->
            logger.infoF { "invite: $inviteUrl" }
        }
    }

    inner class SetAdminRoleArgs : Arguments() {
        val role by role {
            name = "role".toKey()
            description = "admin role".toKey()
        }
    }

    override suspend fun setup() {
        val self = kord.getSelf()

        ephemeralSlashCommand {
            name = "bot".toKey()
            description = "${self.username} related commands".toKey()

            ephemeralSubCommand() {
                name = "show-config".toKey()
                description = "shows the current configuration of (${self.username} ${self.mention})".toKey()
                allowInDms = false

                check {
                    with(configurationExtension) {
                        requiresBotControl()
                    }
                }

                action {
                    withLogContext(event, guild) { guild ->
                        val guildConfig = configurationExtension.loadConfig(guild) ?: GuildConfig()
                        val roleManagementConfig = roleManagementExtension.loadConfig(guild) ?: RoleManagementConfig()

                        respond {
                            val choosableRoles =
                                roleManagementConfig.roleChoosers.map { (key, roleChooser) ->
                                    val roleMapping = roleChooser.roleMapping
                                    "**${roleChooser.section}**:\n" + roleMapping.map { entry ->
                                        val reaction = entry.reactionEmoji(guild)
                                        val role = entry.getRole(guild)
                                        "  ${reaction.mention}: ${role.mention}"
                                    }.joinToString("\n")
                                }
                                    .joinToString("\n\n")

                            val twitch = twitchExtensions.loadConfig(guild)?.configs?.values.orEmpty()
                                .sortedBy { it.roleId }
                                .sortedBy { it.channelId }
                                .map { twitchNotif ->
                                    val channel = twitchNotif.channel(guild)
                                "${channel.mention} ${twitchNotif.role(guild).mention} <${twitchNotif.twitchUrl}>"
                            }.joinToString("\n")
                            content = """
                                |adminRole: ${guildConfig.adminRole(guild)?.mention}
                                |role pickers: 
                                ${choosableRoles.indent("|  ")}
                                |twitch notifications:
                                ${twitch.indent("|  ")}
                            """.trimMargin()
                        }
                    }
                }
            }

            ephemeralSubCommand {
                name = "invite".toKey()
                description = "get invite url".toKey()

                action {
                    withLogContextOptionalGuild(event, guild) { guild ->
                        this@BotInfoExtension.logger.infoF { "executed invite" }
                        respond {
                            content = inviteUrl
                        }
                    }
                }
            }

            ephemeralSubCommand {
                name = "stats".toKey()
                description = "shows some numbers about (${self.username} ${self.mention})".toKey()
                action {
                    val roleManagement: RoleManagementExtension? = getKoin().getOrNull()
                    val twitch: TwitchExtension? = getKoin().getOrNull()

                    withLogContextOptionalGuild(event, guild) { guild ->
                        respond {
                            val guildConfigs = this@BotInfoExtension.kord.guilds.toList().associateWith { guild ->
                                configurationExtension.loadConfig(guild)
                            }.toList()

                            run {
                                val guilds = this@BotInfoExtension.kord.guilds.count()

                                embed {
                                    color = DISCORD_GREEN
                                    title = "General"
                                    description = """
                                        |${self.mention} is in `$guilds` guilds
                                    """.trimMargin()
                                    field {
                                        inline = true
                                        name = "guilds"
                                        value = guilds.toString()
                                    }
                                }
                            }

                            roleManagement?.run {
                                val roleManagementConfigs = this@BotInfoExtension.kord.guilds.toList().mapNotNull { guild ->
                                    roleManagementExtension.loadConfig(guild)?.takeIf { it.roleChoosers.isNotEmpty() }
                                }
                                val roleChoosers = roleManagementConfigs.flatMap {
                                    it.roleChoosers.values
                                }
                                val roleMappings = roleChoosers.flatMap { it.roleMapping }

                                val distinctRoles = roleMappings.distinctBy() {
                                    it.role
                                }.count()
                                val distinctEmoji = roleMappings.distinctBy() {
                                    it.emoji
                                }.count()
                                val guilds = roleManagementConfigs.size

                                val content = """
                                        |configured in `$guilds` guilds
                                        |`$distinctRoles` roles using `$distinctEmoji` distinct emojis
                                    """.trimMargin()
                                val pairs = listOf(
                                    "roles" to distinctRoles,
                                    "emojis" to distinctEmoji,
                                )

                                embed {
                                    color = DISCORD_FUCHSIA
                                    title = "Roles"
                                    description = content
                                    pairs.forEach { (key, v) ->
                                        field {
                                            inline = true
                                            name = key
                                            value = v.toString()
                                        }
                                    }
                                }
                            }

                            twitch?.run {

                                // FIXME migrate twitch to json as well
                                val filteredTwitchConfigs = guildConfigs.mapNotNull { (guild, config) ->
                                    twitch.loadConfig(guild)?.takeIf { it.configs.isNotEmpty() }
                                }
                                val twitchConfigs = filteredTwitchConfigs.flatMap {
                                    it.configs.values
                                }
                                val roles = twitchConfigs.distinctBy {
                                    it.roleId
                                }.count()
                                val twitchUsers = twitchConfigs.distinctBy {
                                    it.twitchUserName
                                }.count()
                                val guilds = filteredTwitchConfigs.size

                                val pairs = listOf(
                                    "streams" to twitchUsers,
                                    "roles" to roles,
                                )

                                embed {
                                    color = twitch.color
                                    title = "Twitch"
                                    description = """
                                        |configured in `$guilds` guilds
                                        |`$twitchUsers` streams are set up to ping `${roles}` unique roles
                                    """.trimMargin()
                                    pairs.forEach { (key, v) ->
                                        field {
                                            inline = true
                                            name = key
                                            value = v.toString()
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}
