package top.ppparty.stronghold_client

/** Client builds connect only to production; preferences and Intent extras cannot override it. */
object ServerConfig {
    const val ORIGIN = "https://spa.ppparty.top:8000/"
}
