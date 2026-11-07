package dev.sushnt.journal

/** Where the server lives. One place to change it (later: a debug build can point at localhost). */
object ApiConfig {
    const val BASE_URL = "https://journalservice-production.up.railway.app"

    /**
     * The "Web application" OAuth client ID from Google Cloud Console
     * (looks like 1234-abc.apps.googleusercontent.com). The server checks tokens
     * against the same ID. Empty = the Google button is hidden.
     */
    const val GOOGLE_WEB_CLIENT_ID = "536979279428-b932trs54t6a779e7684447u6v9ppth5.apps.googleusercontent.com"
}
