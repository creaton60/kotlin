/*
 * Copyright 2010-2019 JetBrains s.r.o. Use of this source code is governed by the Apache 2.0 license
 * that can be found in the license/LICENSE.txt file.
 */

package org.jetbrains.kotlin.gradle.plugin.cocoapods

import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.tasks.Sync
import org.jetbrains.kotlin.gradle.dsl.KotlinMultiplatformExtension
import org.jetbrains.kotlin.gradle.dsl.kotlinExtension
import org.jetbrains.kotlin.gradle.plugin.KotlinCompilation
import org.jetbrains.kotlin.gradle.plugin.addExtension
import org.jetbrains.kotlin.gradle.plugin.mpp.KotlinNativeTarget
import org.jetbrains.kotlin.gradle.plugin.whenEvaluated
import org.jetbrains.kotlin.gradle.tasks.DummyFrameworkTask
import org.jetbrains.kotlin.gradle.tasks.PodspecTask
import org.jetbrains.kotlin.konan.target.Family
import org.jetbrains.kotlin.konan.target.HostManager
import java.io.File

internal val Project.cocoapodsBuildDirs: CocoapodsBuildDirs
        get() = CocoapodsBuildDirs(this)

internal class CocoapodsBuildDirs(val project: Project) {
    val root: File
        get() = project.buildDir.resolve("cocoapods")

    val framework: File
        get() = root.resolve("framework")

    val defs: File
        get() = root.resolve("defs")
}

open class KotlinCocoapodsPlugin: Plugin<Project> {

    private fun KotlinMultiplatformExtension.supportedTargets() = targets
        .withType(KotlinNativeTarget::class.java)
        .matching { it.konanTarget.family == Family.IOS || it.konanTarget.family == Family.OSX }

    private fun createDefaultFrameworks(kotlinExtension: KotlinMultiplatformExtension) {
        kotlinExtension.supportedTargets().all { target ->
            target.binaries.framework {
                // TODO: Add in the framework DSL.
                this.freeCompilerArgs.add("-Xstatic-framework")
            }
        }
    }

    private fun createSyncTask(
        project: Project,
        kotlinExtension: KotlinMultiplatformExtension
    ) = project.whenEvaluated {
        val requestedTargetName = project.findProperty(TARGET_PROPERTY)?.toString() ?: return@whenEvaluated
        val requestedBuildType = project.findProperty(CONFIGURATION_PROPERTY)?.toString()?.toUpperCase() ?: return@whenEvaluated

        val requestedTarget = HostManager().targetByName(requestedTargetName)

        val targets = kotlinExtension.supportedTargets().matching {
            it.konanTarget == requestedTarget
        }

        check(targets.isNotEmpty()) { "The project doesn't contain a target for the requested platform: $requestedTargetName" }
        check(targets.size == 1) { "The project has more than one targets for the requested platform: $requestedTargetName" }

        val framework =  targets.single().binaries.getFramework(requestedBuildType)
        project.tasks.create("syncFramework", Sync::class.java) {
            it.group = TASK_GROUP
            it.description = "Copies a framework for given platform and build type into the cocoapods build directory"

            it.dependsOn(framework.linkTask)
            it.from(framework.linkTask.destinationDir)
            it.destinationDir = cocoapodsBuildDirs.framework
        }
    }

    private fun createPodspecGenerationTask(
        project: Project,
        cocoapodsExtension: CocoapodsExtension
    ) {
        val dummyFrameworkTask = project.tasks.create("generateDummyFramework", DummyFrameworkTask::class.java)

        project.tasks.create("generatePodspec", PodspecTask::class.java) {
            it.group = TASK_GROUP
            it.description = "Generates a podspec file for Cocoapods import"
            it.settings = cocoapodsExtension
            it.dependsOn(dummyFrameworkTask)
        }
    }

    private fun String.processSearchPath(prefix: String): List<String> =
        this.split(' ')
            .filter { it.isNotBlank() }
            .mapNotNull { "^\\s*\"?([^\"]*)\"?\$".toRegex().matchEntire(it)?.groupValues?.get(1) }
            .map { "$prefix$it" }

    private fun createInterops(
        project: Project,
        kotlinExtension: KotlinMultiplatformExtension,
        cocoapodsExtension: CocoapodsExtension
    ) {
        cocoapodsExtension.pods.all { pod ->
            kotlinExtension.supportedTargets().all { target ->
                target.compilations.getByName(KotlinCompilation.MAIN_COMPILATION_NAME).cinterops.create(pod.name) { interop ->
                    val defDir = project.cocoapodsBuildDirs.defs.apply {
                        mkdirs()
                    }
                    interop.defFile = defDir.resolve("${pod.name}.def").apply {
                        writeText("""
                            language = Objective-C
                            package = cocoapods.${pod.moduleName}

                            modules = ${pod.moduleName}
                        """.trimIndent())

                        // TODO: Use project properties to pass these settings.
                        // TODO: Process " in these args
                        System.getenv("OTHER_CFLAGS")?.let {
                            interop.compilerOpts.addAll(
                                it.split(' ').map {
                                    it.filter { it != '"' }
                                }
                            )
                        }
                        System.getenv("HEADER_SEARCH_PATHS")?.let { interop.compilerOpts.addAll(it.processSearchPath("-I")) }
                        System.getenv("FRAMEWORK_SEARCH_PATHS")?.let { interop.compilerOpts.addAll(it.processSearchPath("-F")) }
                    }
                }
            }
        }
    }

    override fun apply(project: Project): Unit = with(project) {
        pluginManager.withPlugin("kotlin-multiplatform") {
            val kotlinExtension = project.kotlinExtension as? KotlinMultiplatformExtension
            if (kotlinExtension == null) {
                logger.info("Cannot apply cocoapods plugin: Cannot cast ${project.kotlinExtension} to KotlinMultiplatformExtension")
                return@withPlugin
            }

            val cocoapodsExtension = CocoapodsExtension(this, kotlinExtension)

            kotlinExtension.addExtension(EXTENSION_NAME, cocoapodsExtension)
            createDefaultFrameworks(kotlinExtension)
            createSyncTask(project, kotlinExtension)
            createPodspecGenerationTask(project, cocoapodsExtension)
            createInterops(project, kotlinExtension, cocoapodsExtension)
        }
    }

    companion object {
        const val EXTENSION_NAME = "cocoapods"
        const val TASK_GROUP = "cocoapods"

        const val TARGET_PROPERTY = "kotlin.native.cocoapods.target"
        const val CONFIGURATION_PROPERTY = "kotlin.native.cocoapods.configuration"
    }
}