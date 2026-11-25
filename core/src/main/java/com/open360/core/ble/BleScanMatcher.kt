package com.open360.core.ble

import com.open360.core.model.Insta360CameraModel
import java.util.UUID

/**
 * Pure JVM BLE advertisement matcher covering Insta360 camera compatibility profiles,
 * GATT services, manufacturer IDs, and device naming schemes.
 */
object BleScanMatcher {
    /** Primary Insta360 BLE GATT control service (modern GO / X / Ace / Luna cameras). */
    val SERVICE_BE80: UUID = UUID.fromString("0000be80-0000-1000-8000-00805f9b34fb")

    /** Legacy / GPS Remote control GATT service (`0xCE80` -> `0xCE81` write, `0xCE82` notify). */
    val SERVICE_CE80: UUID = UUID.fromString("0000ce80-0000-1000-8000-00805f9b34fb")

    /** Alternative legacy / accessory GATT service (`0xAE00` -> `0xAE01` write, `0xAE02` notify). */
    val SERVICE_AE00: UUID = UUID.fromString("0000ae00-0000-1000-8000-00805f9b34fb")

    val WRITE_AE01: UUID = UUID.fromString("0000ae01-0000-1000-8000-00805f9b34fb")
    val NOTIFY_AE02: UUID = UUID.fromString("0000ae02-0000-1000-8000-00805f9b34fb")

    /** Manufacturer specific data company ID observed in Insta360 camera BLE advertisements. */
    const val MANUFACTURER_ID_INSTA360_OG: Int = 0x4F47

    val KNOWN_CAMERA_SERVICE_UUIDS: Set<UUID> = setOf(SERVICE_BE80, SERVICE_CE80, SERVICE_AE00)

    data class MatchResult(
        val isMatch: Boolean,
        val reason: String,
        val model: Insta360CameraModel?
    )

    private val GENERIC_SHORT_MODELS = setOf(
        Insta360CameraModel.X3,
        Insta360CameraModel.X4,
        Insta360CameraModel.X5,
        Insta360CameraModel.X6,
        Insta360CameraModel.ACE,
        Insta360CameraModel.SPHERE
    )

    private val GENERIC_SERIAL_SUFFIX_REGEX =
        Regex("^(X3|X4|X5|X6|Ace|Sphere)[ _-][A-Za-z0-9]{6}$", RegexOption.IGNORE_CASE)

    /**
     * Evaluates whether a BLE advertisement belongs to an Insta360 camera (or a user-supplied
     * `targetSerialSuffix`) without depending on Android framework types so it is directly unit-testable.
     */
    fun evaluate(
        deviceName: String?,
        serviceUuids: Collection<UUID>,
        manufacturerIds: Collection<Int> = emptyList(),
        targetSerialSuffix: String? = null
    ): MatchResult {
        val cleanName = deviceName?.trim().orEmpty()
        val inferredModel = Insta360CameraModel.resolveByIdentity(deviceName = cleanName)

        val cleanTarget = targetSerialSuffix?.trim().orEmpty()
        if (cleanTarget.isNotEmpty()) {
            if (cleanName.contains(cleanTarget, ignoreCase = true)) {
                return MatchResult(true, "serial_suffix", inferredModel)
            }
            if (cleanName.isNotEmpty()) {
                return MatchResult(false, "serial_mismatch", inferredModel)
            }
        }

        if (serviceUuids.any { it in KNOWN_CAMERA_SERVICE_UUIDS }) {
            return MatchResult(true, "service_uuid", inferredModel)
        }

        if (MANUFACTURER_ID_INSTA360_OG in manufacturerIds) {
            return MatchResult(true, "manufacturer_data", inferredModel)
        }

        if (cleanName.equals("Insta360", ignoreCase = true) ||
            cleanName.startsWith("Insta360 ", ignoreCase = true) ||
            cleanName.startsWith("Insta360_", ignoreCase = true) ||
            cleanName.startsWith("Insta360-", ignoreCase = true)
        ) {
            return MatchResult(true, "insta360_name", inferredModel)
        }

        if (inferredModel != null) {
            val isGenericShort = inferredModel in GENERIC_SHORT_MODELS
            val matchesNameRule = if (isGenericShort) {
                cleanName.equals(inferredModel.shortName, ignoreCase = true) ||
                    cleanName.equals(inferredModel.displayName, ignoreCase = true) ||
                    GENERIC_SERIAL_SUFFIX_REGEX.matches(cleanName)
            } else {
                true
            }
            if (matchesNameRule) {
                return MatchResult(true, "model_prefix", inferredModel)
            }
        }

        return MatchResult(false, "unmatched", null)
    }
}
