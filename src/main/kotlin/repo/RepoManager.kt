package moe.nea.firmament.repo

import io.github.moulberry.repo.NEURepository
import io.github.moulberry.repo.NEURepositoryException
import io.github.moulberry.repo.data.NEUItem
import io.github.moulberry.repo.data.Rarity
import java.nio.file.Path
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import moe.nea.firmament.Firmament
import moe.nea.firmament.Firmament.logger
import moe.nea.firmament.events.ReloadRegistrationEvent
import moe.nea.firmament.util.ErrorUtil
import moe.nea.firmament.util.MC
import moe.nea.firmament.util.SkyblockId
import moe.nea.firmament.util.TestUtil
import moe.nea.firmament.util.data.Config
import moe.nea.firmament.util.data.ManagedConfig
import moe.nea.firmament.util.tr

object RepoManager {
	@Config
	object TConfig : ManagedConfig("repo", Category.META) {
		var username by string("username") { "NotEnoughUpdates" }
		var reponame by string("reponame") { "NotEnoughUpdates-REPO" }
		var branch by string("branch") { "master" }
		val autoUpdate by toggle("autoUpdate") { true }
		val reset by button("reset") {
			username = "NotEnoughUpdates"
			reponame = "NotEnoughUpdates-REPO"
			branch = "master"
			markDirty()
		}
		val reload by button("reload") {
			markDirty()
			Firmament.coroutineScope.launch {
				RepoManager.reload()
			}
		}
		val redownload by button("redownload") {
			markDirty()
			RepoManager.launchAsyncUpdate(true)
		}
	}

	val currentDownloadedSha by RepoDownloadManager::latestSavedVersionHash

	val miningData = MiningRepoData()
	val overlayData = ModernOverlaysData()
	val enchantedBookCache = EnchantedBookCache()
	val enchantData = EnchantData()

	fun makeNEURepository(path: Path): NEURepository {
		return NEURepository.of(path).apply {
			registerReloadListener(overlayData)
			registerReloadListener(ItemCache)
			registerReloadListener(RepoItemTypeCache)
			registerReloadListener(ExpLadders)
			registerReloadListener(ItemNameLookup)
			registerReloadListener(ReforgeStore)
			registerReloadListener(miningData)
			registerReloadListener(enchantedBookCache)
			registerReloadListener(enchantData)
			ReloadRegistrationEvent.publish(ReloadRegistrationEvent(this))
		}
	}

	lateinit var neuRepo: NEURepository
		private set

	fun getNEUItem(skyblockId: SkyblockId): NEUItem? = neuRepo.items.getItemBySkyblockId(skyblockId.neuItem)

	fun downloadOverridenBranch(branch: String) {
		Firmament.coroutineScope.launch {
			RepoDownloadManager.downloadUpdate(true, branch)
			reload()
		}
	}

	fun launchAsyncUpdate(force: Boolean = false) {
		Firmament.coroutineScope.launch {
			RepoDownloadManager.downloadUpdate(force)
			reload()
		}
	}

	fun reloadForTest(from: Path) {
		neuRepo = makeNEURepository(from)
		reloadSync()
	}


	suspend fun reload() {
		withContext(Dispatchers.IO) {
			reloadSync()
		}
	}

	fun reloadSync() {
		try {
			logger.info("Repo reload started.")
			neuRepo.reload()
			logger.info("Repo reload completed.")
		} catch (exc: NEURepositoryException) {
			ErrorUtil.softError("Failed to reload repository", exc)
			MC.sendChat(
				tr(
					"firmament.repo.reloadfail",
					"Failed to reload repository. This will result in some mod features not working."
				)
			)
		}
	}

	private var wasInitialized = false
	fun initialize() {
		if (wasInitialized) return
		wasInitialized = true
		System.getProperty("firmament.testrepo")?.let { compTimeRepo ->
			reloadForTest(Path.of(compTimeRepo))
			return
		}
		neuRepo = makeNEURepository(RepoDownloadManager.repoSavedLocation)
		if (TConfig.autoUpdate) {
			launchAsyncUpdate()
		} else {
			Firmament.coroutineScope.launch {
				reload()
			}
		}
	}

	init {
		if (TestUtil.isInTest) {
			initialize()
		}
	}

	fun getPotentialStubPetData(skyblockId: SkyblockId): PetData? {
		val parts = skyblockId.neuItem.split(";")
		if (parts.size != 2) {
			return null
		}
		val (petId, rarityIndex) = parts
		if (!rarityIndex.all { it.isDigit() }) {
			return null
		}
		val intIndex = rarityIndex.toInt()
		if (intIndex !in Rarity.entries.indices) return null
		if (petId !in neuRepo.constants.petNumbers) return null
		return PetData(Rarity.entries[intIndex], petId, 0.0, true)
	}

	fun getRepoRef(): String {
		return "${TConfig.username}/${TConfig.reponame}#${TConfig.branch}"
	}

}
