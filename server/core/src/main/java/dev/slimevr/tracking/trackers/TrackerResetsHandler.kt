package dev.slimevr.tracking.trackers

import dev.slimevr.VRServer
import dev.slimevr.config.ArmsResetModeConfig
import dev.slimevr.config.ArmsResetModes
import dev.slimevr.config.DriftCompensationConfig
import dev.slimevr.filtering.CircularArrayList
import io.github.axisangles.ktmath.EulerAngles
import io.github.axisangles.ktmath.EulerOrder
import io.github.axisangles.ktmath.Quaternion
import io.github.axisangles.ktmath.Vector3
import kotlin.math.*

private const val DRIFT_COOLDOWN_MS = 50000L

/**
 * Class taking care of full reset, yaw reset, mounting reset,
 * and drift compensation logic.
 */
class TrackerResetsHandler(val tracker: Tracker) {

	private var driftAmount = 0f
	private var averagedDriftQuat = Quaternion.IDENTITY
	private var rotationSinceReset = Quaternion.IDENTITY
	private var driftQuats = CircularArrayList<Quaternion>(0)
	private var driftTimes = CircularArrayList<Long>(0)
	private var totalDriftTime: Long = 0
	private var driftSince: Long = 0
	private var timeAtLastReset: Long = 0
	private var compensateDrift = false
	private var driftCompensationEnabled = false
	private var armsResetMode = ArmsResetModes.BACK
	var allowDriftCompensation = false
	var lastResetQuaternion: Quaternion? = null

	// Manual mounting orientation
	var mountingOrientation: Quaternion = EulerAngles(
		EulerOrder.YZX,
		0f,
		Math.PI.toFloat(),
		0f
	).toQuaternion()
		set(value) {
			field = value
			// Clear the mounting reset now that it's been set manually
			clearMounting()
		}

	// Reference adjustment quats
	private var gyroFix = Quaternion.IDENTITY
	private var attachmentFix = Quaternion.IDENTITY
	var mountRotFix = Quaternion.IDENTITY
		private set
	private var yawFix = Quaternion.IDENTITY

	// Zero-reference/identity adjustment quats for IMU debugging
	private var gyroFixNoMounting = Quaternion.IDENTITY
	private var attachmentFixNoMounting = Quaternion.IDENTITY
	private var yawFixZeroReference = Quaternion.IDENTITY

	/**
	 * Reads/loads drift compensation settings from given config
	 */
	fun readDriftCompensationConfig(config: DriftCompensationConfig) {
		compensateDrift = config.enabled
		driftAmount = config.amount
		val maxResets = config.maxResets

		if (compensateDrift && maxResets != driftQuats.capacity()) {
			driftQuats = CircularArrayList<Quaternion>(maxResets)
			driftTimes = CircularArrayList<Long>(maxResets)
		}

		refreshDriftCompensationEnabled()
	}

	/**
	 * Clears drift compensation data
	 */
	fun clearDriftCompensation() {
		driftSince = 0L
		timeAtLastReset = 0L
		totalDriftTime = 0L
		driftQuats.clear()
		driftTimes.clear()
	}

	/**
	 * Checks for compensateDrift, allowDriftCompensation, and if
	 * a computed head tracker exists.
	 */
	fun refreshDriftCompensationEnabled() {
		driftCompensationEnabled = compensateDrift && allowDriftCompensation &&
			TrackerUtils.getNonInternalNonImuTrackerForBodyPosition(VRServer.instance.allTrackers, TrackerPosition.HEAD) != null
	}

	/**
	 * Reads/loads arms reset mode settings from given config
	 */
	fun readArmsResetModeConfig(config: ArmsResetModeConfig) {
		armsResetMode = config.mode
	}

	/**
	 * Takes a rotation and adjusts it to resets, mounting,
	 * and drift compensation, with the HMD as the reference.
	 */
	fun getReferenceAdjustedDriftRotationFrom(rotation: Quaternion): Quaternion {
		var rot = adjustToReference(rotation)
		rot = adjustToDrift(rot)
		return rot
	}

