package io.github.kotbctakahe.hys1link.social

import io.github.kotbctakahe.hys1link.LinkConfig
import io.github.kotbctakahe.hys1link.proto.ProtoMessage
import io.github.kotbctakahe.hys1link.proto.ProtoWriter

/*
 * Link social API ("clientapi.*" Wire protos). Tags taken from the generated Wire adapters
 * in Link 7.4.4 (encode(ProtoWriter, …) → encodeWithTag(writer, TAG, …)).
 */

fun mediaUrl(u: String?): String? = when {
    u.isNullOrBlank() -> null
    u.startsWith("http") -> u
    else -> LinkConfig.SOCIAL_CDN_URL + "/" + u.trimStart('/')
}

data class SAvatar(val original: String?, val large: String?, val small: String?, val blur: String?) {
    val best get() = mediaUrl(large ?: original ?: small)
    val thumb get() = mediaUrl(small ?: large ?: original)
    companion object {
        // Avatar: 1 original 2 large 3 small 4 blur
        fun decode(m: ProtoMessage) = SAvatar(m.string(1), m.string(2), m.string(3), m.string(4))
    }
}

data class SUser(
    val id: String, val username: String?, val name: String?, val avatar: SAvatar?, val kind: Int,
    val verified: Boolean, val premium: Boolean, val business: Boolean, val uuid: String?,
    val hasStories: Boolean, val isSelf: Boolean, val isFollowed: Boolean,
) {
    val display get() = name?.takeIf { it.isNotBlank() } ?: username ?: "Пользователь"
    companion object {
        // User: 1 id 2 username 3 name 4 avatar 5 kind 6 badges{1 verified 2 premium 3 business} 7 uuid 8 story_badge{1 has_stories} 9 is_self 10 is_followed
        fun decode(m: ProtoMessage): SUser {
            val b = m.msg(6)
            return SUser(
                m.string(1) ?: "", m.string(2), m.string(3), m.msg(4)?.let(SAvatar::decode), m.int(5) ?: 0,
                b?.bool(1) ?: false, b?.bool(2) ?: false, b?.bool(3) ?: false, m.string(7),
                m.msg(8)?.bool(1) ?: false, m.bool(9) ?: false, m.bool(10) ?: false,
            )
        }
    }
}

data class SRendition(val name: String?, val url: String?, val width: Int, val height: Int)

data class SMedia(val type: Int, val position: Int, val width: Int, val height: Int, val poster: String?, val renditions: List<SRendition>) {
    val isVideo get() = type == 2
    val aspect: Float get() = if (width > 0 && height > 0) width.toFloat() / height else 1f
    /** Largest image rendition, or the poster for a video. */
    val imageUrl: String? get() = if (isVideo) mediaUrl(poster) else
        mediaUrl(renditions.maxByOrNull { it.width * it.height }?.url ?: poster)
    /** Prefer HLS/MP4 rendition of moderate size for video. */
    val videoUrl: String? get() = if (!isVideo) null else mediaUrl(
        renditions.firstOrNull { it.url?.contains(".m3u8") == true }?.url
            ?: renditions.sortedBy { it.width * it.height }.let { it.getOrNull(it.size / 2) }?.url
    )
    companion object {
        // PostMedia: 1 type 2 position 3 width 4 height 5 aspect_ratio 6 poster 7 renditions{1 name 2 url 3 width 4 height}
        fun decode(m: ProtoMessage) = SMedia(
            m.int(1) ?: 0, m.int(2) ?: 0, m.int(3) ?: 0, m.int(4) ?: 0, m.string(6),
            m.all(7).map { it.asMessage().let { r -> SRendition(r.string(1), r.string(2), r.int(3) ?: 0, r.int(4) ?: 0) } }
        )
    }
}

