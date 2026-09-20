package dev.otgformat.app

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.hardware.usb.UsbDevice
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import dev.otgformat.core.ClusterSize
import dev.otgformat.core.FormatCancelledException
import dev.otgformat.core.FormatException
import dev.otgformat.core.FormatOptions
import dev.otgformat.core.Formatter
import dev.otgformat.core.PartitionScheme
import dev.otgformat.core.Phase
import dev.otgformat.core.Progress
import dev.otgformat.core.VerificationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import java.io.IOException

/**
 * Runs a format to completion, in the foreground, holding the CPU awake.
 *
 * A format on a large stick is minutes of continuous writing, and an
 * interruption partway through leaves a FAT that is half-written and a drive
 * that will not mount. Two things are therefore non-negotiable: a partial wake
 * lock, so the CPU keeps working when the screen goes off, and a foreground
 * service, so Android does not reclaim the process when the user switches away.
 * Doing this work in a coroutine tied to an Activity would lose it on a
 * rotation.
 */
class FormatService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent == null || FormatController.isRunning) return START_NOT_STICKY

        @Suppress("DEPRECATION")
        val usbDevice = intent.getParcelableExtra<UsbDevice>(EXTRA_DEVICE)
        val interfaceId = intent.getIntExtra(EXTRA_INTERFACE_ID, -1)
        if (usbDevice == null) {
            FormatController.update(FormatState.Failed("The device was not passed to the format service."))
            stopSelf()
            return START_NOT_STICKY
        }

        val options = FormatOptions(
            label = intent.getStringExtra(EXTRA_LABEL)?.ifBlank { null },
            clusterSize = intent.getIntExtra(EXTRA_CLUSTER_BYTES, 0)
                .let { if (it <= 0) ClusterSize.Auto else ClusterSize.Bytes(it) },
            partitionScheme = PartitionScheme.valueOf(
                intent.getStringExtra(EXTRA_SCHEME) ?: PartitionScheme.MBR.name,
            ),
            bootable = intent.getBooleanExtra(EXTRA_BOOTABLE, false),
        )

        startForeground()
        FormatController.beginRun()
        scope.launch { runFormat(usbDevice, interfaceId, options) }
        return START_NOT_STICKY
    }

    private fun runFormat(usbDevice: UsbDevice, interfaceId: Int, options: FormatOptions) {
        val power = getSystemService(Context.POWER_SERVICE) as PowerManager
        val wakeLock = power.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, WAKE_LOCK_TAG)
        wakeLock.setReferenceCounted(false)

        var open: OpenTarget? = null
        try {
            wakeLock.acquire(MAX_FORMAT_MILLIS)

            val access = UsbAccess(this)
            val candidate = access.findCandidates().firstOrNull {
                it.device.deviceName == usbDevice.deviceName &&
                    (interfaceId < 0 || it.usbInterface.id == interfaceId)
            } ?: throw IOException("The device was unplugged before the format could start.")

            open = access.open(candidate)
            val target = open.target
            val name = target.displayName()

            FormatController.update(FormatState.Running(name, Phase.WIPE_SIGNATURES, 0, 1))
            var lastNotified = 0L

            val progress = object : Progress {
                override fun onProgress(phase: Phase, sectorsDone: Long, sectorsTotal: Long) {
                    FormatController.update(FormatState.Running(name, phase, sectorsDone, sectorsTotal))
                    // The notification is rate-limited; the in-app bar is not.
                    val now = System.currentTimeMillis()
                    if (now - lastNotified > NOTIFICATION_INTERVAL_MILLIS) {
                        lastNotified = now
                        notify(progressNotification(name, sectorsDone, sectorsTotal))
                    }
                }

                override val isCancelled: Boolean get() = FormatController.shouldCancel()
            }

            val result = Formatter.format(open.sectorDevice, options, progress)
            FormatController.update(FormatState.Done(name, result.describe()))
            notify(finishedNotification("Format complete", name))
        } catch (e: FormatCancelledException) {
            FormatController.update(
                FormatState.Cancelled(
                    "Format cancelled. The device was left partly written and will not mount until it is " +
                        "formatted again.",
                ),
            )
            notify(finishedNotification("Format cancelled", "The device is not usable until reformatted"))
        } catch (e: VerificationException) {
            FormatController.update(
                FormatState.Failed(
                    e.message ?: "The device did not store what was written to it.",
                    "This usually means the drive is failing or its capacity is counterfeit. Do not trust " +
                        "data written to it.",
                ),
            )
            notify(finishedNotification("Format failed verification", "Do not use this device"))
        } catch (e: FormatException) {
            FormatController.update(FormatState.Failed(e.message ?: "The format options are not valid."))
            notify(finishedNotification("Format failed", e.message ?: ""))
        } catch (e: Throwable) {
            FormatController.update(
                FormatState.Failed(
                    e.message ?: e.javaClass.simpleName,
                    "If the drive is mounted, eject it in Files first — another app holding the interface " +
                        "will stop this one from claiming it.",
                ),
            )
            notify(finishedNotification("Format failed", e.message ?: ""))
        } finally {
            open?.close()
            runCatching { wakeLock.release() }
            ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_DETACH)
            stopSelf()
        }
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    // ---- notifications ----------------------------------------------------

    private fun startForeground() {
        createChannel()
        val notification = progressNotification("Preparing", 0, 0)
        // The connectedDevice type is what this work actually is, and from
        // API 34 a foreground service must declare one that matches.
        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE
        } else {
            0
        }
        ServiceCompat.startForeground(this, NOTIFICATION_ID, notification, type)
    }

    private fun createChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            getString(R.string.notification_channel_name),
            NotificationManager.IMPORTANCE_LOW,
        ).apply { description = getString(R.string.notification_channel_description) }
        (getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager)
            .createNotificationChannel(channel)
    }

    private fun baseNotification(): NotificationCompat.Builder =
        NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_upload)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(
                android.app.PendingIntent.getActivity(
                    this,
                    0,
                    Intent(this, MainActivity::class.java),
                    android.app.PendingIntent.FLAG_UPDATE_CURRENT or
                        android.app.PendingIntent.FLAG_IMMUTABLE,
                ),
            )

    private fun progressNotification(name: String, done: Long, total: Long): Notification =
        baseNotification()
            .setContentTitle("Formatting $name")
            .setContentText("Do not unplug the device")
            .setProgress(
                if (total > Int.MAX_VALUE) 1000 else total.toInt(),
                if (total > Int.MAX_VALUE) (done * 1000 / total).toInt() else done.toInt(),
                total <= 0,
            )
            .build()

    private fun finishedNotification(title: String, text: String): Notification =
        baseNotification().setOngoing(false).setContentTitle(title).setContentText(text).build()

    private fun notify(notification: Notification) {
        runCatching {
            (getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager)
                .notify(NOTIFICATION_ID, notification)
        }
    }

    companion object {
        const val EXTRA_DEVICE = "device"
        const val EXTRA_INTERFACE_ID = "interfaceId"
        const val EXTRA_LABEL = "label"
        const val EXTRA_CLUSTER_BYTES = "clusterBytes"
        const val EXTRA_SCHEME = "scheme"
        const val EXTRA_BOOTABLE = "bootable"

        private const val CHANNEL_ID = "format"
        private const val NOTIFICATION_ID = 1
        private const val WAKE_LOCK_TAG = "OTGFormat:format"
        private const val NOTIFICATION_INTERVAL_MILLIS = 500L

        /**
         * Upper bound on the wake lock, so a wedged transfer cannot hold the
         * CPU awake indefinitely. Generous: a 128 GB stick with a slow
         * controller is a long wait.
         */
        private const val MAX_FORMAT_MILLIS = 2 * 60 * 60 * 1000L

        fun start(context: Context, candidate: MassStorageCandidate, options: FormatOptions) {
            val intent = Intent(context, FormatService::class.java)
                .putExtra(EXTRA_DEVICE, candidate.device)
                .putExtra(EXTRA_INTERFACE_ID, candidate.usbInterface.id)
                .putExtra(EXTRA_LABEL, options.label)
                .putExtra(
                    EXTRA_CLUSTER_BYTES,
                    (options.clusterSize as? ClusterSize.Bytes)?.bytes ?: 0,
                )
                .putExtra(EXTRA_SCHEME, options.partitionScheme.name)
                .putExtra(EXTRA_BOOTABLE, options.bootable)
            context.startForegroundService(intent)
        }
    }
}
