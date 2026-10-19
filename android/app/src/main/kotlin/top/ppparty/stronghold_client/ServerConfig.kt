package top.ppparty.stronghold_client

/** Preview builds connect only to spa-test; preferences and Intent extras cannot override it. */
object ServerConfig {
    const val ORIGIN = "https://test-spa.ppparty.top:8000/"
}
