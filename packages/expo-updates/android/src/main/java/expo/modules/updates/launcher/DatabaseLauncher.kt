package expo.modules.updates.launcher

import android.content.Context
import android.net.Uri
import android.util.Log
import expo.modules.updates.UpdatesConfiguration
import expo.modules.updates.db.UpdatesDatabase
import expo.modules.updates.db.entity.AssetEntity
import expo.modules.updates.db.entity.UpdateEntity
import expo.modules.updates.db.enums.UpdateStatus
import expo.modules.updates.loader.EmbeddedLoader
import expo.modules.updates.loader.FileDownloader
import expo.modules.updates.loader.LoaderFiles
import expo.modules.updates.manifest.EmbeddedManifest
import expo.modules.updates.manifest.ManifestMetadata
import expo.modules.updates.selectionpolicy.SelectionPolicy
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import java.io.File
import java.util.*

/**
 * Implementation of [Launcher] that uses the SQLite database and expo-updates file store as the
 * source of updates.
 *
 * Uses the [SelectionPolicy] to choose an update from SQLite to launch, then ensures that the
 * update is safe and ready to launch (i.e. all the assets that SQLite expects to be stored on disk
 * are actually there).
 *
 * This class also includes failsafe code to attempt to re-download any assets unexpectedly missing
 * from disk (since it isn't necessarily safe to just revert to an older update in this case).
 * Distinct from the [Loader] classes, though, this class does *not* make any major modifications to
 * the database; its role is mostly to read the database and ensure integrity with the file system.
 *
 * It's important that the update to launch is selected *before* any other checks, e.g. the above
 * check for assets on disk. This is to preserve the invariant that no older update should ever be
 * launched after a newer one has been launched.
 */
