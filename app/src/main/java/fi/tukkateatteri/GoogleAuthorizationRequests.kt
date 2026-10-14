package fi.tukkateatteri

/** Shares one authorization attempt between actions waiting for the same Google grant. */
internal class GoogleAuthorizationRequests {
    private val requests = mutableListOf<Request>()
    private var attemptId = 0L

    /** Returns an attempt ID only when the caller needs to start authorization. */
    fun enqueue(onAuthorized: (String) -> Unit, onUnavailable: (() -> Unit)?): Long? {
        val needsAuthorization = requests.isEmpty()
        requests += Request(onAuthorized, onUnavailable)
        return if (needsAuthorization) ++attemptId else null
    }

    fun isCurrent(id: Long): Boolean = requests.isNotEmpty() && attemptId == id

    fun complete(id: Long, accessToken: String?) {
        if (!isCurrent(id)) return
        val waiting = requests.toList()
        requests.clear()
        waiting.forEach { request ->
            if (accessToken == null) request.onUnavailable?.invoke()
            else request.onAuthorized(accessToken)
        }
    }

    fun cancel() {
        complete(attemptId, accessToken = null)
    }

    private data class Request(
        val onAuthorized: (String) -> Unit,
        val onUnavailable: (() -> Unit)?
    )
}
