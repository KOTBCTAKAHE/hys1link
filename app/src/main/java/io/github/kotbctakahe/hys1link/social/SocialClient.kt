package io.github.kotbctakahe.hys1link.social

import android.os.Build
import android.util.Log
import io.github.kotbctakahe.hys1link.LinkConfig
import io.github.kotbctakahe.hys1link.core.Core
import io.github.kotbctakahe.hys1link.crypto.B64
import io.github.kotbctakahe.hys1link.crypto.randomBytes
import io.github.kotbctakahe.hys1link.net.ApiException
import okhttp3.Credentials
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.Locale
import java.util.TimeZone

/**
 * Client for Link's social backend (social.linkm.me).
 * Auth: "Authorization: Bearer <token>", where the token comes from the chat server:
 * POST /v1/accounts/refresh (Basic device auth, body = UserDeviceInfo) → {"token": "..."}.
 */
class SocialClient(private val core: Core) {
    private val json = "application/json".toMediaType()
    private val protoType = "application/x-protobuf".toMediaType()
    private val lang = Locale.getDefault().language

    @Volatile private var token: String? = core.db.getString("social_token")

    private fun installId(): String = core.db.getString("install_id") ?: B64.encNoPad(randomBytes(12))
        .replace('+', 'a').replace('/', 'b').also { core.db.putString("install_id", it) }

    /** Same shape as Link's AndroidUtilities.getDeviceInfo(), with a random install id instead of ANDROID_ID. */
    private fun userDeviceInfo(): JSONObject = JSONObject()
        .put("uuid", core.account.aci)
        .put("pni", core.account.pni ?: JSONObject.NULL)
        .put("deviceInfo", JSONObject()
            .put("envAttributes", JSONArray()
                .put(JSONObject().put("name", "VersionName").put("values", JSONArray().put(LinkConfig.LINK_VERSION_NAME)))
                .put(JSONObject().put("name", "VersionCode").put("values", JSONArray().put(LinkConfig.LINK_VERSION_CODE)))
                .put(JSONObject().put("name", "OS").put("values", JSONArray().put("android"))))
            .put("mobile", JSONObject()
                .put("device_id", installId())
                .put("meta", JSONObject()
                    .put("locale", "${Locale.getDefault().language}-${Locale.getDefault().country}")
                    .put("model", Build.MODEL)
                    .put("timezone", TimeZone.getDefault().id)
                    .put("version", Build.VERSION.RELEASE))))

    @Synchronized
    fun refreshToken(): String {
        val c = core.account.creds() ?: error("Устройство не привязано")
        val req = Request.Builder().url(LinkConfig.SERVICE_URL + "/v1/accounts/refresh")
            .header("Authorization", Credentials.basic(c.username, c.password))
            .post(userDeviceInfo().toString().toRequestBody(json)).build()
        val t = core.http.newCall(req).execute().use { r ->
            val body = r.body?.string()
            if (!r.isSuccessful) throw ApiException(r.code, body)
            parseToken(body) ?: error("Сервер не выдал токен соцсети")
        }
        token = t
        core.db.putString("social_token", t)
        return t
    }

    /** Link's server answers either {"token":"…"} or a bare JSON string "eyJ…" (JWT). */
    private fun parseToken(body: String?): String? {
        val b = body?.trim().orEmpty()
        if (b.isEmpty()) return null
        return when (val v = runCatching { org.json.JSONTokener(b).nextValue() }.getOrNull()) {
            is JSONObject -> v.optString("token").ifBlank { v.optJSONObject("data")?.optString("token") }?.takeIf { it.isNotBlank() }
            is String -> v.takeIf { it.isNotBlank() }
            else -> b.trim('"').takeIf { it.startsWith("ey") }
        }
    }

    private fun bearer(): String = token ?: refreshToken()

    private fun exec(build: (Request.Builder) -> Request.Builder, proto: Boolean): ByteArray {
        repeat(2) { attempt ->
            val req = build(Request.Builder())
                .header("Authorization", "Bearer " + bearer())
                .header("lang", lang)
                .apply { if (proto) header("Accept", "application/x-protobuf") }
                .build()
            core.http.newCall(req).execute().use { r ->
                if (r.code == 401 && attempt == 0) { token = null; return@repeat }
                val b = r.body?.bytes() ?: ByteArray(0)
                if (!r.isSuccessful) throw ApiException(r.code, String(b).take(300))
                return b
            }
        }
        throw ApiException(401, "Соцсеть отклонила токен")
    }

    private fun url(path: String, vararg q: Pair<String, Any?>): String {
        val b = (LinkConfig.SOCIAL_URL + path).toHttpUrl().newBuilder()
        q.forEach { (k, v) -> if (v != null) b.addQueryParameter(k, v.toString()) }
        return b.build().toString()
    }