	/**
	 * Takes a rotation and adjusts it to resets and mounting,
	 * with the identity Quaternion as the reference.
	 */
	fun getIdentityAdjustedRotationFrom(rotation: Quaternion): Quaternion {
		return adjustToIdentity(rotation)
	}

	/**
	 * Takes a rotation and adjusts it to mounting
	 */
	fun getMountingAdjustedRotationFrom(rotation: Quaternion): Quaternion {
		return rotation * mountingOrientation * mountRotFix
	}

	/**
	 * Converts raw or filtered rotation into reference- and
	 * mounting-reset-adjusted by applying quaternions produced after
	 * full reset, yaw rest and mounting reset
	 */
	private fun adjustToReference(rotation: Quaternion): Quaternion {
		var rot = rotation
		rot *= mountingOrientation
		rot = gyroFix * rot
		rot *= attachmentFix
		rot *= mountRotFix
		return yawFix * rot
	}

	/**
	 * Converts raw or filtered rotation into zero-reference-adjusted by
	 * applying quaternions produced after full reset and yaw reset only
	 */
	private fun adjustToIdentity(rotation: Quaternion): Quaternion {
		var rot = gyroFixNoMounting * rotation
		rot *= attachmentFixNoMounting
		rot = yawFixZeroReference * rot
		return rot
	}

	/**
	 * Adjust the given rotation for drift compensation if enabled,
	 * and returns it
	 */
	private fun adjustToDrift(rotation: Quaternion): Quaternion {
		if (driftCompensationEnabled && totalDriftTime > 0) {
			return rotation
				.interpR(
					averagedDriftQuat * rotation,
					driftAmount * ((System.currentTimeMillis() - driftSince).toFloat() / totalDriftTime)
				)
		}
		return rotation
	}

	/**
	 * Reset the tracker so that its current rotation is counted as (0, HMD Yaw,
	 * 0). This allows the tracker to be strapped to body at any pitch and roll.
	 */
	fun resetFull(reference: Quaternion) {
		lastResetQuaternion = adjustToReference(tracker.getRawRotation())

		val rot: Quaternion = adjustToReference(tracker.getRawRotation())

		if (tracker.needsMounting) {
			gyroFix = fixGyroscope(tracker.getRawRotation() * mountingOrientation)
		} else {
			// Set mounting to the HMD's yaw so that the non-mounting-adjusted
			// tracker goes forward.
			mountRotFix = EulerAngles(EulerOrder.YZX, 0f, getYaw(reference), 0f).toQuaternion()
		}
		attachmentFix = fixAttachment(tracker.getRawRotation() * mountingOrientation)

		makeIdentityAdjustmentQuatsFull()

		yawFix = fixYaw(tracker.getRawRotation() * mountingOrientation, reference)

		calculateDrift(rot)

		if (this.tracker.lastResetStatus != 0u) {
			VRServer.instance.statusSystem.removeStatus(this.tracker.lastResetStatus)
			this.tracker.lastResetStatus = 0u
		}
	}

	/**
	 * Reset the tracker so that its current yaw rotation is aligned with the HMD's
	 * Yaw. This allows the tracker to have yaw independent of the HMD. Tracker
	 * should still report yaw as if it was mounted facing HMD, mounting
	 * position should be corrected in the source.
	 */
	fun resetYaw(reference: Quaternion) {
		lastResetQuaternion = adjustToReference(tracker.getRawRotation())

		val rot: Quaternion = adjustToReference(tracker.getRawRotation())

		yawFix = fixYaw(tracker.getRawRotation() * mountingOrientation, reference)

		makeIdentityAdjustmentQuatsYaw()

		calculateDrift(rot)

		// Let's just remove the status if you do yaw reset if the tracker was
		// disconnected and then connected back
		if (this.tracker.lastResetStatus != 0u && this.tracker.statusResetRecently) {
			VRServer.instance.statusSystem.removeStatus(this.tracker.lastResetStatus)
			this.tracker.statusResetRecently = false
			this.tracker.lastResetStatus = 0u
		}
	}