data class SPost(
    val id: String, val author: SUser?, val description: String?, val hashtags: List<String>, val medias: List<SMedia>,
    val createdAt: Long, val likes: Long, val comments: Long, val views: Long, val reposts: Long,
    val liked: Boolean, val collected: Boolean, val reposted: Boolean, val isSelf: Boolean,
    val allowDownload: Boolean, val commentsAvailable: Boolean, val isAd: Boolean,
) {
    companion object {
        // Post: 1 id 2 author 3 description 4 hashtags[] 5 medias[] 6 created_at(Timestamp) 7 counters 8 viewer 9 flags 10 target_ad
        // Counters: 1 likes 2 comments 3 views 4 super_likes 5 reposts 6 collections 7 shares
        // Viewer: 1 is_liked 2 is_collected 3 is_self 4 seen 5 is_reposted 6 target_status
        // Flags: 1 allow_download 2 comment_available 3 has_ai_badge 4 is_adult
        fun decode(m: ProtoMessage): SPost {
            val c = m.msg(7); val v = m.msg(8); val f = m.msg(9)
            val ts = m.msg(6)?.let { t -> (t.long(1) ?: 0) * 1000 + (t.long(2) ?: 0) / 1_000_000 } ?: 0
            return SPost(
                m.string(1) ?: "", m.msg(2)?.let(SUser::decode), m.string(3), m.all(4).map { it.asString },
                m.all(5).map { SMedia.decode(it.asMessage()) }.sortedBy { it.position }, ts,
                c?.long(1) ?: 0, c?.long(2) ?: 0, c?.long(3) ?: 0, c?.long(5) ?: 0,
                v?.bool(1) ?: false, v?.bool(2) ?: false, v?.bool(5) ?: false, v?.bool(3) ?: false,
                f?.bool(1) ?: true, f?.bool(2) ?: true, m.has(10),
            )
        }
    }
}

data class SPage(val posts: List<SPost>, val nextCursor: String?, val hasMore: Boolean)

object SocialProto {
    /** HomeFeedResponse / PostListResponse: 1 posts[] 2 next_cursor 3 has_more */
    fun postList(b: ByteArray): SPage {
        val m = ProtoMessage.parse(b)
        return SPage(m.all(1).map { SPost.decode(it.asMessage()) }, m.string(2)?.takeIf { it.isNotEmpty() }, m.bool(3) ?: false)
    }
    /** ReelsResponse: 1 posts[] */
    fun reels(b: ByteArray): List<SPost> = ProtoMessage.parse(b).all(1).map { SPost.decode(it.asMessage()) }
    /** ForYouFeedResponse: 1 items[]{1 post 2 page_position} */
    fun forYou(b: ByteArray): List<SPost> = ProtoMessage.parse(b).all(1).mapNotNull { it.asMessage().msg(1)?.let(SPost::decode) }
    /** RepostListResponse: 1 items[]{2 post} 2 next_cursor 3 has_more */
    fun reposts(b: ByteArray): SPage {
        val m = ProtoMessage.parse(b)
        return SPage(m.all(1).mapNotNull { it.asMessage().msg(2)?.let(SPost::decode) }, m.string(2), m.bool(3) ?: false)
    }
    /** Single post endpoint returns a Post (or a wrapper with post at tag 1). */
    fun post(b: ByteArray): SPost {
        val m = ProtoMessage.parse(b)
        val inner = m.msg(1)
        return if (m.string(1) != null && inner == null) SPost.decode(m) else runCatching { SPost.decode(inner!!) }.getOrElse { SPost.decode(m) }
    }
    /** ReactionCatalogResponse: 1 reactions[]{1 key 2 emoji} */
    fun reactionCatalog(b: ByteArray): List<Pair<String, String>> =
        ProtoMessage.parse(b).all(1).map { it.asMessage().let { r -> (r.string(1) ?: "") to (r.string(2) ?: "❤️") } }
    /** LikeRequest: 1 reaction 2 request_id 3 page_position */
    fun likeRequest(reaction: String): ByteArray = ProtoWriter().string(1, reaction).toByteArray()
}

// ---- JSON models (Gson field names from Link's request.models) ----