    private fun getProto(path: String, vararg q: Pair<String, Any?>) = exec({ it.url(url(path, *q)).get() }, true)
    private fun getJson(path: String, vararg q: Pair<String, Any?>): Any {
        val s = String(exec({ it.url(url(path, *q)).get() }, false))
        return if (s.trimStart().startsWith("[")) JSONArray(s) else JSONObject(s.ifBlank { "{}" })
    }
    private fun postJson(path: String, body: JSONObject) = exec({ it.url(url(path)).post(body.toString().toRequestBody(json)) }, false)

    /** Unwraps {"success":..,"data":{...}} when present. */
    private fun Any.data(): JSONObject = (this as? JSONObject)?.let { o -> o.optJSONObject("data") ?: o } ?: JSONObject()

    // ---------- feed / reels ----------
    fun homeFeed(cursor: String?, limit: Int = 15): SPage = SocialProto.postList(getProto("/api/v2/feed", "cursor" to cursor, "limit" to limit))
    fun forYou(count: Int = 15): List<SPost> = SocialProto.forYou(getProto("/api/v2/for-you", "count" to count))
    fun reels(count: Int = 8, anchor: String? = null): List<SPost> = SocialProto.reels(getProto("/api/v2/reels", "count" to count, "anchor_post_id" to anchor))
    fun post(id: String): SPost = SocialProto.post(getProto("/api/v2/post/$id"))
    fun userPosts(userId: String, cursor: String?, limit: Int = 30): SPage =
        SocialProto.postList(getProto("/api/v2/users/$userId/posts", "cursor" to cursor, "limit" to limit))
    fun myPosts(cursor: String?, limit: Int = 30): SPage = SocialProto.postList(getProto("/api/v2/me/posts", "cursor" to cursor, "limit" to limit))

    fun like(postId: String, reaction: String = "") { // Link: DEFAULT_REACTION_KEY = ""
        exec({ it.url(url("/api/v2/reactions/posts/$postId")).post(SocialProto.likeRequest(reaction).toRequestBody(protoType)) }, true)
    }
    fun unlike(postId: String) { exec({ it.url(url("/api/v2/reactions/posts/$postId")).delete() }, true) }
    fun reactionCatalog(): List<Pair<String, String>> = SocialProto.reactionCatalog(getProto("/api/v2/reactions/catalog"))

    // ---------- profiles ----------
    fun profile(userId: Int? = null, username: String? = null, uuid: String? = null): SProfile =
        parseProfile(getJson("/api/v1/users/profile/info", "user_id" to userId, "username" to username, "uuid" to uuid).data())

    fun counts(userId: Int?, uuid: String?): Triple<Long, Long, Long> {
        val o = getJson("/api/v1/users/profile/counts", "user_id" to userId, "uuid" to uuid).data()
        return Triple(o.optLong("posts"), o.optLong("followers"), o.optLong("followings"))
    }

    fun follow(userId: Int) { postJson("/api/v1/followers/subscribe", JSONObject().put("user_id", userId)) }
    fun unfollow(userId: Int) { postJson("/api/v1/followers/unsubscribe", JSONObject().put("user_id", userId)) }

    // ---------- search ----------
    fun searchUsers(q: String, cursor: String? = null, limit: Int = 30): Pair<List<SSearchUser>, String?> {
        val d = getJson("/api/user/users", "search" to q, "limit" to limit, "cursor" to cursor).data()
        val arr = d.optJSONArray("users") ?: JSONArray()
        val list = (0 until arr.length()).map { i ->
            val u = arr.getJSONObject(i)
            SSearchUser(u.optInt("id"), u.optStr("username"), u.optStr("profileName"), mediaUrl(u.optStr("avatar")), u.optBoolean("isFollowing"))
        }
        return list to d.optStr("cursor")
    }

    // ---------- stories ----------
    fun storiesTray(cursor: String? = null): Pair<List<STrayEntry>, String?> = StoryProto.tray(getProto("/api/v1/stories/tray", "cursor" to cursor, "limit" to 30))
    fun storyReel(authorId: String): Pair<SUser?, List<SSegment>> = StoryProto.reel(getProto("/api/v1/stories/users/$authorId"))
    fun recordStoryViews(items: List<SSegment>) {
        if (items.isEmpty()) return
        exec({ it.url(url("/api/v1/stories/views")).post(StoryProto.recordViews(items).toRequestBody(protoType)) }, true)
    }
    fun likeSegment(id: String, like: Boolean) {
        exec({ b -> b.url(url("/api/v1/stories/segments/$id/like")).let { if (like) it.put(ByteArray(0).toRequestBody(protoType)) else it.delete() } }, true)
    }

