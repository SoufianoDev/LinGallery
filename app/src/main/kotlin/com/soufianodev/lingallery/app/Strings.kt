package com.soufianodev.lingallery.app

import java.util.*

object Strings {
    private val bundle: ResourceBundle
        get() = ResourceBundle.getBundle("i18n/strings")

    operator fun get(key: String): String = bundle.getString(key)
    fun fmt(key: String, vararg args: Any?): String =
        String.format(bundle.getString(key), *args)

    object App {
        val name get() = Strings["app.name"]
    }

    object Status {
        val scanning get() = Strings["status.scanning"]
        val scanningShort get() = Strings["status.scanning.short"]
        fun loadedCache(count: Int) = Strings.fmt("status.loaded.cache", count)
        fun discovered(name: String) = Strings.fmt("status.discovered", name)
        fun progress(dirs: Int, images: Int) = Strings.fmt("status.scanning.progress", dirs, images)
        fun summary(albums: Int, images: Int) = Strings.fmt("status.albums.summary", albums, images)
        fun newAlbum(name: String) = Strings.fmt("status.new.album", name)
        val albumRemoved get() = Strings["status.album.removed"]
        val albumRenamed get() = Strings["status.album.renamed"]
        fun phoneConnected(name: String, count: Int) = Strings.fmt("status.phone.connected", name, count)
        fun phoneDisconnected(name: String) = Strings.fmt("status.phone.disconnected", name)
    }

    object Gallery {
        val albums get() = Strings["gallery.albums"]
        val empty get() = Strings["gallery.empty"]
        val noAlbums get() = Strings["gallery.no.albums"]
    }

    object Viewer {
        val failedLoad get() = Strings["viewer.failed.load"]
        fun counter(current: Int, total: Int) = Strings.fmt("viewer.image.counter", current, total)
    }

    object Crop {
        val mode get() = Strings["crop.mode"]
        val saveQuestion get() = Strings["crop.save.question"]
    }

    object Dialogs {
        val details get() = Strings["dialog.details"]
        val renameImage get() = Strings["dialog.rename.image"]
        val saveCrop get() = Strings["dialog.save.crop"]
        val deleteImage get() = Strings["dialog.delete.image"]
        val permanentlyDelete get() = Strings["dialog.permanently.delete"]
        val permanentDeleteCheck get() = Strings["dialog.permanently.delete.check"]
        val selectFolder get() = Strings["dialog.select.folder"]
        fun deleteConfirm(name: String) = Strings.fmt("dialog.delete.confirm", name)
        fun permanentDeleteWarning(name: String) = Strings.fmt("dialog.permanently.delete.warning", name)
    }

    object Labels {
        val filename get() = Strings["label.filename"]
        fun filenameColon(name: String) = Strings.fmt("label.filename.colon", name)
    }

    object Snackbar {
        val savedCopy get() = Strings["snackbar.saved.copy"]
        val imageCropped get() = Strings["snackbar.image.cropped"]
        val cropFailed get() = Strings["snackbar.crop.failed"]
        val imageRenamed get() = Strings["snackbar.image.renamed"]
        val renameFailed get() = Strings["snackbar.rename.failed"]
        val rotateFailed get() = Strings["snackbar.rotate.failed"]
        val flipFailed get() = Strings["snackbar.flip.failed"]
        val undoFailed get() = Strings["snackbar.undo.failed"]
        val albumExists get() = Strings["snackbar.album.exists"]
        fun restored(name: String) = Strings.fmt("snackbar.restored", name)
        val deleteFailed get() = Strings["snackbar.delete.failed"]
        fun deleted(name: String) = Strings.fmt("snackbar.deleted", name)
        fun permanentlyDeleted(name: String) = Strings.fmt("snackbar.permanently.deleted", name)
        val nameCopied get() = Strings["snackbar.name.copied"]
        val pathCopied get() = Strings["snackbar.path.copied"]
        val imageRestored get() = Strings["snackbar.image.restored"]
        val imageCopied get() = Strings["snackbar.image.copied"]
        val copyFailed get() = Strings["snackbar.copy.failed"]
        val nameCopyFailed get() = Strings["snackbar.name.copy.failed"]
        val imageMoved get() = Strings["snackbar.image.moved"]
        val imageCopiedFolder get() = Strings["snackbar.image.copied.folder"]
        val operationFailed get() = Strings["snackbar.operation.failed"]
        val transferTitleMoved get() = Strings["snackbar.transfer.title.moved"]
        val transferTitleCopied get() = Strings["snackbar.transfer.title.copied"]
        fun transferDetails(src: String, dst: String) = Strings.fmt("snackbar.transfer.details", src, dst)
    }

    object Buttons {
        val cancel get() = Strings["button.cancel"]
        val apply get() = Strings["button.apply"]
        val rename get() = Strings["button.rename"]
        val saveCopy get() = Strings["button.save.copy"]
        val overwrite get() = Strings["button.overwrite"]
        val delete get() = Strings["button.delete"]
        val deletePermanently get() = Strings["button.delete.permanently"]
        val close get() = Strings["button.close"]
        val undo get() = Strings["button.undo"]
    }

