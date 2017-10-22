package com.camnter.gradle.plugin.resources.optimize.l2

import com.android.build.gradle.*
import com.android.build.gradle.api.BaseVariant
import com.camnter.gradle.plugin.resources.optimize.l2.utils.CommandUtils
import com.camnter.gradle.plugin.resources.optimize.l2.utils.CompressUtils
import com.camnter.gradle.plugin.resources.optimize.l2.utils.ImageUtils
import com.camnter.gradle.plugin.resources.optimize.l2.utils.WebpUtils
import org.gradle.api.DomainObjectSet
import org.gradle.api.Plugin
import org.gradle.api.Project

/**
 * @author CaMnter
 */

class ResourcesOptimizeL2Plugin implements Plugin<Project> {

    static final String BIN_DIR = '/usr/local/bin'
    static final String NOT_SUCH = 'no such'

    ResourcesOptimizeL2Extension resourcesOptimizeL2Extension

    /**
     * Apply this plugin to the given target object.
     *
     * @param target The target object
     */
    @Override
    void apply(Project target) {
        println "[ResourcesOptimizeL2Plugin]"
        target.extensions.create('resourcesOptimizeL2Extension', ResourcesOptimizeL2Extension)
        resourcesOptimizeL2Extension = target.resourcesOptimizeL2Extension
        // variants
        target.plugins.all {
            if (it instanceof FeaturePlugin) {
                FeatureExtension featureExtension = target.extensions.getByType(
                        FeatureExtension.class)
                execute(target, featureExtension.featureVariants)
            } else if (it instanceof AppPlugin) {
                AppExtension appExtension = target.extensions.getByType(AppExtension.class)
                execute(target, appExtension.applicationVariants)
            } else if (it instanceof LibraryPlugin) {
                LibraryExtension libraryExtension = target.extensions.getByType(
                        LibraryExtension.class)
                execute(target, libraryExtension.libraryVariants)
            }
        }
    }