class DatabaseLauncher(
  private val context: Context,
  private val database: UpdatesDatabase,
  private val configuration: UpdatesConfiguration,
  private val updatesDirectory: File?,
  private val fileDownloader: FileDownloader,
  private val selectionPolicy: SelectionPolicy
) {
  private val loaderFiles: LoaderFiles = LoaderFiles()

  suspend fun launch(): LauncherResult {
    val launchedUpdate = getLaunchableUpdate() ?: throw Exception("No launchable update was found. If this is a bare workflow app, make sure you have configured expo-updates correctly in android/app/build.gradle.")

    database.updateDao().markUpdateAccessed(launchedUpdate)

    if (launchedUpdate.status == UpdateStatus.EMBEDDED) {
      return LauncherResult(
        launchedUpdate = launchedUpdate,
        launchAssetFile = null,
        bundleAssetName = EmbeddedLoader.BARE_BUNDLE_FILENAME,
        localAssetFiles = null,
        isUsingEmbeddedAssets = true
      )
    } else if (launchedUpdate.status == UpdateStatus.DEVELOPMENT) {
      return LauncherResult(
        launchedUpdate = launchedUpdate,
        launchAssetFile = null,
        bundleAssetName = null,
        localAssetFiles = null,
        isUsingEmbeddedAssets = true
      )
    }

    // verify that we have all assets on disk
    // according to the database, we should, but something could have gone wrong on disk
    val launchAsset = database.updateDao().loadLaunchAsset(launchedUpdate.id)
    if (launchAsset.relativePath == null) {
      throw AssertionError("Launch Asset relativePath should not be null")
    }
    var launchAssetFile = ensureAssetExists(launchAsset, database, context)?.toString()

    val assetEntities = database.assetDao().loadAssetsForUpdate(launchedUpdate.id)

    val localAssetFiles = coroutineScope {
      val embeddedAssetMap = embeddedAssetFileMap(context)

      assetEntities.map { asset ->
        async {
          if (asset.id == launchAsset.id) {
            // we took care of this one above
            return@async
          }
          val filename = asset.relativePath
          if (filename != null) {
            val assetFile = ensureAssetExists(asset, database, context)
            if (asset.isLaunchAsset) {
              launchAssetFile = if (assetFile == null) {
                Log.e(TAG, "Could not launch; failed to load update from disk or network")
                null
              } else {
                assetFile.toString()
              }
            }
            if (assetFile != null) {
              embeddedAssetMap[asset] = Uri.fromFile(assetFile).toString()
            }
          }
        }
      }.awaitAll()

      embeddedAssetMap
    }

    if (launchAssetFile == null) {
      throw Exception("launch asset is unexpectedly null")
    }

    return LauncherResult(
      launchedUpdate = launchedUpdate,
      launchAssetFile = launchAssetFile,
      bundleAssetName = null,
      localAssetFiles = localAssetFiles,
      isUsingEmbeddedAssets = false
    )
  }

  fun getLaunchableUpdate(): UpdateEntity? {
    val launchableUpdates = database.updateDao().loadLaunchableUpdatesForScope(configuration.scopeKey)

    // We can only run an update marked as embedded if it's actually the update embedded in the
    // current binary. We might have an older update from a previous binary still listed as
    // "EMBEDDED" in the database so we need to do this check.
    val embeddedUpdateManifest = EmbeddedManifest.get(context, configuration)
    val filteredLaunchableUpdates = mutableListOf<UpdateEntity>()
    for (update in launchableUpdates) {
      if (update.status == UpdateStatus.EMBEDDED) {
        if (embeddedUpdateManifest != null && embeddedUpdateManifest.updateEntity.id != update.id) {
          continue
        }
      }
      filteredLaunchableUpdates.add(update)
    }
    val manifestFilters = ManifestMetadata.getManifestFilters(database, configuration)
    return selectionPolicy.selectUpdateToLaunch(filteredLaunchableUpdates, manifestFilters)
  }

  private fun embeddedAssetFileMap(context: Context): MutableMap<AssetEntity, String> {
    val embeddedManifest = EmbeddedManifest.get(context, this.configuration)
    val embeddedAssets: List<AssetEntity> = embeddedManifest?.assetEntityList ?: listOf()
    return mutableMapOf<AssetEntity, String>().apply {
      for (asset in embeddedAssets) {
        if (asset.isLaunchAsset) {
          continue
        }
        val filename = asset.relativePath
        if (filename != null) {
          val embeddedAssetFilename = asset.embeddedAssetFilename
          val file = if (embeddedAssetFilename != null) {
            File(embeddedAssetFilename)
          } else {
            File(updatesDirectory, asset.relativePath!!)
          }
          this[asset] = Uri.fromFile(file).toString()
        }
      }
    }
  }

  private suspend fun ensureAssetExists(asset: AssetEntity, database: UpdatesDatabase, context: Context): File? {
    val assetFile = File(updatesDirectory, asset.relativePath ?: "")
    val assetFileExists = assetFile.exists()
    if (assetFileExists) {
      return assetFile
    }

    // something has gone wrong, we're missing this asset
    // first we check to see if a copy is embedded in the binary
    val embeddedUpdateManifest = EmbeddedManifest.get(context, configuration)
    if (embeddedUpdateManifest != null) {
      val embeddedAssets = embeddedUpdateManifest.assetEntityList
      var matchingEmbeddedAsset: AssetEntity? = null
      for (embeddedAsset in embeddedAssets) {
        if (embeddedAsset.key != null && embeddedAsset.key == asset.key) {
          matchingEmbeddedAsset = embeddedAsset
          break
        }
      }

      if (matchingEmbeddedAsset != null) {
        try {
          val hash = loaderFiles.copyAssetAndGetHash(matchingEmbeddedAsset, assetFile, context)
          if (Arrays.equals(hash, asset.hash)) {
            return assetFile
          }
        } catch (e: Exception) {
          // things are really not going our way...
          Log.e(TAG, "Failed to copy matching embedded asset", e)
        }
      }
    }

    // we still don't have the asset locally, so try downloading it remotely
    try {
      val assetDownloadResult = fileDownloader.downloadAsset(
        asset,
        updatesDirectory,
        configuration,
        context,
      )
      database.assetDao().updateAsset(assetDownloadResult.assetEntity)
      val assetFileLocal = File(updatesDirectory, assetDownloadResult.assetEntity.relativePath!!)
      return if (assetFileLocal.exists()) assetFileLocal else null
    } catch (e: Exception) {
      Log.e(TAG, "Failed to load asset from disk or network", e)
      if (asset.isLaunchAsset) {
        throw e
      } else {
        return null
      }
    }
  }

  companion object {
    private val TAG = DatabaseLauncher::class.java.simpleName
  }
}
