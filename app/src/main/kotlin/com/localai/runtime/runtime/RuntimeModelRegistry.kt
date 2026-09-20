package com.localai.runtime.runtime

import com.localai.runtime.core.model.ModelInfo
import com.localai.runtime.core.model.ModelState
import com.localai.runtime.core.repo.ModelRepository
import com.localai.runtime.server.api.ModelRegistry
import com.localai.runtime.server.api.ServerModel

/**
 * Bridges the core model repository into the server-facing [ModelRegistry] view.
 * Backend ids are honest: only backends listed on the model are reported.
 */
class RuntimeModelRegistry(
    private val modelRepository: ModelRepository,
) : ModelRegistry {

    override suspend fun list(): List<ServerModel> =
        modelRepository.listAll().map { it.toServerModel() }

    override suspend fun get(id: String): ServerModel? =
        modelRepository.get(id)?.toServerModel()

    private fun ModelInfo.toServerModel(): ServerModel = ServerModel(
        id = id,
        name = name,
        version = version,
        format = format.name,
        quantization = quantization,
        sizeBytes = sizeBytes,
        state = state.name,
        backend = backends.firstOrNull()?.id,
        contextLength = contextLength,
        minRamMb = minRamMb,
        backends = backends.map { it.id },
    )
}
