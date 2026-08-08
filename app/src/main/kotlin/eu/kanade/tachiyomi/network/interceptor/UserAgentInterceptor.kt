package eu.kanade.tachiyomi.network.interceptor

import okhttp3.Interceptor
import okhttp3.Response

/**
 * Applies a default User-Agent when the request does not already carry one.
 *
 * Many sources reject the OkHttp default UA outright, so this is applied on the
 * shared client. Requests that set their own UA are left untouched, since some
 * extensions depend on a very specific value.
 */
class UserAgentInterceptor(private val userAgentProvider: () -> String) : Interceptor {

    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()

        return if (request.header("User-Agent").isNullOrEmpty()) {
            val withUa = request.newBuilder()
                .header("User-Agent", userAgentProvider())
                .build()
            chain.proceed(withUa)
        } else {
            chain.proceed(request)
        }
    }
}
