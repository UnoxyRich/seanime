package app.seanime.tv.platform

/** Only the three reviewed, MIT-licensed versions approved for this distribution. */
internal object BundledEnglishProviders {
    const val commit = "641b9d842e5502d34fb923dac959129d3da9fc1a"
    const val repository = "Seanime-contributions/Seanime-Providers"
    val entries = listOf(
        BundledProviderSpec("animeheaven", "1.1.4", "onlinestream-provider", "anime/animeheaven",
            "ea2ec531c514a06134f93d2cec10921a6dc2cc6965240d7b0a6a57134eaff16c",
            "bd26ab1d453a19694c36da8d9ab54b29aed1d0292d053d4d14b5e7042e2f9452"),
        BundledProviderSpec("anidb", "1.0.1", "onlinestream-provider", "anime/anidb",
            "0ed2ecf7063a18375cdac70cedf8963264536f53bec19712db1360f6bd4876de",
            "065ec31a990f7196605f4a7ab67b5441d2c709a3f28e9272752c104c6f90502d"),
        BundledProviderSpec("atsumaru", "1.1.2", "manga-provider", "manga/atsumaru",
            "04fe384db81a6790f661f73d7f37e135b0d9857a9f66500bf9c06246b1fae2d3",
            "605a77d6807f123050405e6136dc19f43aea1680375cecb79572b7352f1a5070"),
    )
}

internal data class BundledProviderSpec(
    val id: String,
    val version: String,
    val type: String,
    val sourcePath: String,
    val manifestSha256: String,
    val payloadSha256: String,
) {
    val manifestURI: String get() = "https://raw.githubusercontent.com/${BundledEnglishProviders.repository}/${BundledEnglishProviders.commit}/src/$sourcePath/manifest.json"
}