	/**
	 * Perform the math to align the tracker to go forward
	 * and stores it in mountRotFix, and adjusts yawFix
	 */
	fun resetMounting(reverseYaw: Boolean, reference: Quaternion) {
		// TODO use HMD as forward
		// TODO armsResetMode
		// Get the current calibrated rotation
		var buffer = adjustToDrift(tracker.getRawRotation() * mountingOrientation)
		buffer = gyroFix * buffer
		buffer *= attachmentFix

		// Use the HMD's yaw as the reference
		buffer *= reference.project(Vector3.POS_Y).unit().inv()

		// Rotate a vector pointing up by the quat
		val rotVector = buffer.sandwich(Vector3.POS_Y)

		// Calculate the yaw angle using tan
		val yawAngle = atan2(rotVector.x, rotVector.z)

		// Make an adjustment quaternion from the angle
		buffer = EulerAngles(
			EulerOrder.YZX,
			0f,
			(if (reverseYaw) yawAngle else yawAngle - Math.PI.toFloat()),
			0f
		).toQuaternion()

		// Get the difference from the last mounting to the current mounting and apply
		// the difference to the yaw fix quaternion to correct for the rotation change
		yawFix *= (buffer * mountRotFix.inv()).inv()
		mountRotFix = buffer
	}

	fun clearMounting() {
		// If there is no mounting reset quaternion, skip clearing
		if (mountRotFix == Quaternion.IDENTITY) return

		// Undo the effect on yaw fix
		yawFix *= mountRotFix.inv()
		// Clear the mounting reset
		mountRotFix = Quaternion.IDENTITY
	}

	private fun fixGyroscope(sensorRotation: Quaternion): Quaternion {
		return EulerAngles(EulerOrder.YZX, 0f, getYaw(sensorRotation), 0f).toQuaternion().inv()
	}

	private fun fixAttachment(sensorRotation: Quaternion): Quaternion {
		return (gyroFix * sensorRotation).inv()
	}

	private fun fixYaw(sensorRotation: Quaternion, reference: Quaternion): Quaternion {
		var rot = gyroFix * sensorRotation
		rot *= attachmentFix
		rot *= mountRotFix
		rot = EulerAngles(EulerOrder.YZX, 0f, getYaw(rot), 0f).toQuaternion()
		return rot.inv() * reference.project(Vector3.POS_Y).unit()
	}

	// TODO : isolating yaw for yaw reset bad.
	// The way we isolate the tracker's yaw for yaw reset is
	// incorrect. Projection around the Y-axis is worse.
	// In both cases, the isolated yaw value changes
	// with the tracker's roll when pointing forward.
	private fun getYaw(rot: Quaternion): Float {
		return rot.toEulerAngles(EulerOrder.YZX).y
	}

	private fun makeIdentityAdjustmentQuatsFull() {
		val sensorRotation = tracker.getRawRotation()
		gyroFixNoMounting = fixGyroscope(sensorRotation)
		attachmentFixNoMounting = fixAttachment(sensorRotation)
	}

	private fun makeIdentityAdjustmentQuatsYaw() {
		var sensorRotation = tracker.getRawRotation()
		sensorRotation = gyroFixNoMounting * sensorRotation
		sensorRotation *= attachmentFixNoMounting
		yawFixZeroReference = fixYaw(sensorRotation, Quaternion.IDENTITY)
	}

