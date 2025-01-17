/*
 * Copyright 2020 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

@file:RequiresApi(21) // TODO(b/200306659): Remove and replace with annotation on package-info.java

package androidx.camera.camera2.pipe.integration.adapter

import android.content.Context
import android.util.Size
import androidx.annotation.RequiresApi
import androidx.camera.camera2.pipe.CameraId
import androidx.camera.camera2.pipe.CameraPipe
import androidx.camera.camera2.pipe.core.Log.debug
import androidx.camera.camera2.pipe.integration.config.CameraAppComponent
import androidx.camera.core.impl.AttachedSurfaceInfo
import androidx.camera.core.impl.CameraDeviceSurfaceManager
import androidx.camera.core.impl.SurfaceConfig
import androidx.camera.core.impl.UseCaseConfig
import androidx.core.util.Preconditions
import kotlinx.coroutines.runBlocking

/**
 * Adapt the [CameraDeviceSurfaceManager] interface to [CameraPipe].
 *
 * This class provides Context-specific utility methods for querying and computing supported
 * outputs.
 */
class CameraSurfaceAdapter(
    context: Context,
    cameraComponent: Any?,
    availableCameraIds: Set<String>
) : CameraDeviceSurfaceManager {
    private val component = cameraComponent as CameraAppComponent
    private val supportedSurfaceCombinationMap = mutableMapOf<String, SupportedSurfaceCombination>()

    init {
        debug { "AvailableCameraIds = $availableCameraIds" }
        debug { "Created StreamConfigurationMap from $context" }
        initSupportedSurfaceCombinationMap(context, availableCameraIds)
    }

    /**
     * Prepare supportedSurfaceCombinationMap for surface adapter.
     */
    private fun initSupportedSurfaceCombinationMap(
        context: Context,
        availableCameraIds: Set<String>
    ) {
        Preconditions.checkNotNull(context)
        for (cameraId in availableCameraIds) {
            supportedSurfaceCombinationMap[cameraId] =
                SupportedSurfaceCombination(
                    context,
                    runBlocking { component.getCameraDevices().awaitMetadata(CameraId(cameraId)) },
                    cameraId,
                    CamcorderProfileProviderAdapter(cameraId)
                )
        }
    }

    /**
     * Check whether the input surface configuration list is under the capability of any combination
     * of this object.
     *
     * @param cameraId          the camera id of the camera device to be compared
     * @param surfaceConfigList the surface configuration list to be compared
     * @return the check result that whether it could be supported
     */
    override fun checkSupported(
        cameraId: String,
        surfaceConfigList: List<SurfaceConfig>?
    ): Boolean {
        if (surfaceConfigList == null || surfaceConfigList.isEmpty()) {
            return true
        }

        if (!checkIfSupportedCombinationExist(cameraId)) {
            return false
        }

        return supportedSurfaceCombinationMap[cameraId]!!.checkSupported(surfaceConfigList)
    }

    /**
     * Transform to a SurfaceConfig object with cameraId, image format and size info
     *
     * @param cameraId    the camera id of the camera device to transform the object
     * @param imageFormat the image format info for the surface configuration object
     * @param size        the size info for the surface configuration object
     * @return new {@link SurfaceConfig} object
     */
    override fun transformSurfaceConfig(
        cameraId: String,
        imageFormat: Int,
        size: Size
    ): SurfaceConfig {
        checkIfSupportedCombinationExist(cameraId)

        return supportedSurfaceCombinationMap[cameraId]!!.transformSurfaceConfig(imageFormat, size)
    }

    /**
     * Check whether the supportedSurfaceCombination for the camera id exists
     *
     * @param cameraId          the camera id of the camera device used by the use case.
     */
    private fun checkIfSupportedCombinationExist(cameraId: String): Boolean {
        return supportedSurfaceCombinationMap.containsKey(cameraId)
    }

    /**
     * Retrieves a map of suggested resolutions for the given list of use cases.
     *
     * @param cameraId          the camera id of the camera device used by the use cases
     * @param existingSurfaces  list of surfaces already configured and used by the camera. The
     *                          resolutions for these surface can not change.
     * @param newUseCaseConfigs list of configurations of the use cases that will be given a
     *                          suggested resolution
     * @return map of suggested resolutions for given use cases
     * @throws IllegalArgumentException if {@code newUseCaseConfigs} is an empty list, if
     *                                  there isn't a supported combination of surfaces
     *                                  available, or if the {@code cameraId}
     *                                  is not a valid id.
     */
    override fun getSuggestedResolutions(
        cameraId: String,
        existingSurfaces: List<AttachedSurfaceInfo>,
        newUseCaseConfigs: List<UseCaseConfig<*>>
    ): Map<UseCaseConfig<*>, Size> {
        checkIfSupportedCombinationExist(cameraId)

        if (!checkIfSupportedCombinationExist(cameraId)) {
            throw IllegalArgumentException(
                "No such camera id in supported combination list: $cameraId"
            )
        }

        return supportedSurfaceCombinationMap[cameraId]!!.getSuggestedResolutions(
            existingSurfaces,
            newUseCaseConfigs
        )
    }
}