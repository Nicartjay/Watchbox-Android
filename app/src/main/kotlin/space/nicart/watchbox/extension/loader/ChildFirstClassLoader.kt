package space.nicart.watchbox.extension.loader

import dalvik.system.PathClassLoader

/**
 * Parent-last classloader for extension APKs.
 *
 * Extensions bundle their own copies of common libraries (kotlinx-serialization,
 * okio helpers, and so on). If the host's copies won on lookup, an extension
 * compiled against a different minor version can fail with obscure
 * `NoSuchMethodError`s deep inside a parse routine. Resolving from the extension
 * dex first avoids that.
 *
 * Ordering is: already-loaded -> system (so `java.*`/`android.*` can never be
 * shadowed by a malicious dex) -> extension dex -> host. The host still wins for
 * `eu.kanade.tachiyomi.*`, because that ABI is deliberately absent from the
 * extension APK, so those lookups fall through to the parent.
 */
internal class ChildFirstClassLoader(
    dexPath: String,
    librarySearchPath: String?,
    private val host: ClassLoader,
) : PathClassLoader(dexPath, librarySearchPath, host) {

    private val system: ClassLoader? = getSystemClassLoader()

    @Throws(ClassNotFoundException::class)
    override fun loadClass(name: String, resolve: Boolean): Class<*> {
        // Locking on the loader itself rather than a per-name lock: extensions
        // are loaded once at startup, so contention is not a concern and this
        // avoids reaching for ClassLoader's protected lock helper.
        synchronized(this) {
            findLoadedClass(name)?.let { return it }

            // Platform classes must never be overridable.
            system?.let { sys ->
                runCatching { return sys.loadClass(name) }
            }

            // Then the extension's own dex.
            runCatching { return findClass(name) }

            // Finally the host, which is where the source API lives.
            return host.loadClass(name)
        }
    }

    override fun getResource(name: String): java.net.URL? =
        system?.getResource(name)
            ?: findResource(name)
            ?: host.getResource(name)

    override fun getResources(name: String): java.util.Enumeration<java.net.URL> {
        val all = buildList {
            system?.getResources(name)?.let { addAll(it.toList()) }
            runCatching { addAll(findResources(name).toList()) }
            host.getResources(name)?.let { addAll(it.toList()) }
        }
        return java.util.Collections.enumeration(all)
    }
}