    // ---------- chat requests ----------
    fun chatRequests(): List<SChatRequest> {
        val raw = getJson("/api/chat_requests/list", "Pagination.Limit" to 50)
        val root = raw as? JSONObject
        val d = root?.optJSONObject("data") ?: root
        val arr = (raw as? JSONArray) ?: d?.optJSONArray("items") ?: root?.optJSONArray("data") ?: JSONArray()
        return (0 until arr.length()).mapNotNull { i -> arr.optJSONObject(i)?.let { o -> SChatRequest(o.optLong("id"), o.optInt("status"), o.optJSONObject("user")?.let(::parseProfile)) } }
    }
    fun acceptChatRequest(id: Long) { postJson("/api/chat_requests/accept", JSONObject().put("id", id)) }
    fun rejectChatRequest(id: Long) { postJson("/api/chat_requests/reject", JSONObject().put("id", id)) }
    fun sendChatRequest(userId: Int) { postJson("/api/chat_requests/add", JSONObject().put("user_id", userId)) }

    // ---------- stickers (Link store) ----------
    fun stickerPacks(): List<io.github.kotbctakahe.hys1link.core.StickerPack> {
        fun parse(raw: Any): List<io.github.kotbctakahe.hys1link.core.StickerPack> {
            val root = raw as? JSONObject
            val d = root?.optJSONObject("data") ?: root
            val arr = (raw as? JSONArray) ?: d?.optJSONArray("packs") ?: root?.optJSONArray("data") ?: JSONArray()
            return (0 until arr.length()).mapNotNull { i ->
                val p = arr.optJSONObject(i) ?: return@mapNotNull null
                val name = p.optStr("name")
                val st = p.optJSONArray("stickers") ?: JSONArray()
                val items = (0 until st.length()).mapNotNull { j ->
                    val it = st.optJSONObject(j) ?: return@mapNotNull null
                    val url = it.optStr("url") ?: return@mapNotNull null
                    io.github.kotbctakahe.hys1link.core.StickerItem(it.optInt("id"), url, it.optStr("emoji"), it.optStr("type"), it.optStr("packName") ?: name)
                }
                if (items.isEmpty()) null
                else io.github.kotbctakahe.hys1link.core.StickerPack(p.optInt("id"), p.optStr("title") ?: name ?: "Стикеры",
                    mediaUrl(p.optJSONObject("cover")?.optStr("url")) ?: mediaUrl(items.first().url), items)
            }
        }
        val mine = runCatching { parse(getJson("/api/v1/stickers/my_packs", "limit" to 50)) }.getOrDefault(emptyList())
        if (mine.isNotEmpty()) return mine
        return parse(getJson("/api/v1/stickers/popular_packs", "limit" to 20))
    }

    // ---------- comments ----------
    fun comments(postId: String, cursor: String? = null, limit: Int = 30): Pair<List<SComment>, String?> {
        val raw = getJson("/api/v1/comments/post_comments", "postId" to postId, "limit" to limit, "cursor" to cursor)
        // Tolerate: {comments:[…]}, {data:{comments:[…]}}, {data:[…]}, or a bare array.
        val root = raw as? JSONObject
        val d = root?.optJSONObject("data") ?: root
        val arr = (raw as? JSONArray) ?: root?.optJSONArray("data") ?: d?.optJSONArray("comments") ?: d?.optJSONArray("items") ?: JSONArray()
        val list = (0 until arr.length()).mapNotNull { i ->
            val c = arr.optJSONObject(i) ?: return@mapNotNull null
            val text = c.optStr("comment") ?: c.optStr("content") ?: c.optStr("text") ?: ""
            SComment(c.optLong("id"), text, c.optStr("created_at") ?: c.optStr("createdAt"),
                (c.optJSONObject("user") ?: c.optJSONObject("author"))?.let(::parseProfile),
                c.optInt("replies_count", c.optInt("repliesCount")), c.optBoolean("is_self"))
        }
        return list to d?.optStr("cursor")
    }

    fun addComment(postId: String, text: String) {
        postJson("/api/v1/comments/post_comment", JSONObject()
            .put("postId", postId.toLongOrNull() ?: postId).put("content", text).put("type", 0))
    }

    private fun parseProfile(o: JSONObject): SProfile {
        val avatars = o.optJSONObject("avatars")
        val av = avatars?.optJSONObject("large")?.optStr("url") ?: avatars?.optJSONObject("original")?.optStr("url") ?: o.optStr("avatar")
        return SProfile(
            o.optInt("id"), o.optStr("username"), o.optStr("profileName"), o.optStr("description"), mediaUrl(av),
            o.optInt("followers_count"), o.optInt("subscriptions_count"), o.optInt("posts_count"),
            o.optBoolean("isFollowing") || o.optBoolean("isSubscribed"), o.optBoolean("is_self"), o.optBoolean("isVerified"),
            o.optJSONObject("chatData")?.optStr("uuid"), o.optBoolean("isBlocked"),
        )
    }

    private fun JSONObject.optStr(k: String): String? = if (has(k) && !isNull(k)) optString(k).takeIf { it.isNotBlank() } else null

    companion object { private const val TAG = "Social" }
}
