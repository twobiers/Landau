package de.tobi6112.landau

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.parameters.options.associate
import com.github.ajalt.clikt.parameters.options.default
import com.github.ajalt.clikt.parameters.options.option
import com.github.ajalt.clikt.parameters.options.required
import de.tobi6112.landau.command.CommandModule
import de.tobi6112.landau.command.core.AbstractCommand
import de.tobi6112.landau.command.service.ApplicationCommandService
import de.tobi6112.landau.core.config.Config
import de.tobi6112.landau.data.Database
import de.tobi6112.landau.data.DatabaseModule
import de.tobi6112.landau.data.command.GlobalCommandRepository
import de.tobi6112.landau.data.command.GuildCommandRepository
import de.tobi6112.landau.discord.ApplicationInfo
import discord4j.core.GatewayDiscordClient
import discord4j.core.event.domain.InteractionCreateEvent
import discord4j.core.event.domain.lifecycle.ReadyEvent
import mu.KotlinLogging
import org.koin.core.context.startKoin
import org.koin.environmentProperties
import org.koin.java.KoinJavaComponent.getKoin
import org.koin.java.KoinJavaComponent.inject
import org.koin.logger.slf4jLogger
import reactor.core.publisher.Mono

import kotlin.system.exitProcess

/** Main class of Landau Bot, processes CLI parameters and creates DiscordClient */
class Landau : CliktCommand() {
  private val logger = KotlinLogging.logger {}

  // CLI Options
  private val token by option("-t", "--token", help = "Bot token", envvar = "BOT_TOKEN").required()
  private val configEnv by option(
      "-c", "--config", help = "Configuration environment", envvar = "CONFIG_PROFILE")
      .default("")
  private val systemProperties: Map<String, String> by option("-D").associate()

  @Suppress("MAGIC_NUMBER", "LOCAL_VARIABLE_EARLY_DECLARATION")
  override fun run() {
    // Set system properties
    systemProperties.entries.forEach { System.setProperty(it.key, it.value) }

    startKoin {
      slf4jLogger()
      environmentProperties()
      properties(mapOf("BOT_TOKEN" to token, "CONFIG_PROFILE" to configEnv))
      modules(CommandModule.module, LandauModule.module, DatabaseModule.module)
    }

    val config: Config by inject(Config::class.java)
    val client: GatewayDiscordClient by inject(GatewayDiscordClient::class.java)
    val applicationInfo by inject<ApplicationInfo>(ApplicationInfo::class.java)
    val applicationCommands: List<AbstractCommand> by lazy {
      getKoin().getAll<AbstractCommand>().distinct()
    }
    val applicationCommandService: ApplicationCommandService by inject(
        ApplicationCommandService::class.java)
    val guildCommandRepository: GuildCommandRepository by inject(GuildCommandRepository::class.java)
    val globalCommandRepository: GlobalCommandRepository by inject(
        GlobalCommandRepository::class.java)

    require(applicationCommands.groupBy { it.name.trim() }.filter { it.value.size > 1 }.isEmpty()) {
      "It is not possible to register multiple commands with the same name"
    }

    Database.connect(
        url = config.database.jdbcUrl,
        driver = config.database.driver,
        user = config.database.username,
        password = config.database.password)

    client
        .on(ReadyEvent::class.java)
        .doOnNext { logger.info { "${applicationInfo.applicationName} is ready..." } }
        .blockFirst()

    val commands: MutableMap<Long, AbstractCommand> = mutableMapOf()

    applicationCommandService.processGlobalCommands(applicationCommands)
    applicationCommandService.processGuildCommands(applicationCommands)

    /* applicationCommandService
    .createCommands(applicationCommands)
    .doOnNext {
      if (commands.containsKey(it.first)) {
        logger.warn { "Command with id ${it.first} already exist" }
      } else {
        commands[it.first] = it.second
      }
    }
    .subscribe()*/

    val allCommands = guildCommandRepository.getAll().plus(globalCommandRepository.getAll())

    // Listen for Interaction events
    client
        .eventDispatcher
        .on(InteractionCreateEvent::class.java)
        .flatMap { event ->
          val command = allCommands.find { it.first == event.commandId.asLong() }
          command?.let { cmd ->
            return@flatMap applicationCommands.find { it.name == cmd.second }!!.handleEvent(event)
          }
              ?: run {
                logger.debug {
                  "No command with ID ${event.commandId.asLong()} (name: ${event.commandName}) found"
                }
                return@flatMap Mono.empty()
              }
        }
        .subscribe()

    // Shutdown on disconnect
    client.onDisconnect().block()
    logger.info { "Shutting down..." }
    exitProcess(0)
  }
}

fun main(args: Array<String>) = Landau().main(args)