	/**
	 * Calculates drift since last reset and store the data related to it in
	 * driftQuat and timeAtLastReset
	 */
	private fun calculateDrift(beforeQuat: Quaternion) {
		if (driftCompensationEnabled) {
			val rotQuat = adjustToReference(tracker.getRawRotation())

			if (driftSince > 0 && System.currentTimeMillis() - timeAtLastReset > DRIFT_COOLDOWN_MS) {
				// Check and remove from lists to keep them under the reset limit
				if (driftQuats.size == driftQuats.capacity()) {
					driftQuats.removeLast()
					driftTimes.removeLast()
				}

				// Add new drift quaternion
				driftQuats.add(
					EulerAngles(EulerOrder.YZX, 0f, getYaw(rotQuat), 0f).toQuaternion() *
						EulerAngles(EulerOrder.YZX, 0f, getYaw(beforeQuat), 0f).toQuaternion().inv()
				)

				// Add drift time to total
				driftTimes.add(System.currentTimeMillis() - driftSince)
				totalDriftTime = 0
				for (time in driftTimes) { totalDriftTime += time }

				// Calculate drift Quaternions' weights
				val driftWeights = ArrayList<Float>(driftTimes.size)
				for (time in driftTimes) { driftWeights.add(time.toFloat() / totalDriftTime.toFloat()) }

				// Make it so recent Quaternions weigh more
				for (i in driftWeights.size - 1 downTo 1) {
					// Add some of i-1's value to i
					driftWeights[i] = driftWeights[i] + driftWeights[i - 1] / driftWeights.size
					// Remove the value that was added to i from i-1
					driftWeights[i - 1] = driftWeights[i - 1] - driftWeights[i - 1] / driftWeights.size
				}

				// Set final averaged drift Quaternion
				averagedDriftQuat = fromAveragedQuaternions(driftQuats, driftWeights)

				// Save tracker rotation and current time
				rotationSinceReset = driftQuats.latest
				timeAtLastReset = System.currentTimeMillis()
			} else if (System.currentTimeMillis() - timeAtLastReset < DRIFT_COOLDOWN_MS && driftQuats.size > 0) {
				// Replace latest drift quaternion
				rotationSinceReset *= (
					EulerAngles(EulerOrder.YZX, 0f, getYaw(rotQuat), 0f).toQuaternion() *
						EulerAngles(EulerOrder.YZX, 0f, getYaw(beforeQuat), 0f).toQuaternion().inv()
					)
				driftQuats[driftQuats.size - 1] = rotationSinceReset

				// Add drift time to total
				driftTimes[driftTimes.size - 1] = driftTimes.latest + System.currentTimeMillis() - driftSince
				totalDriftTime = 0
				for (time in driftTimes) { totalDriftTime += time }

				// Calculate drift Quaternions' weights
				val driftWeights = ArrayList<Float>(driftTimes.size)
				for (time in driftTimes) { driftWeights.add(time.toFloat() / totalDriftTime.toFloat()) }

				// Make it so recent Quaternions weigh more
				for (i in driftWeights.size - 1 downTo 1) {
					// Add some of i-1's value to i
					driftWeights[i] = driftWeights[i] + driftWeights[i - 1] / driftWeights.size
					// Remove the value that was added to i from i-1
					driftWeights[i - 1] = driftWeights[i - 1] - driftWeights[i - 1] / driftWeights.size
				}

				// Set final averaged drift Quaternion
				averagedDriftQuat = fromAveragedQuaternions(driftQuats, driftWeights)
			} else {
				timeAtLastReset = System.currentTimeMillis()
			}

			driftSince = System.currentTimeMillis()
		}
	}

	/**
	 * Calculates and returns the averaged Quaternion
	 * from the given Quaternions and weights.
	 */
	private fun fromAveragedQuaternions(
		qn: CircularArrayList<Quaternion>,
		tn: ArrayList<Float>,
	): Quaternion {
		var totalMatrix = qn[0].toMatrix() * tn[0]
		for (i in 1 until qn.size) {
			totalMatrix += (qn[i].toMatrix() * tn[i])
		}
		return totalMatrix.toQuaternion()
	}
}