    private void execute(Project project, DomainObjectSet<BaseVariant> variants) {
        // debug or assemble
        def taskNames = project.gradle.startParameter.taskNames
        def debugTask = false
        def containAssembleTask = false

        for (def taskName : taskNames) {
            if (taskName.contains("assemble") || taskName.contains("resguard")) {
                if (taskName.toLowerCase().endsWith("debug") && taskName.toLowerCase().
                        contains("debug")) {
                    debugTask = true
                }
                containAssembleTask = true
                break
            }
        }

        if (debugTask && !resourcesOptimizeL2Extension.debugResourcesSize) {
            return
        }

        if (containAssembleTask && !resourcesOptimizeL2Extension.debugResourcesSize) {
            return
        }

        project.afterEvaluate {
            variants.all {
                def resourcesDir
                if (it.productFlavors.size() == 0) {
                    resourcesDir = 'merged'
                } else {
                    resourcesDir = "merged/${it.productFlavors[0].name}"
                }

                def capitalize = it.name.capitalize()
                def processResourceTask = project.tasks.findByName(
                        "process${it.name.capitalize()}Resources")
                def taskName = "resourcesOptimizeL2${capitalize}"

                project.task(taskName) {
                    doLast {
                        def resourcesDirFile = new File(
                                "${project.projectDir}/build/intermediates/res/${resourcesDir}/")
                        def bigImagePathList = ([] as LinkedList<ArrayList<String>>)

                        def binResult = CommandUtils.exec("ls ${BIN_DIR}", null)
                        printf "%-57s = \n",
                                ['[ResourcesOptimizeL2Plugin]   [which bin]', binResult]

                        /**
                         * check compress tools
                         * */
                        def cwebpPath = binResult.contains('cwebp') ? "${BIN_DIR}/cwebp" : ''
                        def guetzliPath = binResult.contains('guetzliPath') ? "${BIN_DIR}/guetzli" :
                                ''
                        def pngquantPath = binResult.contains('pngquantPath') ?
                                "${BIN_DIR}/pngquant" : ''

                        printf "%-57s = \n",
                                ['[ResourcesOptimizeL2Plugin]   [bin cwebp path]', cwebpPath]
                        printf "%-57s = \n",
                                ['[ResourcesOptimizeL2Plugin]   [bin guetzli path]', guetzliPath]
                        printf "%-57s = \n",
                                ['[ResourcesOptimizeL2Plugin]   [bin pngquantPath path]', pngquantPath]

                        /**
                         * set path of extension
                         * */
                        if (resourcesOptimizeL2Extension.cwebpPath != '') {
                            cwebpPath = resourcesOptimizeL2Extension.cwebpPath
                        }
                        if (resourcesOptimizeL2Extension.guetzliPath != '') {
                            guetzliPath = resourcesOptimizeL2Extension.guetzliPath
                        }
                        if (resourcesOptimizeL2Extension.pngquantPath != '') {
                            pngquantPath = resourcesOptimizeL2Extension.pngquantPath
                        }

                        printf "%-57s = \n",
                                ['[ResourcesOptimizeL2Plugin]   [execute cwebp path]', cwebpPath]
                        printf "%-57s = \n",
                                ['[ResourcesOptimizeL2Plugin]   [execute guetzli path]', guetzliPath]
                        printf "%-57s = \n",
                                ['[ResourcesOptimizeL2Plugin]   [execute pngquantPath path]', pngquantPath]

                        def cwebpEnable = checkPath(cwebpPath)
                        def guetzliEnable = checkPath(guetzliPath)
                        def pngquantEnable = checkPath(pngquantPath)

                        printf "%-57s = \n",
                                ['[ResourcesOptimizeL2Plugin]   [cwebpEnable]', cwebpEnable]
                        printf "%-57s = \n",
                                ['[ResourcesOptimizeL2Plugin]   [guetzliEnable]', guetzliEnable]
                        printf "%-57s = \n",
                                ['[ResourcesOptimizeL2Plugin]   [pngquantEnable]', pngquantEnable]


                        resourcesDirFile.traverse {
                            def fileName = it.name
                            if (fileName.contains('drawable') || fileName.contains('mipmap')) {
                                if (ImageUtils.checkImageSize(it,
                                        1024 * project.resourcesSizeExtension.maxSize
                                        /* default 100kb*/)) {
                                    def name = it.path.
                                            replace('.flat' as String, '').
                                            replace(resourcesDirFile.path as String,
                                                    '').
                                            replace(capitalize.toLowerCase() as String,
                                                    '')
                                            .replaceAll('/', '')
                                    bigImagePathList << (["${((float) it.length() / 1024.0f).round(2)}kb", name] as ArrayList<String>)
                                }

                                // compress
                                if (resourcesOptimizeL2Extension.debugResourcesOptimize) {
                                    /**
                                     * jpg
                                     * eg: "/usr/local/bin/guetzli ${file.path} ${file.path}"
                                     *
                                     * png
                                     * eg: "/usr/local/bin/pngquant --skip-if-larger --speed 3 --force --output ${file.path} -- ${file.path}"
                                     * */
                                    CompressUtils.compressResource(it) { File file ->
                                        if (!pngquantEnable) return
                                        CommandUtils.command(
                                                "${pngquantPath} --skip-if-larger --speed 3 --force --output ${file.path} -- ${file.path}") {
                                            String output ->
                                        } { String error ->
                                            printf "%-44s >> \n",
                                                    ['[ResourcesOptimizeL2Plugin]   [CommandUtils]   [error]', error]
                                        }
                                    } { File file ->
                                        if (!guetzliEnable) return
                                        CommandUtils.command(
                                                "${guetzliPath} ${file.path} ${file.path}") {
                                            String output ->
                                        } { String error ->
                                            printf "%-44s >> \n",
                                                    ['[ResourcesOptimizeL2Plugin]   [CommandUtils]   [error]', error]
                                        }
                                    }
                                }

                                if (resourcesOptimizeL2Extension.webpConvert) {
                                    WebpUtils.securityFormatWebp(project, it) {
                                        File imageFile, File webpFile ->
                                            if (!cwebpEnable) return
                                            /**
                                             * "/usr/local/bin/cwebp ${imageFile.getPath()} -o ${webpFile.getPath()} -quiet"
                                             * */
                                            CommandUtils.command(
                                                    "${cwebpPath} ${imageFile.getPath()} -o ${webpFile.getPath()} -quiet") {
                                                String output ->
                                            } { String error ->
                                                printf "%-44s >> \n",
                                                        ['[ResourcesOptimizeL2Plugin]   [CommandUtils]   [error]', error]
                                            }
                                    }
                                }
                            }
                        }
                        if (bigImagePathList.size() > 0) {
                            printf "%-21s >> \n", ['[ResourcesOptimizeL2Plugin]']
                            printf "%-21s >> %s\n", ['[TaskName]', taskName]
                            printf "%-21s >> %s\n",
                                    ['[Directory]', resourcesDirFile]
                            printf "%-21s >> \n", ['[BigImage]']
                            // sort
                            bigImagePathList.sort { current, next ->
                                (current.get(0).
                                        replace('kb' as String, '') as Float) <=> (next.get(0).
                                        replace('kb' as String, '') as Float)
                            }
                        }
                        bigImagePathList.each {
                            printf "%10s : %s\n", it
                        }
                    }
                }
                project.tasks.findByName(taskName).dependsOn processResourceTask.taskDependencies.
                        getDependencies(processResourceTask)
                processResourceTask.dependsOn project.tasks.findByName(taskName)
            }
        }
    }

    static def checkPath(String path) {
        def result = CommandUtils.exec("${path}", null)
        if (result == null || result == '' || result.length() == 0) return false
        def lowerResult = result.toLowerCase()
        if (lowerResult.contains(NOT_SUCH)) return false
        result true
    }
}