data class SProfile(
    val id: Int, val username: String?, val profileName: String?, val description: String?, val avatar: String?,
    val followers: Int, val following: Int, val posts: Int, val isFollowing: Boolean, val isSelf: Boolean,
    val isVerified: Boolean, val chatUuid: String?, val isBlocked: Boolean,
) {
    val display get() = profileName?.takeIf { it.isNotBlank() } ?: username ?: "Профиль"
}

data class SSearchUser(val id: Int, val username: String?, val profileName: String?, val avatar: String?, val isFollowing: Boolean) {
    val display get() = profileName?.takeIf { it.isNotBlank() } ?: username ?: "Пользователь"
}

data class SComment(val id: Long, val text: String, val createdAt: String?, val author: SProfile?, val repliesCount: Int, val isSelf: Boolean)


// ---- stories (clientapi.stories.v1) ----
data class STrayEntry(val author: SUser, val latest: Long, val hasUnseen: Boolean)
data class SSegment(
    val id: String, val authorId: String?, val kind: Int, val publishedAt: Long, val durationMs: Int,
    val imageUrl: String?, val videoUrl: String?, val text: String?, val textBg: String?, val textColor: String?,
    val caption: String?, val seen: Boolean, val viewCount: Long, val liked: Boolean,
)

object StoryProto {
    private fun ts(m: ProtoMessage?): Long = m?.let { (it.long(1) ?: 0) * 1000 + (it.long(2) ?: 0) / 1_000_000 } ?: 0

    /** TrayResponse: 1 entries[]{1 author 2 latest_ms 3 has_unseen} 2 next_cursor 3 has_more */
    fun tray(b: ByteArray): Pair<List<STrayEntry>, String?> {
        val m = ProtoMessage.parse(b)
        return m.all(1).mapNotNull { f -> f.asMessage().let { e -> e.msg(1)?.let { STrayEntry(SUser.decode(it), e.long(2) ?: 0, e.bool(3) ?: false) } } } to m.string(2)
    }

    /** StoryReelResponse: 1 segments[] 2 author. SegmentView: 1 id 2 author_id 3 kind 6 published_at 8 duration_ms 10 media 11 text 12 caption 15 seen 17 view_count 19 liked */
    fun reel(b: ByteArray): Pair<SUser?, List<SSegment>> {
        val m = ProtoMessage.parse(b)
        val segs = m.all(1).map { f ->
            val s = f.asMessage()
            val media = s.msg(10)
            val rends = media?.all(5)?.map { it.asMessage().let { r -> Triple(r.string(2), r.int(3) ?: 0, r.int(4) ?: 0) } }.orEmpty()
            val kind = s.int(3) ?: 0
            val text = s.msg(11)
            SSegment(
                s.string(1) ?: "", s.string(2), kind, ts(s.msg(6)), s.int(8) ?: 0,
                mediaUrl(media?.string(1) ?: rends.maxByOrNull { it.second * it.third }?.first),
                if (kind == 2) mediaUrl(rends.firstOrNull { it.first?.contains(".m3u8") == true }?.first ?: rends.maxByOrNull { it.second * it.third }?.first) else null,
                text?.string(1), text?.string(2), text?.string(5), s.string(12), s.bool(15) ?: false, s.long(17) ?: 0, s.bool(19) ?: false,
            )
        }
        return m.msg(2)?.let(SUser::decode) to segs
    }

    /** RecordViewsRequest: 1 items[]{1 segment_id 2 author_id 3 published_ms 4 viewed_ms} */
    fun recordViews(items: List<SSegment>): ByteArray {
        val w = ProtoWriter()
        val now = System.currentTimeMillis()
        items.forEach { s -> w.message(1, ProtoWriter().string(1, s.id).string(2, s.authorId).uint(3, s.publishedAt).uint(4, now)) }
        return w.toByteArray()
    }
}

data class SChatRequest(val id: Long, val status: Int, val user: SProfile?)
