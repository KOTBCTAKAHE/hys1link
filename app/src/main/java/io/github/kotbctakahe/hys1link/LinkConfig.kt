package io.github.kotbctakahe.hys1link

/** Server configuration of Link Messenger 7.4.4 (taken from its BuildConfig). */
object LinkConfig {
    const val SERVICE_URL = "https://chat.linkmessenger.me"
    const val SOCKET_URL = "wss://chat.linkmessenger.me"
    /** CDN number -> base URL. Link serves attachments at "<cdn>/<id-or-key>". */
    val CDN = mapOf(
        0 to "https://cdn-aws.linkmessenger.me",
        2 to "https://cdn-gcp.linkmessenger.me",
        3 to "https://cdn-aws.linkmessenger.me",
    )
    const val STORAGE_URL = "https://stg.linkmessenger.me"
    const val UNIDENTIFIED_SENDER_TRUST_ROOT = "BbZ54gjdnxB0L39NHJSzTaO3J5q4MYjQ6pHccGylMbw2"
    const val ZKGROUP_SERVER_PUBLIC_PARAMS =
        "APypRQinqnc88qLDaKas7UBynNEFBXInGfEgdKrkYJh9uM2v4MNClJu75BLFuER+uvtvSXwNZ3Z5wU01HpB4YxY+Qzu7fbOcvM3x6Jp2Syp0inKCK3HRXQqClmDygHEXOMhTAfNeslVfpfDyUacaHFMq1LUTvQRGatYPKsDjwRpsNrrfsxCrrxdMCJMhJgeVLS4MRMYL7UNhsHF8Arn75y4+NMyvOLO7JzSrOwoqWBCIyzBSFyUjOFBhVfIBpKPidHwT8+ASPhMWbItEiKTSl2RFpFGVN1h7aRLvA+j2Umc5ZtdxcqgTQtSDS5aPo2oydt3Fb7NhvdKqIvyH/SnSnFTaiZmKecD5R2y+ajIp4C+APW38kHQYVHUBrua0ehCeK2qvrItuTy0NuUvmPREZIxemZHVnBU3SWy2UDdV36G1GEGaKrOS8oPyHqSfTLDwibkyezS2zy3AtxbiCiQR4I3SsNYEFvsHwe8o3vQ+PLr/XX6+ocWwChDzPRqryLUxuGfJcu0gtmDFcLHFUEOZlLdh+SG7gppkjNdRbDywvIvdW"
    const val SIGNAL_AGENT = "OWA"
    const val USER_AGENT = "Hys1Link-Android/0.1"
    const val DEVICE_NAME = "Hys1Link (Android)"
}