    object Tooltips {
        val cropMode get() = Strings["tooltip.crop.mode"]
        val backGallery get() = Strings["tooltip.back.gallery"]
        val zoomOut get() = Strings["tooltip.zoom.out"]
        val zoomIn get() = Strings["tooltip.zoom.in"]
        val zoomOutCaps get() = Strings["tooltip.zoom.out.caps"]
        val zoomInCaps get() = Strings["tooltip.zoom.in.caps"]
        val slideshow get() = Strings["tooltip.slideshow"]
        val fullscreen get() = Strings["tooltip.fullscreen"]
        val previous get() = Strings["tooltip.previous"]
        val next get() = Strings["tooltip.next"]
        val rotateLeft get() = Strings["tooltip.rotate.left"]
        val rotateRight get() = Strings["tooltip.rotate.right"]
        val flipHorizontal get() = Strings["tooltip.flip.horizontal"]
        val crop get() = Strings["tooltip.crop"]
        val copyImage get() = Strings["tooltip.copy.image"]
        val transferImage get() = Strings["tooltip.transfer.image"]
        val rename get() = Strings["tooltip.rename"]
        val imageInfo get() = Strings["tooltip.image.info"]
        val delete get() = Strings["tooltip.delete"]
    }

    object Menu {
        val copyClipboard get() = Strings["menu.copy.clipboard"]
        val copyName get() = Strings["menu.copy.name"]
        val copyPath get() = Strings["menu.copy.path"]
        val moveFolder get() = Strings["menu.move.folder"]
        val copyFolder get() = Strings["menu.copy.folder"]
    }

    object ContentDesc {
        val dismiss get() = Strings["contentdesc.dismiss"]
        val exitFullscreen get() = Strings["contentdesc.exit.fullscreen"]
        val delete get() = Strings["contentdesc.delete"]
        val warning get() = Strings["contentdesc.warning"]
    }

    object StatusMtp {
        val connecting get() = Strings["status.mtp.connecting"]
        val connected get() = Strings["status.mtp.connected"]
        val disconnecting get() = Strings["status.mtp.disconnecting"]
        val disconnected get() = Strings["status.mtp.disconnected"]
        val browsing get() = Strings["status.mtp.browsing"]
        val indexing get() = Strings["status.mtp.indexing"]
        val error get() = Strings["status.mtp.error"]
        val timeout get() = Strings["status.mtp.timeout"]
        val scanning get() = Strings["status.mtp.scanning"]
        val scanningDone get() = Strings["status.mtp.scanning.done"]
    }

    object Device {
        val androidPhone get() = Strings["device.android.phone"]
    }

    object DeviceIssue {
        fun permissionTitle(name: String) = Strings.fmt("device.issue.permission.title", name)
        val permissionMessage get() = Strings["device.issue.permission.message"]
        fun permissionSteps() = Strings["device.issue.permission.steps"].split("|")
        fun permissionNotes() = Strings["device.issue.permission.notes"].split("|")
        fun deviceBusy(name: String) = Strings.fmt("device.issue.busy", name)
        fun unsupportedTitle(name: String) = Strings.fmt("device.issue.unsupported.title", name)
        fun unsupportedMessage(name: String) = Strings.fmt("device.issue.unsupported.message", name)
        val unknownTitle get() = Strings["device.issue.unknown.title"]
        fun unknownMessage(detail: String) = Strings.fmt("device.issue.unknown.message", detail)
        val retry get() = Strings["device.issue.retry"]
        val cancel get() = Strings["button.cancel"]
        val close get() = Strings["button.close"]
        val dismiss get() = Strings["device.issue.dismiss"]
        val learnMore get() = Strings["device.issue.learn.more"]
        val reconnectGuidance get() = Strings["device.issue.reconnect.guidance"]
    }

    object DeviceActivity {
        fun connecting(name: String) = Strings.fmt("device.activity.connecting", name)
        fun mounting(name: String) = Strings.fmt("device.activity.mounting", name)
        fun scanning(name: String, count: Int) = Strings.fmt("device.activity.scanning", name, count)
        fun indexing(name: String, count: Int) = Strings.fmt("device.activity.indexing", name, count)
    }

    object Exif {
        val fileSize get() = Strings["exif.file.size"]
        val modified get() = Strings["exif.modified"]
        val dimensions get() = Strings["exif.dimensions"]
        val format get() = Strings["exif.format"]
        val dateTaken get() = Strings["exif.date.taken"]
        val iso get() = Strings["exif.iso"]
        val aperture get() = Strings["exif.aperture"]
        val shutterSpeed get() = Strings["exif.shutter.speed"]
        val focalLength get() = Strings["exif.focal.length"]
        val cameraMake get() = Strings["exif.camera.make"]
        val cameraModel get() = Strings["exif.camera.model"]
        val gps get() = Strings["exif.gps"]
        val svg get() = Strings["exif.svg"]
    }
}
