#!/usr/bin/env kotlin

@file:DependsOn("it.krzeminski:github-actions-kotlin-dsl:0.6.0")

import Docker_workflow_main.Util.secret
import Docker_workflow_main.Util.variable
import it.krzeminski.githubactions.actions.Action
import it.krzeminski.githubactions.actions.actions.CheckoutV2
import it.krzeminski.githubactions.domain.RunnerType.UbuntuLatest
import it.krzeminski.githubactions.domain.triggers.PullRequest
import it.krzeminski.githubactions.domain.triggers.Push
import it.krzeminski.githubactions.dsl.workflow
import it.krzeminski.githubactions.yaml.toYaml
import java.awt.Color
import java.nio.file.Paths
import kotlin.io.path.writeText

object Util {
    fun variable(variable: String): String = "\${{ $variable }}"
    fun secret(secret: String): String = "\${{ secrets.$secret }}"
    fun dollar(variable: String): String = "\$$variable"
}

val workflow = workflow(
    name = "Docker build & push",
    on = listOf(
        Push(
            branches = listOf("main"),
        ),
        PullRequest(
            branches = listOf("main"),
        )
    ),
    sourceFile = Paths.get(".github/workflows/docker_workflow.main.kts"),
    targetFile = Paths.get(".github/workflows/docker_workflow.yml"),
) {
    val buildJob = job(name = "build_job", runsOn = UbuntuLatest) {
        run(name = "Print greeting", command = "echo 'Hello world!'")
        uses(
            name = "Check out",
            action = CheckoutV2(
                fetchDepth = CheckoutV2.FetchDepth.Quantity(0)
            )
        )
        uses(
            name = "Cache",
            action = CacheV2(
                paths = listOf(
                    "~/.gradle",
                    ".gradle",
                    "~/.docker",
                ),
                key = "${variable("runner.os")}-gradle-cache-${variable("github.ref")}-${variable("hashFiles('**/build.gradle.kts', 'version.properties')")}"
            )
        )
        uses(
            name = "Docker Login",
            action = DockerLogin(
                username = secret("DOCKER_HUB_USERNAME"),
                password = secret("DOCKER_HUB_ACCESS_TOKEN"),
            )
        )
        uses(
            name = "Docker Setup buildx",
            action = SetupBuildX()
        )
        uses(
            name = "Build and push",
//            id = "docker_build_push",
            action = DockerBuildPush(
                context = ".",
                file = "./Dockerfile",
                push = true,
                tags = "${secret("DOCKER_HUB_USERNAME")}/${secret("DOCKER_HUB_REPOSITORY")}:latest",
            )
        )
        run(
            name = "image digest",
            command = "echo ${variable("steps.docker_build.outputs.digest")}",
//            command = "docker inspect ${secret("DOCKER_HUB_USERNAME")}/${secret("DOCKER_HUB_REPOSITORY")}:latest" +
//                    " | jq -r .[0].Id"
        )
    }
    job(
        name = "discord-notification",
        runsOn = UbuntuLatest,
        needs = listOf(
            buildJob
        ),
        condition = variable("always()"),
    ) {
        uses(
            name = "Discord Workflow Status Notifier",
            action = DiscordWebhook(
                webhookUrl = secret("WEBHOOK_URL"),
                includeDetails = true,
            ),
            condition = "always()"
        )
    }
}

class CacheV2(
    private val paths: List<String>,
    private val key: String,
) : Action("actions", "cache", "v2") {
    override fun toYamlArguments() = linkedMapOf(
        "path" to paths.joinToString("\n"),
        "key" to key,
    )
}

class DockerLogin(
    val username: String,
    val password: String,
) : Action("docker", "login-action", "v1") {
    override fun toYamlArguments() = linkedMapOf(
        "username" to username,
        "password" to password,
    )
}

class SetupBuildX(

) : Action("docker", "setup-buildx-action", "v1") {
    override fun toYamlArguments() = linkedMapOf<String, String>()
}

class DockerBuildPush(
    val context: String,
    val file: String,
    val push: Boolean,
    val tags: String,
) : Action("docker", "build-push-action", "v2") {
    override fun toYamlArguments() = linkedMapOf(
        "context" to context,
        "file" to file,
        "push" to push.toString(),
        "tags" to tags,
    )
}

class DiscordWebhook(
    val webhookUrl: String,
    val username: String? = null,
    val avatarUrl: String? = null,
    val title: String? = null,
    val description: String? = null,
    val includeDetails: Boolean? = null,
    val colorSuccess: Color? = null,
    val colorFailure: Color? = null,
    val colorCancelled: Color? = null,
    val githubToken: String? = null,
) : Action("nobrayner", "discord-webhook", "v1") {
    override fun toYamlArguments(): LinkedHashMap<String, String> = linkedMapOf(
        "github-token" to (githubToken ?: variable("github.token")),
        "discord-webhook" to webhookUrl,
        *listOfNotNull(
            username?.let {
                "username" to it
            },
            avatarUrl?.let {
                "avatar-url" to it
            },
            title?.let {
                "title" to it
            },
            description?.let {
                "description" to it
            },
            includeDetails?.let {
                "include-details" to it.toString()
            },
            colorSuccess?.let {
                "color-success" to "#" + Integer.toHexString(it.rgb).substring(2)
            },
            colorFailure?.let {
                "color-failure" to "#" + Integer.toHexString(it.rgb).substring(2)
            },
            colorCancelled?.let {
                "color-cancelled" to "#" + Integer.toHexString(it.rgb).substring(2)
            }
        ).toTypedArray()
    )
}

val yaml = workflow.toYaml(addConsistencyCheck = true)
    .replaceFirst(
        """
            |      - name: Build and push
        """.trimMargin(),
        """
            |      - name: Build and push
            |        id: docker_build
        """.trimMargin()
    )

if (args.contains("--save")) {
//    workflow.writeToFile()
    workflow.targetFile.writeText(yaml + "\n")
} else {
    println(yaml)
}