# Plex Updater Gradle Plugin [![License](https://img.shields.io/github/license/plexusorg/plex-updater-gradle-plugin)](https://github.com/plexusorg/plex-updater-gradle-plugin/blob/master/LICENSE.md) [![Discord](https://img.shields.io/discord/927737516864446495)](https://discord.plex.us.org)

The Plex Updater Gradle Plugin writes the metadata that Plex's built-in updater reads for a Jenkins build of Plex or a
Plex module: the build's file, and the latest pointers of its channel (`dev` for a `-SNAPSHOT` version, `stable`
otherwise). Its `updaterMetadata` task writes them to `build/updater`, with a `telesphorepo.toml` for the
`plex-update-files` repository, and the Jenkins job uploads them with `telesphorepo publish`.

A module needs only the plugin: its name and API compatibility come from `module.yml`, and its jar is the Shadow jar
when the project has one, else the plain jar. Plex sets its own:

```kotlin
plexUpdater {
    projectName = "Plex"
    minecraftVersions = listOf("26.2", "26.3")
    apiCompatibility = 1
}
```
