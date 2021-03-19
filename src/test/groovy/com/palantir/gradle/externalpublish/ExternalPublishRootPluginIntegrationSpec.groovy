/*
 * (c) Copyright 2017 Palantir Technologies Inc. All rights reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.palantir.gradle.externalpublish

import com.google.common.collect.ImmutableList
import java.util.stream.Stream
import nebula.test.IntegrationSpec
import nebula.test.functional.ExecutionResult
import org.apache.commons.compress.compressors.gzip.GzipCompressorInputStream
import org.rauschig.jarchivelib.ArchiveFormat
import org.rauschig.jarchivelib.ArchiverFactory
import spock.lang.Unroll

class ExternalPublishRootPluginIntegrationSpec extends IntegrationSpec {
    private static final List<String> PUBLISH_PROJECT_TYPES = ImmutableList.of(
            'jar', 'dist', 'application-dist', 'gradle-plugin', 'conjure', 'custom')
    private static final List<String> SONATYPE_PROJECT_TYPES = PUBLISH_PROJECT_TYPES - 'gradle-plugin'
    private static final List<String> NON_CONFLICTING_PROJECT_TYPES = PUBLISH_PROJECT_TYPES - 'dist'

    def setup() {
        settingsFile << '''
            rootProject.name = 'root'
        '''.stripIndent()

        buildFile << '''
            buildscript {
                repositories {
                    gradlePluginPortal()
                    mavenCentral()
                    maven { url 'https://dl.bintray.com/palantir/releases/' }
                }
                
                dependencies {
                    classpath 'com.gradle.publish:plugin-publish-plugin:0.13.0'
                    classpath 'com.palantir.gradle.conjure:gradle-conjure:5.2.0'
                }
            }

            apply plugin: 'com.palantir.external-publish'
            
            allprojects {
                group = 'group'
                version = 'version'
                
                repositories {
                    mavenCentral()
                    maven { url 'https://dl.bintray.com/palantir/releases/' }
                }
            }
        '''.stripIndent()
    }

    File publishJar() {
        publishProject('jar')
    }

    File publishDist() {
        publishProject('dist')
    }

    File publishApplicationDist() {
        publishProject('application-dist')
    }

    File publishGradlePlugin() {
        publishProject('gradle-plugin')
    }

    File publishConjure() {
        publishProject('conjure')
    }

    File publishCustom() {
        publishProject('custom')
    }

    File publishProject(String type, String subprojectName = type) {
        def subprojectDir = new File(projectDir, subprojectName)

        if (!subprojectDir.exists()) {
            addSubproject(subprojectName)
        }

        def subprojectBuildGradle = new File(subprojectDir, 'build.gradle')

        subprojectBuildGradle << """
            apply plugin: 'com.palantir.external-publish-${type}'
        """.stripIndent()

        writeHelloWorld(subprojectDir)

        if (type == 'dist') {
            subprojectBuildGradle << '''
                task distTar(type: Tar) {
                    archiveFileName = 'foo'
                    destinationDirectory = file('build')
                    compression Compression.GZIP
                    into('/') {
                        from '.'
                        include 'build.gradle'
                    }
                }
            '''.stripIndent()
        }

        if (type == 'gradle-plugin') {
            subprojectBuildGradle << '''
                apply plugin: 'com.gradle.plugin-publish'
                
                gradlePlugin {
                    plugins {
                        test {
                            id = 'test-plugin'
                            implementationClass = 'test.TestPlugin'
                        }
                    }
                }
                
                pluginBundle {
                    website = 'https://example.com/'
                    vcsUrl = 'https://example.com/'
                    description = 'Test'
                    tags = ['testing']
                }
            '''.stripIndent()

            writeJavaSourceFile('''
                import org.gradle.api.Plugin;
                import org.gradle.api.Project;
                class TestPlugin extends Plugin<Project> {
                    public void apply(Project project) { }
                }
            '''.stripIndent(), 'src/main/java/test/TestPlugin.java')
        }

        if (type == 'conjure') {

            buildFile << '''
                configurations.all { conf ->
                    ['com.palantir.conjure:conjure:4.14.2', 'com.palantir.conjure.java:conjure-java:5.49.0'].each {
                        conf.dependencyConstraints.add(project.dependencies.constraints.create(it))
                    }
                }
            '''.stripIndent()

            def conjureObjectsDir = directory('conjure-objects', subprojectDir)
            settingsFile << "include '${subprojectDir.getName()}:${conjureObjectsDir.getName()}'\n"

            file('src/main/conjure/api.yml', subprojectDir) << '{}'
        }

        if (type == 'custom') {
            subprojectBuildGradle << '''
                externalPublishing {
                    publication('foo') {
                        artifactId 'foo'
                        artifact file('build.gradle')
                    }
                    publication('bar') {
                        artifactId 'bar'
                        artifact file('build.gradle')
                    }
                }
            '''
        }

        return subprojectDir
    }

    void allPublishProjects() {
        PUBLISH_PROJECT_TYPES.each {publishProject(it) }
    }

    def 'can apply plugin without signing without exploding'() {
        setup:
        allPublishProjects()

        when:
        ExecutionResult result = runTasksSuccessfully('tasks', '--all', '-i')
        println result.standardOutput

        then:
        result.success
    }

    def 'can publish jar to local maven repo on disk'() {
        setup:
        publishJar()
        def mavenRepoDir = testingMavenRepo()

        when:
        runSuccessfullyWithSigning('publishMavenPublicationToTestRepoRepository')

        then:
        def gnv = new File(mavenRepoDir, 'group/jar/version')

        new File(gnv, 'jar-version.jar').exists()
        new File(gnv, 'jar-version.jar.asc').exists()
        new File(gnv, 'jar-version-javadoc.jar').exists()
        new File(gnv, 'jar-version-javadoc.jar.asc').exists()
        new File(gnv, 'jar-version-sources.jar').exists()
        new File(gnv, 'jar-version-sources.jar.asc').exists()

        verifyPomFile(gnv, 'jar')
    }

    def 'can publish dist to local maven repo on disk'() {
        setup:
        publishDist()
        def mavenRepoDir = testingMavenRepo()

        when:
        runSuccessfullyWithSigning('publishDistPublicationToTestRepoRepository')

        then:
        def gnv = new File(mavenRepoDir, 'group/dist/version')

        def applicationDistTar = new File(gnv, 'dist-version.tgz')
        applicationDistTar.exists()
        new File(gnv, 'dist-version.tgz.asc').exists()

        verifyPomFile(gnv, 'dist')
    }

    def 'can publish application dist to local maven repo on disk'() {
        setup:
        publishApplicationDist()
        def mavenRepoDir = testingMavenRepo()

        when:
        runSuccessfullyWithSigning('publishDistPublicationToTestRepoRepository')

        then:
        def gnv = new File(mavenRepoDir, 'group/application-dist/version')

        def applicationDistTar = new File(gnv, 'application-dist-version.tgz')
        applicationDistTar.exists()
        new File(gnv, 'application-dist-version.tgz.asc').exists()

        // Check that we fix the classpath for windows apps
        def extracted = directory("application-dist-extracted")
        ArchiverFactory.createArchiver(ArchiveFormat.TAR)
                .extract(new GzipCompressorInputStream(new FileInputStream(applicationDistTar)), extracted)
        new File(extracted, "application-dist-version/bin/application-dist.bat").text
                .contains('set CLASSPATH=%APP_HOME%\\lib\\\r\n')

        verifyPomFile(gnv, 'application-dist')
    }

    def 'can publish conjure json to local maven repo on disk'() {
        setup:
        publishConjure()
        def mavenRepoDir = testingMavenRepo()

        when:
        runSuccessfullyWithSigning('publishConjurePublicationToTestRepoRepository')

        then:
        def gnv = new File(mavenRepoDir, 'group/conjure/version')

        def conjureJson = new File(gnv, 'conjure-version.conjure.json')
        conjureJson.exists()
        new File(gnv, 'conjure-version.conjure.json.asc').exists()

        verifyPomFile(gnv, 'conjure')
    }

    def 'can publish custom publications to local maven repo on disk'() {
        setup:
        publishCustom()
        def mavenRepoDir = testingMavenRepo()

        when:
        runSuccessfullyWithSigning(
                'publishFooPublicationToTestRepoRepository',
                'publishBarPublicationToTestRepoRepository')


        then:
        ['foo', 'bar'].each { name ->
            def gnv = new File(mavenRepoDir, "group/${name}/version")
            verifyPomFile(gnv, name)

            new File(gnv, "${name}-version.gradle").exists()
        }
    }

    void verifyPomFile(File gnv, String name) {
        def pom = new XmlParser().parse(new File(gnv, "${name}-version.pom"))

        pom.groupId.text() == 'group'
        pom.name.text() == name
        pom.version.text() == 'version'
        // Sonatype requires a description
        !pom.description.text().isEmpty()
        pom.url.text().endsWith 'gradle-external-publish-plugin'

        def license = pom.licenses.license
        license.name.text() == 'The Apache License, Version 2.0'
        license.url.text() == 'https://www.apache.org/licenses/LICENSE-2.0'

        def developer = pom.developers.developer
        developer.id.text() == 'palantir'
        developer.name.text() == 'Palantir Technologies Inc'
        developer.organizationUrl.text() == 'https://www.palantir.com'

        pom.scm.url.text().endsWith 'gradle-external-publish-plugin.git'
    }

    File testingMavenRepo() {
        def mavenRepoDir = directory('mavenRepo')

        buildFile << """
            subprojects {
                pluginManager.withPlugin('maven-publish') {
                    publishing {
                        repositories {
                            maven {
                                name "testRepo"
                                url "${mavenRepoDir}"
                            }
                        }
                    }
                }
            }
        """.stripIndent()

        return mavenRepoDir
    }

    def 'signs jars correctly'() {
        setup:
        def jarSubprojectDir = publishJar()

        when:
        runSuccessfullyWithSigning('signMavenPublication')

        then:
        new File(jarSubprojectDir, 'build/libs/jar-version.jar.asc').exists()
        new File(jarSubprojectDir, 'build/libs/jar-version-javadoc.jar.asc').exists()
        new File(jarSubprojectDir, 'build/libs/jar-version-sources.jar.asc').exists()
    }

    @Unroll
    def 'publish task for #type depends on publishing to sonatype'() {
        setup:
        publishProject(type)

        when:
        def stdout = runSuccessfullyWithSigning('--dry-run', ":${type}:publish").standardOutput

        then:
        stdout.find ":${type}:publish.*PublicationToSonatypeRepository SKIPPED"
        stdout.contains ':closeSonatypeStagingRepository SKIPPED'

        where:
        type << SONATYPE_PROJECT_TYPES
    }

    @Unroll
    def 'fails with a good error message if signing is not enabled for #type'() {
        setup:
        publishProject(type)

        when:
        def errorMessage = runTasksWithFailure(":${type}:publish").failure.cause.cause.message

        then:
        errorMessage == 'The required environment variables to sign the release could not be found. ' +
                'Check the logs above to find out which ones are missing.'

        where:
        type << SONATYPE_PROJECT_TYPES
    }

    def 'fails build if publish if version ends in dirty'() {
        setup:
        allPublishProjects()

        buildFile << '''
            allprojects {
                version 'version.dirty'
            }
        '''.stripIndent()


        when:
        def executionResult = runFailingWithSigning('publish')
        println executionResult.standardOutput
        def errorMessage = executionResult.failure.cause.cause.message

        then:
        errorMessage.contains 'dirty'
    }

    def 'does close but not release staging sonatype repo if not on a tag build'() {
        setup:
        allPublishProjects()

        when:
        def stdout = runSuccessfullyWithSigning('publish', '--dry-run').standardOutput

        then:
        stdout.contains(':closeSonatypeStagingRepository')
        !stdout.contains(':releaseSonatypeStagingRepository')
    }

    def 'does release staging sonatype repo if on a tag build'() {
        setup:
        allPublishProjects()

        when:
        def stdout = runSuccessfullyWithSigning('-P__TESTING_CIRCLE_TAG=tag', 'publish', '--dry-run').standardOutput

        then:
        stdout.contains(':closeSonatypeStagingRepository')
        stdout.contains(':releaseSonatypeStagingRepository')
    }

    def 'runs publish tasks as a dependency of check on upgrade excavator'() {
        setup:
        allPublishProjects()

        when:
        def stdout = runSuccessfullyWithSigning(
                '--dry-run', '-P__TESTING_CIRCLE_BRANCH=roomba/external-publish-plugin-migration', 'check')
                .standardOutput

        println stdout

        then:
        stdout.contains(':initializeSonatypeStagingRepository SKIPPED')
        stdout.contains(':jar:publishMavenPublicationToSonatypeRepository SKIPPED')
        stdout.contains(':dist:publishDistPublicationToSonatypeRepository SKIPPED')
        stdout.contains(':closeSonatypeStagingRepository SKIPPED')
        !stdout.contains(':releaseSonatypeStagingRepository SKIPPED')
    }

    def 'does not run publish tasks as a dependency of check on normal run'() {
        setup:
        allPublishProjects()

        when:
        def stdout = runSuccessfullyWithSigning(
                '--dry-run', '-P__TESTING_CIRCLE_BRANCH=my-feature-branch', 'check')
                .standardOutput

        println stdout

        then:
        !stdout.contains(':initializeSonatypeStagingRepository SKIPPED')
        !stdout.contains(':jar:publishMavenPublicationToSonatypeRepository SKIPPED')
        !stdout.contains(':dist:publishDistPublicationToSonatypeRepository SKIPPED')
        !stdout.contains(':closeSonatypeStagingRepository SKIPPED')
        !stdout.contains(':releaseSonatypeStagingRepository SKIPPED')
    }

    def 'does not publish gradle plugins on publish on non tag build'() {
        setup:
        publishGradlePlugin()
        disableAllTaskActions()

        when:
        def stdout = runSuccessfullyWithSigning('publish').standardOutput

        then:
        stdout.contains(':gradle-plugin:publishPlugins SKIPPED')
    }

    def 'publishes gradle plugins on publish on tag build'() {
        setup:
        publishGradlePlugin()
        disableAllTaskActions()

        when:
        def stdout = runSuccessfullyWithSigning('-P__TESTING_CIRCLE_TAG=tag', 'publish').standardOutput

        then:
        stdout.contains(':gradle-plugin:publishPlugins UP-TO-DATE')
    }

    def 'does not publish gradle plugin descriptors to sonatype when external-publish-jar is applied'() {
        setup:
        def subprojectDir = publishGradlePlugin()

        file('build.gradle', subprojectDir) << '''
            apply plugin: 'com.palantir.external-publish-jar'
        '''.stripIndent()

        disableAllTaskActions()

        when:
        def stdout = runSuccessfullyWithSigning('-P__TESTING_CIRCLE_TAG=tag', 'publish').standardOutput

        then:
        stdout.contains(':gradle-plugin:publishPluginMavenPublicationToSonatypeRepository SKIPPED')
        stdout.contains(':gradle-plugin:publishTestPluginMarkerMavenPublicationToSonatypeRepository SKIPPED')
        stdout.contains(':gradle-plugin:publishMavenPublicationToSonatypeRepository UP-TO-DATE')
        stdout.contains(':gradle-plugin:publishPlugins UP-TO-DATE')
    }

    def 'can publish all the plugins together in one project to sonatype'() {
        setup:
        NON_CONFLICTING_PROJECT_TYPES.each {type ->
            publishProject(type, 'combined')
        }

        disableAllTaskActions()

        when:
        def stdout = runSuccessfullyWithSigning('publish').standardOutput
        println stdout

        then:
        NON_CONFLICTING_PROJECT_TYPES.each {type ->
            stdout.find ":${type}:publish.*PublicationToSonatypeRepository UP-TO-DATE"
        }

    }

    def 'root plugin does not need to be explicitly applied if there is a publish plugin applied at the root'() {
        setup:
        publishProject('jar', '.')

        buildFile.text = buildFile.text.replace('''apply plugin: 'com.palantir.external-publish'\n''', '')

        when:
        def executionResult = runTasksSuccessfully('tasks')

        then:
        executionResult.success
    }

    private void disableAllTaskActions() {
        buildFile << '''
            allprojects {
                afterEvaluate {
                    tasks.configureEach {
                        setActions([])
                    }
                }
            }
        '''.stripIndent()
    }

    private ExecutionResult runSuccessfullyWithSigning(String... tasks) {
        return runWithSigning({ String... args -> runTasksSuccessfully(args) }, tasks)
    }

    private ExecutionResult runFailingWithSigning(String... tasks) {
        return runWithSigning({ String... args -> runTasksWithFailure(args) }, tasks)
    }

    private ExecutionResult runWithSigning(Closure<ExecutionResult> runTasksMethod, String... tasks) {
        def privateKey = getClass().getClassLoader()
                .getResourceAsStream("testing-gpg-key.pgp")
                .getBytes()

        return runTasksMethod(Stream.concat(Stream.of(
                    '-P__TESTING_GPG_SIGNING_KEY_ID=4F33301C',
                    "-P__TESTING_GPG_SIGNING_KEY=${Base64.getEncoder().encodeToString(privateKey)}",
                    '-P__TESTING_GPG_SIGNING_KEY_PASSWORD=password',
                    '-P__TESTING_CIRCLE_TAG=').map({ it.toString() }),
                Stream.of(tasks)).toArray({new String[it] }))
    }

    @Override
    ExecutionResult runTasksSuccessfully(String... tasks) {
        def executionResult = runTasks(tasks)
        if (executionResult.failure) {
            println executionResult.standardOutput
            println executionResult.standardError
            executionResult.rethrowFailure()
        }

        return executionResult
    }
}
