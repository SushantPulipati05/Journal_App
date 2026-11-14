package dev.sushnt.journal.data

import dev.sushnt.journal.api.ApiError
import dev.sushnt.journal.api.ApiResult
import dev.sushnt.journal.api.CreateAttachmentRequest
import dev.sushnt.journal.api.JournalApi

/**
 * Moves photo FILES between this device and the server. Runs after the entries have
 * synced (so the server already knows the entry a photo belongs to).
 *
 * Never fails the entry sync: if the server has no photo storage yet, or we're offline,
 * photos just stay on this device and the next sync tries again.
 */
class PhotoSync(
    private val api: JournalApi,
    private val photos: PhotoStore,
    private val entries: EntryStore,
) {
    suspend fun sync() {
        val live = entries.list()
        // Which entry uses which photo, and where in it (the server wants both).
        val owner = HashMap<String, Pair<String, Int>>()
        for (e in live) photoIds(e.body).forEachIndexed { i, id -> if (id !in owner) owner[id] = e.id to i }

        // Photos of entries in the Trash are kept too, so restoring brings them back.
        val inTrash = entries.trashPhotoIds()
        photos.collectGarbage(owner.keys + inTrash)
        val all = photos.all()

        // 1. Tell the server about photos removed here.
        for (p in all.filter { it.deleted }) {
            when (val r = api.deleteAttachment(p.id)) {
                is ApiResult.Ok -> photos.forget(p.id)
                is ApiResult.Err -> if (stop(r.error)) return
            }
        }

        // 2. Upload new photos. Only ones whose entry has reached the server.
        for (p in all.filter { !it.uploaded && !it.deleted && it.haveFile }) {
            val (entryId, position) = owner[p.id] ?: continue
            if (entries.get(entryId)?.dirty == true) continue // next sync, after the entry
            val bytes = photos.fileBytes(p.id) ?: continue
            val created = api.createAttachment(
                CreateAttachmentRequest(
                    id = p.id,
                    entryId = entryId,
                    contentType = "image/jpeg",
                    sizeBytes = bytes.size.toLong(),
                    width = p.width,
                    height = p.height,
                    position = position.coerceAtMost(99),
                ),
            )
            val response = when (created) {
                is ApiResult.Ok -> created.value
                is ApiResult.Err -> if (stop(created.error)) return else continue
            }
            if (!response.uploaded) {
                val url = response.uploadUrl ?: continue
                val put = api.uploadFile(url, bytes, "image/jpeg")
                if (put is ApiResult.Err) return // storage unreachable: try again next sync
                when (val done = api.completeAttachment(p.id)) {
                    is ApiResult.Ok -> {}
                    is ApiResult.Err -> if (stop(done.error)) return else continue
                }
            }
            photos.markUploaded(p.id)
        }

        // 3. Learn about photos added or removed on other devices.
        var cursor = photos.cursor()
        while (true) {
            val page = when (val r = api.pullAttachments(cursor)) {
                is ApiResult.Ok -> r.value
                is ApiResult.Err -> return
            }
            for (a in page.attachments) {
                if (a.deletedAt != null) {
                    // Gone on the server, but an entry in this device's Trash still shows it: keep the file.
                    if (a.id !in inTrash) photos.forget(a.id)
                }
                else photos.addRemote(a.id, a.width, a.height, a.sizeBytes)
            }
            photos.setCursor(page.nextCursor)
            if (!page.hasMore || page.nextCursor <= cursor) break
            cursor = page.nextCursor
        }

        // 4. Download the files we don't have yet (only ones an entry still shows).
        for (p in photos.all().filter { !it.haveFile && !it.deleted && it.id in owner }) {
            when (val r = api.downloadAttachment(p.id)) {
                is ApiResult.Ok -> photos.saveDownloaded(p.id, r.value)
                // 404: not uploaded by the other device yet. Only stop when offline/signed out.
                is ApiResult.Err -> if (r.error is ApiError.Network || r.error == ApiError.SessionExpired) return
            }
        }
    }

    /**
     * true = stop photo syncing for now: offline, signed out, or the server has no photo
     * storage (503) / doesn't know these endpoints yet (404 on an old server).
     */
    private fun stop(error: ApiError): Boolean = when (error) {
        is ApiError.Network, ApiError.SessionExpired -> true
        is ApiError.Server -> error.status == 503 || error.status == 404 || error.status >= 500
    }
}
